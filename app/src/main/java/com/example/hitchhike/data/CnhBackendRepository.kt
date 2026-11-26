package com.example.hitchhike.data

import com.example.hitchhike.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

data class ResultadoEnvioCnh(
    val statusSolicitacao: String,
    val versao: Int,
    val recebidoEm: String,
    val aprovacaoRetirada: Boolean,
    val substituiu: Boolean
)

data class StatusCnhRemoto(
    val solicitacaoStatus: String?,
    val motivoRecusa: String?,
    val documentoRecebido: Boolean,
    val arquivoPresente: Boolean,
    val versaoDocumento: Int?,
    val recebidoEm: String?,
    val conferenciaSituacao: String,
    val cnhValidade: String?,
    val diasRestantes: Int?,
    val condutorValidado: Boolean,
    val papelAtivo: String?,
    val revisaoNecessaria: Boolean
)

enum class FalhaCnhBackend {
    NAO_CONFIGURADO,
    NAO_AUTENTICADO,
    REDE,
    LIMITE_EXCEDIDO,
    PDF_INVALIDO,
    INDISPONIVEL
}

class CnhBackendException(
    val falha: FalhaCnhBackend,
    mensagem: String
) : Exception(mensagem)

/**
 * Cliente HTTP autenticado com Firebase ID Token para envio, consulta e exclusão
 * do PDF da CNH no backend (reutilizando BuildConfig.PAGAMENTOS_BASE_URL).
 */
class CnhBackendRepository(
    private val baseUrl: String = BuildConfig.PAGAMENTOS_BASE_URL
) {

    suspend fun enviarCnh(
        pdfDataUrlBase64: String,
        placa: String,
        modelo: String,
        cor: String
    ): Result<ResultadoEnvioCnh> = executar {
        val corpo = JSONObject()
            .put("pdf", pdfDataUrlBase64)
            .put("placa", placa.trim().uppercase())
            .put("modelo", modelo.trim())
            .put("cor", cor.trim())
            .toString()

        val (codigo, json) = requisitar("POST", "/api/motoristas/cnh", corpo, timeoutLeituraMs = 65_000)
        verificarCodigo(codigo, json)

        ResultadoEnvioCnh(
            statusSolicitacao = json.optString("status", "PENDENTE"),
            versao = json.optInt("versao", 1),
            recebidoEm = json.optString("recebidoEm"),
            aprovacaoRetirada = json.optBoolean("aprovacaoRetirada", false),
            substituiu = json.optBoolean("substituiu", false)
        )
    }

    suspend fun consultarStatus(): Result<StatusCnhRemoto> = executar {
        val (codigo, json) = requisitar("GET", "/api/motoristas/cnh/status", null)
        verificarCodigo(codigo, json)

        val doc = json.optJSONObject("documento") ?: JSONObject()
        val conf = json.optJSONObject("conferencia") ?: JSONObject()
        val solicitacao = if (json.isNull("solicitacao")) null else json.optString("solicitacao").takeIf { it.isNotBlank() }
        val motivoRecusa = if (json.isNull("motivoRecusa")) null else json.optString("motivoRecusa").takeIf { it.isNotBlank() }
        val validade = if (conf.isNull("validade")) null else conf.optString("validade").takeIf { it.isNotBlank() }
        val dias = if (conf.has("dias") && !conf.isNull("dias")) conf.optInt("dias") else null
        val versao = if (doc.has("versao") && !doc.isNull("versao")) doc.optInt("versao") else null
        val recebidoEm = if (doc.isNull("recebidoEm")) null else doc.optString("recebidoEm").takeIf { it.isNotBlank() }
        val papel = if (json.isNull("papelAtivo")) null else json.optString("papelAtivo").takeIf { it.isNotBlank() }

        StatusCnhRemoto(
            solicitacaoStatus = solicitacao,
            motivoRecusa = motivoRecusa,
            documentoRecebido = doc.optBoolean("recebido", false),
            arquivoPresente = doc.optBoolean("arquivoPresente", false),
            versaoDocumento = versao,
            recebidoEm = recebidoEm,
            conferenciaSituacao = conf.optString("situacao", "sem_documento"),
            cnhValidade = validade,
            diasRestantes = dias,
            condutorValidado = json.optBoolean("condutorValidado", false),
            papelAtivo = papel,
            revisaoNecessaria = json.optBoolean("revisaoNecessaria", false)
        )
    }

    suspend fun excluirArquivo(): Result<Unit> = executar {
        val (codigo, json) = requisitar("DELETE", "/api/motoristas/cnh/arquivo", null)
        verificarCodigo(codigo, json)
    }

    private suspend fun <T> executar(bloco: suspend () -> T): Result<T> =
        try {
            Result.success(bloco())
        } catch (e: CnhBackendException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(
                CnhBackendException(
                    FalhaCnhBackend.REDE,
                    "Sem conexão com o servidor de validação de CNH. Verifique se o backend está ativo."
                )
            )
        } catch (e: Exception) {
            Result.failure(
                CnhBackendException(
                    FalhaCnhBackend.INDISPONIVEL,
                    "Servidor de validação de CNH indisponível no momento."
                )
            )
        }

    private fun verificarCodigo(codigo: Int, json: JSONObject) {
        if (codigo in 200..299) return
        val msg = json.optString("error")
        throw when (codigo) {
            401, 403 -> CnhBackendException(
                FalhaCnhBackend.NAO_AUTENTICADO,
                msg.ifBlank { "Sessão inválida ou e-mail institucional não verificado." }
            )
            429 -> CnhBackendException(
                FalhaCnhBackend.LIMITE_EXCEDIDO,
                msg.ifBlank { "Muitas tentativas em sequência. Aguarde alguns segundos e tente novamente." }
            )
            400, 404, 409, 413, 415, 422 -> CnhBackendException(
                FalhaCnhBackend.PDF_INVALIDO,
                msg.ifBlank { "O documento PDF não pôde ser aceito. Envie o PDF original da CNH Digital (1 página, até 5 MB, com QR Code legível)." }
            )
            else -> CnhBackendException(
                FalhaCnhBackend.INDISPONIVEL,
                msg.ifBlank { "Servidor de validação de CNH indisponível." }
            )
        }
    }

    private suspend fun obterToken(): String {
        val usuario = FirebaseAuth.getInstance().currentUser
            ?: throw CnhBackendException(
                FalhaCnhBackend.NAO_AUTENTICADO,
                "Entre na sua conta institucional verificada para enviar a CNH."
            )
        return suspendCancellableCoroutine { cont ->
            usuario.getIdToken(false)
                .addOnSuccessListener { cont.resume(it.token) }
                .addOnFailureListener { cont.resume(null) }
        } ?: throw CnhBackendException(
            FalhaCnhBackend.NAO_AUTENTICADO,
            "Não foi possível validar sua sessão no Firebase."
        )
    }

    private suspend fun requisitar(
        metodo: String,
        caminho: String,
        corpo: String?,
        timeoutLeituraMs: Int = 30_000
    ): Pair<Int, JSONObject> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            throw CnhBackendException(
                FalhaCnhBackend.NAO_CONFIGURADO,
                "URL do backend não configurada em local.properties (hitchhike.pagamentosBaseUrl)."
            )
        }
        val token = obterToken()
        return withContext(Dispatchers.IO) {
            val conexao = URL(base + caminho).openConnection() as HttpURLConnection
            try {
                conexao.requestMethod = metodo
                conexao.connectTimeout = 15_000
                conexao.readTimeout = timeoutLeituraMs
                conexao.setRequestProperty("Authorization", "Bearer $token")
                conexao.setRequestProperty("Accept", "application/json")
                if (corpo != null) {
                    conexao.doOutput = true
                    conexao.setRequestProperty("Content-Type", "application/json")
                    conexao.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                }
                val codigo = conexao.responseCode
                val stream = if (codigo in 200..299) conexao.inputStream else conexao.errorStream
                val texto = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = try {
                    JSONObject(texto.ifBlank { "{}" })
                } catch (e: Exception) {
                    JSONObject()
                }
                codigo to json
            } finally {
                conexao.disconnect()
            }
        }
    }
}

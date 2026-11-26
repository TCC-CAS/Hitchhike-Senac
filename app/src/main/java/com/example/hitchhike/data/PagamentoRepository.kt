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

/** Resultado da criação do checkout (valores apenas informativos; o backend é a fonte da verdade). */
data class PreferenciaPagamento(
    val pagamentoId: String,
    val checkoutUrl: String
)

/** Status validado pelo backend: PENDENTE, APROVADO, RECUSADO ou DIVERGENTE. */
data class StatusPagamento(
    val pagamentoId: String,
    val status: String
)

/** Vínculo OAuth do motorista; `splitAtivo` indica se o servidor está com o split habilitado. */
data class VinculoMercadoPago(
    val vinculado: Boolean,
    val splitAtivo: Boolean
)

enum class FalhaPagamento {
    NAO_CONFIGURADO,
    NAO_AUTENTICADO,
    REDE,
    INDISPONIVEL,
    DUPLICADO,
    RECUSADO_PELO_BACKEND
}

class PagamentoException(
    val falha: FalhaPagamento,
    mensagem: String
) : Exception(mensagem)

/**
 * Cliente isolado do backend de pagamentos de teste (Mercado Pago Checkout Pro).
 * Não conhece Access Token, não grava no Firestore e envia somente o caronaId.
 */
class PagamentoRepository(
    private val baseUrl: String = BuildConfig.PAGAMENTOS_BASE_URL
) {

    suspend fun criarPreferencia(caronaId: String): Result<PreferenciaPagamento> =
        executar {
            val corpo = JSONObject().put("caronaId", caronaId).toString()
            val (codigo, json) = requisitar("POST", "/api/pagamentos/preferencia", corpo)
            verificarCodigo(codigo, json)
            val id = json.optString("pagamentoId")
            val url = json.optString("checkoutUrl")
            if (id.isBlank() || !url.startsWith("https://")) {
                throw PagamentoException(
                    FalhaPagamento.INDISPONIVEL,
                    "Resposta inválida do servidor de pagamentos."
                )
            }
            PreferenciaPagamento(id, url)
        }

    suspend fun consultarStatus(pagamentoId: String): Result<StatusPagamento> =
        executar {
            val (codigo, json) = requisitar(
                "GET",
                "/api/pagamentos/${java.net.URLEncoder.encode(pagamentoId, "UTF-8")}/status",
                null
            )
            verificarCodigo(codigo, json)
            val status = json.optString("status").uppercase()
            StatusPagamento(
                pagamentoId,
                if (status in STATUS_CONHECIDOS) status else "PENDENTE"
            )
        }

    /** Situação do vínculo do motorista com o Mercado Pago (o backend guarda os tokens). */
    suspend fun consultarVinculoMp(): Result<VinculoMercadoPago> =
        executar {
            val (codigo, json) = requisitar("GET", "/api/mercadopago/oauth/status", null)
            verificarCodigo(codigo, json)
            VinculoMercadoPago(
                vinculado = json.optBoolean("vinculado", false),
                splitAtivo = json.optBoolean("splitAtivo", false)
            )
        }

    /** Pede ao backend a URL de autorização OAuth; o app só abre a página, sem ver tokens. */
    suspend fun iniciarVinculoMp(): Result<String> =
        executar {
            val (codigo, json) = requisitar("POST", "/api/mercadopago/oauth/iniciar", "{}")
            verificarCodigo(codigo, json)
            val url = json.optString("authUrl")
            if (!url.startsWith("https://")) {
                throw PagamentoException(
                    FalhaPagamento.INDISPONIVEL,
                    "Vínculo com o Mercado Pago indisponível no momento."
                )
            }
            url
        }

    private suspend fun <T> executar(bloco: suspend () -> T): Result<T> =
        try {
            Result.success(bloco())
        } catch (e: PagamentoException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(
                PagamentoException(
                    FalhaPagamento.REDE,
                    "Sem conexão com o servidor de pagamentos."
                )
            )
        } catch (e: Exception) {
            Result.failure(
                PagamentoException(
                    FalhaPagamento.INDISPONIVEL,
                    "Servidor de pagamentos indisponível."
                )
            )
        }

    private fun verificarCodigo(codigo: Int, json: JSONObject) {
        if (codigo in 200..299) return
        val msg = json.optString("error")
        throw when (codigo) {
            401, 403 -> PagamentoException(
                FalhaPagamento.NAO_AUTENTICADO,
                msg.ifBlank { "Sessão inválida. Entre novamente." }
            )
            409 -> PagamentoException(
                FalhaPagamento.DUPLICADO,
                msg.ifBlank { "Esta solicitação já foi paga ou não permite pagamento." }
            )
            400, 404, 422 -> PagamentoException(
                FalhaPagamento.RECUSADO_PELO_BACKEND,
                msg.ifBlank { "Pagamento não permitido para esta solicitação." }
            )
            else -> PagamentoException(
                FalhaPagamento.INDISPONIVEL,
                msg.ifBlank { "Servidor de pagamentos indisponível." }
            )
        }
    }

    private suspend fun obterToken(): String {
        val usuario = FirebaseAuth.getInstance().currentUser
            ?: throw PagamentoException(
                FalhaPagamento.NAO_AUTENTICADO,
                "Entre na sua conta para pagar."
            )
        return suspendCancellableCoroutine { cont ->
            usuario.getIdToken(false)
                .addOnSuccessListener { cont.resume(it.token) }
                .addOnFailureListener { cont.resume(null) }
        } ?: throw PagamentoException(
            FalhaPagamento.NAO_AUTENTICADO,
            "Não foi possível validar sua sessão."
        )
    }

    private suspend fun requisitar(
        metodo: String,
        caminho: String,
        corpo: String?
    ): Pair<Int, JSONObject> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            throw PagamentoException(
                FalhaPagamento.NAO_CONFIGURADO,
                "URL do servidor de pagamentos não configurada (hitchhike.pagamentosBaseUrl)."
            )
        }
        val token = obterToken()
        return withContext(Dispatchers.IO) {
            val conexao = URL(base + caminho).openConnection() as HttpURLConnection
            try {
                conexao.requestMethod = metodo
                conexao.connectTimeout = 15_000
                conexao.readTimeout = 30_000
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

    companion object {
        val STATUS_CONHECIDOS = setOf("PENDENTE", "APROVADO", "RECUSADO", "DIVERGENTE")
    }
}

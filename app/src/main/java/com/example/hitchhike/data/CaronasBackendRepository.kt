package com.example.hitchhike.data

import com.example.hitchhike.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Cliente HTTP autenticado (Firebase ID Token) para a API de ciclo de vida das caronas,
 * avaliações às cegas, participantes e reputação (docs/CARONAS_API.md).
 */
class CaronasBackendRepository(
    private val baseUrl: String = BuildConfig.PAGAMENTOS_BASE_URL
) {

    suspend fun aceitar(caronaId: String, passageiroUid: String): Result<ResultadoAceite> =
        executar {
            val (codigo, json) = requisitar(
                "POST",
                "/api/caronas/${enc(caronaId)}/solicitacoes/${enc(passageiroUid)}/aceitar",
                "{}"
            )
            verificarCodigo(codigo, json)
            CaronasParser.aceite(paraMapa(json))
        }

    suspend fun recusar(caronaId: String, passageiroUid: String): Result<Unit> =
        executar {
            val (codigo, json) = requisitar(
                "POST",
                "/api/caronas/${enc(caronaId)}/solicitacoes/${enc(passageiroUid)}/recusar",
                "{}"
            )
            verificarCodigo(codigo, json)
        }

    suspend fun iniciar(caronaId: String): Result<Unit> =
        executar {
            val (codigo, json) = requisitar("POST", "/api/caronas/${enc(caronaId)}/iniciar", "{}")
            verificarCodigo(codigo, json)
        }

    suspend fun concluir(caronaId: String): Result<Unit> =
        executar {
            val (codigo, json) = requisitar("POST", "/api/caronas/${enc(caronaId)}/concluir", "{}")
            verificarCodigo(codigo, json)
        }

    suspend fun cancelar(caronaId: String): Result<Unit> =
        executar {
            val (codigo, json) = requisitar("POST", "/api/caronas/${enc(caronaId)}/cancelar", "{}")
            verificarCodigo(codigo, json)
        }

    suspend fun confirmarParticipacao(caronaId: String): Result<Unit> =
        executar {
            val (codigo, json) = requisitar(
                "POST",
                "/api/caronas/${enc(caronaId)}/participacao/confirmar",
                "{}"
            )
            verificarCodigo(codigo, json)
        }

    suspend fun confirmarConclusao(caronaId: String): Result<Unit> =
        executar {
            val (codigo, json) = requisitar(
                "POST",
                "/api/caronas/${enc(caronaId)}/conclusao/confirmar",
                "{}"
            )
            verificarCodigo(codigo, json)
        }

    suspend fun situacao(caronaId: String): Result<Situacao> =
        executar {
            val (codigo, json) = requisitar("GET", "/api/caronas/${enc(caronaId)}/situacao", null)
            verificarCodigo(codigo, json)
            CaronasParser.situacao(paraMapa(json))
        }

    suspend fun situacoes(ids: List<String>): Result<Map<String, SituacaoResumo>> =
        executar {
            val limpos = ids.filter { it.isNotBlank() }.distinct().take(30)
            if (limpos.isEmpty()) return@executar emptyMap()
            val corpo = JSONObject().put("ids", JSONArray(limpos)).toString()
            val (codigo, json) = requisitar("POST", "/api/caronas/situacoes", corpo)
            verificarCodigo(codigo, json)
            CaronasParser.situacoes(paraMapa(json))
        }

    suspend fun participantes(caronaId: String): Result<List<Participante>> =
        executar {
            val (codigo, json) = requisitar(
                "GET",
                "/api/caronas/${enc(caronaId)}/participantes",
                null
            )
            verificarCodigo(codigo, json)
            CaronasParser.participantes(paraMapa(json))
        }

    suspend fun avaliar(caronaId: String, avaliadoUid: String, nota: Int): Result<Boolean> =
        executar {
            val corpo = JSONObject()
                .put("caronaId", caronaId)
                .put("avaliadoUid", avaliadoUid)
                .put("nota", nota)
                .toString()
            val (codigo, json) = requisitar("POST", "/api/avaliacoes", corpo)
            verificarCodigo(codigo, json)
            json.optBoolean("revelada", false)
        }

    suspend fun reputacaoConsultar(uids: List<String>): Result<Map<String, Reputacao>> =
        executar {
            val limpos = uids.filter { it.isNotBlank() }.distinct().take(30)
            if (limpos.isEmpty()) return@executar emptyMap()
            val corpo = JSONObject().put("uids", JSONArray(limpos)).toString()
            val (codigo, json) = requisitar("POST", "/api/reputacao/consultar", corpo)
            verificarCodigo(codigo, json)
            CaronasParser.reputacoes(paraMapa(json))
        }

    suspend fun reputacaoMinha(): Result<MinhaReputacao> =
        executar {
            val (codigo, json) = requisitar("GET", "/api/reputacao/minha", null)
            verificarCodigo(codigo, json)
            CaronasParser.minhaReputacao(paraMapa(json))
        }

    private fun enc(valor: String): String = URLEncoder.encode(valor, "UTF-8")

    private suspend fun <T> executar(bloco: suspend () -> T): Result<T> =
        try {
            Result.success(bloco())
        } catch (e: CaronasException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(
                CaronasException(
                    CaronasException.REDE,
                    "Não foi possível falar com o servidor. Verifique sua conexão e tente novamente."
                )
            )
        } catch (e: Exception) {
            Result.failure(
                CaronasException(
                    CaronasException.INDISPONIVEL,
                    "Não foi possível falar com o servidor."
                )
            )
        }

    private fun verificarCodigo(codigo: Int, json: JSONObject) {
        if (codigo in 200..299) return
        throw CaronasParser.erro(codigo, paraMapa(json))
    }

    private fun paraMapa(json: JSONObject): Map<String, Any?> {
        val mapa = linkedMapOf<String, Any?>()
        val chaves = json.keys()
        while (chaves.hasNext()) {
            val chave = chaves.next()
            val valor = json.opt(chave)
            mapa[chave] = converterValor(valor)
        }
        return mapa
    }

    private fun paraLista(arr: JSONArray): List<Any?> {
        val lista = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            lista.add(converterValor(arr.opt(i)))
        }
        return lista
    }

    private fun converterValor(valor: Any?): Any? = when (valor) {
        null, JSONObject.NULL -> null
        is JSONObject -> paraMapa(valor)
        is JSONArray -> paraLista(valor)
        else -> valor
    }

    private suspend fun obterToken(): String {
        val usuario = FirebaseAuth.getInstance().currentUser
            ?: throw CaronasException(
                CaronasException.NAO_AUTENTICADO,
                "Sessão inválida. Entre novamente.",
                401
            )
        return suspendCancellableCoroutine { cont ->
            usuario.getIdToken(false)
                .addOnSuccessListener { cont.resume(it.token) }
                .addOnFailureListener { cont.resume(null) }
        } ?: throw CaronasException(
            CaronasException.NAO_AUTENTICADO,
            "Sessão inválida. Entre novamente.",
            401
        )
    }

    private suspend fun requisitar(
        metodo: String,
        caminho: String,
        corpo: String?
    ): Pair<Int, JSONObject> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            throw CaronasException(
                CaronasException.NAO_CONFIGURADO,
                "Servidor não configurado neste aplicativo."
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
}

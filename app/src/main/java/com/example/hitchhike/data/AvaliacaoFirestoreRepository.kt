package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Repositório de avaliações e reputação integrado ao backend (docs/CARONAS_API.md).
 * Nenhuma leitura ou escrita direta à coleção `avaliacoes` ocorre no cliente Android,
 * garantindo privacidade e proteção das avaliações às cegas.
 */
class AvaliacaoFirestoreRepository(
    private val backend: CaronasBackendRepository = CaronasBackendRepository(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    fun enviar(
        caronaId: String,
        avaliadoUid: String,
        nota: Int,
        onSuccess: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (uidAutenticado(onError) == null) return
        if (nota !in 1..5) {
            onError("A nota precisa ser um número inteiro de 1 a 5.")
            return
        }
        scope.launch {
            backend.avaliar(caronaId, avaliadoUid, nota).fold(
                onSuccess = { revelada -> onSuccess(revelada) },
                onFailure = { erro ->
                    onError(erro.message ?: "A avaliação não foi autorizada.")
                }
            )
        }
    }

    fun enviar(
        caronaId: String,
        avaliadoUid: String,
        nota: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        enviar(caronaId, avaliadoUid, nota, onSuccess = { _ -> onSuccess() }, onError = onError)
    }

    fun consultarEnviada(
        caronaId: String,
        avaliadoUid: String,
        onSuccess: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (uidAutenticado(onError) == null) return
        scope.launch {
            backend.participantes(caronaId).fold(
                onSuccess = { lista ->
                    val alvo = lista.firstOrNull { it.uid == avaliadoUid }
                    onSuccess(alvo?.jaAvaliou == true)
                },
                onFailure = { erro ->
                    onError(erro.message ?: "Não foi possível verificar a avaliação.")
                }
            )
        }
    }

    fun consultarRecebidas(
        onSuccess: (MinhaReputacao) -> Unit,
        onError: (String) -> Unit
    ) {
        if (uidAutenticado(onError) == null) return
        scope.launch {
            backend.reputacaoMinha().fold(
                onSuccess = { rep -> onSuccess(rep) },
                onFailure = { erro ->
                    onError(erro.message ?: "Não foi possível consultar a reputação.")
                }
            )
        }
    }

    private fun uidAutenticado(onError: (String) -> Unit): String? {
        val usuario = auth.currentUser
        if (usuario == null || !usuario.isEmailVerified) {
            onError("Faça login com seu e-mail institucional verificado.")
            return null
        }
        return usuario.uid
    }
}

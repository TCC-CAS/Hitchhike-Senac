package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SincronizacaoCondutorValidado(
    private val firestore: UsuarioFirestoreRepository,
    private val usuarios: UsuarioRepository,
    private val session: SessionManager,
    private val solicitacoes: SolicitacaoMotoristaFirestoreRepository = SolicitacaoMotoristaFirestoreRepository()
) {
    @Volatile
    private var emAndamento = false

    fun executar(scope: CoroutineScope, onAtualizado: () -> Unit = {}) {
        if (emAndamento || !session.estaLogado()) return

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val usuarioId = session.usuarioId()
        if (usuarioId <= 0L) return

        emAndamento = true

        firestore.buscarPerfil(
            uid = uid,
            onSuccess = { perfil ->
                if (perfil == null) {
                    emAndamento = false
                    return@buscarPerfil
                }

                solicitacoes.consultarDetalhes(
                    onSuccess = { pedido ->
                        aplicarSincronizacao(scope, usuarioId, perfil, pedido, true, onAtualizado)
                    },
                    onError = {
                        aplicarSincronizacao(scope, usuarioId, perfil, null, false, onAtualizado)
                    }
                )
            },
            onError = {
                emAndamento = false
            }
        )
    }

    private fun aplicarSincronizacao(
        scope: CoroutineScope,
        usuarioId: Long,
        perfil: Map<String, String>,
        pedido: DadosSolicitacaoMotorista?,
        atualizarSolicitacao: Boolean,
        onAtualizado: () -> Unit
    ) {
        val validado = perfil["condutorValidado"] == "true"
        val cnhValidade = perfil["cnhValidade"]?.takeIf { it.isNotBlank() }
        val cnhRevisao = if (pedido?.status == "RECUSADA") false else (perfil["cnhRevisaoNecessaria"] == "true")
        val status = perfil["status"]
        val papel = perfil["papel"]

        scope.launch {
            try {
                if (pedido != null) {
                    usuarios.sincronizarVeiculoLocal(
                        usuarioId = usuarioId,
                        placa = pedido.placa,
                        modelo = pedido.modelo,
                        cor = pedido.cor,
                        cnhAnexada = pedido.cnhPdfRecebido
                    )
                }
                usuarios.sincronizarValidacaoMotorista(
                    usuarioId = usuarioId,
                    validado = validado,
                    cnhValidade = cnhValidade,
                    cnhRevisaoNecessaria = cnhRevisao,
                    status = status,
                    papelAtivo = papel,
                    solicitacaoStatus = pedido?.status,
                    motivoRecusa = pedido?.motivoRecusa,
                    atualizarSolicitacao = atualizarSolicitacao
                )
                onAtualizado()
            } finally {
                emAndamento = false
            }
        }
    }
}

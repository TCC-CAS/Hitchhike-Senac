package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SincronizacaoCondutorValidado(
    private val firestore: UsuarioFirestoreRepository,
    private val usuarios: UsuarioRepository,
    private val session: SessionManager
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

                val validado = perfil["condutorValidado"] == "true"

                scope.launch {
                    try {
                        usuarios.sincronizarValidacaoMotorista(
                            usuarioId = usuarioId,
                            validado = validado
                        )
                        onAtualizado()
                    } finally {
                        emAndamento = false
                    }
                }
            },
            onError = {
                emAndamento = false
            }
        )
    }
}

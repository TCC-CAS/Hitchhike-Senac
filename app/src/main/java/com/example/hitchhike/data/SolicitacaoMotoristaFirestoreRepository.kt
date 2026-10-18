
package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class SolicitacaoMotoristaFirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun enviarSolicitacao(
        placa: String,
        modelo: String,
        cor: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        val placaNormalizada = placa.trim().uppercase()
        val modeloNormalizado = modelo.trim()
        val corNormalizada = cor.trim()

        if (placaNormalizada.length !in 7..8) {
            onError("Informe uma placa válida.")
            return
        }

        if (modeloNormalizado.isBlank() || corNormalizada.isBlank()) {
            onError("Preencha os dados do veículo.")
            return
        }

        if (modeloNormalizado.length > 100 || corNormalizada.length > 50) {
            onError("Os dados do veículo ultrapassam o limite permitido.")
            return
        }

        val dados = hashMapOf(
            "motoristaUid" to usuario.uid,
            "placa" to placaNormalizada,
            "modelo" to modeloNormalizado,
            "cor" to corNormalizada,
            "status" to "PENDENTE",
            "criadoEm" to FieldValue.serverTimestamp()
        )

        db.collection("solicitacoes_motoristas")
            .document(usuario.uid)
            .set(dados)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao enviar solicitação."
                )
            }
    }
}

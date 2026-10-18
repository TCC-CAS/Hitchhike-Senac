
package com.example.hitchhike.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

class UsuarioFirestoreRepository {

    private val db = FirebaseFirestore.getInstance()

    fun salvarPerfil(
        uid: String,
        nome: String,
        curso: String,
        instituicao: String,
        email: String,
        papel: PapelUsuario,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val dados = hashMapOf(
            "nome" to nome.trim(),
            "curso" to curso.trim(),
            "instituicao" to instituicao.trim(),
            "email" to email.trim().lowercase(),
            "papelAtivo" to papel.name,
            "status" to "ATIVO",
            "condutorValidado" to false

        )

        db.collection("usuarios")
            .document(uid)
            .set(dados)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao salvar perfil."
                )
            }
    }

    fun buscarPerfil(
        uid: String,
        onSuccess: (Map<String, String>?) -> Unit,
        onError: (String) -> Unit
    ) {
        db.collection("usuarios")
            .document(uid)
            .get(Source.SERVER)
            .addOnSuccessListener { documento ->

                if (!documento.exists()) {
                    onSuccess(null)
                    return@addOnSuccessListener
                }

                val perfil = mapOf(
                    "nome" to documento.getString("nome").orEmpty(),
                    "curso" to documento.getString("curso").orEmpty(),
                    "instituicao" to documento.getString("instituicao").orEmpty(),
                    "email" to documento.getString("email").orEmpty(),

                    "papel" to documento.getString("papelAtivo").orEmpty(),
                    "status" to documento.getString("status").orEmpty(),
                    "condutorValidado" to (
                            documento.getBoolean("condutorValidado") == true
                            ).toString()

                )

                onSuccess(perfil)
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao consultar perfil."
                )
            }
    }

    fun atualizarPapel(
        uid: String,
        novoPapel: PapelUsuario,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = com.google.firebase.auth.FirebaseAuth
            .getInstance().currentUser

        if (usuario == null ||
            usuario.uid != uid ||
            !usuario.isEmailVerified
        ) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        db.collection("usuarios")
            .document(uid)
            .update("papelAtivo", novoPapel.name)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao atualizar o papel no Firebase."
                )
            }
    }

}

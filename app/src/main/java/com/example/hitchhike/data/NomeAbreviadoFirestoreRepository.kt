package com.example.hitchhike.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class NomeAbreviadoFirestoreRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val usuarios: UsuarioFirestoreRepository = UsuarioFirestoreRepository()
) {
    fun garantirProprio(caronaId: String) {
        val usuario = auth.currentUser
        if (usuario == null || !usuario.isEmailVerified || caronaId.isBlank()) return
        usuarios.buscarPerfil(
            uid = usuario.uid,
            onSuccess = { perfil ->
                val abreviado = PrivacidadeNome.abreviar(perfil?.get("nome")) ?: return@buscarPerfil
                db.collection(COLECAO)
                    .document("${caronaId}_${usuario.uid}")
                    .set(
                        mapOf(
                            "caronaId" to caronaId,
                            "uid" to usuario.uid,
                            "nomeAbreviado" to abreviado
                        )
                    )
                    .addOnFailureListener { erro ->
                        Log.e(TAG, "Falha ao registrar o nome abreviado.", erro)
                    }
            },
            onError = { erro ->
                Log.e(TAG, erro)
            }
        )
    }

    fun exibir(
        caronaId: String,
        alvoUid: String,
        papel: PrivacidadeNome.Papel,
        participante: Boolean,
        statusSolicitacao: String?,
        encerrada: Boolean,
        gravarProprio: Boolean,
        onTexto: (String) -> Unit
    ) {
        fun publicar(nome: String?) {
            onTexto(
                PrivacidadeNome.texto(
                    PrivacidadeNome.decidir(
                        papel = papel,
                        participante = participante,
                        statusSolicitacao = statusSolicitacao,
                        encerrada = encerrada,
                        nomeCompleto = nome
                    )
                )
            )
        }

        publicar(null)
        if (gravarProprio) garantirProprio(caronaId)
        if (!PrivacidadeNome.podeRevelar(participante, statusSolicitacao, encerrada)) return
        consultar(caronaId, alvoUid) { nome ->
            if (!nome.isNullOrBlank()) publicar(nome)
        }
    }

    private fun consultar(
        caronaId: String,
        alvoUid: String,
        onResultado: (String?) -> Unit
    ) {
        val usuario = auth.currentUser
        if (
            usuario == null ||
            !usuario.isEmailVerified ||
            caronaId.isBlank() ||
            alvoUid.isBlank() ||
            alvoUid == usuario.uid
        ) {
            onResultado(null)
            return
        }
        db.collection(COLECAO)
            .document("${caronaId}_${alvoUid}")
            .get()
            .addOnSuccessListener { documento ->
                onResultado(documento.getString("nomeAbreviado"))
            }
            .addOnFailureListener { erro ->
                Log.e(TAG, "Nome abreviado indisponível.", erro)
                onResultado(null)
            }
    }

    private companion object {
        const val COLECAO = "nomes_abreviados"
        const val TAG = "HITCHHIKE_NOME"
    }
}

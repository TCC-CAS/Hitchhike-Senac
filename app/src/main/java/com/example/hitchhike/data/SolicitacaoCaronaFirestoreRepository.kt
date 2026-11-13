package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class SolicitacaoCaronaFirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun solicitar(
        caronaId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Faça login com seu e-mail institucional verificado.")
            return
        }

        if (caronaId.isBlank()) {
            onError("Carona inválida.")
            return
        }

        val caronaRef = db.collection("caronas").document(caronaId)

        val solicitacaoRef = db.collection("solicitacoes_caronas")
            .document("${caronaId}_${usuario.uid}")

        db.runTransaction { transaction ->

            val caronaDoc = transaction.get(caronaRef)


            if (!caronaDoc.exists()) {
                throw IllegalStateException("Carona não encontrada.")
            }

            val motoristaUid = caronaDoc.getString("motoristaUid")
            val status = caronaDoc.getString("status")
            val vagas = caronaDoc.getLong("vagasDisponiveis") ?: 0L
            val horario = caronaDoc.getTimestamp("dataHoraSaida")

            if (motoristaUid == usuario.uid) {
                throw IllegalStateException(
                    "Você não pode solicitar sua própria carona."
                )
            }

            if (status != "ABERTA" ||
                vagas <= 0 ||
                horario == null ||
                horario.toDate().time <= System.currentTimeMillis()
            ) {
                throw IllegalStateException(
                    "Esta carona não está disponível."
                )
            }


            val dados = hashMapOf(
                "id" to solicitacaoRef.id,
                "caronaId" to caronaId,
                "motoristaUid" to motoristaUid,
                "passageiroUid" to usuario.uid,
                "status" to "PENDENTE",
                "criadoEm" to FieldValue.serverTimestamp()
            )

            transaction.set(solicitacaoRef, dados)

            null
        }.addOnSuccessListener {
            onSuccess()
        }
            .addOnFailureListener { erro ->
                onError(
                    com.example.hitchhike.util.FirestoreMensagens.amigavel(
                        erro,
                        "Você já pediu esta carona ou ela não está mais disponível."
                    )
                )
            }

    }


    fun listarPorCarona(
        caronaId: String,
        onSuccess: (List<SolicitacaoCaronaFirestore>) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado.")
            return
        }

        if (caronaId.isBlank()) {
            onError("Carona inválida.")
            return
        }

        db.collection("solicitacoes_caronas")
            .whereEqualTo("motoristaUid", usuario.uid)
            .get()
            .addOnSuccessListener { resultado ->
                val solicitacoes = resultado.documents
                    .mapNotNull { documento ->
                        documento.toObject(
                            SolicitacaoCaronaFirestore::class.java
                        )
                    }
                    .filter { it.caronaId == caronaId }
                    .sortedByDescending {
                        it.criadoEm.toDate().time
                    }

                onSuccess(solicitacoes)
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao carregar solicitações."
                )
            }
    }


    fun recusar(
        solicitacaoId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado.")
            return
        }

        val solicitacaoRef = db.collection("solicitacoes_caronas")
            .document(solicitacaoId)

        db.runTransaction { transaction ->
            val documento = transaction.get(solicitacaoRef)

            if (!documento.exists()) {
                throw IllegalStateException("Solicitação não encontrada.")
            }

            if (documento.getString("motoristaUid") != usuario.uid) {
                throw IllegalStateException("Você não é a motorista desta carona.")
            }

            if (documento.getString("status") != "PENDENTE") {
                throw IllegalStateException("Esta solicitação já foi respondida.")
            }

            transaction.update(solicitacaoRef, "status", "RECUSADA")

            null
        }.addOnSuccessListener {
            onSuccess()
        }.addOnFailureListener { erro ->
            onError(
                erro.localizedMessage ?: "Erro ao recusar solicitação."
            )
        }
    }


    fun aceitar(
        solicitacaoId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado.")
            return
        }

        val solicitacaoRef = db.collection("solicitacoes_caronas")
            .document(solicitacaoId)

        db.runTransaction { transaction ->

            val pedido = transaction.get(solicitacaoRef)

            if (!pedido.exists()) {
                throw IllegalStateException("Solicitação não encontrada.")
            }

            if (pedido.getString("motoristaUid") != usuario.uid) {
                throw IllegalStateException("Você não é a motorista desta carona.")
            }

            if (pedido.getString("status") != "PENDENTE") {
                throw IllegalStateException("Esta solicitação já foi respondida.")
            }

            val caronaId = pedido.getString("caronaId")
                ?: throw IllegalStateException("Carona inválida.")

            val caronaRef = db.collection("caronas").document(caronaId)
            val carona = transaction.get(caronaRef)

            if (!carona.exists()) {
                throw IllegalStateException("Carona não encontrada.")
            }

            if (carona.getString("motoristaUid") != usuario.uid ||
                carona.getString("status") != "ABERTA"
            ) {
                throw IllegalStateException("Carona indisponível.")
            }

            val vagas = carona.getLong("vagasDisponiveis") ?: 0L

            if (vagas <= 0L) {
                throw IllegalStateException("Não há vagas disponíveis.")
            }

            val horario = carona.getTimestamp("dataHoraSaida")

            if (horario == null ||
                horario.toDate().time <= System.currentTimeMillis()
            ) {
                throw IllegalStateException("O horário da carona já passou.")
            }


            transaction.update(
                caronaRef,
                mapOf(
                    "vagasDisponiveis" to vagas - 1,
                    "ultimaSolicitacaoAceitaId" to solicitacaoId
                )
            )


            transaction.update(
                solicitacaoRef,
                "status",
                "ACEITA"
            )

            null
        }.addOnSuccessListener {
            onSuccess()
        }.addOnFailureListener { erro ->
            onError(
                erro.localizedMessage ?: "Erro ao aceitar solicitação."
            )
        }
    }


    fun listarMinhasSolicitacoes(
        onSuccess: (List<SolicitacaoCaronaFirestore>) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Faça login com seu e-mail institucional verificado.")
            return
        }

        db.collection("solicitacoes_caronas")
            .whereEqualTo("passageiroUid", usuario.uid)
            .get()
            .addOnSuccessListener { resultado ->
                val solicitacoes = resultado.documents
                    .mapNotNull { documento ->
                        documento.toObject(
                            SolicitacaoCaronaFirestore::class.java
                        )
                    }
                    .sortedByDescending { it.criadoEm.toDate().time }

                onSuccess(solicitacoes)
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao carregar suas solicitações."
                )
            }
    }


    fun buscarMinhaSolicitacao(
        caronaId: String,
        onSuccess: (SolicitacaoCaronaFirestore?) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado.")
            return
        }

        db.collection("solicitacoes_caronas")
            .whereEqualTo("passageiroUid", usuario.uid)
            .get()
            .addOnSuccessListener { resultado ->
                val pedido = resultado.documents
                    .mapNotNull { documento ->
                        documento.toObject(SolicitacaoCaronaFirestore::class.java)
                    }
                    .firstOrNull { it.caronaId == caronaId }
                onSuccess(pedido)
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao consultar sua solicitação."
                )
            }
    }

}

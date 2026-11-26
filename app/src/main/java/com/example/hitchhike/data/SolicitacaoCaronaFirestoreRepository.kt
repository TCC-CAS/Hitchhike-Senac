package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SolicitacaoCaronaFirestoreRepository(
    private val backend: CaronasBackendRepository = CaronasBackendRepository()
) {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(Dispatchers.Main)

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
            val pedidoExistente = transaction.get(solicitacaoRef)

            val decisao = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
                ContextoCaronaParaSolicitacao(
                    caronaExiste = caronaDoc.exists(),
                    motoristaUid = caronaDoc.getString("motoristaUid"),
                    passageiroUid = usuario.uid,
                    statusCarona = caronaDoc.getString("status"),
                    vagasDisponiveis = caronaDoc.getLong("vagasDisponiveis") ?: 0L,
                    saidaMillis = caronaDoc.getTimestamp("dataHoraSaida")?.toDate()?.time,
                    agoraMillis = System.currentTimeMillis(),
                    solicitacaoJaExiste = pedidoExistente.exists()
                )
            )

            if (decisao != DecisaoSolicitacaoCarona.PERMITIDA) {
                throw IllegalStateException(IntegridadeSolicitacaoCarona.mensagemNovaSolicitacao(decisao))
            }

            val motoristaUid = caronaDoc.getString("motoristaUid")

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
        }.addOnFailureListener { erro ->
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
        val (caronaId, passageiroUid) = decomporSolicitacaoId(solicitacaoId) ?: run {
            onError("Solicitação inválida.")
            return
        }
        recusar(caronaId, passageiroUid, onSuccess, onError)
    }

    fun recusar(
        caronaId: String,
        passageiroUid: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser
        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado.")
            return
        }
        scope.launch {
            backend.recusar(caronaId, passageiroUid).fold(
                onSuccess = { onSuccess() },
                onFailure = { erro ->
                    onError(erro.message ?: "Erro ao recusar solicitação.")
                }
            )
        }
    }

    fun aceitar(
        solicitacaoId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val (caronaId, passageiroUid) = decomporSolicitacaoId(solicitacaoId) ?: run {
            onError("Solicitação inválida.")
            return
        }
        aceitar(caronaId, passageiroUid, onSuccess, onError)
    }

    fun aceitar(
        caronaId: String,
        passageiroUid: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser
        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado.")
            return
        }
        scope.launch {
            backend.aceitar(caronaId, passageiroUid).fold(
                onSuccess = { onSuccess() },
                onFailure = { erro ->
                    onError(erro.message ?: "Erro ao aceitar solicitação.")
                }
            )
        }
    }

    private fun decomporSolicitacaoId(solicitacaoId: String): Pair<String, String>? {
        val idx = solicitacaoId.indexOf('_')
        if (idx <= 0 || idx >= solicitacaoId.length - 1) return null
        return solicitacaoId.substring(0, idx) to solicitacaoId.substring(idx + 1)
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

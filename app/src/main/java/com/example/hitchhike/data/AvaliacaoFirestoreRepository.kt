package com.example.hitchhike.data

import com.example.hitchhike.util.FirestoreMensagens
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class AvaliacaoFirestoreRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    fun enviar(
        caronaId: String,
        avaliadoUid: String,
        nota: Int,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val avaliadorUid = uidAutenticado(onError) ?: return
        if (nota !in 1..5) {
            onError(mensagem(DecisaoAvaliacao.NOTA_INVALIDA))
            return
        }
        val agora = System.currentTimeMillis()
        val avaliacaoRef = db.collection(COLECAO)
            .document(AvaliacaoElegibilidade.idDocumento(caronaId, avaliadorUid, avaliadoUid))

        db.runTransaction { transaction ->
            val carona = transaction.get(db.collection("caronas").document(caronaId))
            if (!carona.exists()) {
                throw IllegalStateException(DecisaoAvaliacao.IDENTIFICADOR_INVALIDO.name)
            }
            val motoristaDaCarona = carona.getString("motoristaUid")
            val solicitacaoId = if (motoristaDaCarona == avaliadorUid) {
                "${caronaId}_${avaliadoUid}"
            } else {
                "${caronaId}_${avaliadorUid}"
            }
            val pedido = transaction.get(
                db.collection("solicitacoes_caronas").document(solicitacaoId)
            )
            val existente = transaction.get(avaliacaoRef)
            val contexto = contexto(pedido)
            val decisao = AvaliacaoElegibilidade.decidir(
                PedidoAvaliacao(
                    caronaId = caronaId,
                    avaliadorUid = avaliadorUid,
                    avaliadoUid = avaliadoUid,
                    motoristaUid = contexto?.motoristaUid ?: "",
                    passageiroUid = contexto?.passageiroUid ?: "",
                    solicitacaoId = contexto?.solicitacaoId ?: "",
                    statusSolicitacao = contexto?.status ?: "",
                    saidaMillis = carona.getTimestamp("dataHoraSaida")?.toDate()?.time,
                    agoraMillis = agora,
                    jaExiste = existente.exists(),
                    nota = nota
                )
            )
            if (decisao != DecisaoAvaliacao.PERMITIDA) {
                throw IllegalStateException(decisao.name)
            }
            if (contexto == null ||
                contexto.solicitacaoId != solicitacaoId ||
                !parDaAvaliacao(avaliadorUid, avaliadoUid, contexto, motoristaDaCarona)
            ) {
                throw IllegalStateException(DecisaoAvaliacao.PARTICIPANTES_INVALIDOS.name)
            }
            transaction.set(
                avaliacaoRef,
                mapOf(
                    "caronaId" to caronaId,
                    "solicitacaoId" to contexto.solicitacaoId,
                    "motoristaUid" to contexto.motoristaUid,
                    "passageiroUid" to contexto.passageiroUid,
                    "avaliadorUid" to avaliadorUid,
                    "avaliadoUid" to avaliadoUid,
                    "nota" to nota.toLong(),
                    "criadoEm" to FieldValue.serverTimestamp()
                )
            )
            null
        }.addOnSuccessListener {
            onSuccess()
        }.addOnFailureListener { erro ->
            val decisao = decisaoDoErro(erro)
            if (decisao != null) {
                onError(mensagem(decisao))
                return@addOnFailureListener
            }
            onError(FirestoreMensagens.amigavel(erro, "A avaliação não foi autorizada."))
        }
    }

    fun consultarEnviada(
        caronaId: String,
        avaliadoUid: String,
        onSuccess: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val avaliadorUid = uidAutenticado(onError) ?: return
        db.collection(COLECAO)
            .document(AvaliacaoElegibilidade.idDocumento(caronaId, avaliadorUid, avaliadoUid))
            .get()
            .addOnSuccessListener { documento ->
                onSuccess(documento.exists())
            }
            .addOnFailureListener { erro ->
                onError(FirestoreMensagens.amigavel(erro, "Não foi possível verificar a avaliação."))
            }
    }

    fun consultarRecebidas(
        onSuccess: (ResumoAvaliacoes) -> Unit,
        onError: (String) -> Unit
    ) {
        val uid = uidAutenticado(onError) ?: return
        db.collection(COLECAO)
            .whereEqualTo("avaliadoUid", uid)
            .get()
            .addOnSuccessListener { resultado ->
                val notas = resultado.documents.mapNotNull { documento ->
                    documento.getLong("nota")?.toInt()
                }
                onSuccess(AvaliacaoElegibilidade.resumir(notas))
            }
            .addOnFailureListener { erro ->
                onError(FirestoreMensagens.amigavel(erro, "Não foi possível consultar as avaliações."))
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

    private data class Contexto(
        val solicitacaoId: String,
        val motoristaUid: String,
        val passageiroUid: String,
        val status: String
    )

    private fun parDaAvaliacao(
        avaliadorUid: String,
        avaliadoUid: String,
        contexto: Contexto,
        motoristaDaCarona: String?
    ): Boolean {
        val passageiroAvaliaMotorista = contexto.passageiroUid == avaliadorUid &&
            contexto.motoristaUid == avaliadoUid &&
            motoristaDaCarona == avaliadoUid
        val motoristaAvaliaPassageiro = contexto.motoristaUid == avaliadorUid &&
            contexto.passageiroUid == avaliadoUid &&
            motoristaDaCarona == avaliadorUid
        return passageiroAvaliaMotorista || motoristaAvaliaPassageiro
    }

    private fun contexto(documento: DocumentSnapshot): Contexto? {
        if (!documento.exists()) return null
        val caronaId = documento.getString("caronaId") ?: return null
        val motoristaUid = documento.getString("motoristaUid") ?: return null
        val passageiroUid = documento.getString("passageiroUid") ?: return null
        val status = documento.getString("status") ?: return null
        if (documento.id != "${caronaId}_${passageiroUid}") return null
        return Contexto(documento.id, motoristaUid, passageiroUid, status)
    }

    private fun decisaoDoErro(erro: Throwable): DecisaoAvaliacao? {
        var atual: Throwable? = erro
        while (atual != null) {
            val encontrada = DecisaoAvaliacao.values().firstOrNull { decisao ->
                decisao != DecisaoAvaliacao.PERMITIDA && atual?.message?.contains(decisao.name) == true
            }
            if (encontrada != null) return encontrada
            atual = atual.cause
        }
        return null
    }

    private fun mensagem(decisao: DecisaoAvaliacao): String {
        return when (decisao) {
            DecisaoAvaliacao.NOTA_INVALIDA -> "A nota precisa ser um número inteiro de 1 a 5."
            DecisaoAvaliacao.DUPLICADA -> "Esta avaliação já foi enviada."
            DecisaoAvaliacao.ANTES_DO_HORARIO -> "A avaliação fica disponível depois do horário previsto de saída."
            DecisaoAvaliacao.SOLICITACAO_NAO_ACEITA -> "Só é possível avaliar uma solicitação aceita."
            DecisaoAvaliacao.PARTICIPANTES_INVALIDOS -> "A avaliação só pode ocorrer entre o motorista e o passageiro desta carona."
            DecisaoAvaliacao.IDENTIFICADOR_INVALIDO -> "Não foi possível identificar a carona ou os participantes."
            DecisaoAvaliacao.PERMITIDA -> "Avaliação autorizada."
        }
    }

    private companion object {
        const val COLECAO = "avaliacoes"
    }
}

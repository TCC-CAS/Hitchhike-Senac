package com.example.hitchhike.data

import com.google.firebase.Timestamp

/**
 * Carona no Firestore. A criação NÃO serializa esta classe (veja
 * [CaronaFirestore.payloadCriacao]): só as chaves liberadas pelas regras são gravadas.
 * Os campos de ciclo de vida (`iniciadaEm`...) são somente leitura e preenchidos pelo backend.
 */
data class CaronaFirestore(
    val id: String = "",
    val motoristaUid: String = "",
    val origem: String = "",
    val destino: String = "",
    val dataHoraSaida: Timestamp = Timestamp(0, 0),
    val vagasTotais: Int = 0,
    val vagasDisponiveis: Int = 0,
    val valor: Double = 0.0,
    val status: String = "ABERTA",
    val paradas: List<String>? = null,
    // Somente leitura (gravados pelo backend).
    val iniciadaEm: Timestamp? = null,
    val concluidaEm: Timestamp? = null,
    val canceladaEm: Timestamp? = null,
    val canceladaPor: String? = null,
    val expiradaEm: Timestamp? = null,
    val ultimaSolicitacaoAceitaId: String? = null
) {
    companion object {
        /** Chaves permitidas na criação (docs/CARONAS_API.md e regras do Firestore). */
        val CHAVES_CRIACAO = setOf(
            "id", "motoristaUid", "origem", "destino", "dataHoraSaida",
            "vagasTotais", "vagasDisponiveis", "valor", "status", "paradas"
        )

        fun payloadCriacao(carona: CaronaFirestore): Map<String, Any?> = linkedMapOf(
            "id" to carona.id,
            "motoristaUid" to carona.motoristaUid,
            "origem" to carona.origem,
            "destino" to carona.destino,
            "dataHoraSaida" to carona.dataHoraSaida,
            "vagasTotais" to carona.vagasTotais,
            "vagasDisponiveis" to carona.vagasDisponiveis,
            "valor" to carona.valor,
            "status" to carona.status,
            "paradas" to carona.paradas
        )
    }
}

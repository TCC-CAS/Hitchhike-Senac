package com.example.hitchhike.data

import com.google.firebase.Timestamp

/**
 * Pedido de vaga. Na criação o app grava só id, caronaId, motoristaUid, passageiroUid, status e
 * criadoEm (mapa explícito em [SolicitacaoCaronaFirestoreRepository.solicitar]); esta classe é de leitura.
 * Os campos de ciclo de vida são preenchidos pelo backend.
 */
data class SolicitacaoCaronaFirestore(
    val id: String = "",
    val caronaId: String = "",
    val motoristaUid: String = "",
    val passageiroUid: String = "",
    val status: String = "PENDENTE",
    val criadoEm: Timestamp = Timestamp(0, 0),
    val aceitaEm: Timestamp? = null,
    val participacaoConfirmadaEm: Timestamp? = null,
    val conclusaoConfirmadaEm: Timestamp? = null,
    val statusAnterior: String? = null
)

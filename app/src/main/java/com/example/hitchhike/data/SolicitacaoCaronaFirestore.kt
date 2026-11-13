package com.example.hitchhike.data

import com.google.firebase.Timestamp

data class SolicitacaoCaronaFirestore(
    val id: String = "",
    val caronaId: String = "",
    val motoristaUid: String = "",
    val passageiroUid: String = "",
    val status: String = "PENDENTE",
    val criadoEm: Timestamp = Timestamp(0, 0)
)

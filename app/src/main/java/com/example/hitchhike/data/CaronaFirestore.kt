package com.example.hitchhike.data

import com.google.firebase.Timestamp

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
    val paradas: List<String>? = null
)

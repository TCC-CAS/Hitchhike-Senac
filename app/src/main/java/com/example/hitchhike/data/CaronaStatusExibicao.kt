package com.example.hitchhike.data

object CaronaStatusExibicao {

    fun calcular(
        statusArmazenado: String,
        dataHoraSaidaMillis: Long,
        vagasDisponiveis: Int,
        agoraMillis: Long = System.currentTimeMillis()
    ): String {
        if (statusArmazenado == StatusCarona.CANCELADA.name) {
            return StatusCarona.CANCELADA.name
        }

        if (dataHoraSaidaMillis <= agoraMillis) {
            return StatusCarona.ENCERRADA.name
        }

        if (vagasDisponiveis <= 0 || statusArmazenado == StatusCarona.LOTADA.name) {
            return StatusCarona.LOTADA.name
        }

        return StatusCarona.ABERTA.name
    }

    fun calcular(carona: CaronaFirestore, agoraMillis: Long = System.currentTimeMillis()): String {
        return calcular(
            statusArmazenado = carona.status,
            dataHoraSaidaMillis = carona.dataHoraSaida.toDate().time,
            vagasDisponiveis = carona.vagasDisponiveis,
            agoraMillis = agoraMillis
        )
    }

    fun calcular(carona: CaronaEntity, agoraMillis: Long = System.currentTimeMillis()): String {
        return calcular(
            statusArmazenado = carona.status,
            dataHoraSaidaMillis = carona.dataHoraSaida,
            vagasDisponiveis = carona.vagasDisponiveis,
            agoraMillis = agoraMillis
        )
    }

    fun disponivelParaNovaSolicitacao(
        statusArmazenado: String,
        dataHoraSaidaMillis: Long,
        vagasDisponiveis: Int,
        agoraMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return calcular(
            statusArmazenado,
            dataHoraSaidaMillis,
            vagasDisponiveis,
            agoraMillis
        ) == StatusCarona.ABERTA.name
    }

    fun disponivelParaNovaSolicitacao(
        carona: CaronaFirestore,
        agoraMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return calcular(carona, agoraMillis) == StatusCarona.ABERTA.name
    }
}

package com.example.hitchhike.data

/**
 * Espelha `statusExibicao` do contrato (docs/CARONAS_API.md) para listas:
 *
 * - CANCELADA, CONCLUIDA: iguais.
 * - EM_ANDAMENTO: se agora > iniciadaEm + 24 h → EXPIRADA; senão EM_ANDAMENTO.
 * - ABERTA/LOTADA: se agora > saída + 6 h → EXPIRADA; senão se agora >= saída → AGUARDANDO_INICIO;
 *   senão LOTADA (status LOTADA ou vagas <= 0) ou ABERTA.
 * - Registros antigos nunca viram CONCLUIDA automaticamente.
 *
 * ENCERRADA/FINALIZADA são valores legados do Room e são devolvidos como ENCERRADA.
 */
object CaronaStatusExibicao {

    const val SEIS_HORAS_MS = 6L * 60 * 60 * 1000
    const val VINTE_QUATRO_HORAS_MS = 24L * 60 * 60 * 1000

    fun calcular(
        statusArmazenado: String,
        dataHoraSaidaMillis: Long,
        vagasDisponiveis: Int,
        agoraMillis: Long = System.currentTimeMillis(),
        iniciadaEmMillis: Long? = null
    ): String {
        return when (statusArmazenado) {
            StatusCarona.CANCELADA.name -> StatusCarona.CANCELADA.name
            StatusCarona.CONCLUIDA.name -> StatusCarona.CONCLUIDA.name
            StatusCarona.EXPIRADA.name -> StatusCarona.EXPIRADA.name
            StatusCarona.ENCERRADA.name, StatusCarona.FINALIZADA.name -> StatusCarona.ENCERRADA.name
            StatusCarona.EM_ANDAMENTO.name -> {
                // Sem iniciadaEm (registro incompleto) usa-se a saída como referência.
                val inicio = iniciadaEmMillis ?: dataHoraSaidaMillis
                if (agoraMillis > inicio + VINTE_QUATRO_HORAS_MS) {
                    StatusCarona.EXPIRADA.name
                } else {
                    StatusCarona.EM_ANDAMENTO.name
                }
            }
            else -> when {
                agoraMillis > dataHoraSaidaMillis + SEIS_HORAS_MS -> StatusCarona.EXPIRADA.name
                agoraMillis >= dataHoraSaidaMillis -> StatusCarona.AGUARDANDO_INICIO.name
                vagasDisponiveis <= 0 || statusArmazenado == StatusCarona.LOTADA.name ->
                    StatusCarona.LOTADA.name
                else -> StatusCarona.ABERTA.name
            }
        }
    }

    fun calcular(carona: CaronaFirestore, agoraMillis: Long = System.currentTimeMillis()): String {
        return calcular(
            statusArmazenado = carona.status,
            dataHoraSaidaMillis = carona.dataHoraSaida.toDate().time,
            vagasDisponiveis = carona.vagasDisponiveis,
            agoraMillis = agoraMillis,
            iniciadaEmMillis = carona.iniciadaEm?.toDate()?.time
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

    /** Estados finais: nenhuma ação de pedir vaga, aceitar, pagar ou avaliar o andamento. */
    fun estadoFinal(statusExibicao: String): Boolean = statusExibicao in ESTADOS_FINAIS

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

    private val ESTADOS_FINAIS = setOf(
        StatusCarona.CONCLUIDA.name,
        StatusCarona.CANCELADA.name,
        StatusCarona.EXPIRADA.name,
        StatusCarona.ENCERRADA.name
    )
}

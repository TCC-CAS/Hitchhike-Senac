package com.example.hitchhike.data

enum class DecisaoAvaliacao {
    PERMITIDA,
    IDENTIFICADOR_INVALIDO,
    NOTA_INVALIDA,
    DUPLICADA,
    PARTICIPANTES_INVALIDOS,
    SOLICITACAO_NAO_ACEITA,
    ANTES_DO_HORARIO
}

data class ResumoAvaliacoes(
    val quantidade: Int,
    val soma: Int,
    val media: Double?
)

data class PedidoAvaliacao(
    val caronaId: String,
    val avaliadorUid: String,
    val avaliadoUid: String,
    val motoristaUid: String,
    val passageiroUid: String,
    val solicitacaoId: String,
    val statusSolicitacao: String,
    val saidaMillis: Long?,
    val agoraMillis: Long,
    val jaExiste: Boolean,
    val nota: Int
)

object AvaliacaoElegibilidade {
    private val identificador = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun idDocumento(caronaId: String, avaliadorUid: String, avaliadoUid: String): String {
        return "${caronaId}_${avaliadorUid}_${avaliadoUid}"
    }

    fun resumir(notas: List<Int>): ResumoAvaliacoes {
        val validas = notas.filter { it in 1..5 }
        if (validas.isEmpty()) return ResumoAvaliacoes(quantidade = 0, soma = 0, media = null)
        val soma = validas.sum()
        return ResumoAvaliacoes(
            quantidade = validas.size,
            soma = soma,
            media = soma.toDouble() / validas.size
        )
    }

    fun decidir(pedido: PedidoAvaliacao): DecisaoAvaliacao {
        if (!identificadorValido(pedido.caronaId) ||
            !identificadorValido(pedido.avaliadorUid) ||
            !identificadorValido(pedido.avaliadoUid) ||
            !identificadorValido(pedido.motoristaUid) ||
            !identificadorValido(pedido.passageiroUid) ||
            pedido.solicitacaoId != "${pedido.caronaId}_${pedido.passageiroUid}"
        ) {
            return DecisaoAvaliacao.IDENTIFICADOR_INVALIDO
        }
        if (pedido.nota !in 1..5) return DecisaoAvaliacao.NOTA_INVALIDA
        if (pedido.jaExiste) return DecisaoAvaliacao.DUPLICADA
        val parValido = (pedido.avaliadorUid == pedido.passageiroUid && pedido.avaliadoUid == pedido.motoristaUid) ||
            (pedido.avaliadorUid == pedido.motoristaUid && pedido.avaliadoUid == pedido.passageiroUid)
        if (!parValido || pedido.avaliadorUid == pedido.avaliadoUid) {
            return DecisaoAvaliacao.PARTICIPANTES_INVALIDOS
        }
        if (pedido.statusSolicitacao != "ACEITA") return DecisaoAvaliacao.SOLICITACAO_NAO_ACEITA
        val saida = pedido.saidaMillis
        if (saida == null || saida > pedido.agoraMillis) return DecisaoAvaliacao.ANTES_DO_HORARIO
        return DecisaoAvaliacao.PERMITIDA
    }

    private fun identificadorValido(valor: String): Boolean = identificador.matches(valor)
}

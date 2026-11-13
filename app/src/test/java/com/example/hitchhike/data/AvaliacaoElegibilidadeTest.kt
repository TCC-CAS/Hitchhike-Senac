package com.example.hitchhike.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvaliacaoElegibilidadeTest {
    private val agora = 1_700_000_000_000L
    private val saidaPassada = agora - 60_000L
    private val saidaFutura = agora + 60_000L

    @Test
    fun calculaMediaEQuantidade() {
        val resumo = AvaliacaoElegibilidade.resumir(listOf(5, 4, 3))
        assertEquals(3, resumo.quantidade)
        assertEquals(12, resumo.soma)
        assertEquals(4.0, resumo.media!!, 0.0001)

        val unica = AvaliacaoElegibilidade.resumir(listOf(5))
        assertEquals(1, unica.quantidade)
        assertEquals(5.0, unica.media!!, 0.0001)

        val vazia = AvaliacaoElegibilidade.resumir(emptyList())
        assertEquals(0, vazia.quantidade)
        assertNull(vazia.media)
    }

    @Test
    fun mediaIgnoraNotaForaDaEscala() {
        val resumo = AvaliacaoElegibilidade.resumir(listOf(5, 0, 6))
        assertEquals(1, resumo.quantidade)
        assertEquals(5.0, resumo.media!!, 0.0001)
    }

    @Test
    fun passageiroPodeAvaliarMotoristaDepoisDaSaida() {
        val pedido = base().copy(
            avaliadorUid = "passageiro",
            avaliadoUid = "motorista"
        )
        assertEquals(DecisaoAvaliacao.PERMITIDA, AvaliacaoElegibilidade.decidir(pedido))
        assertEquals(
            "carona1_passageiro_motorista",
            AvaliacaoElegibilidade.idDocumento(pedido.caronaId, pedido.avaliadorUid, pedido.avaliadoUid)
        )
    }

    @Test
    fun motoristaPodeAvaliarPassageiroAceito() {
        val pedido = base()
        assertEquals(DecisaoAvaliacao.PERMITIDA, AvaliacaoElegibilidade.decidir(pedido))
        assertEquals(
            "carona1_motorista_passageiro",
            AvaliacaoElegibilidade.idDocumento(pedido.caronaId, pedido.avaliadorUid, pedido.avaliadoUid)
        )
    }

    @Test
    fun rejeitaNotaInvalidaEDuplicidade() {
        assertEquals(DecisaoAvaliacao.NOTA_INVALIDA, AvaliacaoElegibilidade.decidir(base().copy(nota = 0)))
        assertEquals(DecisaoAvaliacao.NOTA_INVALIDA, AvaliacaoElegibilidade.decidir(base().copy(nota = 6)))
        assertEquals(DecisaoAvaliacao.DUPLICADA, AvaliacaoElegibilidade.decidir(base().copy(jaExiste = true)))
    }

    @Test
    fun rejeitaQuemNaoParticipaStatusPendenteEHorarioFuturo() {
        assertEquals(
            DecisaoAvaliacao.PARTICIPANTES_INVALIDOS,
            AvaliacaoElegibilidade.decidir(base().copy(avaliadorUid = "outraPessoa", avaliadoUid = "passageiro"))
        )
        assertEquals(
            DecisaoAvaliacao.PARTICIPANTES_INVALIDOS,
            AvaliacaoElegibilidade.decidir(base().copy(avaliadorUid = "motorista", avaliadoUid = "motorista"))
        )
        assertEquals(
            DecisaoAvaliacao.SOLICITACAO_NAO_ACEITA,
            AvaliacaoElegibilidade.decidir(base().copy(statusSolicitacao = "PENDENTE"))
        )
        assertEquals(
            DecisaoAvaliacao.SOLICITACAO_NAO_ACEITA,
            AvaliacaoElegibilidade.decidir(base().copy(statusSolicitacao = "RECUSADA"))
        )
        assertEquals(
            DecisaoAvaliacao.ANTES_DO_HORARIO,
            AvaliacaoElegibilidade.decidir(base().copy(saidaMillis = saidaFutura))
        )
        assertEquals(
            DecisaoAvaliacao.PERMITIDA,
            AvaliacaoElegibilidade.decidir(base().copy(saidaMillis = agora))
        )
    }

    private fun base(): PedidoAvaliacao {
        return PedidoAvaliacao(
            caronaId = "carona1",
            avaliadorUid = "motorista",
            avaliadoUid = "passageiro",
            motoristaUid = "motorista",
            passageiroUid = "passageiro",
            solicitacaoId = "carona1_passageiro",
            statusSolicitacao = "ACEITA",
            saidaMillis = saidaPassada,
            agoraMillis = agora,
            jaExiste = false,
            nota = 5
        )
    }
}

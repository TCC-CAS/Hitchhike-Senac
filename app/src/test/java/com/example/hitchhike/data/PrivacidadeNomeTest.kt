package com.example.hitchhike.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacidadeNomeTest {
    private val agora = 1_700_000_000_000L

    @Test
    fun caronaAbertaOcultaMotoristaEPassageiro() {
        val encerrada = encerrada("ABERTA", agora + 60_000L)
        assertFalse(encerrada)
        assertEquals(
            "Motorista verificado",
            texto(PrivacidadeNome.Papel.MOTORISTA, participante = false, status = null, encerrada = encerrada, nome = "Isabella Duarte")
        )
        assertEquals(
            "Passageiro verificado",
            texto(PrivacidadeNome.Papel.PASSAGEIRO, participante = true, status = "PENDENTE", encerrada = encerrada, nome = "Pedro Costa")
        )
    }

    @Test
    fun solicitacaoAceitaAntesDoHorarioContinuaOculta() {
        val encerrada = encerrada("ABERTA", agora + 60_000L)
        assertEquals(
            "Motorista verificado",
            texto(PrivacidadeNome.Papel.MOTORISTA, participante = true, status = "ACEITA", encerrada = encerrada, nome = "Isabella Duarte")
        )
        assertEquals(
            "Passageiro verificado",
            texto(PrivacidadeNome.Papel.PASSAGEIRO, participante = true, status = "ACEITA", encerrada = encerrada, nome = "Pedro Costa")
        )
    }

    @Test
    fun pendenteERecusadaNaoRevelamNomeDepoisDaSaida() {
        val encerrada = encerrada("ABERTA", agora - 60_000L)
        assertEquals("ENCERRADA", CaronaStatusExibicao.calcular("ABERTA", agora - 60_000L, 1, agora))
        listOf("PENDENTE", "RECUSADA").forEach { status ->
            assertEquals(
                "Motorista verificado",
                texto(PrivacidadeNome.Papel.MOTORISTA, participante = true, status = status, encerrada = encerrada, nome = "Isabella Duarte")
            )
            assertEquals(
                "Passageiro verificado",
                texto(PrivacidadeNome.Papel.PASSAGEIRO, participante = true, status = status, encerrada = encerrada, nome = "Pedro Costa")
            )
        }
    }

    @Test
    fun historicoAceitoMostraPrimeiroNomeEInicial() {
        val encerrada = encerrada("ABERTA", agora)
        assertEquals(
            "Isabella D.",
            texto(PrivacidadeNome.Papel.MOTORISTA, participante = true, status = "ACEITA", encerrada = encerrada, nome = "Isabella Duarte")
        )
        assertEquals(
            "Pedro C.",
            texto(PrivacidadeNome.Papel.PASSAGEIRO, participante = true, status = "ACEITA", encerrada = encerrada, nome = "Pedro Costa")
        )
        assertEquals("Isabella S.", PrivacidadeNome.abreviar("Isabella Duarte Silva"))
    }

    @Test
    fun naoParticipanteNaoConsultaNomeMesmoComDados() {
        val encerrada = encerrada("ABERTA", agora - 60_000L)
        assertFalse(PrivacidadeNome.podeRevelar(false, "ACEITA", encerrada))
        assertEquals(
            "Motorista verificado",
            texto(PrivacidadeNome.Papel.MOTORISTA, participante = false, status = "ACEITA", encerrada = encerrada, nome = "Isabella Duarte")
        )
        assertEquals(
            "Passageiro verificado",
            texto(PrivacidadeNome.Papel.PASSAGEIRO, participante = false, status = "ACEITA", encerrada = encerrada, nome = "Pedro Costa")
        )
    }

    @Test
    fun canceladaNaoUsaOStatusEncerrada() {
        val status = CaronaStatusExibicao.calcular("CANCELADA", agora - 60_000L, 1, agora)
        assertEquals("CANCELADA", status)
        assertEquals(
            "Motorista verificado",
            texto(PrivacidadeNome.Papel.MOTORISTA, participante = true, status = "ACEITA", encerrada = status == "ENCERRADA", nome = "Isabella Duarte")
        )
    }

    @Test
    fun nomeCompletoEmailETelefoneNaoSaoExibidos() {
        assertEquals("Isabella D.", PrivacidadeNome.abreviar("  Isabella   Duarte  "))
        assertNull(PrivacidadeNome.abreviar("isabella.duarte@ufpr.br"))
        assertNull(PrivacidadeNome.abreviar("41 99999-9999"))
        assertNull(PrivacidadeNome.abreviar("   "))
        val revelado = texto(
            PrivacidadeNome.Papel.MOTORISTA,
            participante = true,
            status = "ACEITA",
            encerrada = true,
            nome = "Isabella Duarte isabella@ufpr.br"
        )
        assertEquals("Motorista verificado", revelado)
    }

    @Test
    fun abreviacaoGeradaPodeSerExibidaSemONomeCompleto() {
        val gerada = PrivacidadeNome.abreviar("Isabella Duarte Silva")
        assertEquals("Isabella S.", gerada)
        assertEquals(gerada, PrivacidadeNome.abreviar(gerada))
        assertEquals(
            "Isabella S.",
            texto(
                PrivacidadeNome.Papel.MOTORISTA,
                participante = true,
                status = "ACEITA",
                encerrada = true,
                nome = gerada
            )
        )
    }

    @Test
    fun avaliacaoNaoAlteraAIdentidade() {
        val semNota = texto(PrivacidadeNome.Papel.PASSAGEIRO, true, "ACEITA", true, "Pedro Costa")
        val comNota = texto(PrivacidadeNome.Papel.PASSAGEIRO, true, "ACEITA", true, "Pedro Costa")
        assertEquals(semNota, comNota)
        assertEquals("Pedro C.", semNota)
    }

    private fun encerrada(status: String, saida: Long): Boolean {
        return CaronaStatusExibicao.calcular(status, saida, 2, agora) == "ENCERRADA"
    }

    private fun texto(
        papel: PrivacidadeNome.Papel,
        participante: Boolean,
        status: String?,
        encerrada: Boolean,
        nome: String?
    ): String {
        return PrivacidadeNome.texto(
            PrivacidadeNome.decidir(papel, participante, status, encerrada, nome)
        )
    }
}

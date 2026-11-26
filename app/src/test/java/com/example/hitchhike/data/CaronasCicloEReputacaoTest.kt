package com.example.hitchhike.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaronasCicloEReputacaoTest {

    private val agora = 1_764_100_000_000L
    private val hora = 3_600_000L

    private val modelosReputacao = ReputacaoTexto.Modelos(
        semAvaliacoes = "Ainda sem avaliações",
        singular = "%1\$s estrelas · 1 avaliação",
        plural = "%1\$s estrelas · %2\$d avaliações",
        comCnh = "%1\$s · CNH validada"
    )

    private val modelosViagens = ResumoViagens.Modelos(
        zero = "Nenhuma viagem realizada ainda",
        singular = "1 viagem realizada",
        plural = "%1\$d viagens realizadas"
    )

    @Test
    fun `statusExibicao segue ciclo de vida e nunca promove viagem antiga para CONCLUIDA automaticamente`() {
        // Futura com vagas -> ABERTA
        assertEquals(
            "ABERTA",
            CaronaStatusExibicao.calcular("ABERTA", agora + 2 * hora, 2, agora)
        )
        // Futura sem vagas -> LOTADA
        assertEquals(
            "LOTADA",
            CaronaStatusExibicao.calcular("ABERTA", agora + 2 * hora, 0, agora)
        )
        // Horário de saída chegou (mas dentro das 6h) sem iniciar -> AGUARDANDO_INICIO (nunca CONCLUIDA)
        assertEquals(
            "AGUARDANDO_INICIO",
            CaronaStatusExibicao.calcular("ABERTA", agora - 2 * hora, 2, agora)
        )
        // Mais de 6h após a saída sem iniciar -> EXPIRADA
        assertEquals(
            "EXPIRADA",
            CaronaStatusExibicao.calcular("ABERTA", agora - 7 * hora, 2, agora)
        )
        // EM_ANDAMENTO dentro de 24h -> EM_ANDAMENTO
        assertEquals(
            "EM_ANDAMENTO",
            CaronaStatusExibicao.calcular("EM_ANDAMENTO", agora - 3 * hora, 1, agora, iniciadaEmMillis = agora - 2 * hora)
        )
        // EM_ANDAMENTO há mais de 24h sem conclusão -> EXPIRADA
        assertEquals(
            "EXPIRADA",
            CaronaStatusExibicao.calcular("EM_ANDAMENTO", agora - 26 * hora, 1, agora, iniciadaEmMillis = agora - 25 * hora)
        )
        // CONCLUIDA e CANCELADA preservadas
        assertEquals(
            "CONCLUIDA",
            CaronaStatusExibicao.calcular("CONCLUIDA", agora - 10 * hora, 0, agora)
        )
        assertEquals(
            "CANCELADA",
            CaronaStatusExibicao.calcular("CANCELADA", agora + 2 * hora, 2, agora)
        )
        // Registro legado ENCERRADA no Room não é promovido para CONCLUIDA
        assertEquals(
            "ENCERRADA",
            CaronaStatusExibicao.calcular("ENCERRADA", agora - 10 * hora, 0, agora)
        )
        assertTrue(CaronaStatusExibicao.estadoFinal("CONCLUIDA"))
        assertTrue(CaronaStatusExibicao.estadoFinal("CANCELADA"))
        assertTrue(CaronaStatusExibicao.estadoFinal("EXPIRADA"))
        assertFalse(CaronaStatusExibicao.estadoFinal("ABERTA"))
        assertFalse(CaronaStatusExibicao.estadoFinal("EM_ANDAMENTO"))
    }

    @Test
    fun `formatacao de reputacao exibe media em pt-BR ou Ainda sem avaliacoes sem nota zero ficticia`() {
        assertEquals(
            "Ainda sem avaliações",
            ReputacaoTexto.formatar(null, 0, modelosReputacao)
        )
        assertEquals(
            "Ainda sem avaliações",
            ReputacaoTexto.formatar(0.0, 0, modelosReputacao)
        )
        assertEquals(
            "5,0 estrelas · 1 avaliação",
            ReputacaoTexto.formatar(5.0, 1, modelosReputacao)
        )
        assertEquals(
            "4,8 estrelas · 23 avaliações",
            ReputacaoTexto.formatar(4.8, 23, modelosReputacao)
        )
        assertEquals(
            "4,8 estrelas · 23 avaliações · CNH validada",
            ReputacaoTexto.formatarComCnh(4.8, 23, cnhValidada = true, modelos = modelosReputacao)
        )
        assertEquals(
            "Ainda sem avaliações · CNH validada",
            ReputacaoTexto.formatarComCnh(null, 0, cnhValidada = true, modelos = modelosReputacao)
        )
    }

    @Test
    fun `separa quantidade de viagens realizadas da quantidade de avaliacoes recebidas`() {
        val minha = CaronasParser.minhaReputacao(
            mapOf(
                "media" to null,
                "quantidade" to 0,
                "viagens" to 3,
                "viagensComoMotorista" to 2,
                "viagensComoPassageiro" to 1,
                "cnhValidada" to true
            )
        )
        assertNull(minha.media)
        assertEquals(0, minha.quantidade)
        assertEquals(3, minha.viagens)
        assertEquals("Ainda sem avaliações", ReputacaoTexto.formatar(minha.media, minha.quantidade, modelosReputacao))
        assertEquals("3 viagens realizadas", ResumoViagens.viagens(minha.viagens, modelosViagens))
        assertEquals("1 viagem realizada", ResumoViagens.viagens(1, modelosViagens))
        assertEquals("Nenhuma viagem realizada ainda", ResumoViagens.viagens(0, modelosViagens))
    }

    @Test
    fun `privacidade exibe rotulo generico antes da conclusao confirmada e primeiro nome com inicial no historico`() {
        assertEquals("Motorista", PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.MOTORISTA))
        assertEquals("Passageiro", PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.PASSAGEIRO))

        assertEquals(
            "Motorista",
            PrivacidadeNome.nomeParticipanteConfirmado(
                papel = PrivacidadeNome.Papel.MOTORISTA,
                concluidaEConfirmada = false,
                nomeOuAbreviado = "Mariana Souza"
            )
        )
        assertEquals(
            "Mariana S.",
            PrivacidadeNome.nomeParticipanteConfirmado(
                papel = PrivacidadeNome.Papel.MOTORISTA,
                concluidaEConfirmada = true,
                nomeOuAbreviado = "Mariana Souza"
            )
        )
        assertEquals(
            "Pedro A.",
            PrivacidadeNome.nomeParticipanteConfirmado(
                papel = PrivacidadeNome.Papel.PASSAGEIRO,
                concluidaEConfirmada = true,
                nomeOuAbreviado = "Pedro Augusto Almeida"
            )
        )
        assertEquals(
            "Cher",
            PrivacidadeNome.nomeParticipanteConfirmado(
                papel = PrivacidadeNome.Papel.PASSAGEIRO,
                concluidaEConfirmada = true,
                nomeOuAbreviado = "Cher"
            )
        )
    }

    @Test
    fun `parser de situacao e participantes respeita acoes permitidas e ocultacao de veiculo`() {
        val situacaoAntesAceite = CaronasParser.situacao(
            mapOf(
                "caronaId" to "c1",
                "statusExibicao" to "ABERTA",
                "papel" to "PASSAGEIRO",
                "acoes" to mapOf("iniciar" to false, "concluir" to false, "cancelar" to false),
                "veiculo" to null,
                "motorista" to mapOf("media" to 4.8, "quantidade" to 23, "viagens" to 31, "cnhValidada" to true)
            )
        )
        assertNull(situacaoAntesAceite.veiculo)
        assertFalse(situacaoAntesAceite.acoes.iniciar)
        assertFalse(situacaoAntesAceite.acoes.avaliar)
        assertEquals(4.8, situacaoAntesAceite.motorista?.media!!, 0.001)

        val situacaoAceito = CaronasParser.situacao(
            mapOf(
                "caronaId" to "c1",
                "statusExibicao" to "EM_ANDAMENTO",
                "papel" to "PASSAGEIRO",
                "acoes" to mapOf("confirmarParticipacao" to true),
                "veiculo" to mapOf("modelo" to "Honda Fit", "cor" to "Prata", "placa" to "ABC1D23")
            )
        )
        assertTrue(situacaoAceito.acoes.confirmarParticipacao)
        assertEquals("ABC1D23", situacaoAceito.veiculo?.placa)
    }

    @Test
    fun `mapeamento de codigos de erro do backend retorna mensagens claras inclusive para bloqueios financeiros`() {
        val textos = CaronasMensagensErro.Textos(
            servidorIndisponivel = "Servidor indisponível",
            sessaoInvalida = "Sessão inválida",
            naoConfigurado = "Não configurado",
            generico = "Erro genérico",
            porCodigo = mapOf(
                "PAGAMENTO_APROVADO" to "Não é possível cancelar: já existe pagamento aprovado nesta carona.",
                "PAGAMENTO_EM_ANDAMENTO" to "Não é possível cancelar: há um pagamento em andamento.",
                "CHECKOUT_ABERTO" to "Não é possível cancelar: há um checkout de pagamento aberto.",
                "FORA_DA_JANELA" to "A viagem só pode ser iniciada a partir de 30 minutos antes da saída."
            )
        )

        val erroAprovado = CaronasParser.erro(409, mapOf("codigo" to "PAGAMENTO_APROVADO", "error" to "msg servidor"))
        assertEquals(
            "Não é possível cancelar: já existe pagamento aprovado nesta carona.",
            CaronasMensagensErro.resolver(erroAprovado, textos)
        )

        val erroCheckout = CaronasParser.erro(409, mapOf("codigo" to "CHECKOUT_ABERTO"))
        assertEquals(
            "Não é possível cancelar: há um checkout de pagamento aberto.",
            CaronasMensagensErro.resolver(erroCheckout, textos)
        )

        val erroRede = CaronasException(CaronasException.REDE, null)
        assertEquals("Servidor indisponível", CaronasMensagensErro.resolver(erroRede, textos))
    }
}

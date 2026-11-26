package com.example.hitchhike.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IntegridadeSolicitacaoCaronaTest {

    private val agora = 1_764_100_000_000L
    private val saidaFutura = agora + 3_600_000L
    private val saidaPassada = agora - 60_000L

    @Test
    fun `permite nova solicitacao em carona aberta com vagas e sem pedido anterior`() {
        val decisao = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
            ContextoCaronaParaSolicitacao(
                caronaExiste = true,
                motoristaUid = "motorista-1",
                passageiroUid = "passageiro-1",
                statusCarona = "ABERTA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                solicitacaoJaExiste = false
            )
        )

        assertEquals(DecisaoSolicitacaoCarona.PERMITIDA, decisao)
    }

    @Test
    fun `bloqueia solicitacao duplicada inclusive em tentativas simultaneas na mesma carona`() {
        val decisao = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
            ContextoCaronaParaSolicitacao(
                caronaExiste = true,
                motoristaUid = "motorista-1",
                passageiroUid = "passageiro-1",
                statusCarona = "ABERTA",
                vagasDisponiveis = 3L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                solicitacaoJaExiste = true
            )
        )

        assertEquals(DecisaoSolicitacaoCarona.SOLICITACAO_DUPLICADA, decisao)
        assertEquals(
            "Você já pediu vaga nesta carona.",
            IntegridadeSolicitacaoCarona.mensagemNovaSolicitacao(decisao)
        )
    }

    @Test
    fun `bloqueia autosolicitacao do motorista na propria carona`() {
        val decisao = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
            ContextoCaronaParaSolicitacao(
                caronaExiste = true,
                motoristaUid = "motorista-1",
                passageiroUid = "motorista-1",
                statusCarona = "ABERTA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                solicitacaoJaExiste = false
            )
        )

        assertEquals(DecisaoSolicitacaoCarona.AUTOSOLICITACAO, decisao)
    }

    @Test
    fun `bloqueia solicitacao em carona cancelada lotada ou vencida`() {
        val cancelada = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
            ContextoCaronaParaSolicitacao(
                caronaExiste = true,
                motoristaUid = "motorista-1",
                passageiroUid = "passageiro-1",
                statusCarona = "CANCELADA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                solicitacaoJaExiste = false
            )
        )
        assertEquals(DecisaoSolicitacaoCarona.CARONA_CANCELADA, cancelada)

        val lotada = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
            ContextoCaronaParaSolicitacao(
                caronaExiste = true,
                motoristaUid = "motorista-1",
                passageiroUid = "passageiro-1",
                statusCarona = "LOTADA",
                vagasDisponiveis = 0L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                solicitacaoJaExiste = false
            )
        )
        assertEquals(DecisaoSolicitacaoCarona.CARONA_LOTADA_OU_SEM_VAGAS, lotada)

        val vencida = IntegridadeSolicitacaoCarona.validarNovaSolicitacao(
            ContextoCaronaParaSolicitacao(
                caronaExiste = true,
                motoristaUid = "motorista-1",
                passageiroUid = "passageiro-1",
                statusCarona = "ABERTA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaPassada,
                agoraMillis = agora,
                solicitacaoJaExiste = false
            )
        )
        assertEquals(DecisaoSolicitacaoCarona.CARONA_ENCERRADA_OU_VENCIDA, vencida)
    }

    @Test
    fun `aceite valido pelo motorista responsavel e apto na ultima vaga`() {
        val decisao = IntegridadeSolicitacaoCarona.validarAceite(
            ContextoTransicaoSolicitacao(
                solicitacaoExiste = true,
                motoristaDaSolicitacaoUid = "motorista-1",
                motoristaDaCaronaUid = "motorista-1",
                usuarioAutenticadoUid = "motorista-1",
                statusSolicitacaoAtual = "PENDENTE",
                statusCaronaAtual = "ABERTA",
                vagasDisponiveis = 1L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                motoristaApto = true
            )
        )

        assertEquals(DecisaoTransicaoSolicitacao.PERMITIDA, decisao)
    }

    @Test
    fun `impede que dois passageiros ocupem a ultima vaga ou aceite sem vagas`() {
        val segundoAceiteNaUltimaVaga = IntegridadeSolicitacaoCarona.validarAceite(
            ContextoTransicaoSolicitacao(
                solicitacaoExiste = true,
                motoristaDaSolicitacaoUid = "motorista-1",
                motoristaDaCaronaUid = "motorista-1",
                usuarioAutenticadoUid = "motorista-1",
                statusSolicitacaoAtual = "PENDENTE",
                statusCaronaAtual = "LOTADA",
                vagasDisponiveis = 0L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                motoristaApto = true
            )
        )

        assertEquals(DecisaoTransicaoSolicitacao.CARONA_INDISPONIVEL, segundoAceiteNaUltimaVaga)

        val semVagas = IntegridadeSolicitacaoCarona.validarAceite(
            ContextoTransicaoSolicitacao(
                solicitacaoExiste = true,
                motoristaDaSolicitacaoUid = "motorista-1",
                motoristaDaCaronaUid = "motorista-1",
                usuarioAutenticadoUid = "motorista-1",
                statusSolicitacaoAtual = "PENDENTE",
                statusCaronaAtual = "ABERTA",
                vagasDisponiveis = 0L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                motoristaApto = true
            )
        )

        assertEquals(DecisaoTransicaoSolicitacao.SEM_VAGAS, semVagas)
    }

    @Test
    fun `impede aceite ou recusa por terceiro que nao seja o motorista responsavel`() {
        val aceiteTerceiro = IntegridadeSolicitacaoCarona.validarAceite(
            ContextoTransicaoSolicitacao(
                solicitacaoExiste = true,
                motoristaDaSolicitacaoUid = "motorista-1",
                motoristaDaCaronaUid = "motorista-1",
                usuarioAutenticadoUid = "intruso-99",
                statusSolicitacaoAtual = "PENDENTE",
                statusCaronaAtual = "ABERTA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                motoristaApto = true
            )
        )

        assertEquals(DecisaoTransicaoSolicitacao.NAO_EH_MOTORISTA_RESPONSAVEL, aceiteTerceiro)

        val recusaTerceiro = IntegridadeSolicitacaoCarona.validarRecusa(
            solicitacaoExiste = true,
            motoristaDaSolicitacaoUid = "motorista-1",
            usuarioAutenticadoUid = "intruso-99",
            statusSolicitacaoAtual = "PENDENTE"
        )

        assertEquals(DecisaoTransicaoSolicitacao.NAO_EH_MOTORISTA_RESPONSAVEL, recusaTerceiro)
    }

    @Test
    fun `valida transicoes de status impedindo reprocessar solicitacao ja aceita ou recusada`() {
        val jaAceita = IntegridadeSolicitacaoCarona.validarAceite(
            ContextoTransicaoSolicitacao(
                solicitacaoExiste = true,
                motoristaDaSolicitacaoUid = "motorista-1",
                motoristaDaCaronaUid = "motorista-1",
                usuarioAutenticadoUid = "motorista-1",
                statusSolicitacaoAtual = "ACEITA",
                statusCaronaAtual = "ABERTA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                motoristaApto = true
            )
        )

        assertEquals(DecisaoTransicaoSolicitacao.TRANSICAO_INVALIDA_JA_RESPONDIDA, jaAceita)

        val recusaAposAceite = IntegridadeSolicitacaoCarona.validarRecusa(
            solicitacaoExiste = true,
            motoristaDaSolicitacaoUid = "motorista-1",
            usuarioAutenticadoUid = "motorista-1",
            statusSolicitacaoAtual = "ACEITA"
        )

        assertEquals(DecisaoTransicaoSolicitacao.TRANSICAO_INVALIDA_JA_RESPONDIDA, recusaAposAceite)
    }

    @Test
    fun `impede aceite de passageiro quando motorista esta com CNH vencida ou nao validada`() {
        val decisao = IntegridadeSolicitacaoCarona.validarAceite(
            ContextoTransicaoSolicitacao(
                solicitacaoExiste = true,
                motoristaDaSolicitacaoUid = "motorista-1",
                motoristaDaCaronaUid = "motorista-1",
                usuarioAutenticadoUid = "motorista-1",
                statusSolicitacaoAtual = "PENDENTE",
                statusCaronaAtual = "ABERTA",
                vagasDisponiveis = 2L,
                saidaMillis = saidaFutura,
                agoraMillis = agora,
                motoristaApto = false
            )
        )

        assertEquals(DecisaoTransicaoSolicitacao.MOTORISTA_NAO_APTO, decisao)
        assertEquals(
            "Sua CNH está vencida. Envie uma CNH renovada para voltar a dirigir.",
            IntegridadeSolicitacaoCarona.mensagemTransicao(
                decisao,
                "Sua CNH está vencida. Envie uma CNH renovada para voltar a dirigir."
            )
        )
    }
}

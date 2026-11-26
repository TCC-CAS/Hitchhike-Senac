package com.example.hitchhike.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElegibilidadeMotoristaTest {

    private val hoje = "2026-11-24"

    @Test
    fun motoristaAprovadoComCnhVigenteEAtivoEhApto() {
        val r = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = "2030-11-18",
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertTrue(r.apto)
        assertEquals(EstadoMotorista.APTO, r.estado)
        assertFalse(r.venceEmBreve)
    }

    @Test
    fun validadeNoProprioDiaDeHojeEhConsideradaVigenteAteFimDoDia() {
        val r = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = hoje,
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertTrue(r.apto)
        assertEquals(0, r.diasRestantes)
        assertTrue(r.venceEmBreve)
    }

    @Test
    fun bloqueiaCnhVencidaMesmoQuandoCondutorValidadoAindaEstaTrue() {
        val r = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = "2026-11-23",
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertFalse(r.apto)
        assertEquals(EstadoMotorista.CNH_VENCIDA, r.estado)
        assertEquals(-1, r.diasRestantes)
    }

    @Test
    fun exigeRegularizacaoParaCadastrosAntigosAprovadosSemConferenciaDeCnh() {
        val semValidade = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = null,
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertFalse(semValidade.apto)
        assertEquals(EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA, semValidade.estado)
        assertNull(semValidade.diasRestantes)

        val validadeInvalida = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = "2026-02-30",
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertFalse(validadeInvalida.apto)
        assertEquals(EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA, validadeInvalida.estado)
    }

    @Test
    fun bloqueiaRevisaoPendenteNaoValidadoPerfilInativoEPapelPassageiro() {
        val revisao = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = "2030-01-01",
            cnhRevisaoNecessaria = true,
            hojeIsoSp = hoje
        )
        assertFalse(revisao.apto)
        assertEquals(EstadoMotorista.REVISAO_PENDENTE, revisao.estado)

        val naoValidado = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = false,
            papelAtivo = "MOTORISTA",
            cnhValidade = "2030-01-01",
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertFalse(naoValidado.apto)
        assertEquals(EstadoMotorista.NAO_VALIDADO, naoValidado.estado)

        val inativo = ElegibilidadeMotorista.avaliar(
            status = "SUSPENSO",
            condutorValidado = true,
            papelAtivo = "MOTORISTA",
            cnhValidade = "2030-01-01",
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje
        )
        assertFalse(inativo.apto)
        assertEquals(EstadoMotorista.PERFIL_INATIVO, inativo.estado)

        val passageiro = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = true,
            papelAtivo = "PASSAGEIRO",
            cnhValidade = "2030-01-01",
            cnhRevisaoNecessaria = false,
            hojeIsoSp = hoje,
            exigirPapelMotorista = true
        )
        assertFalse(passageiro.apto)
        assertEquals(EstadoMotorista.PAPEL_PASSAGEIRO, passageiro.estado)
        assertTrue(passageiro.aprovadoComCnhVigente)
    }

    @Test
    fun solicitacaoRecusadaTemPrioridadeSobreRevisaoNecessariaEExibeMotivo() {
        // Mesmo que cnhRevisaoNecessaria ainda esteja true no documento de usuario (legado),
        // o status RECUSADA da solicitação prevalece e retorna SOLICITACAO_RECUSADA com o motivo.
        val recusada = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = false,
            papelAtivo = "PASSAGEIRO",
            cnhValidade = "2030-11-18",
            cnhRevisaoNecessaria = true,
            hojeIsoSp = hoje,
            exigirPapelMotorista = false,
            solicitacaoStatus = "RECUSADA",
            motivoRecusa = "Categoria incompatível ou documento ilegível."
        )
        assertFalse(recusada.apto)
        assertEquals(EstadoMotorista.SOLICITACAO_RECUSADA, recusada.estado)
        assertEquals("Categoria incompatível ou documento ilegível.", recusada.motivoRecusa)
        assertTrue(
            ElegibilidadeMotorista.mensagemBloqueio(recusada.estado, recusada.motivoRecusa)
                .contains("Motivo: Categoria incompatível ou documento ilegível.")
        )

        // Após reenvio do PDF, a solicitação volta para PENDENTE e o estado retorna para REVISAO_PENDENTE
        val reenviada = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = false,
            papelAtivo = "PASSAGEIRO",
            cnhValidade = null,
            cnhRevisaoNecessaria = true,
            hojeIsoSp = hoje,
            exigirPapelMotorista = false,
            solicitacaoStatus = "PENDENTE",
            motivoRecusa = null
        )
        assertFalse(reenviada.apto)
        assertEquals(EstadoMotorista.REVISAO_PENDENTE, reenviada.estado)
        assertNull(reenviada.motivoRecusa)
    }
}

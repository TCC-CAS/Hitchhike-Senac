package com.example.hitchhike.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class EstadoMotorista {
    APTO,
    PERFIL_INATIVO,
    SOLICITACAO_RECUSADA,
    NAO_VALIDADO,
    REGULARIZACAO_CNH_NECESSARIA,
    REVISAO_PENDENTE,
    CNH_VENCIDA,
    PAPEL_PASSAGEIRO
}

data class ResultadoElegibilidadeMotorista(
    val estado: EstadoMotorista,
    val cnhValidade: String?,
    val diasRestantes: Int?,
    val motivoRecusa: String? = null
) {
    val apto: Boolean get() = estado == EstadoMotorista.APTO
    val aprovadoComCnhVigente: Boolean
        get() = estado == EstadoMotorista.APTO || estado == EstadoMotorista.PAPEL_PASSAGEIRO
    val venceEmBreve: Boolean
        get() = diasRestantes != null && diasRestantes in 0..30
}

object ElegibilidadeMotorista {

    private val REGEX_DATA = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private const val FUSO_SAO_PAULO = "America/Sao_Paulo"
    private const val MS_POR_DIA = 86_400_000L

    fun hojeIsoSaoPaulo(agoraMillis: Long = System.currentTimeMillis()): String {
        val formato = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(FUSO_SAO_PAULO)
        }
        return formato.format(Date(agoraMillis))
    }

    fun dataIsoValida(dataIso: String?): Boolean {
        if (dataIso.isNullOrBlank() || !REGEX_DATA.matches(dataIso)) return false
        return try {
            val formato = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parseada = formato.parse(dataIso) ?: return false
            formato.format(parseada) == dataIso
        } catch (e: Exception) {
            false
        }
    }

    fun calcularDiasRestantes(validadeIso: String, hojeIso: String): Int? {
        if (!dataIsoValida(validadeIso) || !dataIsoValida(hojeIso)) return null
        return try {
            val formato = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val validadeMs = formato.parse(validadeIso)?.time ?: return null
            val hojeMs = formato.parse(hojeIso)?.time ?: return null
            ((validadeMs - hojeMs) / MS_POR_DIA).toInt()
        } catch (e: Exception) {
            null
        }
    }

    fun normalizarStatusSolicitacao(status: String?): String? {
        val limpo = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (limpo) {
            "APROVADO", "APROVADA" -> "APROVADA"
            "RECUSADO", "RECUSADA", "REJEITADO", "REJEITADA" -> "RECUSADA"
            "PENDENTE", "EM_ANALISE", "REVISAO", "REVISAO_NECESSARIA" -> "PENDENTE"
            else -> limpo
        }
    }

    /**
     * Avalia se o usuário pode oferecer caronas ou aceitar solicitações de passageiros.
     * Trata recusa administrativa com prioridade sobre cnhRevisaoNecessaria,
     * trata cadastros antigos aprovados sem registro de conferência de CNH (cnhValidade ausente)
     * exigindo regularização, e bloqueia CNH vencida mesmo com o servidor administrativo desligado.
     */
    fun avaliar(
        status: String?,
        condutorValidado: Boolean,
        papelAtivo: String?,
        cnhValidade: String?,
        cnhRevisaoNecessaria: Boolean,
        hojeIsoSp: String = hojeIsoSaoPaulo(),
        exigirPapelMotorista: Boolean = true,
        solicitacaoStatus: String? = null,
        motivoRecusa: String? = null
    ): ResultadoElegibilidadeMotorista {
        val validadeLimpa = cnhValidade?.trim()?.takeIf { it.isNotEmpty() }
        val dias = if (validadeLimpa != null) calcularDiasRestantes(validadeLimpa, hojeIsoSp) else null
        val statusSolicitacaoNorm = normalizarStatusSolicitacao(solicitacaoStatus)
        val motivoLimpo = motivoRecusa?.trim()?.takeIf { it.isNotEmpty() }

        if (!status.isNullOrBlank() && status != "ATIVO") {
            return ResultadoElegibilidadeMotorista(EstadoMotorista.PERFIL_INATIVO, validadeLimpa, dias)
        }
        // A decisão administrativa de RECUSA tem prioridade sobre cnhRevisaoNecessaria
        if (statusSolicitacaoNorm == "RECUSADA" && !condutorValidado) {
            return ResultadoElegibilidadeMotorista(
                estado = EstadoMotorista.SOLICITACAO_RECUSADA,
                cnhValidade = validadeLimpa,
                diasRestantes = dias,
                motivoRecusa = motivoLimpo
            )
        }
        if (dias != null && dias < 0) {
            return ResultadoElegibilidadeMotorista(EstadoMotorista.CNH_VENCIDA, validadeLimpa, dias)
        }
        if (cnhRevisaoNecessaria || (statusSolicitacaoNorm == "PENDENTE" && !condutorValidado)) {
            return ResultadoElegibilidadeMotorista(EstadoMotorista.REVISAO_PENDENTE, validadeLimpa, dias)
        }
        if (!condutorValidado) {
            return ResultadoElegibilidadeMotorista(EstadoMotorista.NAO_VALIDADO, validadeLimpa, dias)
        }
        if (validadeLimpa == null || dias == null) {
            return ResultadoElegibilidadeMotorista(
                EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA,
                validadeLimpa,
                null
            )
        }
        if (exigirPapelMotorista && papelAtivo != PapelUsuario.MOTORISTA.name) {
            return ResultadoElegibilidadeMotorista(EstadoMotorista.PAPEL_PASSAGEIRO, validadeLimpa, dias)
        }
        return ResultadoElegibilidadeMotorista(EstadoMotorista.APTO, validadeLimpa, dias)
    }

    fun mensagemBloqueio(estado: EstadoMotorista, motivoRecusa: String? = null): String = when (estado) {
        EstadoMotorista.APTO -> "Condutor habilitado e com CNH vigente."
        EstadoMotorista.PERFIL_INATIVO -> "Seu cadastro não está ativo para atuar como motorista."
        EstadoMotorista.SOLICITACAO_RECUSADA -> {
            val motivo = motivoRecusa?.trim()?.takeIf { it.isNotEmpty() }
            if (motivo != null) {
                "Sua solicitação de motorista foi recusada pela administração.\nMotivo: $motivo\nEnvie um novo PDF original da CNH Digital para nova análise."
            } else {
                "Sua solicitação de motorista foi recusada pela administração. Verifique os dados do veículo e envie um novo PDF original da CNH Digital para nova análise."
            }
        }
        EstadoMotorista.NAO_VALIDADO -> "Para atuar como motorista, envie o PDF da CNH e aguarde a aprovação."
        EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA ->
            "Sua aprovação é anterior à conferência digital de CNH. Envie o PDF original da CNH para regularizar seu acesso de motorista."
        EstadoMotorista.REVISAO_PENDENTE ->
            "Sua CNH está aguardando conferência ou revisão administrativa."
        EstadoMotorista.CNH_VENCIDA ->
            "Sua CNH está vencida. Envie o PDF atualizado da CNH renovada para voltar a oferecer caronas."
        EstadoMotorista.PAPEL_PASSAGEIRO ->
            "Ative o modo Motorista no seu perfil para oferecer ou confirmar caronas."
    }
}

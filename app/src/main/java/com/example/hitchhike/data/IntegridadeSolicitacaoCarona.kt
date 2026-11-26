package com.example.hitchhike.data

data class ContextoCaronaParaSolicitacao(
    val caronaExiste: Boolean,
    val motoristaUid: String?,
    val passageiroUid: String,
    val statusCarona: String?,
    val vagasDisponiveis: Long,
    val saidaMillis: Long?,
    val agoraMillis: Long,
    val solicitacaoJaExiste: Boolean
)

enum class DecisaoSolicitacaoCarona {
    PERMITIDA,
    CARONA_INEXISTENTE,
    AUTOSOLICITACAO,
    SOLICITACAO_DUPLICADA,
    CARONA_CANCELADA,
    CARONA_LOTADA_OU_SEM_VAGAS,
    CARONA_ENCERRADA_OU_VENCIDA,
    CARONA_INDISPONIVEL
}

data class ContextoTransicaoSolicitacao(
    val solicitacaoExiste: Boolean,
    val motoristaDaSolicitacaoUid: String?,
    val motoristaDaCaronaUid: String?,
    val usuarioAutenticadoUid: String,
    val statusSolicitacaoAtual: String?,
    val statusCaronaAtual: String?,
    val vagasDisponiveis: Long,
    val saidaMillis: Long?,
    val agoraMillis: Long,
    val motoristaApto: Boolean
)

enum class DecisaoTransicaoSolicitacao {
    PERMITIDA,
    SOLICITACAO_INEXISTENTE,
    NAO_EH_MOTORISTA_RESPONSAVEL,
    TRANSICAO_INVALIDA_JA_RESPONDIDA,
    MOTORISTA_NAO_APTO,
    CARONA_INDISPONIVEL,
    SEM_VAGAS,
    HORARIO_VENCIDO
}

object IntegridadeSolicitacaoCarona {

    fun validarNovaSolicitacao(ctx: ContextoCaronaParaSolicitacao): DecisaoSolicitacaoCarona {
        if (!ctx.caronaExiste) return DecisaoSolicitacaoCarona.CARONA_INEXISTENTE
        if (ctx.motoristaUid.isNullOrBlank()) return DecisaoSolicitacaoCarona.CARONA_INDISPONIVEL
        if (ctx.motoristaUid == ctx.passageiroUid) return DecisaoSolicitacaoCarona.AUTOSOLICITACAO
        if (ctx.solicitacaoJaExiste) return DecisaoSolicitacaoCarona.SOLICITACAO_DUPLICADA
        if (ctx.statusCarona == StatusCarona.CANCELADA.name) return DecisaoSolicitacaoCarona.CARONA_CANCELADA
        if (ctx.saidaMillis == null || ctx.saidaMillis <= ctx.agoraMillis) {
            return DecisaoSolicitacaoCarona.CARONA_ENCERRADA_OU_VENCIDA
        }
        if (ctx.vagasDisponiveis <= 0L || ctx.statusCarona == StatusCarona.LOTADA.name) {
            return DecisaoSolicitacaoCarona.CARONA_LOTADA_OU_SEM_VAGAS
        }
        if (ctx.statusCarona != StatusCarona.ABERTA.name) {
            return DecisaoSolicitacaoCarona.CARONA_INDISPONIVEL
        }
        return DecisaoSolicitacaoCarona.PERMITIDA
    }

    fun validarAceite(ctx: ContextoTransicaoSolicitacao): DecisaoTransicaoSolicitacao {
        if (!ctx.solicitacaoExiste) return DecisaoTransicaoSolicitacao.SOLICITACAO_INEXISTENTE
        if (ctx.motoristaDaSolicitacaoUid.isNullOrBlank() ||
            ctx.motoristaDaSolicitacaoUid != ctx.usuarioAutenticadoUid ||
            ctx.motoristaDaCaronaUid != ctx.usuarioAutenticadoUid
        ) {
            return DecisaoTransicaoSolicitacao.NAO_EH_MOTORISTA_RESPONSAVEL
        }
        if (ctx.statusSolicitacaoAtual != StatusSolicitacao.PENDENTE.name) {
            return DecisaoTransicaoSolicitacao.TRANSICAO_INVALIDA_JA_RESPONDIDA
        }
        if (!ctx.motoristaApto) {
            return DecisaoTransicaoSolicitacao.MOTORISTA_NAO_APTO
        }
        if (ctx.statusCaronaAtual != StatusCarona.ABERTA.name) {
            return DecisaoTransicaoSolicitacao.CARONA_INDISPONIVEL
        }
        if (ctx.vagasDisponiveis <= 0L) {
            return DecisaoTransicaoSolicitacao.SEM_VAGAS
        }
        if (ctx.saidaMillis == null || ctx.saidaMillis <= ctx.agoraMillis) {
            return DecisaoTransicaoSolicitacao.HORARIO_VENCIDO
        }
        return DecisaoTransicaoSolicitacao.PERMITIDA
    }

    fun validarRecusa(
        solicitacaoExiste: Boolean,
        motoristaDaSolicitacaoUid: String?,
        usuarioAutenticadoUid: String,
        statusSolicitacaoAtual: String?
    ): DecisaoTransicaoSolicitacao {
        if (!solicitacaoExiste) return DecisaoTransicaoSolicitacao.SOLICITACAO_INEXISTENTE
        if (motoristaDaSolicitacaoUid.isNullOrBlank() || motoristaDaSolicitacaoUid != usuarioAutenticadoUid) {
            return DecisaoTransicaoSolicitacao.NAO_EH_MOTORISTA_RESPONSAVEL
        }
        if (statusSolicitacaoAtual != StatusSolicitacao.PENDENTE.name) {
            return DecisaoTransicaoSolicitacao.TRANSICAO_INVALIDA_JA_RESPONDIDA
        }
        return DecisaoTransicaoSolicitacao.PERMITIDA
    }

    fun mensagemNovaSolicitacao(decisao: DecisaoSolicitacaoCarona): String = when (decisao) {
        DecisaoSolicitacaoCarona.PERMITIDA -> "Solicitação permitida."
        DecisaoSolicitacaoCarona.CARONA_INEXISTENTE -> "Carona não encontrada."
        DecisaoSolicitacaoCarona.AUTOSOLICITACAO -> "Você não pode solicitar sua própria carona."
        DecisaoSolicitacaoCarona.SOLICITACAO_DUPLICADA -> "Você já pediu vaga nesta carona."
        DecisaoSolicitacaoCarona.CARONA_CANCELADA -> "Esta carona foi cancelada pelo motorista."
        DecisaoSolicitacaoCarona.CARONA_LOTADA_OU_SEM_VAGAS -> "Esta carona não possui mais vagas disponíveis."
        DecisaoSolicitacaoCarona.CARONA_ENCERRADA_OU_VENCIDA -> "Esta carona já foi encerrada ou o horário de saída já passou."
        DecisaoSolicitacaoCarona.CARONA_INDISPONIVEL -> "Esta carona não está disponível."
    }

    fun mensagemTransicao(decisao: DecisaoTransicaoSolicitacao, mensagemElegibilidade: String? = null): String = when (decisao) {
        DecisaoTransicaoSolicitacao.PERMITIDA -> "Operação permitida."
        DecisaoTransicaoSolicitacao.SOLICITACAO_INEXISTENTE -> "Solicitação não encontrada."
        DecisaoTransicaoSolicitacao.NAO_EH_MOTORISTA_RESPONSAVEL -> "Você não é o motorista responsável por esta carona."
        DecisaoTransicaoSolicitacao.TRANSICAO_INVALIDA_JA_RESPONDIDA -> "Esta solicitação já foi respondida."
        DecisaoTransicaoSolicitacao.MOTORISTA_NAO_APTO ->
            mensagemElegibilidade ?: "Motorista sem autorização ou CNH vigente para confirmar caronas."
        DecisaoTransicaoSolicitacao.CARONA_INDISPONIVEL -> "Carona indisponível."
        DecisaoTransicaoSolicitacao.SEM_VAGAS -> "Não há vagas disponíveis."
        DecisaoTransicaoSolicitacao.HORARIO_VENCIDO -> "O horário da carona já passou."
    }
}

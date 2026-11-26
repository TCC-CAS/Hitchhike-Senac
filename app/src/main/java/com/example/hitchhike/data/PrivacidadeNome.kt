package com.example.hitchhike.data

object PrivacidadeNome {
    enum class Papel {
        MOTORISTA,
        PASSAGEIRO
    }

    sealed class Exibicao {
        data object MotoristaVerificado : Exibicao()
        data object PassageiroVerificado : Exibicao()
        data class Abreviada(val nome: String) : Exibicao()
    }

    fun decidir(
        papel: Papel,
        participante: Boolean,
        statusSolicitacao: String?,
        encerrada: Boolean,
        nomeCompleto: String?
    ): Exibicao {
        val abreviado = if (podeRevelar(participante, statusSolicitacao, encerrada)) {
            abreviar(nomeCompleto)
        } else {
            null
        }
        if (abreviado != null) return Exibicao.Abreviada(abreviado)
        return rotulo(papel)
    }

    fun podeRevelar(
        participante: Boolean,
        statusSolicitacao: String?,
        encerrada: Boolean
    ): Boolean {
        return participante && encerrada && statusSolicitacao == "ACEITA"
    }

    fun texto(exibicao: Exibicao): String {
        return when (exibicao) {
            Exibicao.MotoristaVerificado -> "Motorista verificado"
            Exibicao.PassageiroVerificado -> "Passageiro verificado"
            is Exibicao.Abreviada -> exibicao.nome
        }
    }

    /** Rótulo genérico para exibição antes do aceite e durante a viagem. */
    fun rotuloGenerico(papel: Papel): String =
        if (papel == Papel.MOTORISTA) "Motorista" else "Passageiro"

    /**
     * Formata o nome de exibição apenas para participantes elegíveis de viagem CONCLUIDA
     * com participação e conclusão confirmadas.
     */
    fun nomeParticipanteConfirmado(
        papel: Papel,
        concluidaEConfirmada: Boolean,
        nomeOuAbreviado: String?
    ): String {
        if (!concluidaEConfirmada) return rotuloGenerico(papel)
        return abreviar(nomeOuAbreviado) ?: rotuloGenerico(papel)
    }

    fun abreviar(nomeCompleto: String?): String? {
        val nome = nomeCompleto
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        if (nome.isEmpty() || nome.contains('@') || nome.count { it.isDigit() } >= 8) {
            return null
        }
        val partes = nome.split(' ')
        val primeiro = partes.first()
        if (partes.size == 1) return primeiro
        val inicial = partes.last().firstOrNull() ?: return null
        if (!inicial.isLetter()) return null
        return "$primeiro ${inicial.uppercaseChar()}."
    }

    private fun rotulo(papel: Papel): Exibicao {
        return if (papel == Papel.MOTORISTA) {
            Exibicao.MotoristaVerificado
        } else {
            Exibicao.PassageiroVerificado
        }
    }
}

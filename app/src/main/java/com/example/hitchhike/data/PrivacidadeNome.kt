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

package com.example.hitchhike.util

data class ResultadoValidacaoSenha(
    val tamanhoMinimo: Boolean,
    val temMaiuscula: Boolean,
    val temMinuscula: Boolean,
    val temNumero: Boolean,
    val temEspecial: Boolean,
    val confirmacaoCorresponde: Boolean
) {
    val requisitosAtendidos: Int
        get() = listOf(
            tamanhoMinimo,
            temMaiuscula,
            temMinuscula,
            temNumero,
            temEspecial
        ).count { it }

    val totalRequisitos: Int = 5

    val senhaForte: Boolean
        get() = tamanhoMinimo &&
            temMaiuscula &&
            temMinuscula &&
            temNumero &&
            temEspecial

    val validaComConfirmacao: Boolean
        get() = senhaForte && confirmacaoCorresponde
}

object PasswordValidator {

    const val TAMANHO_MINIMO = 8

    fun avaliar(senha: String, confirmacao: String = ""): ResultadoValidacaoSenha {
        val tamanhoMinimo = senha.length >= TAMANHO_MINIMO
        val temMaiuscula = senha.any { it in 'A'..'Z' }
        val temMinuscula = senha.any { it in 'a'..'z' }
        val temNumero = senha.any { it in '0'..'9' }
        val temEspecial = senha.any { !it.isLetterOrDigit() && !it.isWhitespace() }
        val confirmacaoCorresponde = senha.isNotEmpty() && senha == confirmacao

        return ResultadoValidacaoSenha(
            tamanhoMinimo = tamanhoMinimo,
            temMaiuscula = temMaiuscula,
            temMinuscula = temMinuscula,
            temNumero = temNumero,
            temEspecial = temEspecial,
            confirmacaoCorresponde = confirmacaoCorresponde
        )
    }

    fun isStrong(senha: String): Boolean = avaliar(senha).senhaForte

    fun isValid(senha: String, confirmacao: String): Boolean =
        avaliar(senha, confirmacao).validaComConfirmacao

    fun mensagemErro(senha: String): String? {
        val r = avaliar(senha)
        return when {
            !r.tamanhoMinimo -> "A senha deve ter pelo menos 8 caracteres."
            !r.temMaiuscula -> "A senha deve conter pelo menos uma letra maiúscula."
            !r.temMinuscula -> "A senha deve conter pelo menos uma letra minúscula."
            !r.temNumero -> "A senha deve conter pelo menos um número."
            !r.temEspecial -> "A senha deve conter pelo menos um caractere especial."
            else -> null
        }
    }
}

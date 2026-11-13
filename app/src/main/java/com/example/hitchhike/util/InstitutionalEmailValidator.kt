package com.example.hitchhike.util

object InstitutionalEmailValidator {

    private val dominios = listOf(
        "@senacsp.edu.br",
        "@sp.senac.br",
        "@senac.br"
    )

    fun isValid(email: String): Boolean {
        val emailLimpo = email.trim().lowercase()
        return android.util.Patterns.EMAIL_ADDRESS.matcher(emailLimpo).matches() &&
            dominios.any { emailLimpo.endsWith(it) }
    }
}

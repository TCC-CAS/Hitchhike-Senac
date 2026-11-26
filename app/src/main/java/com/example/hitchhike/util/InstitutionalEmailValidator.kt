package com.example.hitchhike.util

object InstitutionalEmailValidator {

    private val dominios = listOf(
        "@senacsp.edu.br",
        "@sp.senac.br",
        "@senac.br"
    )

    private val EMAIL_REGEX = Regex(
        "^[a-z0-9+._%-]{1,256}@[a-z0-9][a-z0-9-]{0,64}(\\.[a-z0-9][a-z0-9-]{0,25})+$"
    )

    fun isValid(email: String): Boolean {
        val emailLimpo = email.trim().lowercase()
        return EMAIL_REGEX.matches(emailLimpo) &&
            dominios.any { emailLimpo.endsWith(it) }
    }
}

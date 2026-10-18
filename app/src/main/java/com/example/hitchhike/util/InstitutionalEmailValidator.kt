
package com.example.hitchhike.util

object InstitutionalEmailValidator {

    fun isValid(email: String): Boolean {
        val emailLimpo = email.trim().lowercase()

        return android.util.Patterns.EMAIL_ADDRESS
            .matcher(emailLimpo).matches() &&
                (
                        emailLimpo.endsWith("@senacsp.edu.br") ||
                                emailLimpo.endsWith("@sp.senac.br")
                        )
    }
}

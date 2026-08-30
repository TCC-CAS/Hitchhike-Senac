package com.example.hitchhike.util

object InstitutionalEmailValidator {
    private val allowedDomains = listOf("@senac.br", "@sp.senac.br")

    fun isValid(email: String): Boolean {
        val normalized = email.trim().lowercase()
        if (!normalized.contains("@") || normalized.count { it == '@' } != 1) {
            return false
        }
        return allowedDomains.any { domain ->
            normalized.endsWith(domain) && normalized.length > domain.length
        }
    }
}

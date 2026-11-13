package com.example.hitchhike.util

object FirestoreMensagens {
    fun amigavel(erro: Throwable, fallback: String): String {
        val bruto = erro.localizedMessage.orEmpty()
        return when {
            bruto.contains("PERMISSION_DENIED", ignoreCase = true) -> fallback
            bruto.contains("UNAVAILABLE", ignoreCase = true) ->
                "Sem conexão com o servidor. Tente novamente."
            bruto.isBlank() -> fallback
            else -> bruto
        }
    }
}

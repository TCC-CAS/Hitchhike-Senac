package com.example.hitchhike.util

import java.security.MessageDigest

object PasswordHasher {
    fun hash(plain: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(plain.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

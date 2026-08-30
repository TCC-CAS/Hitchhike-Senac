package com.example.hitchhike.data

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("hitchhike_session", Context.MODE_PRIVATE)

    fun salvarSessao(usuarioId: Long) {
        prefs.edit()
            .putLong(KEY_USER_ID, usuarioId)
            .putBoolean(KEY_LOGGED, true)
            .apply()
    }

    fun usuarioId(): Long = prefs.getLong(KEY_USER_ID, -1L)

    fun estaLogado(): Boolean = prefs.getBoolean(KEY_LOGGED, false) && usuarioId() > 0

    fun encerrarSessao() {
        prefs.edit().clear().apply()
    }

    fun salvarCodigoRecuperacao(email: String, codigo: String) {
        prefs.edit()
            .putString(KEY_RECOVERY_EMAIL, email.lowercase())
            .putString(KEY_RECOVERY_CODE, codigo)
            .apply()
    }

    fun codigoRecuperacaoValido(email: String, codigo: String): Boolean {
        val savedEmail = prefs.getString(KEY_RECOVERY_EMAIL, null)
        val savedCode = prefs.getString(KEY_RECOVERY_CODE, null)
        return savedEmail == email.lowercase() && savedCode == codigo
    }

    fun limparCodigoRecuperacao() {
        prefs.edit()
            .remove(KEY_RECOVERY_EMAIL)
            .remove(KEY_RECOVERY_CODE)
            .apply()
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LOGGED = "logged"
        private const val KEY_RECOVERY_EMAIL = "recovery_email"
        private const val KEY_RECOVERY_CODE = "recovery_code"
    }
}

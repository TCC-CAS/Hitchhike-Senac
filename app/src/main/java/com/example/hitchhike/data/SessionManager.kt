
package com.example.hitchhike.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth

class SessionManager(context: Context) {

    private val prefs = context.getSharedPreferences(
        "hitchhike_session",
        Context.MODE_PRIVATE
    )

    private val auth = FirebaseAuth.getInstance()

    fun salvarSessao(usuarioId: Long) {
        val usuarioFirebase = auth.currentUser

        if (usuarioFirebase == null ||
            !usuarioFirebase.isEmailVerified
        ) {
            encerrarSessao()
            return
        }

        prefs.edit()
            .putLong(KEY_USER_ID, usuarioId)
            .putString(KEY_FIREBASE_UID, usuarioFirebase.uid)
            .putBoolean(KEY_LOGGED, true)
            .apply()
    }

    fun usuarioId(): Long =
        prefs.getLong(KEY_USER_ID, -1L)

    fun estaLogado(): Boolean {
        val usuarioFirebase = auth.currentUser ?: return false

        return prefs.getBoolean(KEY_LOGGED, false) &&
                usuarioId() > 0 &&
                prefs.getString(KEY_FIREBASE_UID, null) ==
                usuarioFirebase.uid &&
                usuarioFirebase.isEmailVerified
    }

    fun encerrarSessao() {
        prefs.edit().clear().apply()
        auth.signOut()
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LOGGED = "logged"
        private const val KEY_FIREBASE_UID = "firebase_uid"
    }
}

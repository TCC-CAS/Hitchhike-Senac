package com.example.hitchhike.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.example.hitchhike.data.SessionManager

fun AppCompatActivity.garantirSessao(session: SessionManager): Boolean {
    if (session.estaLogado()) return true
    startActivity(
        Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    )
    finish()
    return false
}

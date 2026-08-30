package com.example.hitchhike

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.ui.LoginActivity
import com.example.hitchhike.ui.PerfilActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destino = if (SessionManager(this).estaLogado()) {
            Intent(this, PerfilActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        startActivity(destino)
        finish()
    }
}

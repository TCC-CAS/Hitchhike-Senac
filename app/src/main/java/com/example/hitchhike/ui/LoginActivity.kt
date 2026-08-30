package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityLoginBinding
import com.example.hitchhike.util.InstitutionalEmailValidator
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootLogin.applySystemInsets()

        repository = UsuarioRepository(this)
        session = SessionManager(this)

        if (session.estaLogado()) {
            abrirPerfil()
            return
        }

        binding.btnEntrar.setOnClickListener { autenticar() }
        binding.btnCriarConta.setOnClickListener {
            startActivity(Intent(this, CadastroActivity::class.java))
        }
        binding.btnEsqueciSenha.setOnClickListener {
            startActivity(Intent(this, RecuperarSenhaActivity::class.java))
        }
    }

    private fun autenticar() {
        val email = binding.etEmail.text?.toString().orEmpty()
        val senha = binding.etSenha.text?.toString().orEmpty()
        binding.tilEmail.error = null
        binding.tilSenha.error = null

        if (email.isBlank() || senha.isBlank()) {
            Toast.makeText(this, getString(R.string.erro_campos_obrigatorios), Toast.LENGTH_SHORT).show()
            return
        }
        if (!InstitutionalEmailValidator.isValid(email)) {
            binding.tilEmail.error = getString(R.string.erro_email_institucional)
            return
        }

        lifecycleScope.launch {
            val usuario = repository.autenticar(email, senha)
            if (usuario == null) {
                Toast.makeText(this@LoginActivity, getString(R.string.erro_login), Toast.LENGTH_SHORT).show()
            } else {
                session.salvarSessao(usuario.id)
                abrirPerfil()
            }
        }
    }

    private fun abrirPerfil() {
        startActivity(Intent(this, PerfilActivity::class.java))
        finish()
    }
}

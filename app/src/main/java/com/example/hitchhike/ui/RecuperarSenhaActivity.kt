package com.example.hitchhike.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityRecuperarSenhaBinding
import com.example.hitchhike.util.InstitutionalEmailValidator
import kotlinx.coroutines.launch
import kotlin.random.Random

class RecuperarSenhaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecuperarSenhaBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRecuperarSenhaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootRecuperar.applySystemInsets()

        repository = UsuarioRepository(this)
        session = SessionManager(this)

        binding.btnEnviarCodigo.setOnClickListener { enviarCodigo() }
        binding.btnRedefinir.setOnClickListener { redefinir() }
    }

    private fun enviarCodigo() {
        val email = binding.etEmail.text?.toString().orEmpty()
        if (!InstitutionalEmailValidator.isValid(email)) {
            binding.tilEmail.error = getString(R.string.erro_email_institucional)
            return
        }
        binding.tilEmail.error = null
        lifecycleScope.launch {
            val usuario = repository.buscarPorEmail(email)
            if (usuario == null) {
                Toast.makeText(this@RecuperarSenhaActivity, getString(R.string.erro_email_nao_encontrado), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val codigo = Random.nextInt(100000, 999999).toString()
            session.salvarCodigoRecuperacao(email, codigo)
            binding.grupoRedefinir.visibility = View.VISIBLE
            Toast.makeText(
                this@RecuperarSenhaActivity,
                getString(R.string.sucesso_codigo_enviado, codigo),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun redefinir() {
        val email = binding.etEmail.text?.toString().orEmpty()
        val codigo = binding.etCodigo.text?.toString().orEmpty()
        val novaSenha = binding.etNovaSenha.text?.toString().orEmpty()
        if (novaSenha.length < 6) {
            binding.tilNovaSenha.error = getString(R.string.erro_senha_curta)
            return
        }
        if (!session.codigoRecuperacaoValido(email, codigo)) {
            Toast.makeText(this, getString(R.string.erro_codigo), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val usuario = repository.buscarPorEmail(email) ?: return@launch
            repository.atualizarSenha(usuario.id, novaSenha)
            session.limparCodigoRecuperacao()
            Toast.makeText(this@RecuperarSenhaActivity, getString(R.string.sucesso_senha), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

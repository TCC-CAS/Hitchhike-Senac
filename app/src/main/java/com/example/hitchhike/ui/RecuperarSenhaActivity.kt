
package com.example.hitchhike.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.hitchhike.R
import com.example.hitchhike.databinding.ActivityRecuperarSenhaBinding
import com.example.hitchhike.util.FirebaseAuthManager
import com.example.hitchhike.util.InstitutionalEmailValidator

class RecuperarSenhaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecuperarSenhaBinding

    private val firebaseAuthManager = FirebaseAuthManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding = ActivityRecuperarSenhaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rootRecuperar.applySystemInsets()
        configurarBotaoVoltar(binding.btnVoltar)

        // Não usamos mais o código local de recuperação
        binding.grupoRedefinir.visibility = android.view.View.GONE

        binding.btnEnviarCodigo.text = "Enviar e-mail de recuperação"

        binding.btnEnviarCodigo.setOnClickListener {
            enviarEmailRecuperacao()
        }
    }

    private fun enviarEmailRecuperacao() {

        val email = binding.etEmail.text
            ?.toString()
            ?.trim()
            .orEmpty()

        binding.tilEmail.error = null

        if (!InstitutionalEmailValidator.isValid(email)) {
            binding.tilEmail.error =
                getString(R.string.erro_email_institucional)
            return
        }

        binding.btnEnviarCodigo.isEnabled = false

        firebaseAuthManager.recuperarSenha(
            email = email,

            onSuccess = {
                binding.btnEnviarCodigo.isEnabled = true

                Toast.makeText(
                    this,
                    "Se a conta existir, você receberá um e-mail para redefinir sua senha.",
                    Toast.LENGTH_LONG
                ).show()
            },

            onError = { erro ->
                binding.btnEnviarCodigo.isEnabled = true

                Toast.makeText(
                    this,
                    "Não foi possível solicitar a recuperação: $erro",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}

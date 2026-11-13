
package com.example.hitchhike.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.hitchhike.R
import com.example.hitchhike.data.PapelUsuario
import com.example.hitchhike.databinding.ActivityCadastroBinding
import com.example.hitchhike.util.FirebaseAuthManager
import com.example.hitchhike.util.InstitutionalEmailValidator
import com.example.hitchhike.data.CadastroPendenteManager

class CadastroActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCadastroBinding
    private val firebaseAuthManager = FirebaseAuthManager()
    private lateinit var cadastroPendente: CadastroPendenteManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityCadastroBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootCadastro.applySystemInsets()

        cadastroPendente = CadastroPendenteManager(this)

        val cursos = resources.getStringArray(R.array.cursos_senac)

        binding.etCurso.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                cursos
            )
        )

        binding.etCurso.threshold = 1
        binding.etCurso.setOnClickListener {
            binding.etCurso.showDropDown()
        }

        configurarBotaoVoltar(binding.btnVoltar)

        binding.btnCadastrar.setOnClickListener {
            cadastrar()
        }

        binding.btnIrLogin.setOnClickListener {
            finish()
        }

        configurarLinksTermos()
    }

    private fun configurarLinksTermos() {
        val texto =
            "Li e aceito os Termos de Uso e a Política de Privacidade."

        val spannable = SpannableString(texto)

        val termos = "Termos de Uso"
        val politica = "Política de Privacidade"

        val inicioTermos = texto.indexOf(termos)
        val inicioPolitica = texto.indexOf(politica)

        val cliqueTermos = object : ClickableSpan() {
            override fun onClick(widget: View) {
                abrirDocumento("termos")
            }
        }

        val cliquePolitica = object : ClickableSpan() {
            override fun onClick(widget: View) {
                abrirDocumento("privacidade")
            }
        }

        spannable.setSpan(
            cliqueTermos,
            inicioTermos,
            inicioTermos + termos.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            cliquePolitica,
            inicioPolitica,
            inicioPolitica + politica.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            ForegroundColorSpan(Color.rgb(25, 118, 75)),
            inicioTermos,
            inicioTermos + termos.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        spannable.setSpan(
            ForegroundColorSpan(Color.rgb(25, 118, 75)),
            inicioPolitica,
            inicioPolitica + politica.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tvTermos.text = spannable
        binding.tvTermos.movementMethod =
            LinkMovementMethod.getInstance()

        binding.tvTermos.highlightColor = Color.TRANSPARENT
    }

    private fun abrirDocumento(tipo: String) {
        val intent = Intent(
            this,
            DocumentoLegalActivity::class.java
        )

        intent.putExtra("tipo", tipo)
        startActivity(intent)
    }

    private fun cadastrar() {
        val nome = binding.etNome.text?.toString()?.trim().orEmpty()
        val curso = binding.etCurso.text?.toString()?.trim().orEmpty()
        val instituicao =
            binding.etInstituicao.text?.toString()?.trim().orEmpty()
        val email = binding.etEmail.text?.toString()
            ?.trim()?.lowercase().orEmpty()
        val senha = binding.etSenha.text?.toString().orEmpty()
        val confirmar =
            binding.etConfirmarSenha.text?.toString().orEmpty()

        val papel = if (binding.rbMotorista.isChecked) {
            PapelUsuario.MOTORISTA
        } else {
            PapelUsuario.PASSAGEIRO
        }

        binding.tilEmail.error = null
        binding.tilSenha.error = null
        binding.tilConfirmarSenha.error = null

        if (nome.isBlank() || curso.isBlank() ||
            instituicao.isBlank() || email.isBlank() ||
            senha.isBlank()
        ) {
            Toast.makeText(
                this,
                getString(R.string.erro_campos_obrigatorios),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!binding.cbTermos.isChecked) {
            Toast.makeText(
                this,
                getString(R.string.erro_termos),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!InstitutionalEmailValidator.isValid(email)) {
            binding.tilEmail.error =
                getString(R.string.erro_email_institucional)
            return
        }

        if (senha.length < 6) {
            binding.tilSenha.error =
                getString(R.string.erro_senha_curta)
            return
        }

        if (senha != confirmar) {
            binding.tilConfirmarSenha.error =
                getString(R.string.erro_senhas_diferentes)
            return
        }

        binding.btnCadastrar.isEnabled = false

        firebaseAuthManager.cadastrar(
            email = email,
            senha = senha,


            onSuccess = { uid ->

                cadastroPendente.salvar(
                    uid = uid,
                    nome = nome,
                    curso = curso,
                    instituicao = instituicao,
                    email = email,
                    papel = papel
                )

                binding.btnCadastrar.isEnabled = true

                Toast.makeText(
                    this@CadastroActivity,
                    "Conta criada! Verifique seu e-mail institucional antes de entrar.",
                    Toast.LENGTH_LONG
                ).show()

                finish()
            },


            onError = { erro ->
                binding.btnCadastrar.isEnabled = true

                Toast.makeText(
                    this,
                    "Erro no cadastro: $erro",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}

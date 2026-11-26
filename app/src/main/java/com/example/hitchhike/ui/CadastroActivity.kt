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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.hitchhike.R
import com.example.hitchhike.data.CadastroPendenteManager
import com.example.hitchhike.data.PapelUsuario
import com.example.hitchhike.databinding.ActivityCadastroBinding
import com.example.hitchhike.util.FirebaseAuthManager
import com.example.hitchhike.util.InstitutionalEmailValidator
import com.example.hitchhike.util.PasswordValidator

class CadastroActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCadastroBinding
    private val firebaseAuthManager = FirebaseAuthManager()
    private lateinit var cadastroPendente: CadastroPendenteManager
    private var cadastroEmAndamento = false

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

        binding.etSenha.doAfterTextChanged {
            binding.tilSenha.error = null
            atualizarChecklistSenha()
        }
        binding.etConfirmarSenha.doAfterTextChanged {
            binding.tilConfirmarSenha.error = null
            atualizarChecklistSenha()
        }

        atualizarChecklistSenha()

        binding.btnCadastrar.setOnClickListener {
            cadastrar()
        }

        binding.btnIrLogin.setOnClickListener {
            finish()
        }

        configurarLinksTermos()
    }

    private fun atualizarChecklistSenha() {
        val senha = binding.etSenha.text?.toString().orEmpty()
        val confirmar = binding.etConfirmarSenha.text?.toString().orEmpty()
        val resultado = PasswordValidator.avaliar(senha, confirmar)

        val corAtendido = ContextCompat.getColor(this, R.color.hh_green)
        val corPendente = ContextCompat.getColor(this, R.color.hh_text_secondary)

        aplicarEstadoRequisito(
            view = binding.tvReqTamanho,
            atendido = resultado.tamanhoMinimo,
            rotulo = getString(R.string.senha_req_minimo_8),
            corAtendido = corAtendido,
            corPendente = corPendente
        )
        aplicarEstadoRequisito(
            view = binding.tvReqMaiuscula,
            atendido = resultado.temMaiuscula,
            rotulo = getString(R.string.senha_req_maiuscula),
            corAtendido = corAtendido,
            corPendente = corPendente
        )
        aplicarEstadoRequisito(
            view = binding.tvReqMinuscula,
            atendido = resultado.temMinuscula,
            rotulo = getString(R.string.senha_req_minuscula),
            corAtendido = corAtendido,
            corPendente = corPendente
        )
        aplicarEstadoRequisito(
            view = binding.tvReqNumero,
            atendido = resultado.temNumero,
            rotulo = getString(R.string.senha_req_numero),
            corAtendido = corAtendido,
            corPendente = corPendente
        )
        aplicarEstadoRequisito(
            view = binding.tvReqEspecial,
            atendido = resultado.temEspecial,
            rotulo = getString(R.string.senha_req_especial),
            corAtendido = corAtendido,
            corPendente = corPendente
        )
        aplicarEstadoRequisito(
            view = binding.tvReqConfirmacao,
            atendido = resultado.confirmacaoCorresponde,
            rotulo = getString(R.string.senha_req_confirmacao),
            corAtendido = corAtendido,
            corPendente = corPendente
        )

        binding.progressForcaSenha.progress = resultado.requisitosAtendidos

        if (resultado.senhaForte) {
            binding.tvIndicadorForcaSenha.setTextColor(corAtendido)
            binding.tvIndicadorForcaSenha.text = getString(
                R.string.senha_indicador_completa,
                resultado.requisitosAtendidos,
                resultado.totalRequisitos
            )
        } else {
            binding.tvIndicadorForcaSenha.setTextColor(corPendente)
            binding.tvIndicadorForcaSenha.text = getString(
                R.string.senha_indicador_progresso,
                resultado.requisitosAtendidos,
                resultado.totalRequisitos
            )
        }

        if (confirmar.isNotEmpty() && !resultado.confirmacaoCorresponde) {
            binding.tilConfirmarSenha.error = getString(R.string.erro_senhas_diferentes)
        } else {
            binding.tilConfirmarSenha.error = null
        }
    }

    private fun aplicarEstadoRequisito(
        view: TextView,
        atendido: Boolean,
        rotulo: String,
        corAtendido: Int,
        corPendente: Int
    ) {
        view.setTextColor(if (atendido) corAtendido else corPendente)
        view.text = getString(
            if (atendido) R.string.senha_req_atendido else R.string.senha_req_pendente,
            rotulo
        )
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
        if (cadastroEmAndamento) return

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
            senha.isBlank() || confirmar.isBlank()
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

        val validacaoSenha = PasswordValidator.avaliar(senha, confirmar)
        if (!validacaoSenha.senhaForte) {
            val erroEspecifico = PasswordValidator.mensagemErro(senha)
                ?: getString(R.string.erro_senha_fraca)
            binding.tilSenha.error = erroEspecifico
            Toast.makeText(
                this,
                getString(R.string.erro_senha_fraca),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!validacaoSenha.confirmacaoCorresponde) {
            binding.tilConfirmarSenha.error =
                getString(R.string.erro_senhas_diferentes)
            return
        }

        cadastroEmAndamento = true
        binding.btnCadastrar.isEnabled = false

        firebaseAuthManager.cadastrar(
            email = email,
            senha = senha,
            nome = nome,

            onSuccess = { uid ->
                cadastroPendente.salvar(
                    uid = uid,
                    nome = nome,
                    curso = curso,
                    instituicao = instituicao,
                    email = email,
                    papel = papel
                )

                cadastroEmAndamento = false
                binding.btnCadastrar.isEnabled = true

                Toast.makeText(
                    this@CadastroActivity,
                    "Conta criada! Verifique seu e-mail institucional antes de entrar.",
                    Toast.LENGTH_LONG
                ).show()

                finish()
            },

            onError = { erro ->
                cadastroEmAndamento = false
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

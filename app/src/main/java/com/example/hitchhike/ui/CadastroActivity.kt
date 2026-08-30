package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.PapelUsuario
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityCadastroBinding
import com.example.hitchhike.util.InstitutionalEmailValidator
import kotlinx.coroutines.launch

class CadastroActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCadastroBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCadastroBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootCadastro.applySystemInsets()

        repository = UsuarioRepository(this)
        session = SessionManager(this)

        val cursos = resources.getStringArray(R.array.cursos_senac)
        binding.etCurso.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, cursos)
        )
        binding.etCurso.threshold = 1
        binding.etCurso.setOnClickListener { binding.etCurso.showDropDown() }

        binding.btnCadastrar.setOnClickListener { cadastrar() }
        binding.btnIrLogin.setOnClickListener { finish() }
    }

    private fun cadastrar() {
        val nome = binding.etNome.text?.toString().orEmpty()
        val curso = binding.etCurso.text?.toString().orEmpty()
        val instituicao = binding.etInstituicao.text?.toString().orEmpty()
        val email = binding.etEmail.text?.toString().orEmpty()
        val senha = binding.etSenha.text?.toString().orEmpty()
        val confirmar = binding.etConfirmarSenha.text?.toString().orEmpty()
        val papel = if (binding.rbMotorista.isChecked) PapelUsuario.MOTORISTA else PapelUsuario.PASSAGEIRO

        binding.tilEmail.error = null
        binding.tilSenha.error = null
        binding.tilConfirmarSenha.error = null

        if (nome.isBlank() || curso.isBlank() || email.isBlank() || senha.isBlank()) {
            Toast.makeText(this, getString(R.string.erro_campos_obrigatorios), Toast.LENGTH_SHORT).show()
            return
        }
        if (!binding.cbTermos.isChecked) {
            Toast.makeText(this, getString(R.string.erro_termos), Toast.LENGTH_SHORT).show()
            return
        }
        if (!InstitutionalEmailValidator.isValid(email)) {
            binding.tilEmail.error = getString(R.string.erro_email_institucional)
            return
        }
        if (senha.length < 6) {
            binding.tilSenha.error = getString(R.string.erro_senha_curta)
            return
        }
        if (senha != confirmar) {
            binding.tilConfirmarSenha.error = getString(R.string.erro_senhas_diferentes)
            return
        }

        lifecycleScope.launch {
            val resultado = repository.cadastrar(nome, curso, instituicao, email, senha, papel)
            resultado.fold(
                onSuccess = { id ->
                    session.salvarSessao(id)
                    Toast.makeText(this@CadastroActivity, getString(R.string.sucesso_cadastro), Toast.LENGTH_SHORT).show()
                    val destino = if (papel == PapelUsuario.MOTORISTA) {
                        Intent(this@CadastroActivity, VerificacaoMotoristaActivity::class.java)
                    } else {
                        Intent(this@CadastroActivity, PerfilActivity::class.java)
                    }
                    destino.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(destino)
                },
                onFailure = { erro ->
                    val mensagem = when (erro.message) {
                        "email_existente" -> getString(R.string.erro_email_existente)
                        "email_institucional" -> getString(R.string.erro_email_institucional)
                        else -> getString(R.string.erro_campos_obrigatorios)
                    }
                    Toast.makeText(this@CadastroActivity, mensagem, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

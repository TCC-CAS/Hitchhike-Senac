package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.MainActivity
import com.example.hitchhike.R
import com.example.hitchhike.data.CadastroPendenteManager
import com.example.hitchhike.data.DadosSolicitacaoMotorista
import com.example.hitchhike.data.PapelUsuario
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.SolicitacaoMotoristaFirestoreRepository
import com.example.hitchhike.data.UsuarioFirestoreRepository
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityLoginBinding
import com.example.hitchhike.util.FirebaseAuthManager
import com.example.hitchhike.util.InstitutionalEmailValidator
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private lateinit var cadastroPendente: CadastroPendenteManager

    private val firebaseAuthManager = FirebaseAuthManager()
    private val firestoreRepository = UsuarioFirestoreRepository()
    private val solicitacaoMotoristaRepository = SolicitacaoMotoristaFirestoreRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootLogin.applySystemInsets()

        repository = UsuarioRepository(this)
        session = SessionManager(this)

        cadastroPendente = CadastroPendenteManager(this)


        if (session.estaLogado()) {
            val usuarioFirebase = FirebaseAuth.getInstance().currentUser

            if (usuarioFirebase == null) {
                session.encerrarSessao()
            } else {
                binding.btnEntrar.isEnabled = false

                usuarioFirebase.reload()
                    .addOnSuccessListener {
                        if (!session.estaLogado()) {
                            session.encerrarSessao()
                            binding.btnEntrar.isEnabled = true
                        } else {
                            usuarioFirebase.getIdToken(true)
                                .addOnSuccessListener {
                                    firestoreRepository.buscarPerfil(
                                        uid = usuarioFirebase.uid,

                                        onSuccess = { perfil ->
                                            val emailFirebase = usuarioFirebase.email
                                                ?.trim()?.lowercase()

                                            val emailPerfil = perfil?.get("email")
                                                ?.trim()?.lowercase()

                                            if (
                                                session.estaLogado() &&
                                                perfil?.get("status") == "ATIVO" &&
                                                emailFirebase != null &&
                                                emailFirebase == emailPerfil
                                            ) {

                                                sincronizarPerfilLocal(
                                                    uid = usuarioFirebase.uid,
                                                    perfil = perfil!!
                                                )

                                            } else {
                                                session.encerrarSessao()
                                                binding.btnEntrar.isEnabled = true

                                                Toast.makeText(
                                                    this@LoginActivity,
                                                    "Sessão inválida ou perfil inativo.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        },

                                        onError = { erro ->
                                            binding.btnEntrar.isEnabled = true

                                            Toast.makeText(
                                                this@LoginActivity,
                                                "Não foi possível verificar seu perfil: $erro",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    )
                                }
                                .addOnFailureListener {
                                    binding.btnEntrar.isEnabled = true

                                    Toast.makeText(
                                        this@LoginActivity,
                                        "Não foi possível atualizar a autenticação.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        }
                    }
                    .addOnFailureListener {
                        binding.btnEntrar.isEnabled = true

                        Toast.makeText(
                            this@LoginActivity,
                            "Não foi possível verificar sua sessão.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
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
        binding.btnEntrar.isEnabled = false

        firebaseAuthManager.entrar(
            email = email,
            senha = senha,

            onSuccess = { uid ->
                consultarPerfil(uid)
            },

            onError = { erro ->
                binding.btnEntrar.isEnabled = true

                Toast.makeText(
                    this,
                    erro,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }


    private fun consultarPerfil(uid: String) {
        firestoreRepository.buscarPerfil(
            uid = uid,
            onSuccess = { perfil ->
                if (perfil != null) {
                    sincronizarPerfilLocal(uid, perfil)
                } else {
                    criarPerfilPendente(uid)
                }
            },
            onError = { erro ->
                mostrarErro("Erro ao consultar perfil: $erro")
            }
        )
    }

    private fun criarPerfilPendente(uid: String) {
        val usuarioFirebase = FirebaseAuth.getInstance().currentUser
        val emailFirebase = usuarioFirebase?.email?.trim()?.lowercase()

        if (usuarioFirebase == null ||
            usuarioFirebase.uid != uid ||
            !usuarioFirebase.isEmailVerified ||
            emailFirebase == null ||
            !InstitutionalEmailValidator.isValid(emailFirebase)
        ) {
            mostrarErro("Confirme seu e-mail institucional antes de entrar.")
            return
        }

        val dados = cadastroPendente.buscar(uid)

        if (dados == null) {
            // Primeiro login em dispositivo diferente do utilizado no cadastro (ou após limpeza de dados locais).
            // Conclui o perfil sem criar contas duplicadas no Firebase Authentication.
            exibirDialogConcluirCadastro(uid, emailFirebase, usuarioFirebase.displayName.orEmpty())
            return
        }

        if (emailFirebase != dados["email"]?.trim()?.lowercase()) {
            mostrarErro("O e-mail do cadastro não corresponde à conta.")
            return
        }

        // Todo perfil inicia no Firestore como PASSAGEIRO com condutorValidado = false;
        // a habilitação para MOTORISTA depende exclusivamente da validação administrativa da CNH.
        val papelInicial = PapelUsuario.PASSAGEIRO

        firestoreRepository.salvarPerfil(
            uid = uid,
            nome = dados["nome"].orEmpty(),
            curso = dados["curso"].orEmpty(),
            instituicao = dados["instituicao"].orEmpty().ifBlank { getString(R.string.instituicao_padrao) },
            email = emailFirebase,
            papel = papelInicial,
            onSuccess = {
                consultarPerfil(uid)
            },
            onError = { erro ->
                mostrarErro("Erro ao salvar perfil: $erro")
            }
        )
    }

    private fun exibirDialogConcluirCadastro(
        uid: String,
        emailFirebase: String,
        nomeSugerido: String
    ) {
        val densidade = resources.displayMetrics.density
        val padHorizontal = (20 * densidade).toInt()
        val padVertical = (12 * densidade).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
        }

        val tvSubtitulo = TextView(this).apply {
            text = getString(R.string.completar_cadastro_subtitle)
            setPadding(0, 0, 0, (12 * densidade).toInt())
        }
        container.addView(tvSubtitulo)

        val etNome = EditText(this).apply {
            hint = getString(R.string.hint_nome)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(nomeSugerido.trim())
        }
        container.addView(etNome)

        val cursos = resources.getStringArray(R.array.cursos_senac)
        val etCurso = AutoCompleteTextView(this).apply {
            hint = getString(R.string.hint_curso)
            threshold = 1
            setAdapter(
                ArrayAdapter(
                    this@LoginActivity,
                    android.R.layout.simple_list_item_1,
                    cursos
                )
            )
            setOnClickListener { showDropDown() }
        }
        container.addView(etCurso)

        val etInstituicao = EditText(this).apply {
            hint = getString(R.string.hint_instituicao)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(getString(R.string.instituicao_padrao))
        }
        container.addView(etInstituicao)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.completar_cadastro_title))
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.action_salvar_perfil), null)
            .setNegativeButton(getString(R.string.action_voltar)) { d, _ ->
                FirebaseAuth.getInstance().signOut()
                binding.btnEntrar.isEnabled = true
                d.dismiss()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nome = etNome.text?.toString()?.trim().orEmpty()
                val curso = etCurso.text?.toString()?.trim().orEmpty()
                val instituicao = etInstituicao.text?.toString()?.trim()
                    .orEmpty()
                    .ifBlank { getString(R.string.instituicao_padrao) }

                if (nome.isBlank() || curso.isBlank()) {
                    Toast.makeText(
                        this,
                        getString(R.string.erro_campos_obrigatorios),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                firestoreRepository.salvarPerfil(
                    uid = uid,
                    nome = nome,
                    curso = curso,
                    instituicao = instituicao,
                    email = emailFirebase,
                    papel = PapelUsuario.PASSAGEIRO,
                    onSuccess = {
                        dialog.dismiss()
                        consultarPerfil(uid)
                    },
                    onError = { erro ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(
                            this,
                            "Erro ao salvar perfil: $erro",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }

        dialog.show()
    }

    private fun sincronizarPerfilLocal(
        uid: String,
        perfil: Map<String, String>
    ) {
        val usuarioFirebase = FirebaseAuth.getInstance().currentUser

        if (usuarioFirebase == null ||
            usuarioFirebase.uid != uid ||
            !usuarioFirebase.isEmailVerified) {
            mostrarErro("Sessão Firebase inválida.")
            return
        }

        val emailFirebase = usuarioFirebase.email?.trim()?.lowercase()
        val emailPerfil = perfil["email"]?.trim()?.lowercase()

        if (emailFirebase == null || emailFirebase != emailPerfil) {
            mostrarErro("O perfil não corresponde à conta autenticada.")
            return
        }

        if (perfil["status"] != "ATIVO") {
            mostrarErro("Este perfil não está ativo.")
            return
        }

        val papel = try {
            PapelUsuario.valueOf(perfil["papel"].orEmpty())
        } catch (e: IllegalArgumentException) {
            mostrarErro("Papel do usuário inválido.")
            return
        }

        solicitacaoMotoristaRepository.consultarDetalhes(
            onSuccess = { pedido ->
                concluirSincronizacaoLocal(
                    uid = uid,
                    emailFirebase = emailFirebase,
                    papel = papel,
                    perfil = perfil,
                    pedido = pedido,
                    atualizarSolicitacao = true
                )
            },
            onError = {
                concluirSincronizacaoLocal(
                    uid = uid,
                    emailFirebase = emailFirebase,
                    papel = papel,
                    perfil = perfil,
                    pedido = null,
                    atualizarSolicitacao = false
                )
            }
        )
    }

    private fun concluirSincronizacaoLocal(
        uid: String,
        emailFirebase: String,
        papel: PapelUsuario,
        perfil: Map<String, String>,
        pedido: DadosSolicitacaoMotorista?,
        atualizarSolicitacao: Boolean
    ) {
        val validado = perfil["condutorValidado"] == "true"
        val cnhValidade = perfil["cnhValidade"]?.takeIf { it.isNotBlank() }
        val cnhRevisao = if (pedido?.status == "RECUSADA") false else (perfil["cnhRevisaoNecessaria"] == "true")

        lifecycleScope.launch {
            try {
                val idLocal = repository.sincronizarPerfil(
                    nome = perfil["nome"].orEmpty(),
                    curso = perfil["curso"].orEmpty(),
                    instituicao = perfil["instituicao"].orEmpty(),
                    email = emailFirebase,
                    papel = papel
                )

                if (pedido != null) {
                    repository.sincronizarVeiculoLocal(
                        usuarioId = idLocal,
                        placa = pedido.placa,
                        modelo = pedido.modelo,
                        cor = pedido.cor,
                        cnhAnexada = pedido.cnhPdfRecebido
                    )
                }

                repository.sincronizarValidacaoMotorista(
                    usuarioId = idLocal,
                    validado = validado,
                    cnhValidade = cnhValidade,
                    cnhRevisaoNecessaria = cnhRevisao,
                    status = perfil["status"],
                    papelAtivo = perfil["papel"],
                    solicitacaoStatus = pedido?.status,
                    motivoRecusa = pedido?.motivoRecusa,
                    atualizarSolicitacao = atualizarSolicitacao
                )

                if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                    mostrarErro("A conta autenticada mudou.")
                    return@launch
                }

                session.salvarSessao(idLocal)

                if (session.estaLogado()) {
                    cadastroPendente.limpar()
                    abrirPerfil()
                } else {
                    mostrarErro("Não foi possível iniciar a sessão.")
                }

            } catch (e: Exception) {
                mostrarErro(
                    e.localizedMessage
                        ?: "Erro ao sincronizar usuário local."
                )
            }
        }
    }

    private fun mostrarErro(mensagem: String) {
        binding.btnEntrar.isEnabled = true

        Toast.makeText(
            this,
            mensagem,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun abrirPerfil() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.MainActivity
import com.example.hitchhike.R
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioFirestoreRepository
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityLoginBinding
import com.example.hitchhike.util.FirebaseAuthManager
import com.example.hitchhike.util.InstitutionalEmailValidator
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth
import com.example.hitchhike.data.CadastroPendenteManager
import com.example.hitchhike.data.PapelUsuario

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private lateinit var cadastroPendente: CadastroPendenteManager

    private val firebaseAuthManager = FirebaseAuthManager()
    private val firestoreRepository = UsuarioFirestoreRepository()

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
        val dados = cadastroPendente.buscar(uid)

        if (dados == null) {
            mostrarErro(
                "Sua conta está verificada, mas ainda não possui " +
                        "um perfil completo. Precisamos concluir seu cadastro."
            )
            return
        }

        val emailFirebase = FirebaseAuth.getInstance()
            .currentUser?.email?.trim()?.lowercase()

        if (emailFirebase == null ||
            emailFirebase != dados["email"]) {
            mostrarErro("O e-mail do cadastro não corresponde à conta.")
            return
        }

        val papel = try {
            PapelUsuario.valueOf(dados["papel"].orEmpty())
        } catch (e: IllegalArgumentException) {
            mostrarErro("Papel do usuário inválido.")
            return
        }

        firestoreRepository.salvarPerfil(
            uid = uid,
            nome = dados["nome"].orEmpty(),
            curso = dados["curso"].orEmpty(),
            instituicao = dados["instituicao"].orEmpty(),
            email = emailFirebase,
            papel = papel,
            onSuccess = {
                consultarPerfil(uid)
            },
            onError = { erro ->
                mostrarErro("Erro ao salvar perfil: $erro")
            }
        )
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

        lifecycleScope.launch {
            try {
                val idLocal = repository.sincronizarPerfil(
                    nome = perfil["nome"].orEmpty(),
                    curso = perfil["curso"].orEmpty(),
                    instituicao = perfil["instituicao"].orEmpty(),
                    email = emailFirebase,
                    papel = papel
                )

                repository.sincronizarValidacaoMotorista(
                    usuarioId = idLocal,
                    validado = perfil["condutorValidado"] == "true"
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

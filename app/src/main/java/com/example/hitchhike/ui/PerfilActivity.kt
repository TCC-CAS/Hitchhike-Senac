package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.PapelUsuario
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioEntity
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityPerfilBinding
import kotlinx.coroutines.launch
import com.example.hitchhike.data.SincronizacaoCondutorValidado
import com.example.hitchhike.data.UsuarioFirestoreRepository
import com.google.firebase.auth.FirebaseAuth

class PerfilActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPerfilBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private var usuario: UsuarioEntity? = null
    private var atualizandoSwitch = false
    private val firestoreRepository = UsuarioFirestoreRepository()
    private lateinit var sincronizacaoCondutor: SincronizacaoCondutorValidado

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPerfilBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootPerfil.applySystemInsets()

        repository = UsuarioRepository(this)
        session = SessionManager(this)
        sincronizacaoCondutor = SincronizacaoCondutorValidado(
            firestore = firestoreRepository,
            usuarios = repository,
            session = session
        )

        if (!session.estaLogado()) {
            irParaLogin()
            return
        }

        configurarBotaoVoltar(binding.btnVoltar)


        binding.switchPapel.setOnCheckedChangeListener { _, checked ->

            if (atualizandoSwitch) return@setOnCheckedChangeListener

            val atual = usuario ?: return@setOnCheckedChangeListener

            val novoPapel = if (checked) {
                PapelUsuario.MOTORISTA
            } else {
                PapelUsuario.PASSAGEIRO
            }

            val uid = FirebaseAuth.getInstance().currentUser?.uid

            if (uid == null) {
                Toast.makeText(
                    this,
                    "Sessão Firebase inválida.",
                    Toast.LENGTH_SHORT
                ).show()
                bind(atual)
                return@setOnCheckedChangeListener
            }

            binding.switchPapel.isEnabled = false

            firestoreRepository.atualizarPapel(
                uid = uid,
                novoPapel = novoPapel,

                onSuccess = {
                    lifecycleScope.launch {
                        try {
                            val usuarioAtualizado = repository.buscar(atual.id)

                            if (usuarioAtualizado != null) {
                                repository.alternarPapel(
                                    usuarioAtualizado,
                                    novoPapel
                                )
                            }

                            carregar()
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@PerfilActivity,
                                "Firebase atualizado, mas houve erro no Room.",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            binding.switchPapel.isEnabled = true
                        }
                    }
                },

                onError = { erro ->
                    Toast.makeText(
                        this,
                        "Erro ao alterar papel: $erro",
                        Toast.LENGTH_LONG
                    ).show()

                    bind(atual)
                    binding.switchPapel.isEnabled = true
                }
            )
        }

        binding.btnCompletarMotorista.setOnClickListener {
            startActivity(Intent(this, VerificacaoMotoristaActivity::class.java))
        }
        binding.btnSair.setOnClickListener {
            session.encerrarSessao()
            irParaLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!session.estaLogado()) return
        carregar()
        sincronizacaoCondutor.executar(lifecycleScope) {
            carregar()
        }
    }

    private fun carregar() {
        lifecycleScope.launch {
            val atual = repository.buscar(session.usuarioId())
            if (atual == null) {
                session.encerrarSessao()
                irParaLogin()
                return@launch
            }
            usuario = atual
            bind(atual)
        }
    }

    private fun bind(user: UsuarioEntity) {
        binding.tvNome.text = user.nome
        binding.tvCurso.text = user.curso
        binding.tvEmail.text = user.email
        binding.tvInstituicao.text = user.instituicao

        val motorista = user.isMotoristaAtivo()
        atualizandoSwitch = true
        binding.switchPapel.isChecked = motorista
        atualizandoSwitch = false

        binding.chipPapel.text = if (motorista) getString(R.string.papel_motorista) else getString(R.string.papel_passageiro)
        binding.imgAvatar.setImageResource(if (motorista) R.drawable.ic_car else R.drawable.ic_person)

        if (user.totalViagens == 0) {
            binding.tvReputacao.text = getString(R.string.sem_avaliacoes)
        } else {
            binding.tvReputacao.text = String.format(
                "Média %.1f ★ · %d viagens",
                user.mediaAvaliacao,
                user.totalViagens
            )
        }

        binding.bannerMotorista.visibility =
            if (motorista && !user.condutorValidado) View.VISIBLE else View.GONE

        binding.cardMotorista.visibility =
            if (motorista && user.condutorValidado) View.VISIBLE else View.GONE

        if (user.condutorValidado) {
            binding.tvStatusCondutor.text = getString(R.string.status_validado)
            binding.tvDadosVeiculo.text =
                "CNH ${user.cnhNumero}\n${user.veiculoModelo} · ${user.veiculoCor} · ${user.veiculoPlaca}"
            binding.tvEstatisticasMotorista.text =
                "Taxa de aceite: ${"%.0f".format(user.taxaAceite)}%"
        }
    }

    private fun irParaLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.MainActivity
import com.example.hitchhike.R
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityVerificacaoMotoristaBinding
import kotlinx.coroutines.launch
import com.example.hitchhike.data.SolicitacaoMotoristaFirestoreRepository

class VerificacaoMotoristaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVerificacaoMotoristaBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private lateinit var solicitacaoRepository: SolicitacaoMotoristaFirestoreRepository
    private var cnhAnexada = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVerificacaoMotoristaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootVerificacao.applySystemInsets()
        configurarBotaoVoltar(binding.btnVoltar)

        repository = UsuarioRepository(this)
        session = SessionManager(this)
        solicitacaoRepository = SolicitacaoMotoristaFirestoreRepository()

        lifecycleScope.launch {
            val usuario = repository.buscar(session.usuarioId()) ?: return@launch
            binding.etCnh.setText(usuario.cnhNumero.orEmpty())
            binding.etPlaca.setText(usuario.veiculoPlaca.orEmpty())
            binding.etModelo.setText(usuario.veiculoModelo.orEmpty())
            binding.etCor.setText(usuario.veiculoCor.orEmpty())
            cnhAnexada = usuario.cnhAnexada
            atualizarStatusAnexo()
        }

        binding.btnAnexarCnh.setOnClickListener {
            cnhAnexada = true
            atualizarStatusAnexo()
        }
        binding.btnSalvar.setOnClickListener { salvar() }
    }

    private fun atualizarStatusAnexo() {
        binding.tvCnhStatus.visibility = if (cnhAnexada) View.VISIBLE else View.GONE
        binding.tvCnhStatus.text = getString(R.string.cnh_anexada)
    }


    private fun salvar() {
        val cnh = binding.etCnh.text?.toString().orEmpty()
        val placa = binding.etPlaca.text?.toString().orEmpty()
        val modelo = binding.etModelo.text?.toString().orEmpty()
        val cor = binding.etCor.text?.toString().orEmpty()

        if (cnh.isBlank() || !cnhAnexada ||
            placa.isBlank() || modelo.isBlank() || cor.isBlank()
        ) {
            Toast.makeText(
                this,
                getString(R.string.erro_cnh),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        binding.btnSalvar.isEnabled = false

        lifecycleScope.launch {
            val usuario = repository.buscar(session.usuarioId())

            if (usuario == null) {
                binding.btnSalvar.isEnabled = true
                Toast.makeText(
                    this@VerificacaoMotoristaActivity,
                    "Usuário não encontrado.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val resultado = repository.salvarCondutor(
                usuario,
                cnh,
                cnhAnexada,
                placa,
                modelo,
                cor
            )

            resultado.fold(
                onSuccess = {
                    solicitacaoRepository.enviarSolicitacao(
                        placa = placa,
                        modelo = modelo,
                        cor = cor,

                        onSuccess = {
                            Toast.makeText(
                                this@VerificacaoMotoristaActivity,
                                "Solicitação enviada! Aguarde a aprovação.",
                                Toast.LENGTH_LONG
                            ).show()

                            startActivity(
                                Intent(
                                    this@VerificacaoMotoristaActivity,
                                    MainActivity::class.java
                                ).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                                }
                            )

                            finish()
                        },

                        onError = { erro ->
                            binding.btnSalvar.isEnabled = true

                            Toast.makeText(
                                this@VerificacaoMotoristaActivity,
                                "Dados salvos no celular, mas não foi possível enviar ao Firebase: $erro",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                },

                onFailure = {
                    binding.btnSalvar.isEnabled = true

                    Toast.makeText(
                        this@VerificacaoMotoristaActivity,
                        getString(R.string.erro_placa_duplicada),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

}

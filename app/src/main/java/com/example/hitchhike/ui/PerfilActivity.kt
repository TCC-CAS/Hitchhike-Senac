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
import com.example.hitchhike.data.PlanoAssinatura
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioEntity
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityPerfilBinding
import kotlinx.coroutines.launch

class PerfilActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPerfilBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private var usuario: UsuarioEntity? = null
    private var atualizandoSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPerfilBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootPerfil.applySystemInsets()

        repository = UsuarioRepository(this)
        session = SessionManager(this)

        if (!session.estaLogado()) {
            irParaLogin()
            return
        }

        binding.switchPapel.setOnCheckedChangeListener { _, checked ->
            if (atualizandoSwitch) return@setOnCheckedChangeListener
            val atual = usuario ?: return@setOnCheckedChangeListener
            val novoPapel = if (checked) PapelUsuario.MOTORISTA else PapelUsuario.PASSAGEIRO
            lifecycleScope.launch {
                repository.alternarPapel(atual, novoPapel)
                carregar()
            }
        }

        binding.btnCompletarMotorista.setOnClickListener {
            startActivity(Intent(this, VerificacaoMotoristaActivity::class.java))
        }
        binding.btnEditarVeiculo.setOnClickListener {
            startActivity(Intent(this, VerificacaoMotoristaActivity::class.java))
        }
        binding.btnPlanoPremium.setOnClickListener {
            val atual = usuario ?: return@setOnClickListener
            lifecycleScope.launch {
                repository.atualizarPlano(atual, PlanoAssinatura.PREMIUM)
                Toast.makeText(this@PerfilActivity, getString(R.string.sucesso_plano), Toast.LENGTH_SHORT).show()
                carregar()
            }
        }
        binding.btnSair.setOnClickListener {
            session.encerrarSessao()
            irParaLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (session.estaLogado()) carregar()
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

        val planoTexto = if (user.plano == PlanoAssinatura.PREMIUM.name) {
            getString(R.string.plano_premium)
        } else {
            getString(R.string.plano_gratuito)
        }
        binding.tvPlano.text = "$planoTexto\nUso nesta semana: ${user.caronasNaSemana} de ${user.limiteSemanal()}"
        binding.btnPlanoPremium.visibility =
            if (user.plano == PlanoAssinatura.PREMIUM.name) View.GONE else View.VISIBLE

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

package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronasBackendRepository
import com.example.hitchhike.data.ElegibilidadeMotorista
import com.example.hitchhike.data.EstadoMotorista
import com.example.hitchhike.data.PapelUsuario
import com.example.hitchhike.data.ReputacaoTexto
import com.example.hitchhike.data.ResumoViagens
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.SincronizacaoCondutorValidado
import com.example.hitchhike.data.UsuarioEntity
import com.example.hitchhike.data.UsuarioFirestoreRepository
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityPerfilBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class PerfilActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPerfilBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private var usuario: UsuarioEntity? = null
    private var atualizandoSwitch = false
    private val firestoreRepository = UsuarioFirestoreRepository()
    private val caronasBackendRepository = CaronasBackendRepository()
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

            if (checked) {
                val elegibilidade = atual.elegibilidadeMotorista(exigirPapelMotorista = false)
                if (!elegibilidade.apto) {
                    Toast.makeText(
                        this,
                        ElegibilidadeMotorista.mensagemBloqueio(elegibilidade.estado, elegibilidade.motivoRecusa),
                        Toast.LENGTH_LONG
                    ).show()
                    bind(atual)
                    startActivity(Intent(this, VerificacaoMotoristaActivity::class.java))
                    return@setOnCheckedChangeListener
                }
            }

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
                        erro,
                        Toast.LENGTH_LONG
                    ).show()
                    bind(atual)
                    binding.switchPapel.isEnabled = true
                }
            )
        }

        val abrirVerificacao = View.OnClickListener {
            startActivity(Intent(this, VerificacaoMotoristaActivity::class.java))
        }
        binding.btnCompletarMotorista.setOnClickListener(abrirVerificacao)
        binding.btnRenovarCnh.setOnClickListener(abrirVerificacao)

        binding.btnSair.setOnClickListener {
            session.encerrarSessao()
            irParaLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!session.estaLogado()) return
        carregar()
        carregarReputacaoOnline()
        sincronizacaoCondutor.executar(lifecycleScope) {
            carregar()
        }
    }

    private fun carregarReputacaoOnline() {
        lifecycleScope.launch {
            caronasBackendRepository.reputacaoMinha().fold(
                onSuccess = { rep ->
                    val modelosRep = CaronasUiHelper.modelosReputacao(this@PerfilActivity)
                    val modelosViagens = CaronasUiHelper.modelosViagens(this@PerfilActivity)
                    binding.tvReputacao.text = ReputacaoTexto.formatar(
                        media = rep.media,
                        quantidade = rep.quantidade,
                        modelos = modelosRep
                    )
                    val resumoViagens = ResumoViagens.viagens(rep.viagens, modelosViagens)
                    val detalhePapeis = if (rep.viagens > 0) {
                        " (${getString(R.string.viagens_detalhe_papeis, rep.viagensComoMotorista, rep.viagensComoPassageiro)})"
                    } else {
                        ""
                    }
                    binding.tvViagensRealizadas.text = "$resumoViagens$detalhePapeis"
                    binding.tvViagensRealizadas.visibility = View.VISIBLE
                },
                onFailure = {
                    // Mantém exibição padrão sem nota fictícia quando offline
                }
            )
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

        val elegibilidade = user.elegibilidadeMotorista(exigirPapelMotorista = false)
        val motoristaAtivoEApto = user.isMotoristaAtivo() && elegibilidade.apto

        atualizandoSwitch = true
        binding.switchPapel.isChecked = motoristaAtivoEApto
        atualizandoSwitch = false

        binding.chipPapel.text =
            if (motoristaAtivoEApto) getString(R.string.papel_motorista) else getString(R.string.papel_passageiro)
        binding.imgAvatar.setImageResource(
            if (motoristaAtivoEApto) R.drawable.ic_car else R.drawable.ic_person
        )

        val modelosRep = CaronasUiHelper.modelosReputacao(this)
        val modelosViagens = CaronasUiHelper.modelosViagens(this)
        if (binding.tvReputacao.text.isNullOrBlank()) {
            binding.tvReputacao.text = ReputacaoTexto.formatar(null, 0, modelosRep)
        }
        if (binding.tvViagensRealizadas.visibility != View.VISIBLE) {
            binding.tvViagensRealizadas.text = ResumoViagens.viagens(user.totalViagens, modelosViagens)
            binding.tvViagensRealizadas.visibility = View.VISIBLE
        }

        if (elegibilidade.apto) {
            binding.bannerMotorista.visibility = View.GONE
            binding.cardMotorista.visibility = View.VISIBLE
            binding.tvStatusCondutor.setTextColor(ContextCompat.getColor(this, R.color.hh_green))
            val validadeBr = formatarDataBr(elegibilidade.cnhValidade)
            binding.tvStatusCondutor.text = if (elegibilidade.venceEmBreve) {
                "Condutor validado · CNH vence em ${elegibilidade.diasRestantes} dia(s) ($validadeBr)"
            } else {
                "Condutor validado · CNH válida até $validadeBr"
            }
            val dadosVeiculo = listOfNotNull(
                user.veiculoModelo?.takeIf { it.isNotBlank() },
                user.veiculoCor?.takeIf { it.isNotBlank() },
                user.veiculoPlaca?.takeIf { it.isNotBlank() }
            ).joinToString(" · ").ifBlank { "Veículo cadastrado na solicitação" }
            binding.tvDadosVeiculo.text = dadosVeiculo
            binding.tvEstatisticasMotorista.text =
                "Taxa de aceite: ${"%.0f".format(user.taxaAceite)}%"
        } else {
            binding.cardMotorista.visibility = View.GONE
            binding.bannerMotorista.visibility = View.VISIBLE
            val corTexto = if (elegibilidade.estado == EstadoMotorista.SOLICITACAO_RECUSADA ||
                elegibilidade.estado == EstadoMotorista.CNH_VENCIDA
            ) {
                R.color.hh_error
            } else {
                R.color.hh_warning
            }
            binding.tvBannerMotorista.setTextColor(ContextCompat.getColor(this, corTexto))
            binding.tvBannerMotorista.text =
                ElegibilidadeMotorista.mensagemBloqueio(elegibilidade.estado, elegibilidade.motivoRecusa)
            binding.btnCompletarMotorista.text = when (elegibilidade.estado) {
                EstadoMotorista.SOLICITACAO_RECUSADA -> getString(R.string.action_enviar_novo_pdf_cnh)
                EstadoMotorista.CNH_VENCIDA -> "Renovar PDF da CNH"
                EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA -> "Enviar PDF da CNH para regularizar"
                EstadoMotorista.REVISAO_PENDENTE -> "Ver situação / Substituir PDF da CNH"
                else -> getString(R.string.action_completar_motorista)
            }
        }
    }

    private fun formatarDataBr(dataIso: String?): String {
        if (dataIso.isNullOrBlank() || !dataIso.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
            return "data não informada"
        }
        val partes = dataIso.split("-")
        return "${partes[2]}/${partes[1]}/${partes[0]}"
    }

    private fun irParaLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

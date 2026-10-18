package com.example.hitchhike.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronaRepository
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.StatusSolicitacao
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityDetalheCaronaBinding
import com.example.hitchhike.util.DateTimeFormat
import kotlinx.coroutines.launch

class DetalheCaronaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetalheCaronaBinding
    private lateinit var session: SessionManager
    private lateinit var usuarioRepository: UsuarioRepository
    private lateinit var caronaRepository: CaronaRepository
    private var caronaId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDetalheCaronaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootDetalhe.applySystemInsets()

        session = SessionManager(this)
        usuarioRepository = UsuarioRepository(this)
        caronaRepository = CaronaRepository(this)
        caronaId = intent.getLongExtra(EXTRA_CARONA_ID, -1L)

        binding.btnVoltar.setOnClickListener { finish() }
        binding.btnSolicitar.setOnClickListener { solicitar() }
        binding.btnCancelar.setOnClickListener { cancelar() }
        carregar()
    }

    private fun carregar() {
        lifecycleScope.launch {
            val carona = caronaRepository.buscarPorId(caronaId) ?: return@launch
            val motorista = usuarioRepository.buscar(carona.motoristaId)
            val usuarioId = session.usuarioId()
            val dono = carona.motoristaId == usuarioId

            binding.tvTrajeto.text = "${carona.origem} → ${carona.destino}"
            binding.tvHorario.text = DateTimeFormat.dataHora(carona.dataHoraSaida)
            binding.tvMotorista.text = getString(R.string.detalhe_motorista, motorista?.nome.orEmpty())
            binding.tvVagas.text = getString(R.string.detalhe_vagas, carona.vagasDisponiveis, carona.vagasTotais)
            binding.tvValor.text = getString(R.string.detalhe_valor, DateTimeFormat.valor(carona.valor))
            binding.tvStatus.text = carona.status

            binding.btnSolicitar.visibility = if (!dono && carona.status == "ABERTA") View.VISIBLE else View.GONE
            binding.btnCancelar.visibility = if (dono && carona.status == "ABERTA") View.VISIBLE else View.GONE
            binding.tvPedidosTitulo.visibility = if (dono) View.VISIBLE else View.GONE

            binding.listaPedidos.removeAllViews()
            if (dono) {
                val inflater = LayoutInflater.from(this@DetalheCaronaActivity)
                caronaRepository.listarSolicitacoes(caronaId).forEach { pedido ->
                    val passageiro = usuarioRepository.buscar(pedido.passageiroId)
                    val item = inflater.inflate(R.layout.item_solicitacao, binding.listaPedidos, false)
                    item.findViewById<TextView>(R.id.tvPassageiro).text = passageiro?.nome.orEmpty()
                    item.findViewById<TextView>(R.id.tvStatusPedido).text = pedido.status
                    val acoes = item.findViewById<View>(R.id.acoesPedido)
                    val pendente = pedido.status == StatusSolicitacao.PENDENTE.name
                    acoes.visibility = if (pendente) View.VISIBLE else View.GONE
                    item.findViewById<View>(R.id.btnAceitar).setOnClickListener {
                        lifecycleScope.launch {
                            caronaRepository.aceitar(pedido.id).fold(
                                onSuccess = {
                                    Toast.makeText(this@DetalheCaronaActivity, getString(R.string.sucesso_aceitar), Toast.LENGTH_SHORT).show()
                                    carregar()
                                },
                                onFailure = {
                                    Toast.makeText(this@DetalheCaronaActivity, getString(R.string.erro_vaga), Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                    item.findViewById<View>(R.id.btnRecusar).setOnClickListener {
                        lifecycleScope.launch {
                            caronaRepository.recusar(pedido.id)
                            carregar()
                        }
                    }
                    binding.listaPedidos.addView(item)
                }
            }
        }
    }

    private fun solicitar() {
        lifecycleScope.launch {
            caronaRepository.solicitar(caronaId, session.usuarioId()).fold(
                onSuccess = {
                    Toast.makeText(this@DetalheCaronaActivity, getString(R.string.sucesso_solicitar), Toast.LENGTH_SHORT).show()
                    finish()
                },
                onFailure = { erro ->
                    val mensagem = when (erro.message) {
                        "autosolicitacao" -> getString(R.string.erro_autosolicitacao)
                        "carona_indisponivel" -> getString(R.string.erro_vaga)
                        else -> getString(R.string.erro_ja_solicitou)
                    }
                    Toast.makeText(this@DetalheCaronaActivity, mensagem, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun cancelar() {
        lifecycleScope.launch {
            caronaRepository.cancelarCarona(caronaId, session.usuarioId()).fold(
                onSuccess = {
                    Toast.makeText(this@DetalheCaronaActivity, getString(R.string.sucesso_cancelar), Toast.LENGTH_SHORT).show()
                    carregar()
                },
                onFailure = {
                    Toast.makeText(this@DetalheCaronaActivity, getString(R.string.erro_campos_obrigatorios), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    companion object {
        const val EXTRA_CARONA_ID = "carona_id"
    }
}

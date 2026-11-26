package com.example.hitchhike.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.AvaliacaoFirestoreRepository
import com.example.hitchhike.data.CaronaFirestore
import com.example.hitchhike.data.CaronaFirestoreRepository
import com.example.hitchhike.data.CaronaRepository
import com.example.hitchhike.data.CaronaStatusExibicao
import com.example.hitchhike.data.CaronasBackendRepository
import com.example.hitchhike.data.FalhaPagamento
import com.example.hitchhike.data.PagamentoException
import com.example.hitchhike.data.PagamentoRepository
import com.example.hitchhike.data.PrivacidadeNome
import com.example.hitchhike.data.ReputacaoTexto
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.Situacao
import com.example.hitchhike.data.SolicitacaoCaronaFirestoreRepository
import com.example.hitchhike.data.StatusCarona
import com.example.hitchhike.data.StatusSolicitacao
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityDetalheCaronaBinding
import com.example.hitchhike.util.DateTimeFormat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

class DetalheCaronaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetalheCaronaBinding
    private lateinit var session: SessionManager
    private lateinit var usuarioRepository: UsuarioRepository
    private lateinit var caronaRepository: CaronaRepository
    private var caronaId: Long = -1L
    private val caronaFirestoreRepository = CaronaFirestoreRepository()
    private val solicitacaoFirestoreRepository = SolicitacaoCaronaFirestoreRepository()
    private val caronasBackendRepository = CaronasBackendRepository()
    private val avaliacaoRepository = AvaliacaoFirestoreRepository(caronasBackendRepository)
    private var caronaFirestoreId: String? = null
    private var caronaOnline: CaronaFirestore? = null
    private val pagamentoRepository = PagamentoRepository()
    private var pagamentoId: String? = null
    private var aguardandoRetornoCheckout = false

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
        caronaFirestoreId = intent.getStringExtra(EXTRA_CARONA_FIRESTORE_ID)

        configurarBotaoVoltar(binding.btnVoltar)
        binding.btnSolicitar.setOnClickListener { solicitar() }
        binding.btnCancelar.setOnClickListener { cancelar() }
        binding.btnIniciar.setOnClickListener { confirmarEIniciarViagem() }
        binding.btnConcluir.setOnClickListener { confirmarEConcluirViagem() }
        binding.btnConfirmarParticipacao.setOnClickListener { confirmarParticipacaoPassageiro() }
        binding.btnConfirmarConclusao.setOnClickListener { confirmarConclusaoPassageiro() }

        if (caronaFirestoreId != null) {
            carregarOnline()
        } else {
            carregar()
        }
    }

    private fun carregar() {
        lifecycleScope.launch {
            val carona = caronaRepository.buscarPorId(caronaId) ?: return@launch
            val usuarioId = session.usuarioId()
            val dono = carona.motoristaId == usuarioId
            val statusExibicao = CaronaStatusExibicao.calcular(carona)

            binding.tvOrigem.text = carona.origem
            binding.tvDestino.text = carona.destino
            exibirParadas(null)
            binding.tvHorario.text = DateTimeFormat.dataHora(carona.dataHoraSaida)
            binding.tvMotorista.text = PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.MOTORISTA)
            binding.tvVagas.text = getString(
                R.string.detalhe_vagas_compacto,
                carona.vagasDisponiveis,
                carona.vagasTotais
            )
            exibirResumoValores(carona.valor)

            binding.tvStatus.text = CaronasUiHelper.rotuloStatusCarona(this@DetalheCaronaActivity, statusExibicao)

            val disponivel = CaronaStatusExibicao.disponivelParaNovaSolicitacao(
                statusArmazenado = carona.status,
                dataHoraSaidaMillis = carona.dataHoraSaida,
                vagasDisponiveis = carona.vagasDisponiveis
            )
            binding.btnSolicitar.visibility =
                if (!dono && disponivel) View.VISIBLE else View.GONE
            binding.btnCancelar.visibility =
                if (dono && carona.status == "ABERTA") View.VISIBLE else View.GONE
            binding.tvPedidosTitulo.visibility = if (dono) View.VISIBLE else View.GONE

            binding.listaPedidos.removeAllViews()
            if (dono) {
                val inflater = LayoutInflater.from(this@DetalheCaronaActivity)
                caronaRepository.listarSolicitacoes(caronaId).forEach { pedido ->
                    val item = inflater.inflate(R.layout.item_solicitacao, binding.listaPedidos, false)
                    item.findViewById<TextView>(R.id.tvPassageiro).text =
                        PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.PASSAGEIRO)
                    item.findViewById<TextView>(R.id.tvStatusPedido).text = pedido.status
                    val acoes = item.findViewById<View>(R.id.acoesPedido)
                    val pendente = pedido.status == StatusSolicitacao.PENDENTE.name
                    acoes.visibility = if (pendente) View.VISIBLE else View.GONE
                    item.findViewById<View>(R.id.btnAceitar).setOnClickListener {
                        lifecycleScope.launch {
                            caronaRepository.aceitar(pedido.id, usuarioId).fold(
                                onSuccess = {
                                    Toast.makeText(
                                        this@DetalheCaronaActivity,
                                        getString(R.string.sucesso_aceitar),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    carregar()
                                },
                                onFailure = { erro ->
                                    val msg = erro.message?.takeIf { it.isNotBlank() && it != "vaga_indisponivel" }
                                        ?: getString(R.string.erro_vaga)
                                    Toast.makeText(this@DetalheCaronaActivity, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                    item.findViewById<View>(R.id.btnRecusar).setOnClickListener {
                        lifecycleScope.launch {
                            caronaRepository.recusar(pedido.id, usuarioId)
                            carregar()
                        }
                    }
                    binding.listaPedidos.addView(item)
                }
            }
        }
    }

    private fun ocultarAcoesOnline() {
        binding.btnSolicitar.visibility = View.GONE
        binding.btnIniciar.visibility = View.GONE
        binding.btnConcluir.visibility = View.GONE
        binding.btnConfirmarParticipacao.visibility = View.GONE
        binding.btnConfirmarConclusao.visibility = View.GONE
        binding.btnAvaliar.visibility = View.GONE
        binding.tvAvaliacaoEnviada.visibility = View.GONE
        binding.btnCancelar.visibility = View.GONE
        binding.btnPagarMp.visibility = View.GONE
        binding.btnSimularPagamento.visibility = View.GONE
        binding.btnAtualizarPagamento.visibility = View.GONE
        binding.tvBloqueio.visibility = View.GONE
        binding.cardVeiculo.visibility = View.GONE
        binding.tvReputacaoMotorista.visibility = View.GONE
        binding.tvPedidosTitulo.visibility = View.GONE
        binding.tvPedidosVazio.visibility = View.GONE
        binding.listaPedidos.removeAllViews()
    }

    private fun carregarOnline() {
        val id = caronaFirestoreId ?: return
        ocultarAcoesOnline()

        caronaFirestoreRepository.buscarPorId(
            caronaId = id,
            onSuccess = { carona ->
                caronaOnline = carona
                binding.tvOrigem.text = carona.origem
                binding.tvDestino.text = carona.destino
                binding.tvHorario.text = DateTimeFormat.dataHora(carona.dataHoraSaida.toDate().time)
                binding.tvMotorista.text = PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.MOTORISTA)
                binding.tvVagas.text = getString(
                    R.string.detalhe_vagas_compacto,
                    carona.vagasDisponiveis,
                    carona.vagasTotais
                )
                exibirResumoValores(carona.valor)
                exibirParadas(carona.paradas)

                val statusLocal = CaronaStatusExibicao.calcular(carona)
                binding.tvStatus.text = CaronasUiHelper.rotuloStatusCarona(this, statusLocal)

                val usuarioUid = FirebaseAuth.getInstance().currentUser?.uid
                val dono = usuarioUid != null && carona.motoristaUid == usuarioUid

                lifecycleScope.launch {
                    caronasBackendRepository.situacao(carona.id).fold(
                        onSuccess = { situacao ->
                            aplicarSituacaoBackend(carona, situacao, dono)
                        },
                        onFailure = {
                            aplicarFallbackSemSituacao(carona, dono)
                        }
                    )
                }
            },
            onError = { erro ->
                Toast.makeText(
                    this,
                    "Erro ao buscar carona: $erro",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun aplicarSituacaoBackend(
        carona: CaronaFirestore,
        situacao: Situacao,
        dono: Boolean
    ) {
        val statusExibicao = situacao.statusExibicao.ifBlank { CaronaStatusExibicao.calcular(carona) }
        binding.tvStatus.text = CaronasUiHelper.rotuloStatusCarona(this, statusExibicao)

        // Reputação do motorista e selo de CNH validada antes mesmo de solicitar
        val repMotorista = situacao.motorista
        val modelosRep = CaronasUiHelper.modelosReputacao(this)
        if (repMotorista != null) {
            binding.tvReputacaoMotorista.text = ReputacaoTexto.formatarComCnh(
                media = repMotorista.media,
                quantidade = repMotorista.quantidade,
                cnhValidada = repMotorista.cnhValidada,
                modelos = modelosRep
            )
            binding.tvReputacaoMotorista.visibility = View.VISIBLE
        }

        // Veículo (modelo, cor e placa): só é retornado pelo backend para passageiro ACEITO durante a viagem
        val veiculo = situacao.veiculo
        if (veiculo != null && (veiculo.modelo.isNotBlank() || veiculo.placa.isNotBlank())) {
            binding.tvVeiculo.text = getString(
                R.string.detalhe_veiculo_valor,
                veiculo.modelo,
                veiculo.cor,
                veiculo.placa
            )
            binding.cardVeiculo.visibility = View.VISIBLE
        } else {
            binding.cardVeiculo.visibility = View.GONE
        }

        // Mensagens informativas de bloqueio (ex: janela de 30 min para iniciar ou pagamento bloqueando cancelamento)
        val mensagensBloqueio = listOfNotNull(
            situacao.bloqueios["iniciar"],
            situacao.bloqueios["cancelar"]
        ).filter { it.isNotBlank() }
        if (mensagensBloqueio.isNotEmpty() && dono) {
            binding.tvBloqueio.text = mensagensBloqueio.joinToString("\n")
            binding.tvBloqueio.visibility = View.VISIBLE
        } else {
            binding.tvBloqueio.visibility = View.GONE
        }

        // Botões de ciclo de vida controlados estritamente por acoes do backend
        binding.btnIniciar.visibility = if (situacao.acoes.iniciar) View.VISIBLE else View.GONE
        binding.btnIniciar.isEnabled = true

        binding.btnConcluir.visibility = if (situacao.acoes.concluir) View.VISIBLE else View.GONE
        binding.btnConcluir.isEnabled = true

        binding.btnCancelar.visibility = if (situacao.acoes.cancelar) View.VISIBLE else View.GONE
        binding.btnCancelar.isEnabled = true

        binding.btnConfirmarParticipacao.visibility =
            if (situacao.acoes.confirmarParticipacao) View.VISIBLE else View.GONE
        binding.btnConfirmarParticipacao.isEnabled = true

        binding.btnConfirmarConclusao.visibility =
            if (situacao.acoes.confirmarConclusao) View.VISIBLE else View.GONE
        binding.btnConfirmarConclusao.isEnabled = true

        val estadoFinal = CaronaStatusExibicao.estadoFinal(statusExibicao)

        if (dono) {
            val podeAceitar = statusExibicao == StatusCarona.ABERTA.name && carona.vagasDisponiveis > 0
            carregarPedidosOnline(carona.id, podeAceitar)

            if (statusExibicao == StatusCarona.CONCLUIDA.name) {
                configurarBotaoParticipantes(
                    caronaId = carona.id,
                    temPendente = situacao.acoes.avaliar
                )
            }
        } else {
            val statusPedido = situacao.minhaSolicitacao?.status
            val podeNovaSolicitacao =
                statusExibicao == StatusCarona.ABERTA.name && carona.vagasDisponiveis > 0

            when (statusPedido) {
                null -> {
                    if (podeNovaSolicitacao) {
                        binding.btnSolicitar.text = getString(R.string.action_solicitar)
                        binding.btnSolicitar.isEnabled = true
                        binding.btnSolicitar.visibility = View.VISIBLE
                    }
                }
                "PENDENTE" -> {
                    if (!estadoFinal) {
                        binding.btnSolicitar.text = getString(R.string.status_pedido_pendente)
                        binding.btnSolicitar.isEnabled = false
                        binding.btnSolicitar.visibility = View.VISIBLE
                    }
                }
                "ACEITA" -> {
                    val aceitaPagamento = statusExibicao in setOf(
                        StatusCarona.ABERTA.name,
                        StatusCarona.LOTADA.name,
                        StatusCarona.AGUARDANDO_INICIO.name,
                        StatusCarona.EM_ANDAMENTO.name
                    )
                    if (aceitaPagamento) {
                        binding.btnPagarMp.visibility = View.VISIBLE
                        binding.btnPagarMp.isEnabled = true
                        binding.btnPagarMp.setOnClickListener { iniciarCheckoutMercadoPago(carona.id) }
                        binding.btnSimularPagamento.visibility = View.VISIBLE
                        binding.btnSimularPagamento.setOnClickListener { mostrarPagamentoSimulado(carona.valor) }
                        binding.btnAtualizarPagamento.setOnClickListener { atualizarStatusPagamento() }
                        if (pagamentoId != null) {
                            binding.btnAtualizarPagamento.visibility = View.VISIBLE
                        }
                    }

                    val elegivelConfirmado = statusExibicao == StatusCarona.CONCLUIDA.name &&
                        !situacao.minhaSolicitacao?.participacaoConfirmadaEm.isNullOrBlank() &&
                        !situacao.minhaSolicitacao?.conclusaoConfirmadaEm.isNullOrBlank()

                    if (elegivelConfirmado) {
                        configurarBotaoParticipantes(
                            caronaId = carona.id,
                            temPendente = situacao.acoes.avaliar
                        )
                    }
                }
                "RECUSADA" -> {
                    binding.btnSolicitar.text = getString(R.string.status_pedido_recusada)
                    binding.btnSolicitar.isEnabled = false
                    binding.btnSolicitar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun configurarBotaoParticipantes(caronaId: String, temPendente: Boolean) {
        binding.btnAvaliar.visibility = View.VISIBLE
        binding.btnAvaliar.isEnabled = true
        binding.btnAvaliar.text = if (temPendente) {
            getString(R.string.action_avaliar_participantes)
        } else {
            getString(R.string.action_ver_participantes)
        }
        binding.btnAvaliar.setOnClickListener {
            ParticipantesDialog.abrir(
                context = this,
                scope = lifecycleScope,
                backend = caronasBackendRepository,
                avaliacaoRepository = avaliacaoRepository,
                caronaId = caronaId,
                aoAtualizar = { carregarOnline() }
            )
        }
    }

    private fun aplicarFallbackSemSituacao(carona: CaronaFirestore, dono: Boolean) {
        val podeNovaSolicitacao = CaronaStatusExibicao.disponivelParaNovaSolicitacao(carona)
        if (dono) {
            carregarPedidosOnline(carona.id, podeNovaSolicitacao)
        } else if (podeNovaSolicitacao) {
            solicitacaoFirestoreRepository.buscarMinhaSolicitacao(
                caronaId = carona.id,
                onSuccess = { pedido ->
                    if (pedido == null) {
                        binding.btnSolicitar.text = getString(R.string.action_solicitar)
                        binding.btnSolicitar.isEnabled = true
                        binding.btnSolicitar.visibility = View.VISIBLE
                    }
                },
                onError = {}
            )
        }
    }

    private fun confirmarEIniciarViagem() {
        val id = caronaFirestoreId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_iniciar_titulo)
            .setMessage(R.string.dialog_iniciar_mensagem)
            .setNegativeButton(R.string.action_voltar_dialogo, null)
            .setPositiveButton(R.string.action_confirmar) { _, _ ->
                binding.btnIniciar.isEnabled = false
                lifecycleScope.launch {
                    caronasBackendRepository.iniciar(id).fold(
                        onSuccess = {
                            Toast.makeText(this@DetalheCaronaActivity, R.string.sucesso_iniciar, Toast.LENGTH_SHORT).show()
                            carregarOnline()
                        },
                        onFailure = { erro ->
                            binding.btnIniciar.isEnabled = true
                            Toast.makeText(
                                this@DetalheCaronaActivity,
                                CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun confirmarEConcluirViagem() {
        val id = caronaFirestoreId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_concluir_titulo)
            .setMessage(R.string.dialog_concluir_mensagem)
            .setNegativeButton(R.string.action_voltar_dialogo, null)
            .setPositiveButton(R.string.action_confirmar) { _, _ ->
                binding.btnConcluir.isEnabled = false
                lifecycleScope.launch {
                    caronasBackendRepository.concluir(id).fold(
                        onSuccess = {
                            Toast.makeText(this@DetalheCaronaActivity, R.string.sucesso_concluir, Toast.LENGTH_SHORT).show()
                            carregarOnline()
                        },
                        onFailure = { erro ->
                            binding.btnConcluir.isEnabled = true
                            Toast.makeText(
                                this@DetalheCaronaActivity,
                                CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun confirmarParticipacaoPassageiro() {
        val id = caronaFirestoreId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirmar_participacao_titulo)
            .setMessage(R.string.dialog_confirmar_participacao_mensagem)
            .setNegativeButton(R.string.action_voltar_dialogo, null)
            .setPositiveButton(R.string.action_confirmar) { _, _ ->
                binding.btnConfirmarParticipacao.isEnabled = false
                lifecycleScope.launch {
                    caronasBackendRepository.confirmarParticipacao(id).fold(
                        onSuccess = {
                            Toast.makeText(this@DetalheCaronaActivity, R.string.sucesso_participacao, Toast.LENGTH_SHORT).show()
                            carregarOnline()
                        },
                        onFailure = { erro ->
                            binding.btnConfirmarParticipacao.isEnabled = true
                            Toast.makeText(
                                this@DetalheCaronaActivity,
                                CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun confirmarConclusaoPassageiro() {
        val id = caronaFirestoreId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirmar_conclusao_titulo)
            .setMessage(R.string.dialog_confirmar_conclusao_mensagem)
            .setNegativeButton(R.string.action_voltar_dialogo, null)
            .setPositiveButton(R.string.action_confirmar) { _, _ ->
                binding.btnConfirmarConclusao.isEnabled = false
                lifecycleScope.launch {
                    caronasBackendRepository.confirmarConclusao(id).fold(
                        onSuccess = {
                            Toast.makeText(this@DetalheCaronaActivity, R.string.sucesso_conclusao, Toast.LENGTH_SHORT).show()
                            carregarOnline()
                        },
                        onFailure = { erro ->
                            binding.btnConfirmarConclusao.isEnabled = true
                            Toast.makeText(
                                this@DetalheCaronaActivity,
                                CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun exibirParadas(paradas: List<String>?) {
        binding.containerParadasDetalhe.removeAllViews()
        if (paradas.isNullOrEmpty()) {
            binding.containerParadasDetalhe.visibility = View.GONE
            return
        }
        binding.containerParadasDetalhe.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        paradas.forEachIndexed { index, parada ->
            val item = inflater.inflate(
                R.layout.item_parada_detalhe,
                binding.containerParadasDetalhe,
                false
            )
            item.findViewById<TextView>(R.id.tvParadaOrdem).text =
                getString(R.string.detalhe_parada_label, index + 1)
            item.findViewById<TextView>(R.id.tvParadaNome).text = parada
            binding.containerParadasDetalhe.addView(item)
        }
    }

    private fun calcularValores(valorCarona: Double): Triple<BigDecimal, BigDecimal, BigDecimal> {
        val valorBase = BigDecimal.valueOf(valorCarona)
            .setScale(2, RoundingMode.HALF_UP)

        val taxa = valorBase
            .multiply(BigDecimal("0.25"))
            .setScale(2, RoundingMode.HALF_UP)

        val total = valorBase.add(taxa)
        return Triple(valorBase, taxa, total)
    }

    private fun exibirResumoValores(valorCarona: Double) {
        val (valorBase, taxa, total) = calcularValores(valorCarona)
        val formato = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
        binding.tvValorBase.text = formato.format(valorBase)
        binding.tvValorTaxa.text = formato.format(taxa)
        binding.tvValorTotal.text = formato.format(total)
    }

    private fun solicitar() {
        val idOnline = caronaFirestoreId
        if (idOnline != null) {
            val carona = caronaOnline
            if (carona == null || !CaronaStatusExibicao.disponivelParaNovaSolicitacao(carona)) {
                Toast.makeText(
                    this,
                    getString(R.string.erro_carona_nao_disponivel),
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            binding.btnSolicitar.isEnabled = false
            solicitacaoFirestoreRepository.solicitar(
                caronaId = idOnline,
                onSuccess = {
                    Toast.makeText(
                        this,
                        getString(R.string.sucesso_pedido_enviado),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                },
                onError = { erro ->
                    binding.btnSolicitar.isEnabled = true
                    Toast.makeText(this, erro, Toast.LENGTH_LONG).show()
                }
            )
            return
        }

        lifecycleScope.launch {
            caronaRepository.solicitar(caronaId, session.usuarioId()).fold(
                onSuccess = {
                    Toast.makeText(
                        this@DetalheCaronaActivity,
                        getString(R.string.sucesso_solicitar),
                        Toast.LENGTH_SHORT
                    ).show()
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
        val idOnline = caronaFirestoreId
        if (idOnline != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_cancelar_titulo)
                .setMessage(R.string.dialog_cancelar_mensagem)
                .setNegativeButton(R.string.action_voltar_dialogo, null)
                .setPositiveButton(R.string.action_confirmar) { _, _ ->
                    binding.btnCancelar.isEnabled = false
                    lifecycleScope.launch {
                        caronasBackendRepository.cancelar(idOnline).fold(
                            onSuccess = {
                                Toast.makeText(
                                    this@DetalheCaronaActivity,
                                    getString(R.string.sucesso_cancelar),
                                    Toast.LENGTH_SHORT
                                ).show()
                                carregarOnline()
                            },
                            onFailure = { erro ->
                                binding.btnCancelar.isEnabled = true
                                Toast.makeText(
                                    this@DetalheCaronaActivity,
                                    CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                }
                .show()
            return
        }

        lifecycleScope.launch {
            caronaRepository.cancelarCarona(caronaId, session.usuarioId()).fold(
                onSuccess = {
                    Toast.makeText(
                        this@DetalheCaronaActivity,
                        getString(R.string.sucesso_cancelar),
                        Toast.LENGTH_SHORT
                    ).show()
                    carregar()
                },
                onFailure = {
                    Toast.makeText(
                        this@DetalheCaronaActivity,
                        getString(R.string.erro_campos_obrigatorios),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGAMENTO_ID, pagamentoId)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        pagamentoId = savedInstanceState.getString(STATE_PAGAMENTO_ID)
    }

    override fun onResume() {
        super.onResume()
        if (aguardandoRetornoCheckout && pagamentoId != null) {
            aguardandoRetornoCheckout = false
            atualizarStatusPagamento()
        }
    }

    private fun mensagemFalhaPagamento(erro: Throwable): String =
        (erro as? PagamentoException)?.message
            ?: getString(R.string.erro_campos_obrigatorios)

    private fun iniciarCheckoutMercadoPago(caronaId: String) {
        binding.btnPagarMp.isEnabled = false
        Toast.makeText(this, R.string.pagamento_abrindo, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            pagamentoRepository.criarPreferencia(caronaId).fold(
                onSuccess = { pref ->
                    binding.btnPagarMp.isEnabled = true
                    pagamentoId = pref.pagamentoId
                    aguardandoRetornoCheckout = true
                    binding.btnAtualizarPagamento.visibility = View.VISIBLE
                    exibirStatusPagamento("PENDENTE")
                    try {
                        CustomTabsIntent.Builder().build()
                            .launchUrl(this@DetalheCaronaActivity, Uri.parse(pref.checkoutUrl))
                    } catch (e: Exception) {
                        aguardandoRetornoCheckout = false
                        Toast.makeText(
                            this@DetalheCaronaActivity,
                            "Nenhum navegador disponível para abrir o checkout.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onFailure = { erro ->
                    binding.btnPagarMp.isEnabled = true
                    if ((erro as? PagamentoException)?.falha == FalhaPagamento.DUPLICADO) {
                        binding.btnPagarMp.isEnabled = false
                    }
                    Toast.makeText(
                        this@DetalheCaronaActivity,
                        mensagemFalhaPagamento(erro),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun atualizarStatusPagamento() {
        val id = pagamentoId ?: return
        binding.btnAtualizarPagamento.isEnabled = false
        lifecycleScope.launch {
            pagamentoRepository.consultarStatus(id).fold(
                onSuccess = { exibirStatusPagamento(it.status) },
                onFailure = {
                    Toast.makeText(
                        this@DetalheCaronaActivity,
                        mensagemFalhaPagamento(it),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
            binding.btnAtualizarPagamento.isEnabled = true
        }
    }

    private fun exibirStatusPagamento(status: String) {
        val texto = when (status) {
            "APROVADO" -> R.string.pagamento_status_aprovado
            "RECUSADO" -> R.string.pagamento_status_recusado
            "DIVERGENTE" -> R.string.pagamento_status_divergente
            else -> R.string.pagamento_status_pendente
        }
        binding.tvStatusPagamento.setText(texto)
        binding.tvStatusPagamento.visibility = View.VISIBLE
        if (status == "APROVADO") binding.btnPagarMp.isEnabled = false
    }

    private fun mostrarPagamentoSimulado(valorCarona: Double) {
        val (valorBase, taxa, total) = calcularValores(valorCarona)

        val formato = NumberFormat.getCurrencyInstance(
            Locale("pt", "BR")
        )

        val mensagem = """
        Valor definido pelo motorista: ${formato.format(valorBase)}
        Taxa Hitchhike (25%): ${formato.format(taxa)}
        Total a pagar pelo passageiro: ${formato.format(total)}
        Valor destinado ao motorista: ${formato.format(valorBase)}
        
        Esta é uma simulação acadêmica.
        Nenhum pagamento real será realizado.
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Simulação de pagamento")
            .setMessage(mensagem)
            .setNegativeButton("Voltar", null)
            .setPositiveButton("Confirmar simulação") { _, _ ->
                Toast.makeText(
                    this,
                    "Simulação concluída! Nenhum valor foi cobrado.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    companion object {
        const val EXTRA_CARONA_ID = "carona_id"
        const val EXTRA_CARONA_FIRESTORE_ID = "carona_firestore_id"
        private const val STATE_PAGAMENTO_ID = "pagamento_id"
    }

    private fun carregarPedidosOnline(
        caronaId: String,
        podeAceitar: Boolean
    ) {
        binding.tvPedidosTitulo.visibility = View.VISIBLE
        binding.tvPedidosVazio.visibility = View.GONE
        binding.listaPedidos.removeAllViews()

        solicitacaoFirestoreRepository.listarPorCarona(
            caronaId = caronaId,
            onSuccess = { pedidos ->
                binding.listaPedidos.removeAllViews()
                if (pedidos.isEmpty()) {
                    binding.tvPedidosVazio.visibility = View.VISIBLE
                    return@listarPorCarona
                }

                val viewsReputacaoPorPassageiro = mutableMapOf<String, MutableList<TextView>>()
                val modelosRep = CaronasUiHelper.modelosReputacao(this)

                pedidos.forEach { pedido ->
                    val item = LayoutInflater.from(this)
                        .inflate(
                            R.layout.item_solicitacao,
                            binding.listaPedidos,
                            false
                        )

                    // Antes e durante a viagem, sempre exibe apenas "Passageiro" genérico
                    item.findViewById<TextView>(R.id.tvPassageiro).text =
                        PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.PASSAGEIRO)

                    val tvRep = item.findViewById<TextView>(R.id.tvReputacaoPassageiro)
                    tvRep.text = getString(R.string.reputacao_sem_avaliacoes)
                    tvRep.visibility = View.VISIBLE
                    if (pedido.passageiroUid.isNotBlank()) {
                        viewsReputacaoPorPassageiro
                            .getOrPut(pedido.passageiroUid) { mutableListOf() }
                            .add(tvRep)
                    }

                    item.findViewById<TextView>(R.id.tvStatusPedido).text = pedido.status

                    val acoes = item.findViewById<View>(R.id.acoesPedido)
                    val btnAceitar = item.findViewById<View>(R.id.btnAceitar)
                    val btnRecusar = item.findViewById<View>(R.id.btnRecusar)
                    val pendente = pedido.status == "PENDENTE"

                    acoes.visibility = if (pendente) View.VISIBLE else View.GONE
                    btnAceitar.visibility = if (pendente && podeAceitar) View.VISIBLE else View.GONE

                    btnAceitar.setOnClickListener {
                        btnAceitar.isEnabled = false
                        btnRecusar.isEnabled = false

                        lifecycleScope.launch {
                            caronasBackendRepository.aceitar(caronaId, pedido.passageiroUid).fold(
                                onSuccess = {
                                    Toast.makeText(
                                        this@DetalheCaronaActivity,
                                        getString(R.string.sucesso_aceitar),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    carregarOnline()
                                },
                                onFailure = { erro ->
                                    btnAceitar.isEnabled = true
                                    btnRecusar.isEnabled = true
                                    Toast.makeText(
                                        this@DetalheCaronaActivity,
                                        CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    }

                    btnRecusar.setOnClickListener {
                        btnRecusar.isEnabled = false
                        btnAceitar.isEnabled = false

                        lifecycleScope.launch {
                            caronasBackendRepository.recusar(caronaId, pedido.passageiroUid).fold(
                                onSuccess = {
                                    Toast.makeText(
                                        this@DetalheCaronaActivity,
                                        getString(R.string.sucesso_recusar),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    carregarOnline()
                                },
                                onFailure = { erro ->
                                    btnRecusar.isEnabled = true
                                    btnAceitar.isEnabled = true
                                    Toast.makeText(
                                        this@DetalheCaronaActivity,
                                        CaronasUiHelper.mensagemErro(this@DetalheCaronaActivity, erro),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        }
                    }

                    binding.listaPedidos.addView(item)
                }

                val uids = viewsReputacaoPorPassageiro.keys.toList()
                if (uids.isNotEmpty()) {
                    lifecycleScope.launch {
                        caronasBackendRepository.reputacaoConsultar(uids).onSuccess { mapa ->
                            mapa.forEach { (uid, rep) ->
                                val textoRep = ReputacaoTexto.formatar(
                                    media = rep.media,
                                    quantidade = rep.quantidade,
                                    modelos = modelosRep
                                )
                                viewsReputacaoPorPassageiro[uid]?.forEach { view ->
                                    view.text = textoRep
                                }
                            }
                        }
                    }
                }
            },
            onError = { erro ->
                Toast.makeText(
                    this,
                    "Erro ao carregar pedidos: $erro",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}

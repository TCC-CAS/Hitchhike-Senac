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
import com.example.hitchhike.data.AvaliacaoFirestoreRepository
import com.example.hitchhike.data.CaronaFirestore
import com.example.hitchhike.data.CaronaFirestoreRepository
import com.example.hitchhike.data.CaronaStatusExibicao
import com.example.hitchhike.data.NomeAbreviadoFirestoreRepository
import com.example.hitchhike.data.PrivacidadeNome
import com.example.hitchhike.data.StatusCarona
import com.example.hitchhike.data.SolicitacaoCaronaFirestoreRepository
import com.google.firebase.auth.FirebaseAuth
import androidx.appcompat.app.AlertDialog
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
    private val solicitacaoFirestoreRepository =
        SolicitacaoCaronaFirestoreRepository()
    private val avaliacaoRepository = AvaliacaoFirestoreRepository()
    private val nomesAbreviados = NomeAbreviadoFirestoreRepository()
    private var caronaFirestoreId: String? = null
    private var caronaOnline: CaronaFirestore? = null

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
        if (caronaFirestoreId != null) {
            carregarOnline()
        } else {
            carregar()
        }
    }

    private fun carregar() {
        lifecycleScope.launch {
            val carona = caronaRepository.buscarPorId(caronaId) ?: return@launch
            val motorista = usuarioRepository.buscar(carona.motoristaId)
            val usuarioId = session.usuarioId()
            val dono = carona.motoristaId == usuarioId
            val minhaSolicitacao = if (dono) {
                null
            } else {
                caronaRepository.listarMinhasSolicitacoes(usuarioId)
                    .firstOrNull { it.caronaId == carona.id }
            }
            val encerrada = CaronaStatusExibicao.calcular(carona) == StatusCarona.ENCERRADA.name

            binding.tvTrajeto.text = "${carona.origem} → ${carona.destino}"
            binding.tvHorario.text = DateTimeFormat.dataHora(carona.dataHoraSaida)
            binding.tvMotorista.text = PrivacidadeNome.texto(
                PrivacidadeNome.decidir(
                    papel = PrivacidadeNome.Papel.MOTORISTA,
                    participante = minhaSolicitacao != null,
                    statusSolicitacao = minhaSolicitacao?.status,
                    encerrada = encerrada,
                    nomeCompleto = motorista?.nome
                )
            )
            binding.tvVagas.text = getString(R.string.detalhe_vagas, carona.vagasDisponiveis, carona.vagasTotais)
            binding.tvValor.text = getString(R.string.detalhe_valor, DateTimeFormat.valor(carona.valor))
            binding.tvStatus.text = CaronaStatusExibicao.calcular(carona)

            val disponivel = CaronaStatusExibicao.disponivelParaNovaSolicitacao(
                statusArmazenado = carona.status,
                dataHoraSaidaMillis = carona.dataHoraSaida,
                vagasDisponiveis = carona.vagasDisponiveis
            )
            binding.btnSolicitar.visibility =
                if (!dono && disponivel) View.VISIBLE else View.GONE
            binding.btnCancelar.visibility = if (dono && carona.status == "ABERTA") View.VISIBLE else View.GONE
            binding.tvPedidosTitulo.visibility = if (dono) View.VISIBLE else View.GONE

            binding.listaPedidos.removeAllViews()
            if (dono) {
                val inflater = LayoutInflater.from(this@DetalheCaronaActivity)
                caronaRepository.listarSolicitacoes(caronaId).forEach { pedido ->
                    val passageiro = usuarioRepository.buscar(pedido.passageiroId)
                    val item = inflater.inflate(R.layout.item_solicitacao, binding.listaPedidos, false)
                    item.findViewById<TextView>(R.id.tvPassageiro).text = PrivacidadeNome.texto(
                        PrivacidadeNome.decidir(
                            papel = PrivacidadeNome.Papel.PASSAGEIRO,
                            participante = true,
                            statusSolicitacao = pedido.status,
                            encerrada = encerrada,
                            nomeCompleto = passageiro?.nome
                        )
                    )
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

    private fun carregarOnline() {
        val id = caronaFirestoreId ?: return

// O cancelamento e o gerenciamento dos pedidos
// ainda não foram migrados para o Firebase.
        binding.btnSolicitar.visibility = View.GONE
        binding.btnAvaliar.visibility = View.GONE
        binding.tvAvaliacaoEnviada.visibility = View.GONE
        binding.btnCancelar.visibility = View.GONE
        binding.tvPedidosTitulo.visibility = View.GONE
        binding.listaPedidos.removeAllViews()

        caronaFirestoreRepository.buscarPorId(
            caronaId = id,
            onSuccess = { carona ->
                binding.tvTrajeto.text =
                    "${carona.origem} → ${carona.destino}"

                binding.tvHorario.text =
                    DateTimeFormat.dataHora(
                        carona.dataHoraSaida.toDate().time
                    )

                binding.tvMotorista.text = PrivacidadeNome.texto(
                    PrivacidadeNome.decidir(
                        papel = PrivacidadeNome.Papel.MOTORISTA,
                        participante = false,
                        statusSolicitacao = null,
                        encerrada = CaronaStatusExibicao.calcular(carona) == StatusCarona.ENCERRADA.name,
                        nomeCompleto = null
                    )
                )

                binding.tvVagas.text = getString(
                    R.string.detalhe_vagas,
                    carona.vagasDisponiveis,
                    carona.vagasTotais
                )

                binding.tvValor.text = getString(
                    R.string.detalhe_valor,
                    DateTimeFormat.valor(carona.valor)
                )

                caronaOnline = carona
                binding.tvStatus.text = CaronaStatusExibicao.calcular(carona)

                val usuarioUid = FirebaseAuth.getInstance().currentUser?.uid
                val dono = usuarioUid != null &&
                        carona.motoristaUid == usuarioUid
                val podeNovaSolicitacao =
                    CaronaStatusExibicao.disponivelParaNovaSolicitacao(carona)

                if (dono) {
                    carregarPedidosOnline(carona.id, podeNovaSolicitacao)
                } else {
                    binding.tvPedidosTitulo.visibility = View.GONE
                    binding.listaPedidos.removeAllViews()
                    atualizarAcoesPassageiro(carona, podeNovaSolicitacao)
                }

            },
            onError = { erro ->
                android.util.Log.e(
                    "HITCHHIKE_FIREBASE",
                    "Erro ao buscar detalhes da carona: $erro"
                )

                Toast.makeText(
                    this,
                    "Erro ao buscar carona: $erro",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun atualizarAcoesPassageiro(
        carona: CaronaFirestore,
        podeNovaSolicitacao: Boolean
    ) {
        binding.btnSolicitar.visibility = View.GONE
        binding.btnAvaliar.visibility = View.GONE
        binding.tvAvaliacaoEnviada.visibility = View.GONE

        solicitacaoFirestoreRepository.buscarMinhaSolicitacao(
            caronaId = carona.id,
            onSuccess = { pedido ->
                nomesAbreviados.exibir(
                    caronaId = carona.id,
                    alvoUid = carona.motoristaUid,
                    papel = PrivacidadeNome.Papel.MOTORISTA,
                    participante = pedido != null,
                    statusSolicitacao = pedido?.status,
                    encerrada = CaronaStatusExibicao.calcular(carona) == StatusCarona.ENCERRADA.name,
                    gravarProprio = pedido != null,
                    onTexto = { binding.tvMotorista.text = it }
                )
                when (pedido?.status) {
                    null -> {
                        if (podeNovaSolicitacao) {
                            binding.btnSolicitar.text = getString(R.string.action_solicitar)
                            binding.btnSolicitar.isEnabled = true
                            binding.btnSolicitar.visibility = View.VISIBLE
                            binding.btnSolicitar.setOnClickListener { solicitar() }
                        }
                    }

                    "PENDENTE" -> {
                        binding.btnSolicitar.text = getString(R.string.status_pedido_pendente)
                        binding.btnSolicitar.isEnabled = false
                        binding.btnSolicitar.visibility = View.VISIBLE
                    }

                    "ACEITA" -> {
                        binding.btnSolicitar.text = getString(R.string.action_simular_pagamento)
                        binding.btnSolicitar.isEnabled = true
                        binding.btnSolicitar.visibility = View.VISIBLE
                        binding.btnSolicitar.setOnClickListener {
                            mostrarPagamentoSimulado(carona.valor)
                        }
                        val passageiroUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@buscarMinhaSolicitacao
                        AvaliacaoDialog.vincular(
                            context = this,
                            repositorio = avaliacaoRepository,
                            botao = binding.btnAvaliar,
                            enviada = binding.tvAvaliacaoEnviada,
                            caronaId = carona.id,
                            avaliadoUid = carona.motoristaUid,
                            motoristaUid = carona.motoristaUid,
                            passageiroUid = passageiroUid,
                            avaliadorUid = passageiroUid,
                            statusSolicitacao = "ACEITA",
                            saidaMillis = carona.dataHoraSaida.toDate().time
                        )
                    }

                    "RECUSADA" -> {
                        binding.btnSolicitar.text = getString(R.string.status_pedido_recusada)
                        binding.btnSolicitar.isEnabled = false
                        binding.btnSolicitar.visibility = View.VISIBLE
                    }

                    else -> {
                        binding.btnSolicitar.visibility = View.GONE
                    }
                }
            },
            onError = { erro ->
                binding.btnSolicitar.visibility = View.GONE
                Toast.makeText(
                    this,
                    "Erro ao verificar solicitação: $erro",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun solicitar() {

        val idOnline = caronaFirestoreId

        if (idOnline != null) {
            val carona = caronaOnline
            if (carona == null ||
                !CaronaStatusExibicao.disponivelParaNovaSolicitacao(carona)
            ) {
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
                    nomesAbreviados.garantirProprio(idOnline)
                    Toast.makeText(
                        this,
                        "Solicitação enviada! Aguarde a aprovação do motorista.",
                        Toast.LENGTH_LONG
                    ).show()

                    finish()
                },

                onError = { erro ->
                    binding.btnSolicitar.isEnabled = true

                    Toast.makeText(
                        this,
                        erro,
                        Toast.LENGTH_LONG
                    ).show()
                }
            )

            return
        }

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


    private fun mostrarPagamentoSimulado(valorCarona: Double) {
        val valorBase = BigDecimal.valueOf(valorCarona)
            .setScale(2, RoundingMode.HALF_UP)

        val taxa = valorBase
            .multiply(BigDecimal("0.25"))
            .setScale(2, RoundingMode.HALF_UP)

        val total = valorBase.add(taxa)

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
    }


    private fun carregarPedidosOnline(
        caronaId: String,
        podeAceitar: Boolean
    ) {

        binding.tvPedidosTitulo.visibility = View.VISIBLE
        binding.listaPedidos.removeAllViews()

        nomesAbreviados.garantirProprio(caronaId)
        solicitacaoFirestoreRepository.listarPorCarona(
            caronaId = caronaId,

            onSuccess = { pedidos ->

                binding.listaPedidos.removeAllViews()

                pedidos.forEach { pedido ->

                    val item = LayoutInflater.from(this)
                        .inflate(
                            R.layout.item_solicitacao,
                            binding.listaPedidos,
                            false
                        )

                    val encerrada = caronaOnline?.let {
                        CaronaStatusExibicao.calcular(it) == StatusCarona.ENCERRADA.name
                    } == true
                    val passageiro = item.findViewById<TextView>(R.id.tvPassageiro)
                    nomesAbreviados.exibir(
                        caronaId = caronaId,
                        alvoUid = pedido.passageiroUid,
                        papel = PrivacidadeNome.Papel.PASSAGEIRO,
                        participante = true,
                        statusSolicitacao = pedido.status,
                        encerrada = encerrada,
                        gravarProprio = false,
                        onTexto = { passageiro.text = it }
                    )

                    item.findViewById<TextView>(R.id.tvStatusPedido).text =
                        pedido.status


                    val acoes = item.findViewById<View>(R.id.acoesPedido)
                    val btnAceitar = item.findViewById<View>(R.id.btnAceitar)
                    val btnRecusar = item.findViewById<View>(R.id.btnRecusar)
                    val pendente = pedido.status == "PENDENTE"

                    acoes.visibility =
                        if (pendente) View.VISIBLE else View.GONE
                    btnAceitar.visibility =
                        if (pendente && podeAceitar) View.VISIBLE else View.GONE

                    btnAceitar.setOnClickListener {
                        btnAceitar.isEnabled = false
                        btnRecusar.isEnabled = false

                        solicitacaoFirestoreRepository.aceitar(
                            solicitacaoId = pedido.id,

                            onSuccess = {
                                Toast.makeText(
                                    this,
                                    "Solicitação aceita! Vaga confirmada.",
                                    Toast.LENGTH_SHORT
                                ).show()

                                carregarOnline()
                            },

                            onError = { erro ->
                                btnAceitar.isEnabled = true
                                btnRecusar.isEnabled = true

                                Toast.makeText(
                                    this,
                                    erro,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }

                    val saida = caronaOnline?.dataHoraSaida?.toDate()?.time
                    val avaliadorUid = FirebaseAuth.getInstance().currentUser?.uid
                    if (saida != null && avaliadorUid != null) {
                        AvaliacaoDialog.vincular(
                            context = this,
                            repositorio = avaliacaoRepository,
                            botao = item.findViewById(R.id.btnAvaliar),
                            enviada = item.findViewById(R.id.tvAvaliacaoEnviada),
                            caronaId = caronaId,
                            avaliadoUid = pedido.passageiroUid,
                            motoristaUid = pedido.motoristaUid,
                            passageiroUid = pedido.passageiroUid,
                            avaliadorUid = avaliadorUid,
                            statusSolicitacao = pedido.status,
                            saidaMillis = saida
                        )
                    }

                    btnRecusar.setOnClickListener {
                        btnRecusar.isEnabled = false

                        solicitacaoFirestoreRepository.recusar(
                            solicitacaoId = pedido.id,

                            onSuccess = {
                                Toast.makeText(
                                    this,
                                    "Solicitação recusada.",
                                    Toast.LENGTH_SHORT
                                ).show()

                                carregarPedidosOnline(caronaId, podeAceitar)
                            },

                            onError = { erro ->
                                btnRecusar.isEnabled = true

                                Toast.makeText(
                                    this,
                                    erro,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }


                    binding.listaPedidos.addView(item)
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

package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.hitchhike.R
import com.example.hitchhike.data.AvaliacaoFirestoreRepository
import com.example.hitchhike.data.CaronaFirestore
import com.example.hitchhike.data.CaronaFirestoreRepository
import com.example.hitchhike.data.CaronaStatusExibicao
import com.example.hitchhike.data.NomeAbreviadoFirestoreRepository
import com.example.hitchhike.data.PrivacidadeNome
import com.example.hitchhike.data.StatusCarona
import com.example.hitchhike.data.SolicitacaoCaronaFirestore
import com.example.hitchhike.data.SolicitacaoCaronaFirestoreRepository
import com.example.hitchhike.databinding.ActivityMinhasCaronasBinding
import com.example.hitchhike.util.DateTimeFormat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.atomic.AtomicInteger

class MinhasCaronasActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMinhasCaronasBinding
    private val caronaFirestoreRepository = CaronaFirestoreRepository()
    private val solicitacaoFirestoreRepository =
        SolicitacaoCaronaFirestoreRepository()
    private val avaliacaoRepository = AvaliacaoFirestoreRepository()
    private val nomesAbreviados = NomeAbreviadoFirestoreRepository()
    private var carregamentoId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMinhasCaronasBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootMinhas.applySystemInsets()

        configurarBotaoVoltar(binding.btnVoltar)
        configurarAbas()
    }

    override fun onResume() {
        super.onResume()
        carregar()
    }

    private fun configurarAbas() {
        mostrarAba(proximas = true)
        binding.tabsMinhas.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    mostrarAba(proximas = tab.position == 0)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            }
        )
    }

    private fun mostrarAba(proximas: Boolean) {
        binding.scrollProximas.visibility =
            if (proximas) View.VISIBLE else View.GONE
        binding.scrollHistorico.visibility =
            if (proximas) View.GONE else View.VISIBLE
    }

    private fun carregar() {
        val id = ++carregamentoId
        binding.progressMinhas.visibility = View.VISIBLE

        var caronas: List<CaronaFirestore>? = null
        var pedidos: List<SolicitacaoCaronaFirestore>? = null
        var erroCaronas: String? = null
        var erroPedidos: String? = null

        fun tentarContinuar() {
            if (id != carregamentoId) return
            if (caronas == null && erroCaronas == null) return
            if (pedidos == null && erroPedidos == null) return

            val listaCaronas = caronas.orEmpty()
            val listaPedidos = pedidos.orEmpty()

            if (listaPedidos.isEmpty()) {
                concluirCarregamento(
                    id,
                    listaCaronas,
                    emptyList(),
                    erroCaronas,
                    erroPedidos
                )
                return
            }

            val pares = mutableListOf<PedidoComCarona>()
            val restantes = AtomicInteger(listaPedidos.size)

            listaPedidos.forEach { pedido ->
                caronaFirestoreRepository.buscarPorId(
                    caronaId = pedido.caronaId,
                    onSuccess = { carona ->
                        pares.add(PedidoComCarona(pedido, carona))
                        if (restantes.decrementAndGet() == 0) {
                            concluirCarregamento(
                                id,
                                listaCaronas,
                                pares.toList(),
                                erroCaronas,
                                erroPedidos
                            )
                        }
                    },
                    onError = {
                        pares.add(PedidoComCarona(pedido, null))
                        if (restantes.decrementAndGet() == 0) {
                            concluirCarregamento(
                                id,
                                listaCaronas,
                                pares.toList(),
                                erroCaronas,
                                erroPedidos
                            )
                        }
                    }
                )
            }
        }

        caronaFirestoreRepository.listarMinhasCaronas(
            onSuccess = { lista ->
                caronas = lista
                tentarContinuar()
            },
            onError = { erro ->
                erroCaronas = erro
                caronas = emptyList()
                tentarContinuar()
            }
        )

        solicitacaoFirestoreRepository.listarMinhasSolicitacoes(
            onSuccess = { lista ->
                pedidos = lista
                tentarContinuar()
            },
            onError = { erro ->
                erroPedidos = erro
                pedidos = emptyList()
                tentarContinuar()
            }
        )
    }

    private fun concluirCarregamento(
        id: Int,
        caronas: List<CaronaFirestore>,
        pedidos: List<PedidoComCarona>,
        erroCaronas: String?,
        erroPedidos: String?
    ) {
        if (id != carregamentoId) return

        binding.progressMinhas.visibility = View.GONE

        erroCaronas?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        erroPedidos?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }

        mostrarListas(caronas, pedidos)
    }

    private fun mostrarListas(
        caronas: List<CaronaFirestore>,
        pedidos: List<PedidoComCarona>
    ) {
        val agora = System.currentTimeMillis()

        val oferecidasProximas = caronas
            .filter { it.saidaMillis() > agora }
            .sortedBy { it.saidaMillis() }

        val oferecidasHistorico = caronas
            .filter { it.saidaMillis() <= agora }
            .sortedByDescending { it.saidaMillis() }

        val pedidosProximas = pedidos
            .filter { it.pertenceProximas(agora) }
            .sortedBy { it.ordenacaoMillis() }

        val pedidosHistorico = pedidos
            .filter { !it.pertenceProximas(agora) }
            .sortedByDescending { it.ordenacaoMillis() }

        preencherCaronas(
            oferecidasProximas,
            binding.listaOferecidasProximas,
            binding.tvVazioOferecidasProximas,
            historico = false
        )
        preencherCaronas(
            oferecidasHistorico,
            binding.listaOferecidasHistorico,
            binding.tvVazioOferecidasHistorico,
            historico = true
        )
        preencherPedidos(
            pedidosProximas,
            binding.listaPedidosProximas,
            binding.tvVazioPedidosProximas,
            historico = false
        )
        preencherPedidos(
            pedidosHistorico,
            binding.listaPedidosHistorico,
            binding.tvVazioPedidosHistorico,
            historico = true
        )
    }

    private fun preencherCaronas(
        caronas: List<CaronaFirestore>,
        container: LinearLayout,
        vazio: TextView,
        historico: Boolean
    ) {
        container.removeAllViews()
        vazio.visibility = if (caronas.isEmpty()) View.VISIBLE else View.GONE
        caronas.forEach { adicionarCardCarona(it, container, historico) }
    }

    private fun preencherPedidos(
        pedidos: List<PedidoComCarona>,
        container: LinearLayout,
        vazio: TextView,
        historico: Boolean
    ) {
        container.removeAllViews()
        vazio.visibility = if (pedidos.isEmpty()) View.VISIBLE else View.GONE
        pedidos.forEach { adicionarCardPedido(it, container, historico) }
    }

    private fun adicionarCardCarona(
        carona: CaronaFirestore,
        container: LinearLayout,
        historico: Boolean
    ) {
        val item = inflarCard(container)
        val saida = carona.saidaMillis()
        val etiqueta = CaronaStatusExibicao.calcular(carona)

        preencherCard(
            item = item,
            trajeto = "${carona.origem} → ${carona.destino}",
            horario = DateTimeFormat.dataHora(saida),
            detalhe = getString(
                R.string.carona_resumo,
                carona.vagasDisponiveis,
                DateTimeFormat.valor(carona.valor)
            ),
            etiqueta = etiqueta,
            origem = carona.origem,
            destino = carona.destino
        )

        nomesAbreviados.garantirProprio(carona.id)
        item.setOnClickListener { abrirDetalhe(carona.id) }
        if (historico && carona.saidaMillis() <= System.currentTimeMillis()) {
            val avaliar = item.findViewById<MaterialButton>(R.id.btnAvaliar)
            avaliar.visibility = View.VISIBLE
            avaliar.text = getString(R.string.action_avaliar_passageiros)
            avaliar.setOnClickListener { abrirDetalhe(carona.id) }
        }
        container.addView(item)
    }

    private fun adicionarCardPedido(
        pedidoComCarona: PedidoComCarona,
        container: LinearLayout,
        historico: Boolean
    ) {
        val item = inflarCard(container)
        val carona = pedidoComCarona.carona
        val pedido = pedidoComCarona.pedido

        val trajeto = if (carona != null) {
            "${carona.origem} → ${carona.destino}"
        } else {
            getString(R.string.minhas_pedido_detalhe)
        }

        val horario = if (carona != null) {
            DateTimeFormat.dataHora(carona.saidaMillis())
        } else {
            getString(R.string.minhas_pedido_sem_detalhe)
        }

        val encerrada = carona?.let {
            CaronaStatusExibicao.calcular(it) == StatusCarona.ENCERRADA.name
        } == true
        preencherCard(
            item = item,
            trajeto = trajeto,
            horario = horario,
            detalhe = "${
                PrivacidadeNome.texto(
                    PrivacidadeNome.decidir(
                        papel = PrivacidadeNome.Papel.MOTORISTA,
                        participante = true,
                        statusSolicitacao = pedido.status,
                        encerrada = encerrada,
                        nomeCompleto = null
                    )
                )
            } · ${getString(R.string.minhas_pedido_detalhe)}",
            etiqueta = etiquetaPedido(pedido.status),
            origem = carona?.origem.orEmpty(),
            destino = carona?.destino.orEmpty()
        )

        val detalhe = item.findViewById<TextView>(R.id.tvDetalhe)
        nomesAbreviados.exibir(
            caronaId = pedido.caronaId,
            alvoUid = carona?.motoristaUid.orEmpty(),
            papel = PrivacidadeNome.Papel.MOTORISTA,
            participante = true,
            statusSolicitacao = pedido.status,
            encerrada = encerrada,
            gravarProprio = true,
            onTexto = { identidade ->
                detalhe.text = "$identidade · ${getString(R.string.minhas_pedido_detalhe)}"
            }
        )

        if (carona != null) {
            item.setOnClickListener { abrirDetalhe(carona.id) }
            val avaliadorUid = FirebaseAuth.getInstance().currentUser?.uid
            if (historico && avaliadorUid != null && pedido.status == "ACEITA") {
                AvaliacaoDialog.vincular(
                    context = this,
                    repositorio = avaliacaoRepository,
                    botao = item.findViewById(R.id.btnAvaliar),
                    enviada = item.findViewById(R.id.tvAvaliacaoEnviada),
                    caronaId = carona.id,
                    avaliadoUid = carona.motoristaUid,
                    motoristaUid = carona.motoristaUid,
                    passageiroUid = avaliadorUid,
                    avaliadorUid = avaliadorUid,
                    statusSolicitacao = pedido.status,
                    saidaMillis = carona.saidaMillis()
                )
            }
        }

        container.addView(item)
    }

    private fun inflarCard(container: LinearLayout): View {
        return LayoutInflater.from(this).inflate(
            R.layout.item_carona,
            container,
            false
        )
    }

    private fun preencherCard(
        item: View,
        trajeto: String,
        horario: String,
        detalhe: String,
        etiqueta: String,
        origem: String,
        destino: String
    ) {
        item.findViewById<TextView>(R.id.tvTrajeto).text = trajeto
        item.findViewById<TextView>(R.id.tvHorario).text = horario
        item.findViewById<TextView>(R.id.tvDetalhe).text = detalhe

        val chip = item.findViewById<TextView>(R.id.tvStatusChip)
        chip.text = etiqueta
        chip.visibility = View.VISIBLE
        chip.contentDescription = etiqueta

        item.contentDescription = if (origem.isNotBlank() && destino.isNotBlank()) {
            getString(R.string.cd_item_carona, origem, destino)
        } else {
            trajeto
        }
    }

    private fun abrirDetalhe(caronaId: String) {
        startActivity(
            Intent(this, DetalheCaronaActivity::class.java)
                .putExtra(
                    DetalheCaronaActivity.EXTRA_CARONA_FIRESTORE_ID,
                    caronaId
                )
        )
    }

    private fun etiquetaPedido(status: String): String {
        return when (status) {
            "PENDENTE" -> getString(R.string.etiqueta_pendente)
            "ACEITA" -> getString(R.string.etiqueta_aceita)
            "RECUSADA" -> getString(R.string.etiqueta_recusada)
            "CANCELADA" -> getString(R.string.etiqueta_cancelada)
            else -> status
        }
    }

    private fun CaronaFirestore.saidaMillis(): Long =
        dataHoraSaida.toDate().time

    private fun PedidoComCarona.pertenceProximas(agora: Long): Boolean {
        if (pedido.status == "RECUSADA" || pedido.status == "CANCELADA") {
            return false
        }

        val saida = carona?.saidaMillis() ?: return pedido.status == "PENDENTE" ||
                pedido.status == "ACEITA"

        return (pedido.status == "PENDENTE" || pedido.status == "ACEITA") &&
                saida > agora
    }

    private fun PedidoComCarona.ordenacaoMillis(): Long {
        return carona?.saidaMillis()
            ?: pedido.criadoEm.toDate().time
    }

    private data class PedidoComCarona(
        val pedido: SolicitacaoCaronaFirestore,
        val carona: CaronaFirestore?
    )
}

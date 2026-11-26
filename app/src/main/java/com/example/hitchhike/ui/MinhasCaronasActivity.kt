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
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.AvaliacaoFirestoreRepository
import com.example.hitchhike.data.CaronaFirestore
import com.example.hitchhike.data.CaronaFirestoreRepository
import com.example.hitchhike.data.CaronaStatusExibicao
import com.example.hitchhike.data.CaronasBackendRepository
import com.example.hitchhike.data.PrivacidadeNome
import com.example.hitchhike.data.SolicitacaoCaronaFirestore
import com.example.hitchhike.data.SolicitacaoCaronaFirestoreRepository
import com.example.hitchhike.data.StatusCarona
import com.example.hitchhike.databinding.ActivityMinhasCaronasBinding
import com.example.hitchhike.util.DateTimeFormat
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.atomic.AtomicInteger

class MinhasCaronasActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMinhasCaronasBinding
    private val caronaFirestoreRepository = CaronaFirestoreRepository()
    private val solicitacaoFirestoreRepository = SolicitacaoCaronaFirestoreRepository()
    private val caronasBackendRepository = CaronasBackendRepository()
    private val avaliacaoRepository = AvaliacaoFirestoreRepository(caronasBackendRepository)
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
            .filter { !CaronaStatusExibicao.estadoFinal(CaronaStatusExibicao.calcular(it, agora)) }
            .sortedBy { it.saidaMillis() }

        val oferecidasHistorico = caronas
            .filter { CaronaStatusExibicao.estadoFinal(CaronaStatusExibicao.calcular(it, agora)) }
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
        val statusExibicao = CaronaStatusExibicao.calcular(carona)
        val etiqueta = CaronasUiHelper.rotuloStatusCarona(this, statusExibicao)

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

        item.setOnClickListener { abrirDetalhe(carona.id) }

        // Só viagens efetivamente CONCLUIDAS (nunca CANCELADA, EXPIRADA ou registros antigos ENCERRADA)
        // exibem o botão de participantes elegíveis / avaliação.
        if (historico && statusExibicao == StatusCarona.CONCLUIDA.name) {
            val avaliar = item.findViewById<MaterialButton>(R.id.btnAvaliar)
            avaliar.visibility = View.VISIBLE
            avaliar.text = getString(R.string.action_ver_participantes)
            avaliar.setOnClickListener {
                ParticipantesDialog.abrir(
                    context = this,
                    scope = lifecycleScope,
                    backend = caronasBackendRepository,
                    avaliacaoRepository = avaliacaoRepository,
                    caronaId = carona.id,
                    aoAtualizar = { carregar() }
                )
            }
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

        val statusCarona = carona?.let { CaronaStatusExibicao.calcular(it) }
        val concluidaEConfirmada = statusCarona == StatusCarona.CONCLUIDA.name &&
            pedido.status == "ACEITA" &&
            pedido.participacaoConfirmadaEm != null &&
            pedido.conclusaoConfirmadaEm != null

        val aguardandoConfirmacao = statusCarona == StatusCarona.CONCLUIDA.name &&
            pedido.status == "ACEITA" &&
            !concluidaEConfirmada

        val subtituloBase = if (aguardandoConfirmacao) {
            "${PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.MOTORISTA)} · ${getString(R.string.minhas_aguardando_confirmacao)}"
        } else {
            "${PrivacidadeNome.rotuloGenerico(PrivacidadeNome.Papel.MOTORISTA)} · ${getString(R.string.minhas_pedido_detalhe)}"
        }

        val etiquetaExibida = if (statusCarona == StatusCarona.CONCLUIDA.name && pedido.status == "ACEITA") {
            CaronasUiHelper.rotuloStatusCarona(this, StatusCarona.CONCLUIDA.name)
        } else if (statusCarona == StatusCarona.EXPIRADA.name && pedido.status != "RECUSADA") {
            CaronasUiHelper.rotuloStatusCarona(this, StatusCarona.EXPIRADA.name)
        } else {
            etiquetaPedido(pedido.status)
        }

        preencherCard(
            item = item,
            trajeto = trajeto,
            horario = horario,
            detalhe = subtituloBase,
            etiqueta = etiquetaExibida,
            origem = carona?.origem.orEmpty(),
            destino = carona?.destino.orEmpty()
        )

        if (carona != null) {
            item.setOnClickListener { abrirDetalhe(carona.id) }

            // Somente para participante elegível (ACEITA + participação e conclusão confirmadas em carona CONCLUIDA)
            if (historico && concluidaEConfirmada) {
                val avaliar = item.findViewById<MaterialButton>(R.id.btnAvaliar)
                avaliar.visibility = View.VISIBLE
                avaliar.text = getString(R.string.action_ver_participantes)
                avaliar.setOnClickListener {
                    ParticipantesDialog.abrir(
                        context = this,
                        scope = lifecycleScope,
                        backend = caronasBackendRepository,
                        avaliacaoRepository = avaliacaoRepository,
                        caronaId = carona.id,
                        aoAtualizar = { carregar() }
                    )
                }
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
        val c = carona ?: return pedido.status == "PENDENTE" || pedido.status == "ACEITA"
        val statusExibicao = CaronaStatusExibicao.calcular(c, agora)
        if (statusExibicao == StatusCarona.CONCLUIDA.name &&
            pedido.status == "ACEITA" &&
            (pedido.participacaoConfirmadaEm == null || pedido.conclusaoConfirmadaEm == null)
        ) {
            // Viagem recém-concluída pelo motorista que ainda aguarda confirmação do passageiro permanece visível em Próximas
            return true
        }
        return !CaronaStatusExibicao.estadoFinal(statusExibicao)
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

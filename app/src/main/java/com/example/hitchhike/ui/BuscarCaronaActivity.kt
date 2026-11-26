package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronaFirestore
import com.example.hitchhike.data.CaronaFirestoreRepository
import com.example.hitchhike.data.CaronaStatusExibicao
import com.example.hitchhike.data.CaronasBackendRepository
import com.example.hitchhike.data.ReputacaoTexto
import com.example.hitchhike.databinding.ActivityBuscarCaronaBinding
import com.example.hitchhike.util.DateTimeFormat
import kotlinx.coroutines.launch

class BuscarCaronaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuscarCaronaBinding
    private val caronaFirestoreRepository = CaronaFirestoreRepository()
    private val caronasBackendRepository = CaronasBackendRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityBuscarCaronaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootBuscar.applySystemInsets()

        val origens = resources.getStringArray(R.array.origens_campus)

        binding.etOrigem.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                origens
            )
        )

        configurarBotaoVoltar(binding.btnVoltar)

        binding.btnBuscar.setOnClickListener {
            buscar()
        }
    }

    override fun onResume() {
        super.onResume()
        buscar()
    }

    private fun buscar() {
        val origem = binding.etOrigem.text?.toString().orEmpty()
        val destino = binding.etDestino.text?.toString().orEmpty()

        caronaFirestoreRepository.buscar(
            origem = origem,
            destino = destino,
            onSuccess = { caronas ->
                mostrar(caronas)
            },
            onError = { erro ->
                Toast.makeText(
                    this,
                    erro,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun mostrar(caronas: List<CaronaFirestore>) {
        binding.listaCaronas.removeAllViews()

        val inflater = LayoutInflater.from(this)

        val disponiveis = caronas.filter { carona ->
            CaronaStatusExibicao.disponivelParaNovaSolicitacao(carona)
        }

        binding.tvVazio.visibility =
            if (disponiveis.isEmpty()) View.VISIBLE else View.GONE

        val viewsReputacaoPorMotorista = mutableMapOf<String, MutableList<TextView>>()
        val modelosRep = CaronasUiHelper.modelosReputacao(this)

        disponiveis.forEach { carona ->

            val item = inflater.inflate(
                R.layout.item_carona,
                binding.listaCaronas,
                false
            )

            item.findViewById<TextView>(R.id.tvTrajeto).text =
                "${carona.origem} → ${carona.destino}"

            item.findViewById<TextView>(R.id.tvHorario).text =
                DateTimeFormat.dataHora(
                    carona.dataHoraSaida.toDate().time
                )

            item.findViewById<TextView>(R.id.tvDetalhe).text =
                getString(
                    R.string.carona_resumo,
                    carona.vagasDisponiveis,
                    DateTimeFormat.valor(carona.valor)
                )

            val statusExibicao = CaronaStatusExibicao.calcular(carona)
            val chip = item.findViewById<TextView>(R.id.tvStatusChip)
            val rotuloStatus = CaronasUiHelper.rotuloStatusCarona(this, statusExibicao)
            chip.text = rotuloStatus
            chip.contentDescription = rotuloStatus
            chip.visibility = View.VISIBLE

            val tvRep = item.findViewById<TextView>(R.id.tvReputacaoMotorista)
            tvRep.text = "${getString(R.string.papel_motorista)} · ${getString(R.string.reputacao_sem_avaliacoes)}"
            tvRep.visibility = View.VISIBLE
            if (carona.motoristaUid.isNotBlank()) {
                viewsReputacaoPorMotorista
                    .getOrPut(carona.motoristaUid) { mutableListOf() }
                    .add(tvRep)
            }

            item.setOnClickListener {
                val intent = Intent(
                    this,
                    DetalheCaronaActivity::class.java
                )

                intent.putExtra(
                    DetalheCaronaActivity.EXTRA_CARONA_FIRESTORE_ID,
                    carona.id
                )

                startActivity(intent)
            }

            binding.listaCaronas.addView(item)
        }

        val uids = viewsReputacaoPorMotorista.keys.toList()
        if (uids.isNotEmpty()) {
            lifecycleScope.launch {
                caronasBackendRepository.reputacaoConsultar(uids).onSuccess { mapa ->
                    mapa.forEach { (uid, rep) ->
                        val textoRep = ReputacaoTexto.formatarComCnh(
                            media = rep.media,
                            quantidade = rep.quantidade,
                            cnhValidada = rep.cnhValidada,
                            modelos = modelosRep
                        )
                        viewsReputacaoPorMotorista[uid]?.forEach { view ->
                            view.text = "${getString(R.string.papel_motorista)} · $textoRep"
                        }
                    }
                }
            }
        }
    }
}

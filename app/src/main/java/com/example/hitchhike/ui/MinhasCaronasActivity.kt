package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronaEntity
import com.example.hitchhike.data.CaronaRepository
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.databinding.ActivityMinhasCaronasBinding
import com.example.hitchhike.util.DateTimeFormat
import kotlinx.coroutines.launch

class MinhasCaronasActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMinhasCaronasBinding
    private lateinit var session: SessionManager
    private lateinit var caronaRepository: CaronaRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMinhasCaronasBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootMinhas.applySystemInsets()

        session = SessionManager(this)
        caronaRepository = CaronaRepository(this)
        binding.btnVoltar.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        carregar()
    }

    private fun carregar() {
        lifecycleScope.launch {
            val usuarioId = session.usuarioId()
            val oferecidas = caronaRepository.listarMinhasCaronas(usuarioId)
            binding.listaOferecidas.removeAllViews()
            binding.tvVazioOferecidas.visibility = if (oferecidas.isEmpty()) View.VISIBLE else View.GONE
            oferecidas.forEach { adicionarCard(it, binding.listaOferecidas) }

            val pedidos = caronaRepository.listarMinhasSolicitacoes(usuarioId)
            binding.listaSolicitacoes.removeAllViews()
            binding.tvVazioSolicitacoes.visibility = if (pedidos.isEmpty()) View.VISIBLE else View.GONE
            pedidos.forEach { pedido ->
                val carona = caronaRepository.buscarPorId(pedido.caronaId) ?: return@forEach
                adicionarCard(carona, binding.listaSolicitacoes, pedido.status)
            }
        }
    }

    private fun adicionarCard(carona: CaronaEntity, container: android.widget.LinearLayout, extra: String? = null) {
        val item = LayoutInflater.from(this).inflate(R.layout.item_carona, container, false)
        item.findViewById<TextView>(R.id.tvTrajeto).text = "${carona.origem} → ${carona.destino}"
        item.findViewById<TextView>(R.id.tvHorario).text = DateTimeFormat.dataHora(carona.dataHoraSaida)
        item.findViewById<TextView>(R.id.tvDetalhe).text = extra ?: carona.status
        item.setOnClickListener {
            startActivity(
                Intent(this, DetalheCaronaActivity::class.java)
                    .putExtra(DetalheCaronaActivity.EXTRA_CARONA_ID, carona.id)
            )
        }
        container.addView(item)
    }
}

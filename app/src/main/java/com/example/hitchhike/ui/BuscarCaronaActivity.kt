package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronaEntity
import com.example.hitchhike.data.CaronaRepository
import com.example.hitchhike.databinding.ActivityBuscarCaronaBinding
import com.example.hitchhike.util.DateTimeFormat
import kotlinx.coroutines.launch

class BuscarCaronaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBuscarCaronaBinding
    private lateinit var caronaRepository: CaronaRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBuscarCaronaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootBuscar.applySystemInsets()

        caronaRepository = CaronaRepository(this)
        val origens = resources.getStringArray(R.array.origens_campus)
        binding.etOrigem.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, origens))

        binding.btnVoltar.setOnClickListener { finish() }
        binding.btnBuscar.setOnClickListener { buscar() }
        buscar()
    }

    override fun onResume() {
        super.onResume()
        buscar()
    }

    private fun buscar() {
        lifecycleScope.launch {
            val origem = binding.etOrigem.text?.toString().orEmpty()
            val destino = binding.etDestino.text?.toString().orEmpty()
            mostrar(caronaRepository.buscar(origem, destino))
        }
    }

    private fun mostrar(caronas: List<CaronaEntity>) {
        binding.listaCaronas.removeAllViews()
        binding.tvVazio.visibility = if (caronas.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        caronas.forEach { carona ->
            val item = inflater.inflate(R.layout.item_carona, binding.listaCaronas, false)
            item.findViewById<TextView>(R.id.tvTrajeto).text = "${carona.origem} → ${carona.destino}"
            item.findViewById<TextView>(R.id.tvHorario).text = DateTimeFormat.dataHora(carona.dataHoraSaida)
            item.findViewById<TextView>(R.id.tvDetalhe).text = getString(
                R.string.carona_resumo,
                carona.vagasDisponiveis,
                DateTimeFormat.valor(carona.valor)
            )
            item.setOnClickListener {
                startActivity(
                    Intent(this, DetalheCaronaActivity::class.java)
                        .putExtra(DetalheCaronaActivity.EXTRA_CARONA_ID, carona.id)
                )
            }
            binding.listaCaronas.addView(item)
        }
    }
}

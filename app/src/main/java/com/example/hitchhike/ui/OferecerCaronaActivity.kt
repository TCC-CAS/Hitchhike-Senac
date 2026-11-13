package com.example.hitchhike.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronaRepository
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityOferecerCaronaBinding
import com.example.hitchhike.util.DateTimeFormat
import kotlinx.coroutines.launch
import java.util.Calendar
import com.example.hitchhike.data.CaronaFirestore
import com.example.hitchhike.data.CaronaFirestoreRepository
import com.example.hitchhike.data.NomeAbreviadoFirestoreRepository
import com.google.firebase.Timestamp

class OferecerCaronaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOferecerCaronaBinding
    private lateinit var session: SessionManager
    private lateinit var usuarioRepository: UsuarioRepository
    private lateinit var caronaRepository: CaronaRepository

    private val caronaFirestoreRepository = CaronaFirestoreRepository()
    private val nomesAbreviados = NomeAbreviadoFirestoreRepository()

    private val calendario = Calendar.getInstance().apply {
        add(Calendar.HOUR_OF_DAY, 1)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOferecerCaronaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootOferecer.applySystemInsets()

        session = SessionManager(this)
        usuarioRepository = UsuarioRepository(this)
        caronaRepository = CaronaRepository(this)

        val origens = resources.getStringArray(R.array.origens_campus)
        binding.etOrigem.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, origens))
        binding.etOrigem.threshold = 1
        atualizarDataHora()

        configurarBotaoVoltar(binding.btnVoltar)
        binding.btnDataHora.setOnClickListener { escolherDataHora() }
        binding.btnPublicar.setOnClickListener { publicar() }
    }

    private fun escolherDataHora() {
        DatePickerDialog(
            this,
            { _, ano, mes, dia ->
                calendario.set(Calendar.YEAR, ano)
                calendario.set(Calendar.MONTH, mes)
                calendario.set(Calendar.DAY_OF_MONTH, dia)
                TimePickerDialog(
                    this,
                    { _, hora, minuto ->
                        calendario.set(Calendar.HOUR_OF_DAY, hora)
                        calendario.set(Calendar.MINUTE, minuto)
                        atualizarDataHora()
                    },
                    calendario.get(Calendar.HOUR_OF_DAY),
                    calendario.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendario.get(Calendar.YEAR),
            calendario.get(Calendar.MONTH),
            calendario.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun atualizarDataHora() {
        binding.tvDataHora.text = getString(
            R.string.horario_selecionado,
            DateTimeFormat.dataHora(calendario.timeInMillis)
        )
    }


    private fun publicar() {
        val origem = binding.etOrigem.text?.toString().orEmpty().trim()
        val destino = binding.etDestino.text?.toString().orEmpty().trim()
        val vagas = binding.etVagas.text?.toString()?.toIntOrNull() ?: 0
        val valorTexto = binding.etValor.text?.toString()
            ?.replace(",", ".")
            ?.trim()
            .orEmpty()

        val valor = valorTexto.toDoubleOrNull()

        if (origem.isBlank() || destino.isBlank()) {
            Toast.makeText(
                this,
                "Preencha a origem e o destino.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (vagas !in 1..4) {
            Toast.makeText(
                this,
                "Informe de 1 a 4 vagas.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (valor == null || !valor.isFinite() || valor < 0) {
            Toast.makeText(
                this,
                "Informe um valor válido.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dataHora = calendario.timeInMillis

        if (dataHora <= System.currentTimeMillis()) {
            Toast.makeText(
                this,
                "Escolha uma data e horário futuros.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        lifecycleScope.launch {
            val motorista = usuarioRepository.buscar(session.usuarioId())

            if (motorista == null || !motorista.condutorValidado ||
                !motorista.isMotoristaAtivo()
            ) {
                Toast.makeText(
                    this@OferecerCaronaActivity,
                    "É necessário ser um motorista aprovado.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            binding.btnPublicar.isEnabled = false

            val carona = CaronaFirestore(
                origem = origem,
                destino = destino,
                dataHoraSaida = Timestamp(
                    java.util.Date(dataHora)
                ),
                vagasTotais = vagas,
                vagasDisponiveis = vagas,
                valor = valor
            )

            caronaFirestoreRepository.publicar(
                carona = carona,

                onSuccess = { caronaId ->
                    nomesAbreviados.garantirProprio(caronaId)
                    lifecycleScope.launch {
                        try {
                            val resultado = caronaRepository.publicar(
                                motorista,
                                origem,
                                destino,
                                dataHora,
                                vagas,
                                valor
                            )

                            if (resultado.isSuccess) {
                                Toast.makeText(
                                    this@OferecerCaronaActivity,
                                    "Carona publicada no Firebase!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@OferecerCaronaActivity,
                                    "Carona publicada online, mas não foi salva no Room.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            finish()
                        } catch (e: Exception) {
                            Toast.makeText(
                                this@OferecerCaronaActivity,
                                "Carona publicada online, mas houve erro ao salvar localmente.",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                    }
                },

                onError = { erro ->
                    binding.btnPublicar.isEnabled = true

                    Toast.makeText(
                        this@OferecerCaronaActivity,
                        "Erro ao publicar no Firebase: $erro",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

}

package com.example.hitchhike.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronaRepository
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityOferecerCaronaBinding
import com.example.hitchhike.util.DateTimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
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

    private var buscaLocalizacaoId = 0
    private var textoOrigemAoIniciarBusca: String = ""
    private var timeoutJob: Job? = null
    private var activeLocationListener: LocationListener? = null

    companion object {
        private const val TAG = "Hitchhike_GPS"
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val concedida = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (concedida) {
            Log.d(TAG, "Permissões de localização concedidas pelo usuário")
            obterLocalizacaoAtual()
        } else {
            Log.e(TAG, "Permissões de localização negadas pelo usuário")
            Toast.makeText(
                this,
                getString(R.string.localizacao_erro_permissao),
                Toast.LENGTH_LONG
            ).show()
        }
    }

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

        val destinosSugeridos = listOf(getString(R.string.destino_padrao)) + origens
        binding.etDestino.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, destinosSugeridos))
        binding.etDestino.threshold = 1

        atualizarDataHora()

        configurarBotaoVoltar(binding.btnVoltar)
        binding.btnUsarLocalizacao.setOnClickListener { solicitarLocalizacao() }
        binding.btnDataHora.setOnClickListener { escolherDataHora() }
        binding.btnPublicar.setOnClickListener { publicar() }
        binding.btnAddParada.setOnClickListener {
            if (binding.containerParadas.childCount >= 3) {
                Toast.makeText(this, "Limite de 3 paradas atingido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Cria a linha que conterá o TextInputLayout e o botão remover
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val til = com.google.android.material.textfield.TextInputLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                hint = "Parada"
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                setBoxCornerRadii(8f, 8f, 8f, 8f)
            }
            val edit = com.google.android.material.textfield.TextInputEditText(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                maxLines = 1
                maxEms = 150
                minHeight = resources.getDimensionPixelSize(R.dimen.hh_button_min)
            }
            til.addView(edit)
            val btnRemove = com.google.android.material.button.MaterialButton(this).apply {
                text = "✕"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { binding.containerParadas.removeView(row) }
            }
            row.addView(til)
            row.addView(btnRemove)
            binding.containerParadas.addView(row)
        }


    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutJob?.cancel()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        activeLocationListener?.let {
            try {
                locationManager?.removeUpdates(it)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover ouvinte de localização em onDestroy: ${e.message}")
            }
        }
    }

    private fun solicitarLocalizacao() {
        val finePerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarsePerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Verificando permissões: FINE=$finePerm, COARSE=$coarsePerm")

        if (finePerm || coarsePerm) {
            obterLocalizacaoAtual()
        } else {
            Log.d(TAG, "Solicitando permissões de localização ao usuário")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun obterLocalizacaoAtual() {
        val requisicaoAtual = ++buscaLocalizacaoId
        textoOrigemAoIniciarBusca = binding.etOrigem.text?.toString().orEmpty().trim()
        Log.d(TAG, "Iniciando fluxo de localização (requisicaoId=$requisicaoAtual)")

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            Log.e(TAG, "LocationManager indisponível no sistema")
            Toast.makeText(
                this,
                getString(R.string.localizacao_erro_gps_desativado),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val finePerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarsePerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val gpsHabilitado = finePerm && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val redeHabilitada = (finePerm || coarsePerm) && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "Provedores utilizáveis: GPS=$gpsHabilitado, Rede=$redeHabilitada")

        if (!gpsHabilitado && !redeHabilitada) {
            Log.e(TAG, "Nenhum provedor de localização disponível ou habilitado")
            Toast.makeText(
                this,
                getString(R.string.localizacao_erro_gps_desativado),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        binding.btnUsarLocalizacao.isEnabled = false
        Toast.makeText(this, getString(R.string.localizacao_obtendo), Toast.LENGTH_SHORT).show()

        // 1. Procurar melhor última localização conhecida entre todos os provedores permitidos
        val candidatos = mutableListOf<Location>()
        try {
            if (gpsHabilitado) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { candidatos.add(it) }
            }
            if (redeHabilitada) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { candidatos.add(it) }
            }
            if (finePerm && locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { candidatos.add(it) }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException ao obter última localização: ${e.message}")
        }

        val melhorUltimaLocalizacao = candidatos.maxByOrNull { it.time }
        val agora = System.currentTimeMillis()

        if (melhorUltimaLocalizacao != null) {
            val idadeSegundos = (agora - melhorUltimaLocalizacao.time) / 1000
            Log.d(
                TAG,
                "Última localização conhecida: provedor=${melhorUltimaLocalizacao.provider}, " +
                        "idade=${idadeSegundos}s, acurácia=${melhorUltimaLocalizacao.accuracy}m"
            )
            // Se tiver menos de 180s (3 minutos) e boa precisão (<= 150m), usamos imediatamente
            if (idadeSegundos < 180 && melhorUltimaLocalizacao.accuracy <= 150f) {
                Log.d(TAG, "Última localização considerada recente e precisa. Processando...")
                processarCoordenadas(
                    melhorUltimaLocalizacao.latitude,
                    melhorUltimaLocalizacao.longitude,
                    requisicaoAtual
                )
                return
            }
        } else {
            Log.d(TAG, "Nenhuma última localização conhecida disponível em cache.")
        }

        // 2. Requisitar atualização ativa com tempo limite
        Log.d(TAG, "Solicitando nova localização ativa aos provedores...")
        requisitarAtualizacaoAtiva(
            locationManager = locationManager,
            gpsAtivo = gpsHabilitado,
            redeAtiva = redeHabilitada,
            fallbackLocation = melhorUltimaLocalizacao,
            requisicaoId = requisicaoAtual
        )
    }

    private fun requisitarAtualizacaoAtiva(
        locationManager: LocationManager,
        gpsAtivo: Boolean,
        redeAtiva: Boolean,
        fallbackLocation: Location?,
        requisicaoId: Int
    ) {
        timeoutJob?.cancel()
        activeLocationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao remover ouvinte anterior: ${e.message}")
            }
        }

        var localizacaoRecebida = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (localizacaoRecebida || requisicaoId != buscaLocalizacaoId) return
                localizacaoRecebida = true
                Log.d(TAG, "Nova localização obtida com sucesso via ${location.provider} (acurácia: ${location.accuracy}m)")

                timeoutJob?.cancel()
                try {
                    locationManager.removeUpdates(this)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao desregistrar ouvinte: ${e.message}")
                }
                activeLocationListener = null

                processarCoordenadas(location.latitude, location.longitude, requisicaoId)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "Provedor habilitado durante a busca: $provider")
            }
            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "Provedor desabilitado durante a busca: $provider")
            }
        }

        activeLocationListener = listener

        try {
            if (redeAtiva) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    500L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Ouvinte registrado para NETWORK_PROVIDER")
            }
            if (gpsAtivo) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                Log.d(TAG, "Ouvinte registrado para GPS_PROVIDER")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException ao registrar requestLocationUpdates: ${e.message}")
            binding.btnUsarLocalizacao.isEnabled = true
            Toast.makeText(
                this,
                getString(R.string.localizacao_erro_permissao),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Tempo limite de 10 segundos
        timeoutJob = lifecycleScope.launch {
            delay(10_000L)
            if (!localizacaoRecebida && requisicaoId == buscaLocalizacaoId) {
                Log.d(TAG, "Tempo limite de 10s atingido para busca ativa")
                try {
                    locationManager.removeUpdates(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao remover ouvinte após timeout: ${e.message}")
                }
                activeLocationListener = null

                if (fallbackLocation != null) {
                    Log.d(TAG, "Utilizando fallback da última localização conhecida após timeout")
                    processarCoordenadas(
                        fallbackLocation.latitude,
                        fallbackLocation.longitude,
                        requisicaoId
                    )
                } else {
                    Log.e(TAG, "Nenhuma localização obtida após timeout e sem fallback")
                    binding.btnUsarLocalizacao.isEnabled = true
                    Toast.makeText(
                        this@OferecerCaronaActivity,
                        getString(R.string.localizacao_erro_endereco),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun processarCoordenadas(latitude: Double, longitude: Double, requisicaoId: Int) {
        lifecycleScope.launch {
            try {
                if (requisicaoId != buscaLocalizacaoId) {
                    Log.d(TAG, "Descartando coordenadas da requisição antiga $requisicaoId (atual=$buscaLocalizacaoId)")
                    return@launch
                }

                Log.d(TAG, "Iniciando geocodificação reversa...")
                val enderecoFormatado = withContext(Dispatchers.IO) {
                    converterParaEndereco(latitude, longitude)
                }

                if (requisicaoId != buscaLocalizacaoId) {
                    Log.d(TAG, "Descartando endereço pois nova busca foi iniciada")
                    return@launch
                }

                // Proteção: não sobrescrever se o usuário digitou manualmente durante a busca
                val textoAtual = binding.etOrigem.text?.toString().orEmpty().trim()
                if (textoAtual.isNotEmpty() && textoAtual != textoOrigemAoIniciarBusca) {
                    Log.d(TAG, "Origem mantida: usuário digitou manualmente durante a busca")
                    Toast.makeText(
                        this@OferecerCaronaActivity,
                        "Localização obtida, mas a origem foi mantida conforme sua digitação.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                if (!enderecoFormatado.isNullOrBlank()) {
                    binding.etOrigem.setText(enderecoFormatado, false)
                    Log.d(TAG, "Campo de origem preenchido na interface com sucesso")
                    Toast.makeText(
                        this@OferecerCaronaActivity,
                        getString(R.string.localizacao_sucesso),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(TAG, "Geocodificação não conseguiu determinar um endereço legível")
                    Toast.makeText(
                        this@OferecerCaronaActivity,
                        getString(R.string.localizacao_erro_endereco),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao processar coordenadas: ${e.message}")
                Toast.makeText(
                    this@OferecerCaronaActivity,
                    getString(R.string.localizacao_erro_endereco),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                if (requisicaoId == buscaLocalizacaoId) {
                    binding.btnUsarLocalizacao.isEnabled = true
                }
            }
        }
    }

    private fun converterParaEndereco(latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.e(TAG, "Serviço de Geocoder não disponível neste aparelho")
            return null
        }

        val geocoder = Geocoder(this, Locale("pt", "BR"))
        val enderecos = try {
            geocoder.getFromLocation(latitude, longitude, 3)
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao chamar Geocoder.getFromLocation: ${e.message}")
            null
        }

        if (enderecos.isNullOrEmpty()) {
            Log.e(TAG, "Geocoder retornou lista nula ou vazia de endereços")
            return null
        }

        Log.d(TAG, "Geocoder retornou ${enderecos.size} resultado(s)")

        val melhorEndereco = enderecos.firstOrNull { !it.thoroughfare.isNullOrBlank() }
            ?: enderecos.first()

        val rua = melhorEndereco.thoroughfare
        val numero = melhorEndereco.subThoroughfare
        val bairro = melhorEndereco.subLocality
        val cidade = melhorEndereco.locality

        val formatado = when {
            !rua.isNullOrBlank() && !bairro.isNullOrBlank() -> {
                val parteRua = if (!numero.isNullOrBlank()) "$rua, $numero" else rua
                "$parteRua - $bairro"
            }
            !rua.isNullOrBlank() -> {
                if (!numero.isNullOrBlank()) "$rua, $numero" else rua
            }
            !bairro.isNullOrBlank() -> {
                if (!cidade.isNullOrBlank()) "$bairro, $cidade" else bairro
            }
            else -> {
                val linha0 = melhorEndereco.getAddressLine(0)
                if (!linha0.isNullOrBlank()) {
                    linha0.split(", Brasil").firstOrNull()?.trim() ?: linha0
                } else null
            }
        }

        return formatado
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

            // Coleta das paradas informadas
            val paradasList = mutableListOf<String>()
            for (i in 0 until binding.containerParadas.childCount) {
                val row = binding.containerParadas.getChildAt(i) as android.widget.LinearLayout
                val til = row.getChildAt(0) as com.google.android.material.textfield.TextInputLayout
                val texto = til.editText?.text?.toString()?.trim()
                if (!texto.isNullOrEmpty()) {
                    paradasList.add(texto)
                }
            }

            val carona = CaronaFirestore(
                origem = origem,
                destino = destino,
                dataHoraSaida = Timestamp(
                    java.util.Date(dataHora)
                ),
                vagasTotais = vagas,
                vagasDisponiveis = vagas,
                valor = valor,
                paradas = if (paradasList.isEmpty()) null else paradasList
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

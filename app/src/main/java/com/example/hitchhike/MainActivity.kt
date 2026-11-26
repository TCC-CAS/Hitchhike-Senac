package com.example.hitchhike

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.SincronizacaoCondutorValidado
import com.example.hitchhike.data.UsuarioFirestoreRepository
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityMainBinding
import com.example.hitchhike.ui.BuscarCaronaActivity
import com.example.hitchhike.ui.LoginActivity
import com.example.hitchhike.ui.MinhasCaronasActivity
import com.example.hitchhike.ui.OferecerCaronaActivity
import com.example.hitchhike.ui.PerfilActivity
import com.example.hitchhike.ui.VerificacaoMotoristaActivity
import com.example.hitchhike.ui.applySystemInsets
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var session: SessionManager
    private lateinit var repository: UsuarioRepository
    private lateinit var sincronizacaoCondutor: SincronizacaoCondutorValidado

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = SessionManager(this)
        if (!session.estaLogado()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootHome.applySystemInsets()
        repository = UsuarioRepository(this)
        sincronizacaoCondutor = SincronizacaoCondutorValidado(
            firestore = UsuarioFirestoreRepository(),
            usuarios = repository,
            session = session
        )

        binding.cardBuscar.setOnClickListener {
            startActivity(Intent(this, BuscarCaronaActivity::class.java))
        }
        binding.cardOferecer.setOnClickListener { oferecer() }
        binding.cardMinhas.setOnClickListener {
            startActivity(Intent(this, MinhasCaronasActivity::class.java))
        }
        binding.btnPerfil.setOnClickListener {
            startActivity(Intent(this, PerfilActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!session.estaLogado()) return
        atualizarSaudacao()
        sincronizacaoCondutor.executar(lifecycleScope)
    }

    private fun atualizarSaudacao() {
        lifecycleScope.launch {
            val usuario = repository.buscar(session.usuarioId()) ?: return@launch
            binding.tvSaudacao.text = getString(R.string.home_saudacao, usuario.nome)
        }
    }

    private fun oferecer() {
        lifecycleScope.launch {
            val usuario = repository.buscar(session.usuarioId()) ?: return@launch
            val elegibilidade = usuario.elegibilidadeMotorista()
            when {
                elegibilidade.apto -> {
                    startActivity(Intent(this@MainActivity, OferecerCaronaActivity::class.java))
                }
                elegibilidade.estado == com.example.hitchhike.data.EstadoMotorista.PAPEL_PASSAGEIRO -> {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        com.example.hitchhike.data.ElegibilidadeMotorista.mensagemBloqueio(
                            elegibilidade.estado,
                            elegibilidade.motivoRecusa
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(this@MainActivity, PerfilActivity::class.java))
                }
                else -> {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        com.example.hitchhike.data.ElegibilidadeMotorista.mensagemBloqueio(
                            elegibilidade.estado,
                            elegibilidade.motivoRecusa
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(this@MainActivity, VerificacaoMotoristaActivity::class.java))
                }
            }
        }
    }
}

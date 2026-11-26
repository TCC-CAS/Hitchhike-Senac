package com.example.hitchhike.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.hitchhike.MainActivity
import com.example.hitchhike.R
import com.example.hitchhike.data.CnhBackendRepository
import com.example.hitchhike.data.DadosSolicitacaoMotorista
import com.example.hitchhike.data.ElegibilidadeMotorista
import com.example.hitchhike.data.EstadoMotorista
import com.example.hitchhike.data.SessionManager
import com.example.hitchhike.data.SolicitacaoMotoristaFirestoreRepository
import com.example.hitchhike.data.StatusCnhRemoto
import com.example.hitchhike.data.UsuarioEntity
import com.example.hitchhike.data.UsuarioRepository
import com.example.hitchhike.databinding.ActivityVerificacaoMotoristaBinding
import com.example.hitchhike.util.PdfCnhSelecionado
import com.example.hitchhike.util.ValidadorPdfCnh
import kotlinx.coroutines.launch

class VerificacaoMotoristaActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVerificacaoMotoristaBinding
    private lateinit var repository: UsuarioRepository
    private lateinit var session: SessionManager
    private lateinit var solicitacaoRepository: SolicitacaoMotoristaFirestoreRepository
    private val cnhBackendRepository = CnhBackendRepository()

    private var pdfSelecionado: PdfCnhSelecionado? = null
    private var envioEmAndamento = false

    private val selecionarPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || envioEmAndamento) return@registerForActivityResult
        validarPdfSelecionado(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVerificacaoMotoristaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.rootVerificacao.applySystemInsets()
        configurarBotaoVoltar(binding.btnVoltar)

        repository = UsuarioRepository(this)
        session = SessionManager(this)
        solicitacaoRepository = SolicitacaoMotoristaFirestoreRepository()

        lifecycleScope.launch {
            val usuario = repository.buscar(session.usuarioId()) ?: return@launch
            binding.etCnh.setText(usuario.cnhNumero.orEmpty())
            binding.etPlaca.setText(usuario.veiculoPlaca.orEmpty())
            binding.etModelo.setText(usuario.veiculoModelo.orEmpty())
            binding.etCor.setText(usuario.veiculoCor.orEmpty())
            exibirEstadoLocalInicial(usuario)
        }

        binding.btnAnexarCnh.setOnClickListener {
            if (!envioEmAndamento) {
                selecionarPdfLauncher.launch(arrayOf("application/pdf"))
            }
        }
        binding.btnAtualizarStatusCnh.setOnClickListener {
            if (!envioEmAndamento) {
                carregarStatusServidor()
            }
        }
        binding.btnSalvar.setOnClickListener { salvar() }
    }

    override fun onResume() {
        super.onResume()
        if (!envioEmAndamento) {
            carregarStatusServidor()
        }
    }

    private fun validarPdfSelecionado(uri: android.net.Uri) {
        binding.btnAnexarCnh.isEnabled = false
        binding.tvCnhStatus.visibility = View.VISIBLE
        binding.tvCnhStatus.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
        binding.tvCnhStatus.text = "Validando arquivo PDF selecionado…"

        lifecycleScope.launch {
            val resultado = ValidadorPdfCnh.lerEValidar(this@VerificacaoMotoristaActivity, uri)
            binding.btnAnexarCnh.isEnabled = !envioEmAndamento

            resultado.fold(
                onSuccess = { pdf ->
                    pdfSelecionado = pdf
                    val tamanhoKb = (pdf.tamanhoBytes + 1023) / 1024
                    binding.tvCnhStatus.setTextColor(ContextCompat.getColor(this@VerificacaoMotoristaActivity, R.color.hh_green))
                    binding.tvCnhStatus.text = getString(R.string.cnh_anexada, pdf.nomeArquivo, tamanhoKb)
                    binding.btnAnexarCnh.text = getString(R.string.action_substituir_pdf_cnh)
                },
                onFailure = { erro ->
                    pdfSelecionado = null
                    binding.tvCnhStatus.setTextColor(ContextCompat.getColor(this@VerificacaoMotoristaActivity, R.color.hh_error))
                    binding.tvCnhStatus.text = erro.message ?: getString(R.string.erro_cnh)
                    binding.btnAnexarCnh.text = getString(R.string.action_selecionar_pdf_cnh)
                    Toast.makeText(
                        this@VerificacaoMotoristaActivity,
                        erro.message ?: getString(R.string.erro_cnh),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun carregarStatusServidor() {
        binding.btnAtualizarStatusCnh.isEnabled = false
        binding.tvEstadoSolicitacaoTitulo.text = getString(R.string.cnh_status_carregando)

        // Preenche dados do veículo da solicitação existente caso o Room local esteja vazio
        solicitacaoRepository.consultarDetalhes(
            onSuccess = { pedido ->
                if (pedido != null) {
                    preencherVeiculoSeVazio(pedido)
                }
            },
            onError = {}
        )

        lifecycleScope.launch {
            val resultado = cnhBackendRepository.consultarStatus()
            binding.btnAtualizarStatusCnh.isEnabled = !envioEmAndamento

            resultado.fold(
                onSuccess = { statusRemoto ->
                    sincronizarStatusRemotoNoRoom(statusRemoto)
                    exibirStatusRemoto(statusRemoto)
                },
                onFailure = { erro ->
                    // Fallback: se o backend estiver offline, mostra o estado calculado pelo Firestore/Room
                    solicitacaoRepository.consultarDetalhes(
                        onSuccess = { pedido ->
                            lifecycleScope.launch {
                                val usuario = repository.buscar(session.usuarioId())
                                exibirEstadoComFallback(usuario, pedido, erro.message)
                            }
                        },
                        onError = {
                            lifecycleScope.launch {
                                val usuario = repository.buscar(session.usuarioId())
                                exibirEstadoComFallback(usuario, null, erro.message)
                            }
                        }
                    )
                }
            )
        }
    }

    private fun preencherVeiculoSeVazio(pedido: DadosSolicitacaoMotorista) {
        if (binding.etPlaca.text.isNullOrBlank() && pedido.placa.isNotBlank()) {
            binding.etPlaca.setText(pedido.placa)
        }
        if (binding.etModelo.text.isNullOrBlank() && pedido.modelo.isNotBlank()) {
            binding.etModelo.setText(pedido.modelo)
        }
        if (binding.etCor.text.isNullOrBlank() && pedido.cor.isNotBlank()) {
            binding.etCor.setText(pedido.cor)
        }
        lifecycleScope.launch {
            repository.sincronizarVeiculoLocal(
                usuarioId = session.usuarioId(),
                placa = pedido.placa,
                modelo = pedido.modelo,
                cor = pedido.cor,
                cnhAnexada = pedido.cnhPdfRecebido
            )
        }
    }

    private suspend fun sincronizarStatusRemotoNoRoom(status: StatusCnhRemoto) {
        repository.sincronizarValidacaoMotorista(
            usuarioId = session.usuarioId(),
            validado = status.condutorValidado,
            cnhValidade = status.cnhValidade,
            cnhRevisaoNecessaria = if (status.solicitacaoStatus == "RECUSADA") false else status.revisaoNecessaria,
            papelAtivo = status.papelAtivo,
            solicitacaoStatus = status.solicitacaoStatus,
            motivoRecusa = status.motivoRecusa,
            atualizarSolicitacao = true
        )
    }

    private fun exibirEstadoLocalInicial(usuario: UsuarioEntity) {
        val elegibilidade = usuario.elegibilidadeMotorista(exigirPapelMotorista = false)
        when (elegibilidade.estado) {
            EstadoMotorista.SOLICITACAO_RECUSADA -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: SOLICITAÇÃO RECUSADA"
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoDetalhe.text =
                    ElegibilidadeMotorista.mensagemBloqueio(EstadoMotorista.SOLICITACAO_RECUSADA, elegibilidade.motivoRecusa)
                binding.btnSalvar.text = getString(R.string.action_enviar_novo_pdf_cnh)
            }
            EstadoMotorista.APTO -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_green))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: APROVADO"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "CNH conferida e válida até ${formatarDataBr(elegibilidade.cnhValidade)}. Enviar um novo PDF substituirá o documento atual e exigirá nova aprovação."
                binding.btnSalvar.text = getString(R.string.action_reenviar_cnh_condutor)
            }
            EstadoMotorista.CNH_VENCIDA -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: CNH VENCIDA"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Sua CNH venceu em ${formatarDataBr(elegibilidade.cnhValidade)}. Envie o PDF atualizado da CNH renovada para análise."
                binding.btnSalvar.text = getString(R.string.action_enviar_novo_pdf_cnh)
            }
            EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_warning))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: REVISÃO NECESSÁRIA (regularização de CNH)"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    ElegibilidadeMotorista.mensagemBloqueio(EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA)
                binding.btnSalvar.text = getString(R.string.action_enviar_novo_pdf_cnh)
            }
            EstadoMotorista.REVISAO_PENDENTE -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_warning))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: REVISÃO NECESSÁRIA / PENDENTE"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Seus dados aguardam conferência administrativa. Se necessário, você pode substituir o PDF abaixo."
                binding.btnSalvar.text = getString(R.string.action_reenviar_cnh_condutor)
            }
            else -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_text))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: Aguardando envio do PDF da CNH"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Selecione o PDF original da CNH Digital (1 página, até 5 MB) e preencha os dados do veículo."
                binding.btnSalvar.text = getString(R.string.action_enviar_cnh_condutor)
            }
        }
    }

    private fun exibirStatusRemoto(status: StatusCnhRemoto) {
        val elegibilidade = ElegibilidadeMotorista.avaliar(
            status = "ATIVO",
            condutorValidado = status.condutorValidado,
            papelAtivo = status.papelAtivo,
            cnhValidade = status.cnhValidade,
            cnhRevisaoNecessaria = status.revisaoNecessaria,
            exigirPapelMotorista = false,
            solicitacaoStatus = status.solicitacaoStatus,
            motivoRecusa = status.motivoRecusa
        )

        val infoDoc = if (status.documentoRecebido && status.arquivoPresente) {
            "PDF versão ${status.versaoDocumento ?: 1} armazenado com criptografia no servidor."
        } else if (status.documentoRecebido && !status.arquivoPresente) {
            "O PDF anterior foi excluído. Envie um novo PDF quando precisar de nova conferência."
        } else {
            "Nenhum PDF armazenado no servidor."
        }

        when {
            elegibilidade.estado == EstadoMotorista.SOLICITACAO_RECUSADA || status.solicitacaoStatus == "RECUSADA" -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: SOLICITAÇÃO RECUSADA"
                val msgMotivo = status.motivoRecusa?.takeIf { it.isNotBlank() }
                val textoRecusa = if (msgMotivo != null) {
                    "Sua solicitação de motorista foi recusada pela administração.\nMotivo informado: $msgMotivo\nVerifique os dados do veículo e envie um novo PDF original da CNH Digital com QR Code legível."
                } else {
                    "Sua solicitação de motorista foi recusada pela administração. Verifique os dados do veículo e envie um novo PDF original da CNH Digital com QR Code legível."
                }
                binding.tvEstadoSolicitacaoDetalhe.text = textoRecusa
                binding.btnSalvar.text = getString(R.string.action_enviar_novo_pdf_cnh)
            }

            status.conferenciaSituacao == "vencida" || elegibilidade.estado == EstadoMotorista.CNH_VENCIDA -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_error))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: VENCIDO"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "CNH vencida em ${formatarDataBr(status.cnhValidade)}. Envie o PDF atualizado para renovar sua habilitação no app. $infoDoc"
                binding.btnSalvar.text = getString(R.string.action_enviar_novo_pdf_cnh)
            }

            elegibilidade.estado == EstadoMotorista.REGULARIZACAO_CNH_NECESSARIA -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_warning))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: REVISÃO NECESSÁRIA (cadastro antigo sem CNH)"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Sua solicitação consta como aprovada anteriormente, mas ainda não possui conferência de CNH registrada. Envie o PDF original da CNH Digital para regularizar seu acesso."
                binding.btnSalvar.text = getString(R.string.action_enviar_novo_pdf_cnh)
            }

            elegibilidade.apto -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_green))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                val alertaBreve = if (elegibilidade.venceEmBreve) {
                    " (Atenção: vence em ${elegibilidade.diasRestantes} dia(s); você já pode enviar o PDF renovado)."
                } else ""
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: APROVADO"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Motorista aprovado com CNH válida até ${formatarDataBr(status.cnhValidade)}$alertaBreve. $infoDoc\nObservação: enviar um novo PDF retira a aprovação atual até nova conferência administrativa."
                binding.btnSalvar.text = getString(R.string.action_reenviar_cnh_condutor)
            }

            status.revisaoNecessaria || status.conferenciaSituacao == "revisao" -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_warning))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                val titulo = if (status.solicitacaoStatus == "PENDENTE" && status.conferenciaSituacao == "aguardando_conferencia") {
                    "Situação: PENDENTE (aguardando conferência)"
                } else {
                    "Situação: REVISÃO NECESSÁRIA"
                }
                binding.tvEstadoSolicitacaoTitulo.text = titulo
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Aguardando análise e conferência administrativa da CNH e do QR Code no Vio. $infoDoc"
                binding.btnSalvar.text = getString(R.string.action_reenviar_cnh_condutor)
            }

            status.solicitacaoStatus == "PENDENTE" -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_warning))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: PENDENTE"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Sua solicitação está pendente de análise administrativa. $infoDoc"
                binding.btnSalvar.text = getString(R.string.action_reenviar_cnh_condutor)
            }

            else -> {
                binding.tvEstadoSolicitacaoTitulo.setTextColor(ContextCompat.getColor(this, R.color.hh_text))
                binding.tvEstadoSolicitacaoDetalhe.setTextColor(ContextCompat.getColor(this, R.color.hh_text_secondary))
                binding.tvEstadoSolicitacaoTitulo.text = "Situação: Sem solicitação enviada"
                binding.tvEstadoSolicitacaoDetalhe.text =
                    "Selecione o PDF original da CNH Digital (1 página, até 5 MB) e envie seus dados para validação."
                binding.btnSalvar.text = getString(R.string.action_enviar_cnh_condutor)
            }
        }
    }

    private fun exibirEstadoComFallback(
        usuario: UsuarioEntity?,
        pedido: DadosSolicitacaoMotorista?,
        avisoServidor: String?
    ) {
        if (pedido != null && usuario != null) {
            lifecycleScope.launch {
                repository.sincronizarValidacaoMotorista(
                    usuarioId = usuario.id,
                    validado = usuario.condutorValidado,
                    cnhValidade = usuario.cnhValidade,
                    cnhRevisaoNecessaria = if (pedido.status == "RECUSADA") false else usuario.cnhRevisaoNecessaria,
                    status = usuario.status,
                    papelAtivo = usuario.papelAtivo,
                    solicitacaoStatus = pedido.status,
                    motivoRecusa = pedido.motivoRecusa,
                    atualizarSolicitacao = true
                )
                val atualizado = repository.buscar(usuario.id)
                if (atualizado != null) {
                    exibirEstadoLocalInicial(atualizado)
                }
            }
            if (pedido.status == "RECUSADA") return
        } else if (usuario != null) {
            exibirEstadoLocalInicial(usuario)
        }
        val complemento = buildString {
            if (pedido != null) {
                append("Solicitação no Firestore: ${pedido.status}.")
                if (pedido.status == "APROVADA" && usuario?.cnhValidade.isNullOrBlank()) {
                    append(" Cadastro aprovado antes da conferência de CNH: envie o PDF para regularizar.")
                }
            }
            if (!avisoServidor.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(avisoServidor)
            }
        }
        if (complemento.isNotBlank()) {
            binding.tvEstadoSolicitacaoDetalhe.text = complemento
        }
    }

    private fun formatarDataBr(dataIso: String?): String {
        if (dataIso.isNullOrBlank() || !dataIso.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
            return "data não informada"
        }
        val partes = dataIso.split("-")
        return "${partes[2]}/${partes[1]}/${partes[0]}"
    }

    private fun definirProgressoEnvio(ativo: Boolean) {
        envioEmAndamento = ativo
        binding.btnSalvar.isEnabled = !ativo
        binding.btnAnexarCnh.isEnabled = !ativo
        binding.btnAtualizarStatusCnh.isEnabled = !ativo
        binding.etCnh.isEnabled = !ativo
        binding.etPlaca.isEnabled = !ativo
        binding.etModelo.isEnabled = !ativo
        binding.etCor.isEnabled = !ativo
        binding.progressEnvioCnh.visibility = if (ativo) View.VISIBLE else View.GONE
        binding.tvProgressoCnh.visibility = if (ativo) View.VISIBLE else View.GONE
    }

    private fun salvar() {
        if (envioEmAndamento) return

        val cnh = binding.etCnh.text?.toString().orEmpty().trim()
        val placa = binding.etPlaca.text?.toString().orEmpty().trim().uppercase()
        val modelo = binding.etModelo.text?.toString().orEmpty().trim()
        val cor = binding.etCor.text?.toString().orEmpty().trim()
        val pdf = pdfSelecionado

        binding.tilPlaca.error = null
        binding.tilModelo.error = null
        binding.tilCor.error = null

        if (pdf == null) {
            Toast.makeText(
                this,
                "Selecione o PDF original da CNH Digital (1 página, até 5 MB) antes de enviar.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (placa.length !in 7..8 || !placa.matches(Regex("^[A-Z0-9-]+$"))) {
            binding.tilPlaca.error = "Informe uma placa válida (7 a 8 caracteres)."
            return
        }

        if (modelo.isBlank() || cor.isBlank()) {
            Toast.makeText(
                this,
                "Preencha o modelo e a cor do veículo.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (modelo.length > 100 || cor.length > 50) {
            Toast.makeText(
                this,
                "Os dados do veículo ultrapassam o limite permitido.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        definirProgressoEnvio(true)

        lifecycleScope.launch {
            val usuario = repository.buscar(session.usuarioId())
            if (usuario == null) {
                definirProgressoEnvio(false)
                Toast.makeText(
                    this@VerificacaoMotoristaActivity,
                    "Usuário não encontrado.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // Verifica duplicidade de placa no banco local antes do envio
            val validacaoLocal = repository.salvarCondutor(
                usuario = usuario,
                cnh = cnh,
                cnhAnexada = true,
                placa = placa,
                modelo = modelo,
                cor = cor
            )

            if (validacaoLocal.isFailure) {
                definirProgressoEnvio(false)
                Toast.makeText(
                    this@VerificacaoMotoristaActivity,
                    getString(R.string.erro_placa_duplicada),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // Envia o PDF autenticado e os dados do veículo para POST /api/motoristas/cnh
            val resposta = cnhBackendRepository.enviarCnh(
                pdfDataUrlBase64 = pdf.dataUrlBase64,
                placa = placa,
                modelo = modelo,
                cor = cor
            )

            definirProgressoEnvio(false)

            resposta.fold(
                onSuccess = { envio ->
                    val msg = if (envio.substituiu) {
                        "PDF da CNH atualizado (versão ${envio.versao})! Aguarde a nova conferência administrativa."
                    } else {
                        "PDF da CNH enviado com sucesso! Aguarde a conferência e aprovação."
                    }
                    Toast.makeText(
                        this@VerificacaoMotoristaActivity,
                        msg,
                        Toast.LENGTH_LONG
                    ).show()

                    startActivity(
                        Intent(
                            this@VerificacaoMotoristaActivity,
                            MainActivity::class.java
                        ).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )
                    finish()
                },
                onFailure = { erro ->
                    Toast.makeText(
                        this@VerificacaoMotoristaActivity,
                        erro.message ?: "Não foi possível enviar o PDF da CNH.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}

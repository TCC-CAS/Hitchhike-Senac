package com.example.hitchhike.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.hitchhike.R
import com.example.hitchhike.data.AvaliacaoFirestoreRepository
import com.example.hitchhike.data.CaronasBackendRepository
import com.example.hitchhike.data.Participante
import com.example.hitchhike.data.ReputacaoTexto
import com.example.hitchhike.data.ResumoViagens
import com.example.hitchhike.data.Situacao
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Diálogo de participantes elegíveis da viagem concluída e confirmada (docs/CARONAS_API.md).
 * Exibe somente o primeiro nome acompanhado da inicial do último sobrenome ("Pedro A."),
 * papel, reputação e botão de avaliação (1 a 5 estrelas, às cegas).
 */
object ParticipantesDialog {

    fun abrir(
        context: Context,
        scope: CoroutineScope,
        backend: CaronasBackendRepository,
        avaliacaoRepository: AvaliacaoFirestoreRepository,
        caronaId: String,
        aoAtualizar: () -> Unit = {}
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_participantes, null)
        val container = view.findViewById<LinearLayout>(R.id.listaParticipantes)
        val tvVazio = view.findViewById<TextView>(R.id.tvParticipantesVazio)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.participantes_titulo)
            .setView(view)
            .setPositiveButton(R.string.action_voltar_dialogo, null)
            .create()

        fun carregarLista() {
            scope.launch {
                backend.participantes(caronaId).fold(
                    onSuccess = { lista ->
                        preencher(
                            context = context,
                            container = container,
                            tvVazio = tvVazio,
                            participantes = lista,
                            caronaId = caronaId,
                            avaliacaoRepository = avaliacaoRepository,
                            aoAvaliar = {
                                carregarLista()
                                aoAtualizar()
                            }
                        )
                    },
                    onFailure = { erro ->
                        Toast.makeText(
                            context,
                            CaronasUiHelper.mensagemErro(context, erro),
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.show()
        carregarLista()
    }

    private fun preencher(
        context: Context,
        container: LinearLayout,
        tvVazio: TextView,
        participantes: List<Participante>,
        caronaId: String,
        avaliacaoRepository: AvaliacaoFirestoreRepository,
        aoAvaliar: () -> Unit
    ) {
        container.removeAllViews()
        if (participantes.isEmpty()) {
            tvVazio.visibility = View.VISIBLE
            return
        }
        tvVazio.visibility = View.GONE
        val inflater = LayoutInflater.from(context)
        val modelosRep = CaronasUiHelper.modelosReputacao(context)
        val modelosViagens = CaronasUiHelper.modelosViagens(context)

        participantes.forEach { p ->
            val item = inflater.inflate(R.layout.item_participante, container, false)
            val tvNome = item.findViewById<TextView>(R.id.tvParticipanteNome)
            val tvPapel = item.findViewById<TextView>(R.id.tvParticipantePapel)
            val tvReputacao = item.findViewById<TextView>(R.id.tvParticipanteReputacao)
            val tvAvaliada = item.findViewById<TextView>(R.id.tvParticipanteAvaliada)
            val btnAvaliar = item.findViewById<MaterialButton>(R.id.btnParticipanteAvaliar)

            val papelTexto = if (p.papel == Situacao.PAPEL_MOTORISTA) {
                context.getString(R.string.participantes_papel_motorista)
            } else {
                context.getString(R.string.participantes_papel_passageiro)
            }
            tvNome.text = p.nomeExibicao?.takeIf { it.isNotBlank() } ?: papelTexto
            tvPapel.text = papelTexto

            val repStr = ReputacaoTexto.formatar(p.reputacao.media, p.reputacao.quantidade, modelosRep)
            val viagensStr = ResumoViagens.viagens(p.reputacao.viagens, modelosViagens)
            tvReputacao.text = context.getString(R.string.participantes_viagens, repStr, viagensStr)

            when {
                p.jaAvaliou -> {
                    tvAvaliada.visibility = View.VISIBLE
                    btnAvaliar.visibility = View.GONE
                }
                p.podeAvaliar -> {
                    tvAvaliada.visibility = View.GONE
                    btnAvaliar.visibility = View.VISIBLE
                    btnAvaliar.isEnabled = true
                    btnAvaliar.setOnClickListener {
                        btnAvaliar.isEnabled = false
                        AvaliacaoDialog.mostrar(
                            context = context,
                            repositorio = avaliacaoRepository,
                            caronaId = caronaId,
                            avaliadoUid = p.uid,
                            aoEnviar = { aoAvaliar() },
                            aoFecharSemEnvio = { btnAvaliar.isEnabled = true }
                        )
                    }
                }
                else -> {
                    tvAvaliada.visibility = View.GONE
                    btnAvaliar.visibility = View.GONE
                }
            }

            container.addView(item)
        }
    }
}

package com.example.hitchhike.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.hitchhike.R
import com.example.hitchhike.data.AvaliacaoElegibilidade
import com.example.hitchhike.data.AvaliacaoFirestoreRepository
import com.example.hitchhike.data.DecisaoAvaliacao
import com.example.hitchhike.data.PedidoAvaliacao

object AvaliacaoDialog {
    fun podeEnviar(
        caronaId: String,
        avaliadorUid: String,
        avaliadoUid: String,
        motoristaUid: String,
        passageiroUid: String,
        statusSolicitacao: String,
        saidaMillis: Long,
        agoraMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return AvaliacaoElegibilidade.decidir(
            PedidoAvaliacao(
                caronaId = caronaId,
                avaliadorUid = avaliadorUid,
                avaliadoUid = avaliadoUid,
                motoristaUid = motoristaUid,
                passageiroUid = passageiroUid,
                solicitacaoId = "${caronaId}_${passageiroUid}",
                statusSolicitacao = statusSolicitacao,
                saidaMillis = saidaMillis,
                agoraMillis = agoraMillis,
                jaExiste = false,
                nota = 5
            )
        ) == DecisaoAvaliacao.PERMITIDA
    }

    fun vincular(
        context: Context,
        repositorio: AvaliacaoFirestoreRepository,
        botao: TextView,
        enviada: TextView,
        caronaId: String,
        avaliadoUid: String,
        motoristaUid: String,
        passageiroUid: String,
        avaliadorUid: String,
        statusSolicitacao: String,
        saidaMillis: Long
    ) {
        botao.visibility = android.view.View.GONE
        enviada.visibility = android.view.View.GONE
        if (!podeEnviar(
                caronaId = caronaId,
                avaliadorUid = avaliadorUid,
                avaliadoUid = avaliadoUid,
                motoristaUid = motoristaUid,
                passageiroUid = passageiroUid,
                statusSolicitacao = statusSolicitacao,
                saidaMillis = saidaMillis
            )
        ) {
            return
        }
        fun consultar() {
            repositorio.consultarEnviada(
                caronaId = caronaId,
                avaliadoUid = avaliadoUid,
                onSuccess = { existe ->
                    if (existe) {
                        botao.visibility = android.view.View.GONE
                        enviada.visibility = android.view.View.VISIBLE
                    } else {
                        enviada.visibility = android.view.View.GONE
                        botao.visibility = android.view.View.VISIBLE
                        botao.isEnabled = true
                        botao.setOnClickListener {
                            botao.isEnabled = false
                            mostrar(
                                context = context,
                                repositorio = repositorio,
                                caronaId = caronaId,
                                avaliadoUid = avaliadoUid,
                                aoEnviar = { consultar() },
                                aoFecharSemEnvio = { botao.isEnabled = true }
                            )
                        }
                    }
                },
                onError = { erro ->
                    Toast.makeText(context, erro, Toast.LENGTH_LONG).show()
                    enviada.visibility = android.view.View.GONE
                    botao.visibility = android.view.View.VISIBLE
                    botao.isEnabled = true
                    botao.setOnClickListener {
                        botao.isEnabled = false
                        mostrar(
                            context = context,
                            repositorio = repositorio,
                            caronaId = caronaId,
                            avaliadoUid = avaliadoUid,
                            aoEnviar = { consultar() },
                            aoFecharSemEnvio = { botao.isEnabled = true }
                        )
                    }
                }
            )
        }
        consultar()
    }

    fun mostrar(
        context: Context,
        repositorio: AvaliacaoFirestoreRepository,
        caronaId: String,
        avaliadoUid: String,
        aoEnviar: () -> Unit,
        aoFecharSemEnvio: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_avaliacao, null)
        val estrelas = listOf(
            view.findViewById<TextView>(R.id.estrela1),
            view.findViewById(R.id.estrela2),
            view.findViewById(R.id.estrela3),
            view.findViewById(R.id.estrela4),
            view.findViewById(R.id.estrela5)
        )
        val notaTexto = view.findViewById<TextView>(R.id.tvNotaSelecionada)
        var nota = 0
        var enviando = false
        var enviada = false

        fun pintar() {
            estrelas.forEachIndexed { indice, estrela ->
                val selecionada = indice < nota
                estrela.text = if (selecionada) "★" else "☆"
                estrela.contentDescription = context.getString(R.string.cd_estrela, indice + 1)
            }
            notaTexto.text = if (nota == 0) {
                context.getString(R.string.avaliacao_nota_pendente)
            } else {
                context.getString(R.string.cd_estrela, nota)
            }
        }

        estrelas.forEachIndexed { indice, estrela ->
            estrela.setOnClickListener {
                if (enviando) return@setOnClickListener
                nota = indice + 1
                pintar()
            }
        }
        pintar()

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.avaliacao_titulo)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.avaliacao_enviar, null)
            .create()

        dialog.setOnShowListener {
            val confirmar = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmar.setOnClickListener {
                if (enviando) return@setOnClickListener
                if (nota !in 1..5) {
                    Toast.makeText(context, R.string.avaliacao_nota_pendente, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                enviando = true
                confirmar.isEnabled = false
                dialog.setCancelable(false)
                repositorio.enviar(
                    caronaId = caronaId,
                    avaliadoUid = avaliadoUid,
                    nota = nota,
                    onSuccess = {
                        enviando = false
                        enviada = true
                        Toast.makeText(context, R.string.avaliacao_sucesso, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        aoEnviar()
                    },
                    onError = { erro ->
                        enviando = false
                        confirmar.isEnabled = true
                        dialog.setCancelable(true)
                        Toast.makeText(context, erro, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
        dialog.setOnDismissListener {
            if (!enviada && !enviando) aoFecharSemEnvio()
        }
        dialog.show()
    }
}

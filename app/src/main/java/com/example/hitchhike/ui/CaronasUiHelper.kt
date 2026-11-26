package com.example.hitchhike.ui

import android.content.Context
import com.example.hitchhike.R
import com.example.hitchhike.data.CaronasMensagensErro
import com.example.hitchhike.data.ReputacaoTexto
import com.example.hitchhike.data.ResumoViagens
import com.example.hitchhike.data.StatusCarona

object CaronasUiHelper {

    fun modelosReputacao(context: Context): ReputacaoTexto.Modelos = ReputacaoTexto.Modelos(
        semAvaliacoes = context.getString(R.string.reputacao_sem_avaliacoes),
        singular = context.getString(R.string.reputacao_singular),
        plural = context.getString(R.string.reputacao_plural),
        comCnh = context.getString(R.string.reputacao_com_cnh)
    )

    fun modelosViagens(context: Context): ResumoViagens.Modelos = ResumoViagens.Modelos(
        zero = context.getString(R.string.viagens_zero),
        singular = context.getString(R.string.viagens_singular),
        plural = context.getString(R.string.viagens_plural)
    )

    fun mensagemErro(context: Context, erro: Throwable): String {
        val textos = CaronasMensagensErro.Textos(
            servidorIndisponivel = context.getString(R.string.erro_servidor_indisponivel),
            sessaoInvalida = context.getString(R.string.erro_sessao_invalida),
            naoConfigurado = context.getString(R.string.erro_servidor_nao_configurado),
            generico = context.getString(R.string.erro_acao_generica),
            porCodigo = mapOf(
                "CARONA_INEXISTENTE" to context.getString(R.string.erro_cl_carona_inexistente),
                "NAO_EH_MOTORISTA" to context.getString(R.string.erro_cl_nao_eh_motorista),
                "NAO_EH_PASSAGEIRO_ACEITO" to context.getString(R.string.erro_cl_nao_eh_passageiro_aceito),
                "TRANSICAO_INVALIDA" to context.getString(R.string.erro_cl_transicao_invalida),
                "SEM_VAGAS" to context.getString(R.string.erro_cl_sem_vagas),
                "CARONA_VENCIDA" to context.getString(R.string.erro_cl_carona_vencida),
                "MOTORISTA_NAO_APTO" to context.getString(R.string.erro_cl_motorista_nao_apto),
                "FORA_DA_JANELA" to context.getString(R.string.erro_cl_fora_da_janela),
                "SEM_PASSAGEIROS" to context.getString(R.string.erro_cl_sem_passageiros),
                "PAGAMENTO_APROVADO" to context.getString(R.string.erro_cl_pagamento_aprovado),
                "PAGAMENTO_EM_ANDAMENTO" to context.getString(R.string.erro_cl_pagamento_em_andamento),
                "CHECKOUT_ABERTO" to context.getString(R.string.erro_cl_checkout_aberto),
                "JA_CONFIRMADO" to context.getString(R.string.erro_cl_ja_confirmado),
                "NAO_ELEGIVEL" to context.getString(R.string.erro_cl_nao_elegivel),
                "AVALIACAO_DUPLICADA" to context.getString(R.string.erro_cl_avaliacao_duplicada),
                "AUTOAVALIACAO" to context.getString(R.string.erro_cl_autoavaliacao),
                "PAR_INVALIDO" to context.getString(R.string.erro_cl_par_invalido),
                "NOTA_INVALIDA" to context.getString(R.string.erro_cl_nota_invalida),
                "LIMITE_REQUISICOES" to context.getString(R.string.erro_cl_limite_requisicoes)
            )
        )
        return CaronasMensagensErro.resolver(erro, textos)
    }

    fun rotuloStatusCarona(context: Context, statusExibicao: String): String = when (statusExibicao) {
        StatusCarona.ABERTA.name -> context.getString(R.string.etiqueta_aberta)
        StatusCarona.LOTADA.name -> context.getString(R.string.etiqueta_lotada)
        StatusCarona.AGUARDANDO_INICIO.name -> context.getString(R.string.etiqueta_aguardando_inicio)
        StatusCarona.EM_ANDAMENTO.name -> context.getString(R.string.etiqueta_em_andamento)
        StatusCarona.CONCLUIDA.name -> context.getString(R.string.etiqueta_concluida)
        StatusCarona.CANCELADA.name -> context.getString(R.string.etiqueta_cancelada)
        StatusCarona.EXPIRADA.name -> context.getString(R.string.etiqueta_expirada)
        StatusCarona.ENCERRADA.name, StatusCarona.FINALIZADA.name -> context.getString(R.string.etiqueta_encerrada)
        else -> statusExibicao
    }
}

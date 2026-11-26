package com.example.hitchhike.data

import java.util.Locale

/**
 * DTOs e lógica pura (testável em JVM, sem Android/org.json) do contrato docs/CARONAS_API.md.
 * O repositório converte o JSON em Map/List antes de chamar [CaronasParser].
 */

data class AcoesPermitidas(
    val iniciar: Boolean = false,
    val concluir: Boolean = false,
    val cancelar: Boolean = false,
    val confirmarParticipacao: Boolean = false,
    val confirmarConclusao: Boolean = false,
    val avaliar: Boolean = false
)

data class Veiculo(val modelo: String, val cor: String, val placa: String)

/** Reputação pública agregada (sem identidade). `media == null` ⇒ sem avaliações reveladas. */
data class Reputacao(
    val media: Double?,
    val quantidade: Int,
    val viagens: Int,
    val cnhValidada: Boolean
)

data class ReputacaoParticipante(val media: Double?, val quantidade: Int, val viagens: Int)

data class MinhaReputacao(
    val media: Double?,
    val quantidade: Int,
    val viagens: Int,
    val viagensComoMotorista: Int,
    val viagensComoPassageiro: Int,
    val cnhValidada: Boolean
)

data class MinhaSolicitacaoSituacao(
    val status: String,
    val participacaoConfirmadaEm: String?,
    val conclusaoConfirmadaEm: String?
)

data class Situacao(
    val caronaId: String,
    val statusExibicao: String,
    val papel: String,
    val acoes: AcoesPermitidas,
    val bloqueios: Map<String, String>,
    val minhaSolicitacao: MinhaSolicitacaoSituacao?,
    val veiculo: Veiculo?,
    val motorista: Reputacao?,
    val iniciadaEm: String?,
    val concluidaEm: String?
) {
    companion object {
        const val PAPEL_MOTORISTA = "MOTORISTA"
        const val PAPEL_PASSAGEIRO = "PASSAGEIRO"
        const val PAPEL_NENHUM = "NENHUM"
    }
}

data class SituacaoResumo(val statusExibicao: String, val papel: String, val acoes: AcoesPermitidas)

data class Participante(
    val uid: String,
    val papel: String,
    val nomeExibicao: String?,
    val reputacao: ReputacaoParticipante,
    val podeAvaliar: Boolean,
    val jaAvaliou: Boolean
)

data class ResultadoAceite(val status: String, val vagasDisponiveis: Int?, val caronaStatus: String?)

/** Falha do backend de caronas: `codigo` estável + mensagem original do servidor. */
class CaronasException(
    val codigo: String,
    val mensagemBackend: String?,
    val httpStatus: Int = 0
) : Exception(mensagemBackend ?: codigo) {
    companion object {
        const val REDE = "REDE"
        const val INDISPONIVEL = "BACKEND_INDISPONIVEL"
        const val NAO_AUTENTICADO = "NAO_AUTENTICADO"
        const val NAO_CONFIGURADO = "NAO_CONFIGURADO"
        const val RESPOSTA_INVALIDA = "RESPOSTA_INVALIDA"
    }
}

object CaronasParser {

    @Suppress("UNCHECKED_CAST")
    private fun mapa(valor: Any?): Map<String, Any?>? = valor as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun lista(valor: Any?): List<Any?> = (valor as? List<Any?>).orEmpty()

    private fun texto(valor: Any?): String? = (valor as? String)?.takeIf { it.isNotBlank() }

    private fun inteiro(valor: Any?): Int = (valor as? Number)?.toInt() ?: 0

    private fun decimal(valor: Any?): Double? = (valor as? Number)?.toDouble()

    private fun booleano(valor: Any?): Boolean = valor as? Boolean ?: false

    fun acoes(bruto: Any?): AcoesPermitidas {
        val m = mapa(bruto) ?: return AcoesPermitidas()
        return AcoesPermitidas(
            iniciar = booleano(m["iniciar"]),
            concluir = booleano(m["concluir"]),
            cancelar = booleano(m["cancelar"]),
            confirmarParticipacao = booleano(m["confirmarParticipacao"]),
            confirmarConclusao = booleano(m["confirmarConclusao"]),
            avaliar = booleano(m["avaliar"])
        )
    }

    fun reputacao(bruto: Any?): Reputacao? {
        val m = mapa(bruto) ?: return null
        return Reputacao(
            media = decimal(m["media"]),
            quantidade = inteiro(m["quantidade"]),
            viagens = inteiro(m["viagens"]),
            cnhValidada = booleano(m["cnhValidada"])
        )
    }

    fun minhaReputacao(bruto: Map<String, Any?>): MinhaReputacao {
        val viagens = inteiro(bruto["viagens"])
        val motorista = inteiro(bruto["viagensComoMotorista"])
        val passageiro = inteiro(bruto["viagensComoPassageiro"])
        return MinhaReputacao(
            media = decimal(bruto["media"]),
            quantidade = inteiro(bruto["quantidade"]),
            viagens = if (bruto["viagens"] == null) motorista + passageiro else viagens,
            viagensComoMotorista = motorista,
            viagensComoPassageiro = passageiro,
            cnhValidada = booleano(bruto["cnhValidada"])
        )
    }

    fun reputacoes(bruto: Map<String, Any?>): Map<String, Reputacao> {
        val m = mapa(bruto["reputacoes"]) ?: return emptyMap()
        return m.mapNotNull { (uid, valor) -> reputacao(valor)?.let { uid to it } }.toMap()
    }

    fun situacao(bruto: Map<String, Any?>): Situacao {
        val id = texto(bruto["caronaId"]) ?: ""
        val minha = mapa(bruto["minhaSolicitacao"])?.let {
            MinhaSolicitacaoSituacao(
                status = texto(it["status"]) ?: "",
                participacaoConfirmadaEm = texto(it["participacaoConfirmadaEm"]),
                conclusaoConfirmadaEm = texto(it["conclusaoConfirmadaEm"])
            )
        }
        val veiculo = mapa(bruto["veiculo"])?.let {
            Veiculo(
                modelo = texto(it["modelo"]) ?: "",
                cor = texto(it["cor"]) ?: "",
                placa = texto(it["placa"]) ?: ""
            )
        }
        val bloqueios = mapa(bruto["bloqueios"])
            ?.mapNotNull { (chave, valor) -> texto(valor)?.let { chave to it } }
            ?.toMap()
            .orEmpty()
        return Situacao(
            caronaId = id,
            statusExibicao = texto(bruto["statusExibicao"]) ?: "",
            papel = texto(bruto["papel"]) ?: Situacao.PAPEL_NENHUM,
            acoes = acoes(bruto["acoes"]),
            bloqueios = bloqueios,
            minhaSolicitacao = minha,
            veiculo = veiculo,
            motorista = reputacao(bruto["motorista"]),
            iniciadaEm = texto(bruto["iniciadaEm"]),
            concluidaEm = texto(bruto["concluidaEm"])
        )
    }

    fun situacoes(bruto: Map<String, Any?>): Map<String, SituacaoResumo> {
        val m = mapa(bruto["situacoes"]) ?: return emptyMap()
        return m.mapNotNull { (id, valor) ->
            val item = mapa(valor) ?: return@mapNotNull null
            id to SituacaoResumo(
                statusExibicao = texto(item["statusExibicao"]) ?: "",
                papel = texto(item["papel"]) ?: Situacao.PAPEL_NENHUM,
                acoes = acoes(item["acoes"])
            )
        }.toMap()
    }

    fun participantes(bruto: Map<String, Any?>): List<Participante> {
        return lista(bruto["participantes"]).mapNotNull { item ->
            val m = mapa(item) ?: return@mapNotNull null
            val uid = texto(m["uid"]) ?: return@mapNotNull null
            val rep = mapa(m["reputacao"])
            Participante(
                uid = uid,
                papel = texto(m["papel"]) ?: Situacao.PAPEL_PASSAGEIRO,
                nomeExibicao = texto(m["nomeExibicao"]),
                reputacao = ReputacaoParticipante(
                    media = decimal(rep?.get("media")),
                    quantidade = inteiro(rep?.get("quantidade")),
                    viagens = inteiro(rep?.get("viagens"))
                ),
                podeAvaliar = booleano(m["podeAvaliar"]),
                jaAvaliou = booleano(m["jaAvaliou"])
            )
        }
    }

    fun aceite(bruto: Map<String, Any?>): ResultadoAceite = ResultadoAceite(
        status = texto(bruto["status"]) ?: "ACEITA",
        vagasDisponiveis = (bruto["vagasDisponiveis"] as? Number)?.toInt(),
        caronaStatus = texto(bruto["caronaStatus"])
    )

    /** Extrai `codigo` e `error` do corpo de erro (já convertido em mapa). */
    fun erro(httpStatus: Int, corpo: Map<String, Any?>): CaronasException {
        val codigo = texto(corpo["codigo"])
        val mensagem = texto(corpo["error"])
        val efetivo = codigo ?: when (httpStatus) {
            401, 403 -> CaronasException.NAO_AUTENTICADO
            429 -> "LIMITE_REQUISICOES"
            else -> CaronasException.INDISPONIVEL
        }
        return CaronasException(efetivo, mensagem, httpStatus)
    }
}

/** Apresentação da reputação: nunca mostra nota zero. */
object ReputacaoTexto {

    data class Modelos(
        val semAvaliacoes: String,
        val singular: String,
        val plural: String,
        val comCnh: String = "%1\$s · CNH validada"
    )

    fun temAvaliacoes(media: Double?, quantidade: Int): Boolean =
        media != null && media > 0.0 && quantidade > 0

    fun mediaFormatada(media: Double): String =
        String.format(Locale("pt", "BR"), "%.1f", media)

    fun formatar(media: Double?, quantidade: Int, modelos: Modelos): String {
        if (!temAvaliacoes(media, quantidade)) return modelos.semAvaliacoes
        val m = mediaFormatada(media!!)
        return if (quantidade == 1) {
            String.format(Locale("pt", "BR"), modelos.singular, m)
        } else {
            String.format(Locale("pt", "BR"), modelos.plural, m, quantidade)
        }
    }

    fun formatarComCnh(
        media: Double?,
        quantidade: Int,
        cnhValidada: Boolean,
        modelos: Modelos
    ): String {
        val base = formatar(media, quantidade, modelos)
        return if (cnhValidada) String.format(Locale("pt", "BR"), modelos.comCnh, base) else base
    }
}

/** Viagens realizadas e avaliações recebidas são contagens independentes. */
object ResumoViagens {

    data class Modelos(val zero: String, val singular: String, val plural: String)

    fun viagens(quantidade: Int, modelos: Modelos): String = when {
        quantidade <= 0 -> modelos.zero
        quantidade == 1 -> modelos.singular
        else -> String.format(Locale("pt", "BR"), modelos.plural, quantidade)
    }
}

/**
 * Mapeamento determinístico de códigos de erro do backend (docs/CARONAS_API.md)
 * para mensagens de interface, sem depender do framework Android nos testes unitários.
 */
object CaronasMensagensErro {

    data class Textos(
        val servidorIndisponivel: String,
        val sessaoInvalida: String,
        val naoConfigurado: String,
        val generico: String,
        val porCodigo: Map<String, String>
    )

    fun resolver(erro: Throwable, textos: Textos): String {
        val ex = erro as? CaronasException
            ?: return erro.localizedMessage?.takeIf { it.isNotBlank() } ?: textos.generico
        val mapeada = textos.porCodigo[ex.codigo]
        if (!mapeada.isNullOrBlank()) return mapeada
        if (!ex.mensagemBackend.isNullOrBlank()) return ex.mensagemBackend
        return when (ex.codigo) {
            CaronasException.REDE, CaronasException.INDISPONIVEL -> textos.servidorIndisponivel
            CaronasException.NAO_AUTENTICADO, "NAO_AUTORIZADO" -> textos.sessaoInvalida
            CaronasException.NAO_CONFIGURADO -> textos.naoConfigurado
            else -> textos.generico
        }
    }
}

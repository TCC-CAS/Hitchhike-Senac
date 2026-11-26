package com.example.hitchhike.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usuarios",
    indices = [
        Index(value = ["email"], unique = true)
    ]
)
data class UsuarioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String,
    val curso: String,
    val instituicao: String,
    val email: String,
    /**
     * Coluna mantida apenas por compatibilidade de esquema do Room; sempre vazia ("").
     * As senhas são gerenciadas exclusivamente pelo Firebase Authentication.
     */
    val senhaHash: String = "",
    val papelAtivo: String = PapelUsuario.PASSAGEIRO.name,
    val cnhNumero: String? = null,
    val cnhAnexada: Boolean = false,
    val veiculoPlaca: String? = null,
    val veiculoModelo: String? = null,
    val veiculoCor: String? = null,
    val condutorValidado: Boolean = false,
    val status: String = "ATIVO",
    val cnhValidade: String? = null,
    val cnhRevisaoNecessaria: Boolean = false,
    val solicitacaoMotoristaStatus: String? = null,
    val motivoRecusaMotorista: String? = null,
    val mediaAvaliacao: Double = 0.0,
    val totalViagens: Int = 0,
    val taxaAceite: Double = 0.0,
    val plano: String = PlanoAssinatura.GRATUITO.name,
    val caronasNaSemana: Int = 0
) {
    fun isMotoristaAtivo(): Boolean = papelAtivo == PapelUsuario.MOTORISTA.name

    fun elegibilidadeMotorista(
        hojeIsoSp: String = ElegibilidadeMotorista.hojeIsoSaoPaulo(),
        exigirPapelMotorista: Boolean = true
    ): ResultadoElegibilidadeMotorista = ElegibilidadeMotorista.avaliar(
        status = status,
        condutorValidado = condutorValidado,
        papelAtivo = papelAtivo,
        cnhValidade = cnhValidade,
        cnhRevisaoNecessaria = cnhRevisaoNecessaria,
        hojeIsoSp = hojeIsoSp,
        exigirPapelMotorista = exigirPapelMotorista,
        solicitacaoStatus = solicitacaoMotoristaStatus,
        motivoRecusa = motivoRecusaMotorista
    )

    fun podeAtuarComoMotorista(
        hojeIsoSp: String = ElegibilidadeMotorista.hojeIsoSaoPaulo()
    ): Boolean = elegibilidadeMotorista(hojeIsoSp, exigirPapelMotorista = true).apto

    @Deprecated("Modelo de assinaturas descontinuado em favor do uso gratuito com taxa por carona.")
    fun limiteSemanal(): Int = Int.MAX_VALUE
}

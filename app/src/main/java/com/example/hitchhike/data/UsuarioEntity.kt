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
    val senhaHash: String,
    val papelAtivo: String = PapelUsuario.PASSAGEIRO.name,
    val cnhNumero: String? = null,
    val cnhAnexada: Boolean = false,
    val veiculoPlaca: String? = null,
    val veiculoModelo: String? = null,
    val veiculoCor: String? = null,
    val condutorValidado: Boolean = false,
    val mediaAvaliacao: Double = 0.0,
    val totalViagens: Int = 0,
    val taxaAceite: Double = 0.0,
    val plano: String = PlanoAssinatura.GRATUITO.name,
    val caronasNaSemana: Int = 0
) {
    fun isMotoristaAtivo(): Boolean = papelAtivo == PapelUsuario.MOTORISTA.name

    @Deprecated("Modelo de assinaturas descontinuado em favor do uso gratuito com taxa por carona.")
    fun limiteSemanal(): Int = Int.MAX_VALUE
}

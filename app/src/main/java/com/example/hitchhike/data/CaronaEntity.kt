package com.example.hitchhike.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "caronas",
    foreignKeys = [
        ForeignKey(
            entity = UsuarioEntity::class,
            parentColumns = ["id"],
            childColumns = ["motoristaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("motoristaId")]
)
data class CaronaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val motoristaId: Long,
    val origem: String,
    val destino: String,
    val dataHoraSaida: Long,
    val vagasTotais: Int,
    val vagasDisponiveis: Int,
    val valor: Double = 0.0,
    val status: String = StatusCarona.ABERTA.name
)

package com.example.hitchhike.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "solicitacoes",
    foreignKeys = [
        ForeignKey(
            entity = CaronaEntity::class,
            parentColumns = ["id"],
            childColumns = ["caronaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UsuarioEntity::class,
            parentColumns = ["id"],
            childColumns = ["passageiroId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("caronaId"),
        Index("passageiroId"),
        Index(value = ["caronaId", "passageiroId"], unique = true)
    ]
)
data class SolicitacaoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caronaId: Long,
    val passageiroId: Long,
    val status: String = StatusSolicitacao.PENDENTE.name
)

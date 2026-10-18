package com.example.hitchhike.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface CaronaDao {
    @Insert
    suspend fun inserir(carona: CaronaEntity): Long

    @Update
    suspend fun atualizar(carona: CaronaEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun solicitar(solicitacao: SolicitacaoEntity): Long

    @Query("SELECT * FROM caronas WHERE id = :id LIMIT 1")
    suspend fun buscarPorId(id: Long): CaronaEntity?

    @Query("SELECT * FROM solicitacoes WHERE id = :id LIMIT 1")
    suspend fun buscarSolicitacao(id: Long): SolicitacaoEntity?

    @Query(
        """
        SELECT * FROM caronas
        WHERE status = 'ABERTA' AND vagasDisponiveis > 0
        AND origem LIKE '%' || :origem || '%'
        AND destino LIKE '%' || :destino || '%'
        ORDER BY dataHoraSaida
        """
    )
    suspend fun buscarDisponiveis(origem: String, destino: String): List<CaronaEntity>

    @Query("SELECT * FROM caronas WHERE motoristaId = :motoristaId ORDER BY dataHoraSaida DESC")
    suspend fun listarPorMotorista(motoristaId: Long): List<CaronaEntity>

    @Query("SELECT * FROM solicitacoes WHERE caronaId = :caronaId ORDER BY id DESC")
    suspend fun listarSolicitacoes(caronaId: Long): List<SolicitacaoEntity>

    @Query("SELECT * FROM solicitacoes WHERE passageiroId = :passageiroId ORDER BY id DESC")
    suspend fun listarSolicitacoesDoPassageiro(passageiroId: Long): List<SolicitacaoEntity>

    @Query(
        """
        UPDATE caronas
        SET vagasDisponiveis = vagasDisponiveis - 1,
            status = CASE WHEN vagasDisponiveis - 1 = 0 THEN 'LOTADA' ELSE status END
        WHERE id = :caronaId AND status = 'ABERTA' AND vagasDisponiveis > 0
        """
    )
    suspend fun reservarVaga(caronaId: Long): Int

    @Query("UPDATE solicitacoes SET status = :status WHERE id = :solicitacaoId")
    suspend fun atualizarStatus(solicitacaoId: Long, status: String)

    @Query("UPDATE caronas SET status = :status WHERE id = :caronaId")
    suspend fun atualizarStatusCarona(caronaId: Long, status: String)

    @Transaction
    suspend fun aceitarSolicitacao(solicitacaoId: Long): Boolean {
        val solicitacao = buscarSolicitacao(solicitacaoId) ?: return false
        if (solicitacao.status != StatusSolicitacao.PENDENTE.name) return false
        if (reservarVaga(solicitacao.caronaId) != 1) return false
        atualizarStatus(solicitacaoId, StatusSolicitacao.ACEITA.name)
        return true
    }
}

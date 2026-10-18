package com.example.hitchhike.data

import android.content.Context

class CaronaRepository(context: Context) {
    private val dao = AppDatabase.get(context).caronaDao()

    suspend fun publicar(
        motorista: UsuarioEntity,
        origem: String,
        destino: String,
        dataHoraSaida: Long,
        vagas: Int,
        valor: Double
    ): Result<Long> = runCatching {
        check(motorista.condutorValidado) { "motorista_nao_validado" }
        require(origem.isNotBlank() && destino.isNotBlank()) { "trajeto_incompleto" }
        require(vagas in 1..4) { "vagas_invalidas" }
        require(valor >= 0) { "valor_invalido" }
        dao.inserir(
            CaronaEntity(
                motoristaId = motorista.id,
                origem = origem.trim(),
                destino = destino.trim(),
                dataHoraSaida = dataHoraSaida,
                vagasTotais = vagas,
                vagasDisponiveis = vagas,
                valor = valor
            )
        )
    }

    suspend fun buscar(origem: String, destino: String): List<CaronaEntity> {
        return dao.buscarDisponiveis(origem.trim(), destino.trim())
    }

    suspend fun buscarPorId(id: Long): CaronaEntity? = dao.buscarPorId(id)

    suspend fun listarMinhasCaronas(motoristaId: Long): List<CaronaEntity> =
        dao.listarPorMotorista(motoristaId)

    suspend fun listarSolicitacoes(caronaId: Long): List<SolicitacaoEntity> =
        dao.listarSolicitacoes(caronaId)

    suspend fun listarMinhasSolicitacoes(passageiroId: Long): List<SolicitacaoEntity> =
        dao.listarSolicitacoesDoPassageiro(passageiroId)

    suspend fun solicitar(caronaId: Long, passageiroId: Long): Result<Long> = runCatching {
        val carona = requireNotNull(dao.buscarPorId(caronaId)) { "carona_inexistente" }
        check(carona.motoristaId != passageiroId) { "autosolicitacao" }
        check(carona.status == StatusCarona.ABERTA.name && carona.vagasDisponiveis > 0) {
            "carona_indisponivel"
        }
        dao.solicitar(
            SolicitacaoEntity(caronaId = caronaId, passageiroId = passageiroId)
        )
    }

    suspend fun aceitar(solicitacaoId: Long): Result<Unit> = runCatching {
        check(dao.aceitarSolicitacao(solicitacaoId)) { "vaga_indisponivel" }
    }

    suspend fun recusar(solicitacaoId: Long): Result<Unit> = runCatching {
        val solicitacao = requireNotNull(dao.buscarSolicitacao(solicitacaoId)) { "solicitacao_inexistente" }
        check(solicitacao.status == StatusSolicitacao.PENDENTE.name) { "status_invalido" }
        dao.atualizarStatus(solicitacaoId, StatusSolicitacao.RECUSADA.name)
    }

    suspend fun cancelarCarona(caronaId: Long, motoristaId: Long): Result<Unit> = runCatching {
        val carona = requireNotNull(dao.buscarPorId(caronaId)) { "carona_inexistente" }
        check(carona.motoristaId == motoristaId) { "nao_autorizado" }
        dao.atualizarStatusCarona(caronaId, StatusCarona.CANCELADA.name)
    }
}

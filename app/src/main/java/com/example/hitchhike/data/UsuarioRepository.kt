package com.example.hitchhike.data

import android.content.Context

class UsuarioRepository(context: Context) {
    private val dao = AppDatabase.get(context).usuarioDao()

    /**
     * Sincroniza os dados públicos/cadastrais do perfil vindos do Firestore com o Room local.
     * Nunca grava senhas ou hashes locais (as senhas são gerenciadas exclusivamente pelo Firebase Authentication)
     * e preserva os campos administrativos existentes até a chamada de sincronizarValidacaoMotorista.
     */
    suspend fun sincronizarPerfil(
        nome: String,
        curso: String,
        instituicao: String,
        email: String,
        papel: PapelUsuario
    ): Long {
        val emailNorm = email.trim().lowercase()

        val usuarioExistente = dao.buscarPorEmail(emailNorm)

        if (usuarioExistente != null) {
            dao.atualizar(
                usuarioExistente.copy(
                    nome = nome.trim(),
                    curso = curso.trim(),
                    instituicao = instituicao.trim(),
                    senhaHash = "",
                    papelAtivo = papel.name
                )
            )

            return usuarioExistente.id
        }

        return dao.inserir(
            UsuarioEntity(
                nome = nome.trim(),
                curso = curso.trim(),
                instituicao = instituicao.trim(),
                email = emailNorm,
                senhaHash = "",
                papelAtivo = papel.name
            )
        )
    }

    suspend fun buscar(id: Long): UsuarioEntity? = dao.buscarPorId(id)

    suspend fun buscarPorEmail(email: String): UsuarioEntity? =
        dao.buscarPorEmail(email.trim().lowercase())

    suspend fun alternarPapel(usuario: UsuarioEntity, novoPapel: PapelUsuario) {
        dao.atualizar(usuario.copy(papelAtivo = novoPapel.name))
    }

    suspend fun salvarCondutor(
        usuario: UsuarioEntity,
        cnh: String,
        cnhAnexada: Boolean,
        placa: String,
        modelo: String,
        cor: String
    ): Result<Unit> {
        val placaNorm = placa.trim().uppercase()

        val existente = dao.buscarPorPlaca(placaNorm, usuario.id)

        if (existente != null) {
            return Result.failure(
                IllegalStateException("placa_duplicada")
            )
        }

        dao.atualizar(
            usuario.copy(
                cnhNumero = cnh.trim().ifBlank { usuario.cnhNumero },
                cnhAnexada = cnhAnexada,
                veiculoPlaca = placaNorm,
                veiculoModelo = modelo.trim(),
                veiculoCor = cor.trim(),
                condutorValidado = false,
                cnhRevisaoNecessaria = true,
                solicitacaoMotoristaStatus = "PENDENTE",
                motivoRecusaMotorista = null,
                papelAtivo = PapelUsuario.PASSAGEIRO.name
            )
        )

        return Result.success(Unit)
    }

    suspend fun sincronizarVeiculoLocal(
        usuarioId: Long,
        placa: String?,
        modelo: String?,
        cor: String?,
        cnhAnexada: Boolean
    ) {
        val usuario = dao.buscarPorId(usuarioId) ?: return
        dao.atualizar(
            usuario.copy(
                veiculoPlaca = placa?.takeIf { it.isNotBlank() } ?: usuario.veiculoPlaca,
                veiculoModelo = modelo?.takeIf { it.isNotBlank() } ?: usuario.veiculoModelo,
                veiculoCor = cor?.takeIf { it.isNotBlank() } ?: usuario.veiculoCor,
                cnhAnexada = cnhAnexada
            )
        )
    }

    @Deprecated("Modelo de assinaturas descontinuado em favor do uso gratuito com taxa por carona.")
    suspend fun atualizarPlano(usuario: UsuarioEntity, plano: PlanoAssinatura) {
        dao.atualizar(usuario.copy(plano = plano.name))
    }

    suspend fun sincronizarValidacaoMotorista(
        usuarioId: Long,
        validado: Boolean,
        cnhValidade: String? = null,
        cnhRevisaoNecessaria: Boolean? = null,
        status: String? = null,
        papelAtivo: String? = null,
        solicitacaoStatus: String? = null,
        motivoRecusa: String? = null,
        atualizarSolicitacao: Boolean = false
    ) {
        val usuario = dao.buscarPorId(usuarioId) ?: return
        val novoPapel = papelAtivo?.takeIf {
            it == PapelUsuario.MOTORISTA.name || it == PapelUsuario.PASSAGEIRO.name
        } ?: usuario.papelAtivo
        val novoStatusSol = if (atualizarSolicitacao) {
            ElegibilidadeMotorista.normalizarStatusSolicitacao(solicitacaoStatus)
        } else {
            ElegibilidadeMotorista.normalizarStatusSolicitacao(solicitacaoStatus) ?: usuario.solicitacaoMotoristaStatus
        }
        val novoMotivo = if (novoStatusSol == "RECUSADA") {
            if (atualizarSolicitacao) motivoRecusa?.takeIf { it.isNotBlank() }
            else motivoRecusa?.takeIf { it.isNotBlank() } ?: usuario.motivoRecusaMotorista
        } else {
            null
        }

        dao.atualizar(
            usuario.copy(
                senhaHash = "",
                condutorValidado = validado,
                cnhValidade = cnhValidade?.takeIf { it.isNotBlank() },
                cnhRevisaoNecessaria = cnhRevisaoNecessaria ?: usuario.cnhRevisaoNecessaria,
                status = status?.takeIf { it.isNotBlank() } ?: usuario.status,
                papelAtivo = novoPapel,
                solicitacaoMotoristaStatus = novoStatusSol,
                motivoRecusaMotorista = novoMotivo
            )
        )
    }

}

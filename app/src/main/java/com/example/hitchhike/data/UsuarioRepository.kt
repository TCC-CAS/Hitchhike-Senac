package com.example.hitchhike.data

import android.content.Context
import com.example.hitchhike.util.InstitutionalEmailValidator
import com.example.hitchhike.util.PasswordHasher

class UsuarioRepository(context: Context) {
    private val dao = AppDatabase.get(context).usuarioDao()

    suspend fun cadastrar(
        nome: String,
        curso: String,
        instituicao: String,
        email: String,
        senha: String,
        papelInicial: PapelUsuario
    ): Result<Long> {
        val emailNorm = email.trim().lowercase()
        if (!InstitutionalEmailValidator.isValid(emailNorm)) {
            return Result.failure(IllegalArgumentException("email_institucional"))
        }
        if (dao.buscarPorEmail(emailNorm) != null) {
            return Result.failure(IllegalStateException("email_existente"))
        }
        val id = dao.inserir(
            UsuarioEntity(
                nome = nome.trim(),
                curso = curso.trim(),
                instituicao = instituicao.trim(),
                email = emailNorm,
                senhaHash = PasswordHasher.hash(senha),
                papelAtivo = papelInicial.name
            )
        )
        return Result.success(id)
    }

    suspend fun autenticar(email: String, senha: String): UsuarioEntity? {
        val usuario = dao.buscarPorEmail(email.trim().lowercase()) ?: return null
        return if (usuario.senhaHash == PasswordHasher.hash(senha)) usuario else null
    }


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

    suspend fun atualizarSenha(usuarioId: Long, novaSenha: String) {
        val usuario = dao.buscarPorId(usuarioId) ?: return
        dao.atualizar(usuario.copy(senhaHash = PasswordHasher.hash(novaSenha)))
    }

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
                cnhNumero = cnh.trim(),
                cnhAnexada = cnhAnexada,
                veiculoPlaca = placaNorm,
                veiculoModelo = modelo.trim(),
                veiculoCor = cor.trim(),
                condutorValidado = false,
                papelAtivo = PapelUsuario.MOTORISTA.name
            )
        )

        return Result.success(Unit)
    }


    @Deprecated("Modelo de assinaturas descontinuado em favor do uso gratuito com taxa por carona.")
    suspend fun atualizarPlano(usuario: UsuarioEntity, plano: PlanoAssinatura) {
        dao.atualizar(usuario.copy(plano = plano.name))
    }

    suspend fun sincronizarValidacaoMotorista(
        usuarioId: Long,
        validado: Boolean
    ) {
        val usuario = dao.buscarPorId(usuarioId) ?: return

        dao.atualizar(
            usuario.copy(
                condutorValidado = validado
            )
        )
    }

}

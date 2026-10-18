
package com.example.hitchhike.util

import com.google.firebase.auth.FirebaseAuth

class FirebaseAuthManager {

    private val auth = FirebaseAuth.getInstance()

    // Cria a conta e envia o e-mail de confirmação
    fun cadastrar(
        email: String,
        senha: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!InstitutionalEmailValidator.isValid(email)) {
            onError("Utilize um e-mail institucional válido.")
            return
        }

        auth.createUserWithEmailAndPassword(email.trim(), senha)
            .addOnSuccessListener {
                val usuario = auth.currentUser

                if (usuario == null) {
                    onError("Não foi possível recuperar o usuário.")
                    return@addOnSuccessListener
                }

                usuario.sendEmailVerification()
                    .addOnSuccessListener {
                        val uid = usuario.uid
                        auth.signOut()
                        onSuccess(uid)
                    }
                    .addOnFailureListener { erro ->
                        auth.signOut()
                        onError(
                            erro.localizedMessage
                                ?: "Erro ao enviar confirmação."
                        )
                    }
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao criar conta."
                )
            }
    }

    // Envia um e-mail para redefinir a senha
    fun recuperarSenha(
        email: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!InstitutionalEmailValidator.isValid(email)) {
            onError("Informe um e-mail institucional válido.")
            return
        }

        auth.sendPasswordResetEmail(email.trim())
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao solicitar recuperação."
                )
            }
    }

    // Consulta se o usuário confirmou o e-mail
    fun emailVerificado(): Boolean {
        return auth.currentUser?.isEmailVerified == true
    }

    fun entrar(
        email: String,
        senha: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!InstitutionalEmailValidator.isValid(email)) {
            onError("Informe um e-mail institucional válido.")
            return
        }

        auth.signInWithEmailAndPassword(email.trim(), senha)
            .addOnSuccessListener {
                val usuario = auth.currentUser

                if (usuario == null) {
                    onError("Usuário não encontrado.")
                    return@addOnSuccessListener
                }

                usuario.reload()
                    .addOnSuccessListener {
                        if (usuario.isEmailVerified) {
                            usuario.getIdToken(true)
                                .addOnSuccessListener {
                                    onSuccess(usuario.uid)
                                }
                                .addOnFailureListener { erro ->
                                    auth.signOut()
                                    onError(
                                        erro.localizedMessage
                                            ?: "Erro ao atualizar a autenticação."
                                    )
                                }
                        } else {
                            auth.signOut()
                            onError("Confirme seu e-mail institucional antes de entrar.")
                        }
                    }
                    .addOnFailureListener { erro ->
                        auth.signOut()
                        onError(
                            erro.localizedMessage
                                ?: "Não foi possível verificar o e-mail."
                        )
                    }
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "E-mail ou senha inválidos."
                )
            }
    }

}

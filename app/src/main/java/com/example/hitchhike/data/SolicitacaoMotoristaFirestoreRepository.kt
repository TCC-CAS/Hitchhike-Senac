package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

data class DadosSolicitacaoMotorista(
    val status: String,
    val placa: String,
    val modelo: String,
    val cor: String,
    val cnhPdfRecebido: Boolean,
    val motivoRecusa: String? = null
)

class SolicitacaoMotoristaFirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun enviarSolicitacao(
        placa: String,
        modelo: String,
        cor: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        val placaNormalizada = placa.trim().uppercase()
        val modeloNormalizado = modelo.trim()
        val corNormalizada = cor.trim()

        if (placaNormalizada.length !in 7..8) {
            onError("Informe uma placa válida.")
            return
        }

        if (modeloNormalizado.isBlank() || corNormalizada.isBlank()) {
            onError("Preencha os dados do veículo.")
            return
        }

        if (modeloNormalizado.length > 100 || corNormalizada.length > 50) {
            onError("Os dados do veículo ultrapassam o limite permitido.")
            return
        }

        db.collection("solicitacoes_motoristas")
            .document(usuario.uid)
            .get()
            .addOnSuccessListener { documento ->
                if (documento.exists()) {
                    onError("ja_enviada")
                    return@addOnSuccessListener
                }
                enviarNovoPedido(
                    usuario.uid,
                    placaNormalizada,
                    modeloNormalizado,
                    corNormalizada,
                    onSuccess,
                    onError
                )
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao consultar solicitação existente."
                )
            }
    }

    fun consultar(
        onSuccess: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser
        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        db.collection("solicitacoes_motoristas")
            .document(usuario.uid)
            .get()
            .addOnSuccessListener { documento ->
                onSuccess(documento.exists())
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage ?: "Erro ao consultar solicitação."
                )
            }
    }

    fun consultarDetalhes(
        onSuccess: (DadosSolicitacaoMotorista?) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser
        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        val ref = db.collection("solicitacoes_motoristas").document(usuario.uid)
        ref.get(Source.SERVER)
            .addOnSuccessListener { doc ->
                onSuccess(extrairDados(doc))
            }
            .addOnFailureListener {
                // Fallback para cache caso o dispositivo esteja sem internet no momento
                ref.get()
                    .addOnSuccessListener { doc -> onSuccess(extrairDados(doc)) }
                    .addOnFailureListener { erro ->
                        onError(erro.localizedMessage ?: "Erro ao consultar solicitação.")
                    }
            }
    }

    private fun extrairDados(doc: com.google.firebase.firestore.DocumentSnapshot): DadosSolicitacaoMotorista? {
        if (!doc.exists()) return null
        val statusBruto = doc.getString("status").orEmpty().trim().uppercase()
        val statusNormalizado = ElegibilidadeMotorista.normalizarStatusSolicitacao(statusBruto) ?: "PENDENTE"
        val motivo = doc.getString("motivoRecusa")?.trim()?.takeIf { it.isNotEmpty() }
        return DadosSolicitacaoMotorista(
            status = statusNormalizado,
            placa = doc.getString("placa").orEmpty(),
            modelo = doc.getString("modelo").orEmpty(),
            cor = doc.getString("cor").orEmpty(),
            cnhPdfRecebido = doc.getBoolean("cnhPdfRecebido") == true,
            motivoRecusa = motivo
        )
    }

    private fun enviarNovoPedido(
        uid: String,
        placaNormalizada: String,
        modeloNormalizado: String,
        corNormalizada: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val dados = hashMapOf(
            "motoristaUid" to uid,
            "placa" to placaNormalizada,
            "modelo" to modeloNormalizado,
            "cor" to corNormalizada,
            "status" to "PENDENTE",
            "criadoEm" to FieldValue.serverTimestamp()
        )

        db.collection("solicitacoes_motoristas")
            .document(uid)
            .set(dados)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao enviar solicitação."
                )
            }
    }
}

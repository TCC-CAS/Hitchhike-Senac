
package com.example.hitchhike.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class CaronaFirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    fun publicar(
        carona: CaronaFirestore,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        if (carona.origem.isBlank() || carona.destino.isBlank()) {
            onError("Informe a origem e o destino.")
            return
        }

        if (carona.vagasTotais !in 1..4 || carona.valor < 0) {
            onError("Vagas ou valor inválidos.")
            return
        }

        if (carona.dataHoraSaida.toDate().time <= System.currentTimeMillis()) {
            onError("A data e o horário da carona devem estar no futuro.")
            return
        }


        val documento = db.collection("caronas").document()

        val dados = carona.copy(
            id = documento.id,
            motoristaUid = usuario.uid,
            origem = carona.origem.trim(),
            destino = carona.destino.trim(),
            vagasDisponiveis = carona.vagasTotais,
            status = "ABERTA"
        )

        documento.set(dados)
            .addOnSuccessListener {
                onSuccess(documento.id)
            }
            .addOnFailureListener { erro ->
                onError(erro.localizedMessage ?: "Erro ao publicar carona.")
            }
    }
}

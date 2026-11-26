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
        val motoristaRef = db.collection("usuarios").document(usuario.uid)

        val dados = carona.copy(
            id = documento.id,
            motoristaUid = usuario.uid,
            origem = carona.origem.trim(),
            destino = carona.destino.trim(),
            vagasDisponiveis = carona.vagasTotais,
            status = "ABERTA"
        )

        db.runTransaction { transaction ->
            val perfil = transaction.get(motoristaRef)
            if (!perfil.exists()) {
                throw IllegalStateException("Perfil de motorista não encontrado.")
            }
            val elegibilidade = ElegibilidadeMotorista.avaliar(
                status = perfil.getString("status"),
                condutorValidado = perfil.getBoolean("condutorValidado") == true,
                papelAtivo = perfil.getString("papelAtivo"),
                cnhValidade = perfil.getString("cnhValidade"),
                cnhRevisaoNecessaria = perfil.getBoolean("cnhRevisaoNecessaria") == true
            )
            if (!elegibilidade.apto) {
                throw IllegalStateException(ElegibilidadeMotorista.mensagemBloqueio(elegibilidade.estado))
            }
            transaction.set(documento, CaronaFirestore.payloadCriacao(dados))
            documento.id
        }
            .addOnSuccessListener { id ->
                onSuccess(id)
            }
            .addOnFailureListener { erro ->
                onError(erro.localizedMessage ?: "Erro ao publicar carona.")
            }
    }

    fun buscar(
        origem: String,
        destino: String,
        onSuccess: (List<CaronaFirestore>) -> Unit,
        onError: (String) -> Unit
    ) {
        db.collection("caronas")
            .whereEqualTo("status", "ABERTA")
            .limit(50)
            .get()
            .addOnSuccessListener { resultado ->

                val agora = System.currentTimeMillis()

                val caronas = resultado.documents
                    .mapNotNull { documento ->
                        documento.toObject(CaronaFirestore::class.java)
                    }
                    .filter { carona ->
                        CaronaStatusExibicao.disponivelParaNovaSolicitacao(
                            carona,
                            agora
                        ) &&
                            carona.origem.contains(origem.trim(), ignoreCase = true) &&
                            carona.destino.contains(destino.trim(), ignoreCase = true)
                    }
                    .sortedBy { it.dataHoraSaida.toDate().time }

                onSuccess(caronas)
            }
            .addOnFailureListener { erro ->
                onError(erro.localizedMessage ?: "Erro ao buscar caronas.")
            }
    }

    fun buscarPorId(
        caronaId: String,
        onSuccess: (CaronaFirestore) -> Unit,
        onError: (String) -> Unit
    ) {
        if (caronaId.isBlank()) {
            onError("Identificador da carona inválido.")
            return
        }

        db.collection("caronas")
            .document(caronaId)
            .get()
            .addOnSuccessListener { documento ->
                val carona = documento.toObject(CaronaFirestore::class.java)

                if (carona != null) {
                    onSuccess(carona)
                } else {
                    onError("Carona não encontrada.")
                }
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage ?: "Erro ao carregar a carona."
                )
            }
    }


    fun listarMinhasCaronas(
        onSuccess: (List<CaronaFirestore>) -> Unit,
        onError: (String) -> Unit
    ) {
        val usuario = auth.currentUser

        if (usuario == null || !usuario.isEmailVerified) {
            onError("Usuário não autenticado ou e-mail não verificado.")
            return
        }

        db.collection("caronas")
            .whereEqualTo("motoristaUid", usuario.uid)
            .get()
            .addOnSuccessListener { documentos ->
                val caronas = documentos.documents
                    .mapNotNull { documento ->
                        documento.toObject(CaronaFirestore::class.java)
                    }
                    .sortedByDescending {
                        it.dataHoraSaida.toDate().time
                    }

                onSuccess(caronas)
            }
            .addOnFailureListener { erro ->
                onError(
                    erro.localizedMessage
                        ?: "Erro ao carregar suas caronas."
                )
            }
    }

}

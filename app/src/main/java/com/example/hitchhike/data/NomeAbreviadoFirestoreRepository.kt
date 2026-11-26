package com.example.hitchhike.data

/**
 * Repositório de privacidade de nomes compatível com a arquitetura do backend (docs/CARONAS_API.md).
 *
 * Nenhum cliente Android lê ou grava a coleção `nomes_abreviados` no Firestore.
 * Antes do aceite e durante a viagem, sempre exibe a identificação genérica
 * ("Motorista" / "Passageiro").
 * Os nomes abreviados ("Pedro A.", "Mariana S.") são fornecidos exclusivamente
 * pelo endpoint autenticado `GET /api/caronas/{id}/participantes` após a conclusão
 * e confirmação válidas da viagem.
 */
class NomeAbreviadoFirestoreRepository {

    fun garantirProprio(@Suppress("UNUSED_PARAMETER") caronaId: String) {
        // Operação desativada no cliente: nomes abreviados são projetados exclusivamente pelo backend.
    }

    fun exibir(
        @Suppress("UNUSED_PARAMETER") caronaId: String,
        @Suppress("UNUSED_PARAMETER") alvoUid: String,
        papel: PrivacidadeNome.Papel,
        @Suppress("UNUSED_PARAMETER") participante: Boolean = false,
        @Suppress("UNUSED_PARAMETER") statusSolicitacao: String? = null,
        @Suppress("UNUSED_PARAMETER") encerrada: Boolean = false,
        @Suppress("UNUSED_PARAMETER") gravarProprio: Boolean = false,
        onTexto: (String) -> Unit
    ) {
        onTexto(PrivacidadeNome.rotuloGenerico(papel))
    }
}

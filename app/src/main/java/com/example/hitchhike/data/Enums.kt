package com.example.hitchhike.data

enum class PapelUsuario {
    PASSAGEIRO,
    MOTORISTA
}

enum class PlanoAssinatura {
    GRATUITO,
    PREMIUM
}

/**
 * Ciclo de vida de uma carona. `AGUARDANDO_INICIO` é apenas de exibição (calculado a partir do
 * horário); o backend grava ABERTA, LOTADA, EM_ANDAMENTO, CONCLUIDA, CANCELADA e EXPIRADA.
 * `ENCERRADA` e `FINALIZADA` são valores legados (Room/uso local) e nunca são gravados pelo backend.
 */
enum class StatusCarona {
    ABERTA,
    LOTADA,
    AGUARDANDO_INICIO,
    EM_ANDAMENTO,
    CONCLUIDA,
    CANCELADA,
    EXPIRADA,
    ENCERRADA,
    FINALIZADA
}

enum class StatusSolicitacao {
    PENDENTE,
    ACEITA,
    RECUSADA,
    CANCELADA
}

# API de ciclo de vida, avaliações e reputação (Hitchhike)

Contrato entre o backend (`caronasApi.js`) e o aplicativo Android. O backend usa o Firebase Admin SDK;
**o aplicativo não grava mais** em `caronas` (após a criação), `solicitacoes_caronas` (após a criação),
`avaliacoes` nem `nomes_abreviados` — as regras do Firestore bloqueiam essas escritas.

Autenticação: `Authorization: Bearer <Firebase ID Token>` (e-mail institucional verificado, perfil `ATIVO`).
Mesma base URL do módulo de pagamentos (`BuildConfig.PAGAMENTOS_BASE_URL`). Respostas `application/json`, `Cache-Control: no-store`.
Erros: `{ "error": "mensagem em português", "codigo": "CODIGO_ESTAVEL" }` com HTTP 400/401/403/404/409/429/503.

## Estados da carona (`caronas/{id}.status`, gravado só pelo backend após a criação)

`ABERTA` → `LOTADA` (todas as vagas aceitas) → `EM_ANDAMENTO` (motorista iniciou) → `CONCLUIDA` (motorista concluiu).
`CANCELADA` (motorista cancelou, a partir de ABERTA/LOTADA, antes de iniciar). `EXPIRADA` (prazo vencido sem início/conclusão válidos).
Transições retroativas são recusadas. Estados finais: `CONCLUIDA`, `CANCELADA`, `EXPIRADA`.

**`statusExibicao`** (calculado no servidor e espelhado no app para listas), dado `status`, `saida`, `agora`:

* `CANCELADA`, `CONCLUIDA`: iguais.
* `EM_ANDAMENTO`: se `agora > iniciadaEm + 24 h` → `EXPIRADA`; senão `EM_ANDAMENTO`.
* `ABERTA`/`LOTADA`: se `agora > saida + 6 h` → `EXPIRADA`; senão se `agora >= saida` → `AGUARDANDO_INICIO`;
  senão `LOTADA` (se `status == LOTADA` ou `vagasDisponiveis <= 0`) ou `ABERTA`.
* Registros antigos (sem campos de ciclo de vida) seguem a mesma regra: nunca viram `CONCLUIDA` automaticamente.

Campos novos em `caronas/{id}` (somente leitura no app; timestamps do servidor): `iniciadaEm`, `concluidaEm`, `canceladaEm`,
`canceladaPor`, `expiradaEm`, `ultimaSolicitacaoAceitaId`.

Campos novos em `solicitacoes_caronas/{caronaId}_{passageiroUid}`: `status` ∈ `PENDENTE|ACEITA|RECUSADA|CANCELADA`,
`aceitaEm`, `participacaoConfirmadaEm`, `conclusaoConfirmadaEm`, `statusAnterior` (quando cancelada pela carona).
Participante **elegível** = `ACEITA` + `participacaoConfirmadaEm` + `conclusaoConfirmadaEm` com a carona `CONCLUIDA`.

## Endpoints

| Método e caminho | Quem | Efeito |
|---|---|---|
| `POST /api/caronas/{caronaId}/solicitacoes/{passageiroUid}/aceitar` | motorista dono, CNH vigente | Aceita e decrementa 1 vaga numa transação (`LOTADA` na última). `200 {status:"ACEITA", vagasDisponiveis, caronaStatus}` |
| `POST /api/caronas/{caronaId}/solicitacoes/{passageiroUid}/recusar` | motorista dono | `PENDENTE → RECUSADA`. `200 {status:"RECUSADA"}` |
| `POST /api/caronas/{caronaId}/iniciar` | motorista dono, CNH vigente | Só entre `saida − 30 min` e o prazo, com ≥ 1 passageiro aceito. `200 {status:"EM_ANDAMENTO", iniciadaEm}` |
| `POST /api/caronas/{caronaId}/concluir` | motorista dono | Só a partir de `EM_ANDAMENTO`. `200 {status:"CONCLUIDA", concluidaEm}` |
| `POST /api/caronas/{caronaId}/cancelar` | motorista dono | Só `ABERTA`/`LOTADA`. Bloqueado se houver pagamento. `200 {status:"CANCELADA", solicitacoesCanceladas}`; `409` com `codigo` abaixo |
| `POST /api/caronas/{caronaId}/participacao/confirmar` | passageiro aceito | Só com a carona `EM_ANDAMENTO`. `200 {participacaoConfirmadaEm}` |
| `POST /api/caronas/{caronaId}/conclusao/confirmar` | passageiro aceito | Só com a carona `CONCLUIDA` e participação confirmada, até 14 dias. `200 {conclusaoConfirmadaEm}` |
| `GET /api/caronas/{caronaId}/situacao` | qualquer usuário autenticado | Estado e ações permitidas para quem consulta (abaixo) |
| `POST /api/caronas/situacoes` `{ids:[...≤30]}` | qualquer | `{situacoes:{<id>:{statusExibicao,papel,acoes}}}` (sem veículo) |
| `GET /api/caronas/{caronaId}/participantes` | participante elegível (ou motorista com ≥ 1 elegível) | Histórico compartilhado (abaixo) |
| `POST /api/avaliacoes` `{caronaId, avaliadoUid, nota}` | participante elegível | `201 {revelada:boolean}` |
| `POST /api/reputacao/consultar` `{uids:[...≤30]}` | qualquer | Reputação agregada, sem identidade |
| `GET /api/reputacao/minha` | o próprio | Reputação e contagens de viagens do usuário |

Códigos de erro estáveis (`codigo`): `CARONA_INEXISTENTE`, `NAO_EH_MOTORISTA`, `NAO_EH_PASSAGEIRO_ACEITO`, `TRANSICAO_INVALIDA`,
`SEM_VAGAS`, `CARONA_VENCIDA`, `MOTORISTA_NAO_APTO`, `FORA_DA_JANELA`, `SEM_PASSAGEIROS`, `PAGAMENTO_APROVADO`,
`PAGAMENTO_EM_ANDAMENTO`, `CHECKOUT_ABERTO`, `JA_CONFIRMADO`, `NAO_ELEGIVEL`, `AVALIACAO_DUPLICADA`, `AUTOAVALIACAO`,
`PAR_INVALIDO`, `NOTA_INVALIDA`, `LIMITE_REQUISICOES`.

### `GET /api/caronas/{id}/situacao`

```json
{
  "caronaId": "abc",
  "statusExibicao": "EM_ANDAMENTO",
  "papel": "MOTORISTA | PASSAGEIRO | NENHUM",
  "acoes": {
    "iniciar": false, "concluir": true, "cancelar": false,
    "confirmarParticipacao": false, "confirmarConclusao": false, "avaliar": false
  },
  "bloqueios": { "iniciar": "texto opcional explicando", "cancelar": "texto opcional" },
  "minhaSolicitacao": { "status": "ACEITA", "participacaoConfirmadaEm": "ISO|null", "conclusaoConfirmadaEm": "ISO|null" },
  "veiculo": { "modelo": "...", "cor": "...", "placa": "..." },
  "motorista": { "media": 4.8, "quantidade": 23, "viagens": 31, "cnhValidada": true },
  "iniciadaEm": "ISO|null", "concluidaEm": "ISO|null"
}
```

`veiculo` só existe para passageiro com solicitação `ACEITA` e carona `ABERTA|LOTADA|AGUARDANDO_INICIO|EM_ANDAMENTO`; caso contrário é `null`.
`minhaSolicitacao` é `null` para o motorista. `avaliar` indica participante elegível com ao menos um par ainda não avaliado.

### `GET /api/caronas/{id}/participantes` (somente carona `CONCLUIDA`)

```json
{ "participantes": [
  { "uid": "u1", "papel": "PASSAGEIRO", "nomeExibicao": "Pedro A.",
    "reputacao": { "media": null, "quantidade": 0, "viagens": 2 },
    "podeAvaliar": true, "jaAvaliou": false }
] }
```

Nunca contém nome completo, e-mail, telefone ou foto. O próprio solicitante não aparece. O passageiro vê apenas o motorista
(passageiros não se enxergam nem se avaliam). `nomeExibicao` = primeiro nome + inicial do último sobrenome; nome único → só ele.

### Reputação

`POST /api/reputacao/consultar` → `{"reputacoes": {"<uid>": {"media": 4.8, "quantidade": 23, "viagens": 31, "cnhValidada": true}}}`.
`media` é `null` e `quantidade` é `0` para quem ainda não tem avaliações **reveladas** (o app exibe "Ainda sem avaliações";
nunca nota zero). `viagens` = viagens realizadas (concluídas e confirmadas) e é independente de `quantidade`.
`GET /api/reputacao/minha` → mesmos campos, com `viagensComoMotorista` e `viagensComoPassageiro`.

### Avaliações às cegas (implementadas no servidor)

* `avaliacoes/{caronaId}_{avaliadorUid}_{avaliadoUid}`: nota 1–5, `criadoEm` (servidor), `liberaEm` (= confirmação da conclusão do passageiro + 48 h),
  `parCompleto` (as duas avaliações do par existem). Nenhum cliente lê ou grava essa coleção.
* Uma nota só entra na reputação quando `parCompleto == true` **ou** `agora >= liberaEm`. O cálculo é feito a cada consulta,
  portanto não depende de temporizadores em memória e sobrevive a reinicializações.
* Prazo para avaliar: 14 dias após a conclusão confirmada do passageiro.

import test from 'node:test';
import assert from 'node:assert/strict';
import { criarFirestoreFalso } from './helpers/firestoreFalso.js';
import {
  HORA, MINUTO, DIA, abreviarNome, avaliacaoRevelada, bloqueioFinanceiro, calcularReputacao,
  caronaAceitaPagamento, exibicaoDeCarona, motoristaApto, passageiroElegivel, statusExibicao,
  transicaoPermitida
} from '../caronasCiclo.js';
import { criarServicoCaronas } from '../caronasApi.js';

const AGORA_BASE = Date.parse('2026-11-26T14:00:00.000Z');
const SAIDA_FUTURA = AGORA_BASE + 2 * HORA;
const CNH_VIGENTE_MS = AGORA_BASE + 30 * DIA;

function criarAmbiente(docsIniciais = [], agoraInicial = AGORA_BASE) {
  let relogio = agoraInicial;
  const fs = criarFirestoreFalso([
    ['usuarios/mot1', {
      nome: 'Mariana Souza',
      email: 'mariana@senacsp.edu.br',
      status: 'ATIVO',
      papelAtivo: 'MOTORISTA',
      condutorValidado: true,
      cnhValidade: '2030-11-18',
      cnhValidadeFimMs: CNH_VIGENTE_MS,
      cnhRevisaoNecessaria: false
    }],
    ['usuarios/motVencido', {
      nome: 'Carlos Vencido',
      email: 'carlos@senacsp.edu.br',
      status: 'ATIVO',
      papelAtivo: 'MOTORISTA',
      condutorValidado: true,
      cnhValidade: '2024-01-01',
      cnhValidadeFimMs: AGORA_BASE - DIA,
      cnhRevisaoNecessaria: false
    }],
    ['usuarios/pass1', {
      nome: 'Pedro Almeida',
      email: 'pedro@senacsp.edu.br',
      status: 'ATIVO',
      papelAtivo: 'PASSAGEIRO',
      condutorValidado: false
    }],
    ['usuarios/pass2', {
      nome: 'Cher',
      email: 'cher@sp.senac.br',
      status: 'ATIVO',
      papelAtivo: 'PASSAGEIRO',
      condutorValidado: false
    }],
    ['usuarios/intruso', {
      nome: 'Intruso Silva',
      email: 'intruso@senac.br',
      status: 'ATIVO',
      papelAtivo: 'PASSAGEIRO',
      condutorValidado: false
    }],
    ['solicitacoes_motoristas/mot1', {
      motoristaUid: 'mot1',
      status: 'APROVADA',
      modelo: 'Honda Fit',
      cor: 'Prata',
      placa: 'ABC1D23'
    }],
    ...docsIniciais
  ]);

  const servico = criarServicoCaronas({
    firestore: () => fs,
    agoraMs: () => relogio,
    serverTimestamp: () => new Date(relogio)
  });

  return {
    fs,
    servico,
    avancar(ms) { relogio += ms; },
    definirAgora(ms) { relogio = ms; },
    agora: () => relogio
  };
}

test('funções puras: statusExibicao, transições, abreviação de nomes e registros antigos', () => {
  assert.equal(abreviarNome('Pedro Augusto Almeida'), 'Pedro A.');
  assert.equal(abreviarNome('Mariana Souza'), 'Mariana S.');
  assert.equal(abreviarNome('Cher'), 'Cher');
  assert.equal(abreviarNome('pedro@senacsp.edu.br'), null);
  assert.equal(abreviarNome('11999998888'), null);
  assert.equal(abreviarNome('   '), null);

  assert.equal(transicaoPermitida('ABERTA', 'EM_ANDAMENTO'), true);
  assert.equal(transicaoPermitida('EM_ANDAMENTO', 'CONCLUIDA'), true);
  assert.equal(transicaoPermitida('CONCLUIDA', 'ABERTA'), false);
  assert.equal(transicaoPermitida('CANCELADA', 'ABERTA'), false);

  // Registro antigo cujo horário passou sem início nunca vira CONCLUIDA automaticamente
  const saidaPassada2h = AGORA_BASE - 2 * HORA;
  assert.equal(
    statusExibicao({ status: 'ABERTA', saidaMs: saidaPassada2h, vagasDisponiveis: 2 }, AGORA_BASE),
    'AGUARDANDO_INICIO'
  );
  const saidaPassada8h = AGORA_BASE - 8 * HORA;
  assert.equal(
    statusExibicao({ status: 'ABERTA', saidaMs: saidaPassada8h, vagasDisponiveis: 2 }, AGORA_BASE),
    'EXPIRADA'
  );
  assert.equal(
    statusExibicao({ status: 'ENCERRADA', saidaMs: saidaPassada8h, vagasDisponiveis: 0 }, AGORA_BASE),
    'EXPIRADA'
  );
});

test('aceite e decremento atômico de vagas, concorrência pela última vaga e bloqueio de motorista não apto', async () => {
  const amb = criarAmbiente([
    ['caronas/c1', {
      id: 'c1',
      motoristaUid: 'mot1',
      origem: 'Jabaquara',
      destino: 'Senac Santo Amaro',
      dataHoraSaida: new Date(SAIDA_FUTURA),
      vagasTotais: 1,
      vagasDisponiveis: 1,
      valor: 12,
      status: 'ABERTA'
    }],
    ['solicitacoes_caronas/c1_pass1', {
      id: 'c1_pass1',
      caronaId: 'c1',
      motoristaUid: 'mot1',
      passageiroUid: 'pass1',
      status: 'PENDENTE'
    }],
    ['solicitacoes_caronas/c1_pass2', {
      id: 'c1_pass2',
      caronaId: 'c1',
      motoristaUid: 'mot1',
      passageiroUid: 'pass2',
      status: 'PENDENTE'
    }]
  ]);

  // Terceiro não pode aceitar
  await assert.rejects(
    () => amb.servico.aceitar('intruso', 'c1', 'pass1'),
    err => err.codigo === 'NAO_EH_MOTORISTA' && err.status === 403
  );

  // Concorrência pela última vaga: duas chamadas simultâneas na mesma fila transacional
  const [r1, r2] = await Promise.allSettled([
    amb.servico.aceitar('mot1', 'c1', 'pass1'),
    amb.servico.aceitar('mot1', 'c1', 'pass2')
  ]);
  const sucessos = [r1, r2].filter(r => r.status === 'fulfilled');
  const falhas = [r1, r2].filter(r => r.status === 'rejected');
  assert.equal(sucessos.length, 1);
  assert.equal(falhas.length, 1);
  assert.equal(sucessos[0].value.vagasDisponiveis, 0);
  assert.equal(sucessos[0].value.caronaStatus, 'LOTADA');
  assert.equal(falhas[0].reason.codigo, 'SEM_VAGAS');

  const caronaDepois = amb.fs.docs.get('caronas/c1');
  assert.equal(caronaDepois.vagasDisponiveis, 0);
  assert.equal(caronaDepois.status, 'LOTADA');

  // Motorista com CNH vencida não consegue aceitar
  amb.fs.docs.set('caronas/cVenc', {
    id: 'cVenc',
    motoristaUid: 'motVencido',
    dataHoraSaida: new Date(SAIDA_FUTURA),
    vagasTotais: 2,
    vagasDisponiveis: 2,
    valor: 10,
    status: 'ABERTA'
  });
  amb.fs.docs.set('solicitacoes_caronas/cVenc_pass1', {
    id: 'cVenc_pass1',
    caronaId: 'cVenc',
    motoristaUid: 'motVencido',
    passageiroUid: 'pass1',
    status: 'PENDENTE'
  });
  await assert.rejects(
    () => amb.servico.aceitar('motVencido', 'cVenc', 'pass1'),
    err => err.codigo === 'MOTORISTA_NAO_APTO'
  );
});

test('cancelamento de caronas online: autorização, preservação do histórico e bloqueios financeiros (PENDENTE, EM_PROCESSAMENTO, APROVADO)', async () => {
  const amb = criarAmbiente([
    ['caronas/cCancel', {
      id: 'cCancel',
      motoristaUid: 'mot1',
      origem: 'Interlagos',
      destino: 'Senac Santo Amaro',
      dataHoraSaida: new Date(SAIDA_FUTURA),
      vagasTotais: 2,
      vagasDisponiveis: 1,
      valor: 15,
      status: 'ABERTA'
    }],
    ['solicitacoes_caronas/cCancel_pass1', {
      id: 'cCancel_pass1',
      caronaId: 'cCancel',
      motoristaUid: 'mot1',
      passageiroUid: 'pass1',
      status: 'ACEITA'
    }],
    ['solicitacoes_caronas/cCancel_pass2', {
      id: 'cCancel_pass2',
      caronaId: 'cCancel',
      motoristaUid: 'mot1',
      passageiroUid: 'pass2',
      status: 'PENDENTE'
    }]
  ]);

  // Terceiro não pode cancelar
  await assert.rejects(
    () => amb.servico.cancelar('pass1', 'cCancel'),
    err => err.codigo === 'NAO_EH_MOTORISTA' && err.status === 403
  );

  // Bloqueio quando há checkout PENDENTE
  amb.fs.docs.set('pagamentos/pag1', { id: 'pag1', caronaId: 'cCancel', status: 'PENDENTE' });
  await assert.rejects(
    () => amb.servico.cancelar('mot1', 'cCancel'),
    err => err.codigo === 'CHECKOUT_ABERTO' && err.status === 409
  );

  // Bloqueio quando há pagamento EM_PROCESSAMENTO
  amb.fs.docs.set('pagamentos/pag1', { id: 'pag1', caronaId: 'cCancel', status: 'EM_PROCESSAMENTO' });
  await assert.rejects(
    () => amb.servico.cancelar('mot1', 'cCancel'),
    err => err.codigo === 'PAGAMENTO_EM_ANDAMENTO' && err.status === 409
  );

  // Bloqueio quando há pagamento APROVADO
  amb.fs.docs.set('pagamentos/pag1', { id: 'pag1', caronaId: 'cCancel', status: 'APROVADO' });
  await assert.rejects(
    () => amb.servico.cancelar('mot1', 'cCancel'),
    err => err.codigo === 'PAGAMENTO_APROVADO' && err.status === 409
  );

  // Se o pagamento foi RECUSADO ou CANCELADO, o cancelamento é permitido e o registro financeiro é preservado
  amb.fs.docs.set('pagamentos/pag1', { id: 'pag1', caronaId: 'cCancel', status: 'RECUSADO' });
  const res = await amb.servico.cancelar('mot1', 'cCancel');
  assert.equal(res.status, 'CANCELADA');
  assert.equal(res.solicitacoesCanceladas, 2);
  assert.equal(amb.fs.docs.get('caronas/cCancel').status, 'CANCELADA');
  assert.equal(amb.fs.docs.get('solicitacoes_caronas/cCancel_pass1').status, 'CANCELADA');
  assert.equal(amb.fs.docs.get('solicitacoes_caronas/cCancel_pass1').statusAnterior, 'ACEITA');
  assert.equal(amb.fs.docs.has('pagamentos/pag1'), true, 'registro financeiro preservado');
  assert.equal(caronaAceitaPagamento(amb.fs.docs.get('caronas/cCancel'), amb.agora()), false);
});

test('ciclo completo da viagem, privacidade por etapa, avaliações às cegas e cálculo de reputação', async () => {
  const amb = criarAmbiente([
    ['caronas/cViagem', {
      id: 'cViagem',
      motoristaUid: 'mot1',
      origem: 'Santo Amaro',
      destino: 'Senac Santo Amaro',
      dataHoraSaida: new Date(SAIDA_FUTURA),
      vagasTotais: 2,
      vagasDisponiveis: 2,
      valor: 10,
      status: 'ABERTA'
    }],
    ['solicitacoes_caronas/cViagem_pass1', {
      id: 'cViagem_pass1',
      caronaId: 'cViagem',
      motoristaUid: 'mot1',
      passageiroUid: 'pass1',
      status: 'PENDENTE'
    }],
    ['solicitacoes_caronas/cViagem_pass2', {
      id: 'cViagem_pass2',
      caronaId: 'cViagem',
      motoristaUid: 'mot1',
      passageiroUid: 'pass2',
      status: 'PENDENTE'
    }]
  ]);

  // 1. Antes do aceite: passageiro solicitante NÃO vê veículo nem participantes
  const sitAntes = await amb.servico.situacao('pass1', 'cViagem');
  assert.equal(sitAntes.veiculo, null);
  assert.equal(sitAntes.motorista.cnhValidada, true);
  assert.equal(sitAntes.motorista.media, null);
  assert.equal(sitAntes.motorista.quantidade, 0);
  await assert.rejects(
    () => amb.servico.participantes('pass1', 'cViagem'),
    err => err.codigo === 'NAO_ELEGIVEL'
  );

  // 2. Aceite de pass1; pass2 continua PENDENTE
  await amb.servico.aceitar('mot1', 'cViagem', 'pass1');
  const sitAceito = await amb.servico.situacao('pass1', 'cViagem');
  assert.deepEqual(sitAceito.veiculo, { modelo: 'Honda Fit', cor: 'Prata', placa: 'ABC1D23' });
  const sitPendente = await amb.servico.situacao('pass2', 'cViagem');
  assert.equal(sitPendente.veiculo, null, 'passageiro pendente não vê placa/veículo');
  const sitIntruso = await amb.servico.situacao('intruso', 'cViagem');
  assert.equal(sitIntruso.veiculo, null, 'terceiro não vê placa/veículo');

  // 3. Não pode iniciar mais de 30 min antes da saída nem concluir antes de iniciar
  await assert.rejects(
    () => amb.servico.iniciar('mot1', 'cViagem'),
    err => err.codigo === 'FORA_DA_JANELA'
  );
  await assert.rejects(
    () => amb.servico.concluir('mot1', 'cViagem'),
    err => err.codigo === 'TRANSICAO_INVALIDA'
  );

  // Avança para 15 min antes da saída e inicia
  amb.definirAgora(SAIDA_FUTURA - 15 * MINUTO);
  const inicio = await amb.servico.iniciar('mot1', 'cViagem');
  assert.equal(inicio.status, 'EM_ANDAMENTO');
  assert.equal(amb.fs.docs.get('solicitacoes_caronas/cViagem_pass2').status, 'CANCELADA');

  // Durante a viagem: veículo continua visível para pass1, mas nomes abreviados e avaliação ainda estão bloqueados
  await assert.rejects(
    () => amb.servico.avaliar('pass1', { caronaId: 'cViagem', avaliadoUid: 'mot1', nota: 5 }),
    err => err.codigo === 'NAO_ELEGIVEL'
  );

  // Passageiro confirma participação durante a viagem
  await amb.servico.confirmarParticipacao('pass1', 'cViagem');

  // Motorista conclui a viagem
  amb.avancar(30 * MINUTO);
  const conc = await amb.servico.concluir('mot1', 'cViagem');
  assert.equal(conc.status, 'CONCLUIDA');

  // Antes de o passageiro confirmar a conclusão, nem histórico de participantes nem avaliação são liberados (impede ação unilateral do motorista)
  await assert.rejects(
    () => amb.servico.participantes('mot1', 'cViagem'),
    err => err.codigo === 'NAO_ELEGIVEL'
  );
  await assert.rejects(
    () => amb.servico.avaliar('mot1', { caronaId: 'cViagem', avaliadoUid: 'pass1', nota: 5 }),
    err => err.codigo === 'NAO_ELEGIVEL'
  );

  // Passageiro confirma conclusão
  await amb.servico.confirmarConclusao('pass1', 'cViagem');

  // Após conclusão confirmada: veículo deixa de ser exibido em situacao e nomes abreviados são liberados somente para os participantes elegíveis
  const sitConcluida = await amb.servico.situacao('pass1', 'cViagem');
  assert.equal(sitConcluida.veiculo, null);
  assert.equal(sitConcluida.acoes.avaliar, true);

  const partPass = await amb.servico.participantes('pass1', 'cViagem');
  assert.equal(partPass.participantes.length, 1);
  assert.equal(partPass.participantes[0].nomeExibicao, 'Mariana S.');
  assert.equal(partPass.participantes[0].papel, 'MOTORISTA');

  const partMot = await amb.servico.participantes('mot1', 'cViagem');
  assert.equal(partMot.participantes.length, 1);
  assert.equal(partMot.participantes[0].nomeExibicao, 'Pedro A.');
  assert.equal(partMot.participantes[0].papel, 'PASSAGEIRO');

  // Terceiro e passageiro não confirmado são bloqueados de ver participantes
  await assert.rejects(() => amb.servico.participantes('intruso', 'cViagem'), err => err.codigo === 'NAO_ELEGIVEL');
  await assert.rejects(() => amb.servico.participantes('pass2', 'cViagem'), err => err.codigo === 'NAO_ELEGIVEL');

  // 4. Avaliações às cegas e bloqueios de autoavaliação / duplicidade / terceiros
  await assert.rejects(
    () => amb.servico.avaliar('pass1', { caronaId: 'cViagem', avaliadoUid: 'pass1', nota: 5 }),
    err => err.codigo === 'AUTOAVALIACAO'
  );
  await assert.rejects(
    () => amb.servico.avaliar('intruso', { caronaId: 'cViagem', avaliadoUid: 'mot1', nota: 5 }),
    err => err.codigo === 'NAO_ELEGIVEL'
  );

  // Passageiro avalia motorista com nota 5: como o motorista ainda não avaliou e não passaram 48h, fica oculta!
  const av1 = await amb.servico.avaliar('pass1', { caronaId: 'cViagem', avaliadoUid: 'mot1', nota: 5 });
  assert.equal(av1.revelada, false);

  // Avaliação duplicada bloqueada
  await assert.rejects(
    () => amb.servico.avaliar('pass1', { caronaId: 'cViagem', avaliadoUid: 'mot1', nota: 4 }),
    err => err.codigo === 'AVALIACAO_DUPLICADA'
  );

  // Antes da contraparte avaliar (< 48h), a reputação pública do motorista continua sem avaliações reveladas, mas viagens realizadas já conta 1!
  const repAntesPar = await amb.servico.minhaReputacao('mot1');
  assert.equal(repAntesPar.media, null);
  assert.equal(repAntesPar.quantidade, 0);
  assert.equal(repAntesPar.viagens, 1);
  assert.equal(repAntesPar.viagensComoMotorista, 1);

  // Quando o motorista também avalia o passageiro (nota 4), ambas as avaliações do par são reveladas imediatamente
  const av2 = await amb.servico.avaliar('mot1', { caronaId: 'cViagem', avaliadoUid: 'pass1', nota: 4 });
  assert.equal(av2.revelada, true);

  const repMot = await amb.servico.minhaReputacao('mot1');
  assert.equal(repMot.media, 5);
  assert.equal(repMot.quantidade, 1);
  assert.equal(repMot.viagens, 1);

  const repPass = await amb.servico.minhaReputacao('pass1');
  assert.equal(repPass.media, 4);
  assert.equal(repPass.quantidade, 1);
  assert.equal(repPass.viagens, 1);
  assert.equal(repPass.viagensComoPassageiro, 1);
});

test('avaliação às cegas unilateral é revelada automaticamente após 48 horas da conclusão confirmada', async () => {
  const concluidaMs = AGORA_BASE;
  const amb = criarAmbiente([
    ['caronas/c48', {
      id: 'c48',
      motoristaUid: 'mot1',
      dataHoraSaida: new Date(concluidaMs - HORA),
      iniciadaEm: new Date(concluidaMs - HORA),
      concluidaEm: new Date(concluidaMs),
      vagasTotais: 2,
      vagasDisponiveis: 1,
      valor: 10,
      status: 'CONCLUIDA'
    }],
    ['solicitacoes_caronas/c48_pass2', {
      id: 'c48_pass2',
      caronaId: 'c48',
      motoristaUid: 'mot1',
      passageiroUid: 'pass2',
      status: 'ACEITA',
      participacaoConfirmadaEm: new Date(concluidaMs - 30 * MINUTO),
      conclusaoConfirmadaEm: new Date(concluidaMs)
    }]
  ]);

  const envio = await amb.servico.avaliar('pass2', { caronaId: 'c48', avaliadoUid: 'mot1', nota: 3 });
  assert.equal(envio.revelada, false);
  assert.equal((await amb.servico.minhaReputacao('mot1')).quantidade, 0);

  // Após 47 horas ainda oculta
  amb.avancar(47 * HORA);
  assert.equal((await amb.servico.minhaReputacao('mot1')).quantidade, 0);

  // Após 48 horas revelada sem precisar de timer em memória
  amb.avancar(2 * HORA);
  const rep = await amb.servico.minhaReputacao('mot1');
  assert.equal(rep.quantidade, 1);
  assert.equal(rep.media, 3);
});


for (const cenario of ['INATIVO', 'SEM_PERFIL']) {
  test(`Bloqueia aceite de passageiro ${cenario} sem consumir vaga`, async () => {
    const amb = criarAmbiente([
      ['caronas/cBloqueio', {
        id: 'cBloqueio',
        motoristaUid: 'mot1',
        dataHoraSaida: new Date(SAIDA_FUTURA),
        vagasTotais: 1,
        vagasDisponiveis: 1,
        valor: 10,
        status: 'ABERTA'
      }],
      ['solicitacoes_caronas/cBloqueio_pass1', {
        id: 'cBloqueio_pass1',
        caronaId: 'cBloqueio',
        motoristaUid: 'mot1',
        passageiroUid: 'pass1',
        status: 'PENDENTE'
      }]
    ]);

    if (cenario === 'INATIVO') {
      amb.fs.docs.set('usuarios/pass1', {
        ...amb.fs.docs.get('usuarios/pass1'),
        status: 'INATIVO'
      });
    } else {
      amb.fs.docs.delete('usuarios/pass1');
    }

    await assert.rejects(
      () => amb.servico.aceitar('mot1', 'cBloqueio', 'pass1'),
      err => err.codigo === 'PASSAGEIRO_INATIVO'
        && err.status === 409
    );

    assert.equal(
      amb.fs.docs.get('caronas/cBloqueio').vagasDisponiveis,
      1
    );

    assert.equal(
      amb.fs.docs.get('caronas/cBloqueio').status,
      'ABERTA'
    );

    assert.equal(
      amb.fs.docs.get('solicitacoes_caronas/cBloqueio_pass1').status,
      'PENDENTE'
    );
  });
}
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  fimDoDiaSP,
  conferenciaVinculada,
  avaliarRegistroCnh,
  normalizarNomeIdentidade,
  compararNomesIdentidade,
  construirPreAnalise,
  preAnaliseVinculada,
  identidadeDivergenteNaoResolvida
} from '../cnh.js';

const conf = { nome: 'Pessoa Ficticia Teste', categoria: 'B', validade: '2099-12-31', documentoConferido: true, qrConferidoVio: true };

test('cnhValidadeFimMs: último milissegundo do dia de validade em America/Sao_Paulo', () => {
  assert.equal(new Date(fimDoDiaSP('2026-11-18')).toISOString(), '2026-11-19T02:59:59.999Z');
  assert.equal(new Date(fimDoDiaSP('2026-02-28')).toISOString(), '2026-03-01T02:59:59.999Z');
  // Antes de 2019 havia horário de verão: o deslocamento vem do fuso, não de -03:00 fixo.
  assert.equal(new Date(fimDoDiaSP('2018-11-18')).toISOString(), '2018-11-19T01:59:59.999Z');
  assert.equal(new Date(fimDoDiaSP('2028-02-29')).toISOString(), '2028-03-01T02:59:59.999Z');
  assert.equal(fimDoDiaSP('2026-02-30'), null);
  assert.equal(fimDoDiaSP('18/11/2026'), null);
  assert.equal(fimDoDiaSP(undefined), null);
});

test('conferência só vale para a mesma versão e hash do PDF analisado', () => {
  const doc = { sha256: 'a'.repeat(64), versao: 2, arquivoPresente: true };
  assert.equal(conferenciaVinculada({ ...conf }), true, 'sem PDF recebido pelo app: fluxo administrativo anterior');
  assert.equal(conferenciaVinculada({ ...conf, documento: doc, pdfSha256: doc.sha256, pdfVersao: 2 }), true);
  assert.equal(conferenciaVinculada({ ...conf, documento: doc }), false, 'conferência anterior ao PDF');
  assert.equal(conferenciaVinculada({ ...conf, documento: doc, pdfSha256: 'b'.repeat(64), pdfVersao: 2 }), false, 'outro hash');
  assert.equal(conferenciaVinculada({ ...conf, documento: doc, pdfSha256: doc.sha256, pdfVersao: 1 }), false, 'mesmo hash, versão antiga (reenvio)');
  assert.equal(avaliarRegistroCnh({ ...conf, documento: doc, pdfSha256: doc.sha256, pdfVersao: 2 }).apta, true);
  const velha = avaliarRegistroCnh({ ...conf, documento: doc, pdfSha256: doc.sha256, pdfVersao: 1 });
  assert.equal(velha.apta, false);
  assert.equal(velha.situacao, 'revisao');
  assert.equal(velha.documentoDivergente, true);
});

test('normalização e comparação de identidade: acentos, espaços extras e caixa', () => {
  assert.equal(normalizarNomeIdentidade('  João   D\'Ávila-Silva  '), 'JOAO D AVILA SILVA');
  const ok = compararNomesIdentidade('PESSOA FICTICIA TESTE', '  Pessoa   Fictícia   Teste ');
  assert.equal(ok.status, 'compativel');
  assert.equal(ok.compativel, true);
  assert.equal(ok.divergente, false);

  const div = compararNomesIdentidade('PESSOA FICTICIA TESTE', 'Marina Oliveira');
  assert.equal(div.status, 'divergente');
  assert.equal(div.compativel, false);
  assert.equal(div.divergente, true);

  const semOcr = compararNomesIdentidade('', 'Marina Oliveira');
  assert.equal(semOcr.status, 'ausente');
  assert.equal(semOcr.divergente, false);
});

test('pré-análise automática: checklist visual, recomendações e bloqueio por divergência de identidade', () => {
  const sha = 'c'.repeat(64);
  const ocrOk = { sugestoes: { nome: 'PESSOA FICTICIA TESTE', categoria: 'B', validade: '2030-11-18' }, confianca: 93, qrDetectado: true, metodo: 'pdf_regioes' };

  // 1. Pré-análise favorável (tudo compatível, mas ainda exige conferência humana do Vio)
  const favoravel = construirPreAnalise({ ocrResultado: ocrOk, nomeCadastrado: 'Pessoa Fictícia Teste', pdfSha256: sha, pdfVersao: 1, hoje: '2026-11-24' });
  assert.equal(favoravel.recomendacao, 'pre_analise_favoravel');
  assert.equal(favoravel.divergenciaIdentidade, false);
  assert.equal(favoravel.revisaoHumanaObrigatoria, true);
  assert.equal(favoravel.checklist.length, 4);
  assert.equal(favoravel.checklist.find(i => i.item === 'identidade').status, 'ok');
  assert.equal(favoravel.checklist.find(i => i.item === 'categoria').status, 'ok');
  assert.equal(favoravel.checklist.find(i => i.item === 'validade').status, 'ok');
  assert.equal(favoravel.checklist.find(i => i.item === 'qr_vio').status, 'pendente_humano', 'QR detectado nunca significa autenticidade comprovada');

  // 2. Inconsistência de identidade (nome extraído diferente do cadastrado)
  const divergente = construirPreAnalise({ ocrResultado: ocrOk, nomeCadastrado: 'Outro Nome Cadastrado', pdfSha256: sha, pdfVersao: 1, hoje: '2026-11-24' });
  assert.equal(divergente.recomendacao, 'inconsistencias_encontradas');
  assert.equal(divergente.divergenciaIdentidade, true);
  assert.equal(divergente.checklist.find(i => i.item === 'identidade').status, 'erro');

  // 3. Inconsistência de categoria (ex.: A) ou validade vencida
  const catIncompativel = construirPreAnalise({ ocrResultado: { ...ocrOk, sugestoes: { ...ocrOk.sugestoes, categoria: 'A' } }, nomeCadastrado: 'Pessoa Fictícia Teste', pdfSha256: sha, pdfVersao: 1, hoje: '2026-11-24' });
  assert.equal(catIncompativel.recomendacao, 'inconsistencias_encontradas');
  assert.equal(catIncompativel.checklist.find(i => i.item === 'categoria').status, 'erro');

  // 4. Revisão necessária (campo não extraído pelo OCR)
  const incompleto = construirPreAnalise({ ocrResultado: { ...ocrOk, sugestoes: { nome: 'PESSOA FICTICIA TESTE', categoria: 'B', validade: '' } }, nomeCadastrado: 'Pessoa Fictícia Teste', pdfSha256: sha, pdfVersao: 1, hoje: '2026-11-24' });
  assert.equal(incompleto.recomendacao, 'revisao_necessaria');
  assert.equal(incompleto.checklist.find(i => i.item === 'validade').status, 'pendente');

  // 5. Vínculo da pré-análise à versão do PDF e bloqueio de aprovação por divergência não resolvida
  const doc = { sha256: sha, versao: 1, arquivoPresente: true };
  assert.equal(preAnaliseVinculada({ documento: doc, preAnalise: divergente }), true);
  assert.equal(preAnaliseVinculada({ documento: { ...doc, versao: 2 }, preAnalise: divergente }), false);

  const regComDivergencia = { ...conf, documento: doc, pdfSha256: sha, pdfVersao: 1, preAnalise: divergente, divergenciaIdentidadeConfirmada: false };
  assert.equal(identidadeDivergenteNaoResolvida(regComDivergencia, 'Outro Nome Cadastrado'), true);
  assert.equal(avaliarRegistroCnh(regComDivergencia, '2026-11-24', 'Outro Nome Cadastrado').apta, false);

  const regResolvido = { ...regComDivergencia, divergenciaIdentidadeConfirmada: true };
  assert.equal(identidadeDivergenteNaoResolvida(regResolvido, 'Outro Nome Cadastrado'), false);
  assert.equal(avaliarRegistroCnh(regResolvido, '2026-11-24', 'Outro Nome Cadastrado').apta, true);
});

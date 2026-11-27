// Testes do Split 1:1 (somente simulados): nenhuma chamada real ao Mercado Pago, sem dinheiro real.
// Eles NÃO homologam o split: a compatibilidade real (incluindo Pix) precisa de validação prática.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHmac, scryptSync } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

const tempSplit = mkdtempSync(join(tmpdir(), 'hitchhike-split-'));
process.env.DB_PATH = join(tempSplit, 'test.sqlite');
delete process.env.FIREBASE_SERVICE_ACCOUNT_PATH;

const { calcularSplit, conferirRepasse, lerTarifaEstimadaBp } = await import('../split.js');
const { cifrar, decifrar, lerChaveCifra, criarOauthMercadoPago } = await import('../mercadopagoOauth.js');
const { criarTratadorPagamentos } = await import('../pagamentos.js');
const { montarPagamento, definirProvedorParaTeste } = await import('../firebaseMotoristas.js');
const { server: servidorAdmin } = await import('../server.js');
const { db: sqliteAdmin } = await import('../db.js');

const CHAVE_HEX = 'ab'.repeat(32);
const SEGREDO = 'segredo-de-teste-do-webhook';
const TOKEN_PASSAGEIRO = 'token-firebase-de-teste-aaaaaaaaaaaaaaaa';
const TOKEN_MOTORISTA_MP = 'APP_USR-token-do-motorista-de-teste';
const REFRESH_MOTORISTA_MP = 'TG-refresh-do-motorista-de-teste';
const T0 = Date.parse('2026-11-15T12:00:00.000Z');

function criarFirestore(inicial) {
  const docs = new Map(Object.entries(inicial));
  let seq = 0;
  const lerDoc = chave => ({ exists: docs.has(chave), data: () => (docs.has(chave) ? { ...docs.get(chave) } : undefined) });
  return {
    docs,
    collection(nome) {
      const filtros = [];
      let limite = Infinity;
      const consulta = {
        doc(id = `auto${++seq}`) {
          const chave = `${nome}/${id}`;
          return {
            id,
            path: chave,
            get: async () => lerDoc(chave),
            set: async dados => { docs.set(chave, { ...dados }); },
            update: async campos => {
              if (!docs.has(chave)) throw new Error('documento inexistente');
              docs.set(chave, { ...docs.get(chave), ...campos });
            }
          };
        },
        where(campo, _op, valor) { filtros.push([campo, valor]); return consulta; },
        limit(n) { limite = n; return consulta; },
        async get() {
          const lista = [];
          for (const [chave, dado] of docs) {
            if (!chave.startsWith(`${nome}/`)) continue;
            if (filtros.every(([c, v]) => dado[c] === v)) lista.push({ id: chave.slice(nome.length + 1), data: () => ({ ...dado }) });
          }
          return { docs: lista.slice(0, limite) };
        }
      };
      return consulta;
    },
    async runTransaction(fn) {
      const preparado = new Map();
      const tx = {
        async get(ref) { return lerDoc(ref.path); },
        update(ref, campos) { preparado.set(ref.path, { ...docs.get(ref.path), ...campos }); }
      };
      const resultado = await fn(tx);
      for (const [chave, valor] of preparado) docs.set(chave, valor);
      return resultado;
    }
  };
}

const cenario = () => ({
  'usuarios/pass1': { status: 'ATIVO' },
  'usuarios/mot1': { status: 'ATIVO' },
  'caronas/carona1': { motoristaUid: 'mot1', valor: 20, status: 'ABERTA', dataHoraSaida: new Date(T0 + 3600_000), vagasDisponiveis: 2, vagasTotais: 3 },
  'solicitacoes_caronas/carona1_pass1': { caronaId: 'carona1', passageiroUid: 'pass1', motoristaUid: 'mot1', status: 'ACEITA' }
});

const respostaToken = (extra = {}) => ({
  status: 200,
  dados: { access_token: TOKEN_MOTORISTA_MP, refresh_token: REFRESH_MOTORISTA_MP, user_id: 555, expires_in: 15552000, live_mode: false, ...extra }
});

function montar({ respostasMp = [], configPag = {}, configOauth = {}, firestore } = {}) {
  const relogio = { t: T0 };
  const store = firestore ?? criarFirestore(cenario());
  const chamadas = [];
  const fila = [...respostasMp];
  const fetchFalso = async (url, opcoes = {}) => {
    chamadas.push({ url, headers: opcoes.headers || {}, corpo: opcoes.body ? JSON.parse(opcoes.body) : null });
    const resposta = fila.length ? fila.shift() : { status: 200, dados: {} };
    if (resposta.erroRede) throw new Error('rede');
    return { ok: resposta.status >= 200 && resposta.status < 300, status: resposta.status, json: async () => resposta.dados };
  };
  const agora = () => new Date(relogio.t);
  const oauth = criarOauthMercadoPago({
    banco: new DatabaseSync(':memory:'),
    fetch: fetchFalso,
    agora,
    config: () => ({
      clientId: 'cid-teste',
      clientSecret: 'segredo-oauth-de-teste',
      redirectUri: 'https://exemplo-teste.onrender.com/api/mercadopago/oauth/callback',
      chave: lerChaveCifra(CHAVE_HEX),
      ...configOauth
    })
  });
  const tratar = criarTratadorPagamentos({
    firestore: () => store,
    verificarToken: async token => {
      if (token !== TOKEN_PASSAGEIRO) throw new Error('inválido');
      return { uid: 'pass1', email_verified: true };
    },
    fetch: fetchFalso,
    agora,
    oauth,
    config: () => ({
      accessToken: 'TOKEN-PLATAFORMA-DE-TESTE', webhookSecret: SEGREDO, baseUrl: 'https://exemplo-teste.onrender.com',
      urlCheckout: 'init_point', splitAtivo: true, tarifaBp: 99, restringirPix: true, ...configPag
    })
  });
  const servidor = http.createServer((req, res) => tratar(req, res, new URL(req.url, 'http://localhost')));
  return { store, chamadas, fila, oauth, servidor, relogio };
}

async function vincular(oauth, fila, resposta = respostaToken()) {
  fila.push(resposta);
  const url = new URL(await oauth.iniciar('mot1'));
  return oauth.concluir('codigo-de-autorizacao-1', url.searchParams.get('state'));
}

async function iniciar(servidor) {
  await new Promise(r => servidor.listen(0, '127.0.0.1', r));
  return `http://127.0.0.1:${servidor.address().port}`;
}
const encerrar = servidor => new Promise(r => servidor.close(r));

const preferencia = base => fetch(`${base}/api/pagamentos/preferencia`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN_PASSAGEIRO}` },
  body: JSON.stringify({ caronaId: 'carona1' })
});

function assinar(dataId, requestId = 'req-1', ts = '1731672000') {
  const manifesto = `id:${dataId};request-id:${requestId};ts:${ts};`;
  return { 'x-signature': `ts=${ts},v1=${createHmac('sha256', SEGREDO).update(manifesto).digest('hex')}`, 'x-request-id': requestId };
}

const webhook = (base, dataId, pid = 'auto1') => fetch(`${base}/api/pagamentos/webhook?type=payment&data.id=${dataId}${pid ? `&pid=${pid}` : ''}`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', ...assinar(dataId) },
  body: JSON.stringify({ type: 'payment', data: { id: dataId } })
});

const pagamentoMp = (extra = {}) => ({
  id: 987654321,
  status: 'approved',
  external_reference: 'auto1',
  transaction_amount: 25,
  currency_id: 'BRL',
  live_mode: false,
  collector_id: 555,
  date_last_updated: '2026-11-15T12:05:00.000-03:00',
  fee_details: [
    { type: 'mercadopago_fee', amount: 0.25, fee_payer: 'collector' },
    { type: 'application_fee', amount: 4.75, fee_payer: 'collector' }
  ],
  transaction_details: { net_received_amount: 20 },
  ...extra
});

async function prepararSplit(opcoes) {
  const ctx = montar(opcoes);
  await vincular(ctx.oauth, ctx.fila);
  ctx.fila.length = 0;
  ctx.chamadas.length = 0;
  const base = await iniciar(ctx.servidor);
  ctx.fila.push({ status: 200, dados: { id: 'pref-split-1', init_point: 'https://www.mercadopago.com.br/checkout/v1/redirect?pref_id=pref-split-1' } });
  const r = await preferencia(base);
  assert.equal(r.status, 201);
  return { ...ctx, base };
}

test('split: comissão de 25% menos tarifa estimada preserva o valor previsto do motorista', () => {
  const calc = calcularSplit({ baseCentavos: 2000, taxaCentavos: 500, tarifaBp: 99 });
  assert.equal(calc.viavel, true);
  assert.equal(calc.totalCentavos, 2500);
  assert.equal(calc.tarifaEstimadaCentavos, 25); // 24,75 arredondado para cima
  assert.equal(calc.marketplaceFeeCentavos, 475);
  assert.equal(calc.motoristaPrevistoCentavos, 2000);
  assert.equal(calc.estimado, true);
  // Tarifa maior: o motorista continua protegido na estimativa, quem absorve é o Hitchhike.
  const alta = calcularSplit({ baseCentavos: 2000, taxaCentavos: 500, tarifaBp: 499 });
  assert.equal(alta.marketplaceFeeCentavos, 500 - 125);
  assert.equal(alta.motoristaPrevistoCentavos, 2000);
});

test('split: valores pequenos, arredondamento e inviabilidade', () => {
  const minimo = calcularSplit({ baseCentavos: 250, taxaCentavos: 63, tarifaBp: 99 });
  assert.equal(minimo.totalCentavos, 313);
  assert.equal(minimo.tarifaEstimadaCentavos, 4);
  assert.equal(minimo.marketplaceFeeCentavos, 59);
  assert.equal(minimo.motoristaPrevistoCentavos, 250);
  assert.equal(calcularSplit({ baseCentavos: 250, taxaCentavos: 63, tarifaBp: 2000 }).viavel, false);
  assert.equal(calcularSplit({ baseCentavos: 250.5, taxaCentavos: 63, tarifaBp: 99 }).viavel, false);
  assert.equal(calcularSplit({ baseCentavos: 250, taxaCentavos: 63, tarifaBp: -1 }).viavel, false);
});

test('tarifa estimada: só aceita percentual explícito e válido', () => {
  assert.equal(lerTarifaEstimadaBp('0.99'), 99);
  assert.equal(lerTarifaEstimadaBp('0,99'), 99);
  assert.equal(lerTarifaEstimadaBp('1'), 100);
  for (const invalido of ['', undefined, 'abc', '-1', '25', '1.234', null]) assert.equal(lerTarifaEstimadaBp(invalido), null);
});

test('conferência do repasse: conferida, divergente e indisponível', () => {
  assert.equal(conferirRepasse(pagamentoMp(), 2000).situacao, 'CONFERIDA');
  const divergente = conferirRepasse(pagamentoMp({ transaction_details: { net_received_amount: 19 } }), 2000);
  assert.equal(divergente.situacao, 'DIVERGENTE');
  assert.equal(divergente.diferencaMotoristaCentavos, -100);
  assert.equal(conferirRepasse(pagamentoMp({ fee_details: [] }), 2000).situacao, 'INDISPONIVEL');
  assert.equal(conferirRepasse({}, 2000).situacao, 'INDISPONIVEL');
});

test('cifra de tokens: ida e volta, chave inválida e adulteração', () => {
  const chave = lerChaveCifra(CHAVE_HEX);
  const guardado = cifrar(TOKEN_MOTORISTA_MP, chave);
  assert.ok(!guardado.includes(TOKEN_MOTORISTA_MP));
  assert.equal(decifrar(guardado, chave), TOKEN_MOTORISTA_MP);
  assert.equal(lerChaveCifra('curta'), null);
  assert.equal(lerChaveCifra(undefined), null);
  const partes = guardado.split(':');
  partes[3] = Buffer.from('adulterado').toString('base64');
  assert.throws(() => decifrar(partes.join(':'), chave));
  assert.throws(() => decifrar(guardado, lerChaveCifra('cd'.repeat(32))));
});

test('oauth: vínculo guarda tokens cifrados e o state é de uso único', async () => {
  const { oauth, fila, chamadas } = montar();
  const url = new URL(await oauth.iniciar('mot1'));
  assert.equal(url.origin + url.pathname, 'https://auth.mercadopago.com.br/authorization');
  assert.equal(url.searchParams.get('client_id'), 'cid-teste');
  assert.equal(url.searchParams.get('redirect_uri'), 'https://exemplo-teste.onrender.com/api/mercadopago/oauth/callback');
  const state = url.searchParams.get('state');
  assert.match(state, /^[a-f0-9]{64}$/);
  fila.push(respostaToken());
  await oauth.concluir('codigo-de-autorizacao-1', state);
  assert.equal(await oauth.vinculado('mot1'), true);
  assert.equal(chamadas[0].corpo.grant_type, 'authorization_code');
  await assert.rejects(() => oauth.concluir('codigo-de-autorizacao-1', state), e => e.codigo === 'state_expirado');
  const conta = await oauth.obterContaMotorista('mot1');
  assert.equal(conta.accessToken, TOKEN_MOTORISTA_MP);
  assert.equal(conta.collectorId, '555');
  assert.equal(await oauth.obterContaMotorista('outro'), null);
});

test('oauth: falhas de autorização, state expirado, produção e indisponibilidade', async () => {
  const a = montar();
  const stateA = new URL(await a.oauth.iniciar('mot1')).searchParams.get('state');
  a.fila.push({ status: 400, dados: { message: 'invalid_grant' } });
  await assert.rejects(() => a.oauth.concluir('codigo-de-autorizacao-1', stateA), e => e.codigo === 'autorizacao_recusada');
  assert.equal(await a.oauth.vinculado('mot1'), false);

  await assert.rejects(() => a.oauth.concluir('codigo-de-autorizacao-1', 'f'.repeat(64)), e => e.codigo === 'state_expirado');
  await assert.rejects(() => a.oauth.concluir('codigo-de-autorizacao-1', 'invalido'), e => e.codigo === 'state_invalido');
  await assert.rejects(() => a.oauth.concluir('<x>', stateA), e => e.codigo === 'codigo_invalido');

  const expirado = montar();
  const stateE = new URL(await expirado.oauth.iniciar('mot1')).searchParams.get('state');
  expirado.relogio.t += 11 * 60 * 1000;
  await assert.rejects(() => expirado.oauth.concluir('codigo-de-autorizacao-1', stateE), e => e.codigo === 'state_expirado');

  const producao = montar();
  await assert.rejects(() => vincular(producao.oauth, producao.fila, respostaToken({ live_mode: true })), e => e.codigo === 'producao_bloqueada');
  assert.equal(await producao.oauth.vinculado('mot1'), false);

  const fora = montar();
  await assert.rejects(() => vincular(fora.oauth, fora.fila, { erroRede: true }), e => e.codigo === 'mp_indisponivel');
  const erro500 = montar();
  await assert.rejects(() => vincular(erro500.oauth, erro500.fila, { status: 500, dados: {} }), e => e.codigo === 'mp_indisponivel');

  const semConfig = montar({ configOauth: { clientSecret: '' } });
  await assert.rejects(() => semConfig.oauth.iniciar('mot1'), e => e.status === 503);
});

test('oauth: token perto de expirar é renovado e segue cifrado', async () => {
  const { oauth, fila, chamadas } = montar();
  await vincular(oauth, fila, respostaToken({ expires_in: 60 }));
  fila.push(respostaToken({ access_token: 'APP_USR-token-renovado', refresh_token: 'TG-refresh-renovado', expires_in: 15552000 }));
  const conta = await oauth.obterContaMotorista('mot1');
  assert.equal(conta.accessToken, 'APP_USR-token-renovado');
  assert.equal(chamadas.at(-1).corpo.grant_type, 'refresh_token');
  assert.equal(chamadas.at(-1).corpo.refresh_token, REFRESH_MOTORISTA_MP);
  assert.equal((await oauth.obterContaMotorista('mot1')).accessToken, 'APP_USR-token-renovado');
  assert.equal(chamadas.length, 2); // sem nova renovação
});

test('oauth: rotas HTTP exigem login e o callback não vaza detalhes', async () => {
  const ctx = montar();
  const base = await iniciar(ctx.servidor);
  try {
    assert.equal((await fetch(`${base}/api/mercadopago/oauth/iniciar`, { method: 'POST' })).status, 401);
    assert.equal((await fetch(`${base}/api/mercadopago/oauth/status`)).status, 401);
    const iniciada = await fetch(`${base}/api/mercadopago/oauth/iniciar`, { method: 'POST', headers: { Authorization: `Bearer ${TOKEN_PASSAGEIRO}` } });
    assert.equal(iniciada.status, 200);
    assert.match((await iniciada.json()).authUrl, /^https:\/\/auth\.mercadopago\.com\.br\/authorization\?/);
    const negado = await fetch(`${base}/api/mercadopago/oauth/callback?error=access_denied`);
    assert.equal(negado.status, 400);
    assert.ok(!(await negado.text()).includes('access_denied'));
    const invalido = await fetch(`${base}/api/mercadopago/oauth/callback?code=abcdef&state=${'0'.repeat(64)}`);
    assert.equal(invalido.status, 400);
    const status = await (await fetch(`${base}/api/mercadopago/oauth/status`, { headers: { Authorization: `Bearer ${TOKEN_PASSAGEIRO}` } })).json();
    assert.equal(status.vinculado, false);
    assert.equal(status.splitAtivo, false); // sem a variável de ambiente o split está desligado
  } finally { await encerrar(ctx.servidor); }
});

test('preferência com split: token do motorista, marketplace_fee e Pix', async () => {
  const ctx = await prepararSplit();
  try {
    const chamada = ctx.chamadas[0];
    assert.equal(chamada.headers.Authorization, `Bearer ${TOKEN_MOTORISTA_MP}`);
    assert.equal(chamada.corpo.marketplace_fee, 4.75);
    assert.equal(chamada.corpo.items[0].unit_price, 25);
    assert.ok(chamada.corpo.payment_methods.excluded_payment_types.includes('credit_card'));
    assert.ok(!chamada.corpo.payment_methods.excluded_payment_types.includes('bank_transfer'));
    assert.equal(chamada.corpo.notification_url, 'https://exemplo-teste.onrender.com/api/pagamentos/webhook?pid=auto1');
    const doc = ctx.store.docs.get('pagamentos/auto1');
    assert.equal(doc.split, true);
    assert.equal(doc.mpCollectorId, '555');
    assert.equal(doc.marketplaceFeeCentavos, 475);
    assert.equal(doc.tarifaEstimadaCentavos, 25);
    assert.equal(doc.motoristaPrevistoCentavos, 2000);
    assert.equal(doc.reconciliacao, 'PENDENTE');
    assert.ok(!JSON.stringify(doc).includes(TOKEN_MOTORISTA_MP));
  } finally { await encerrar(ctx.servidor); }
});

test('preferência: split desligado mantém o fluxo atual com o token da plataforma', async () => {
  const ctx = montar({ configPag: { splitAtivo: false } });
  const base = await iniciar(ctx.servidor);
  try {
    ctx.fila.push({ status: 200, dados: { id: 'pref-1', init_point: 'https://www.mercadopago.com.br/x' } });
    assert.equal((await preferencia(base)).status, 201);
    assert.equal(ctx.chamadas[0].headers.Authorization, 'Bearer TOKEN-PLATAFORMA-DE-TESTE');
    assert.equal(ctx.chamadas[0].corpo.marketplace_fee, undefined);
    assert.equal(ctx.chamadas[0].corpo.payment_methods, undefined);
    assert.equal(ctx.chamadas[0].corpo.notification_url, 'https://exemplo-teste.onrender.com/api/pagamentos/webhook');
    assert.equal(ctx.store.docs.get('pagamentos/auto1').split, false);
  } finally { await encerrar(ctx.servidor); }
});

test('preferência com split: motorista sem conta vinculada e configuração incompleta', async () => {
  const sem = montar();
  const baseSem = await iniciar(sem.servidor);
  try {
    const r = await preferencia(baseSem);
    assert.equal(r.status, 409);
    assert.match((await r.json()).error, /vinculou/);
    assert.equal(sem.chamadas.length, 0);
    assert.equal(sem.store.docs.has('pagamentos/auto1'), false);
  } finally { await encerrar(sem.servidor); }
  const semTarifa = montar({ configPag: { tarifaBp: null } });
  const baseTarifa = await iniciar(semTarifa.servidor);
  try {
    assert.equal((await preferencia(baseTarifa)).status, 503);
    assert.equal(semTarifa.chamadas.length, 0);
  } finally { await encerrar(semTarifa.servidor); }
});

test('preferência com split: falha do Mercado Pago não cria registro e carona expirada/concluída é bloqueada', async () => {
  const ctx = montar();
  await vincular(ctx.oauth, ctx.fila);
  ctx.fila.length = 0;
  const base = await iniciar(ctx.servidor);
  try {
    ctx.fila.push({ status: 500, dados: {} });
    assert.equal((await preferencia(base)).status, 502);
    assert.equal(ctx.store.docs.has('pagamentos/auto1'), false);

    // Regras atuais do ciclo de vida das caronas: carona concluída ou expirada não aceita pagamento
    ctx.store.docs.set('caronas/carona1', { motoristaUid: 'mot1', valor: 20, status: 'CONCLUIDA', dataHoraSaida: new Date(T0 - 7200_000) });
    assert.equal((await preferencia(base)).status, 409);
  } finally { await encerrar(ctx.servidor); }
});

test('webhook com split: aprova pela API, concilia o repasse e ignora duplicados', async () => {
  const ctx = await prepararSplit();
  try {
    ctx.fila.push({ status: 200, dados: pagamentoMp() });
    const r = await webhook(ctx.base, '987654321');
    assert.equal(r.status, 200);
    assert.equal((await r.json()).resultado, 'APROVADO');
    assert.equal(ctx.chamadas.at(-1).headers.Authorization, `Bearer ${TOKEN_MOTORISTA_MP}`);
    const doc = ctx.store.docs.get('pagamentos/auto1');
    assert.equal(doc.status, 'APROVADO');
    assert.equal(doc.reconciliacao, 'CONFERIDA');
    assert.equal(doc.liquidoMotoristaCentavos, 2000);
    assert.equal(doc.tarifaRealCentavos, 25);
    assert.equal(doc.comissaoRealCentavos, 475);
    assert.equal(doc.divergenciaFinanceira, false);
    const aprovadoEm = doc.aprovadoEm;
    ctx.fila.push({ status: 200, dados: pagamentoMp() });
    const repetido = await webhook(ctx.base, '987654321');
    assert.equal((await repetido.json()).resultado, 'ja_aprovado');
    assert.deepEqual(ctx.store.docs.get('pagamentos/auto1').aprovadoEm, aprovadoEm);
  } finally { await encerrar(ctx.servidor); }
});

test('webhook com split: líquido menor que o prometido gera divergência financeira', async () => {
  const ctx = await prepararSplit();
  try {
    ctx.fila.push({ status: 200, dados: pagamentoMp({ transaction_details: { net_received_amount: 19 } }) });
    await webhook(ctx.base, '987654321');
    const doc = ctx.store.docs.get('pagamentos/auto1');
    assert.equal(doc.status, 'APROVADO');
    assert.equal(doc.reconciliacao, 'DIVERGENTE');
    assert.equal(doc.divergenciaFinanceira, true);
    assert.equal(doc.diferencaMotoristaCentavos, -100);
  } finally { await encerrar(ctx.servidor); }
});

test('webhook com split: sem fee_details a conferência fica indisponível e é completada depois', async () => {
  const ctx = await prepararSplit();
  try {
    ctx.fila.push({ status: 200, dados: pagamentoMp({ fee_details: [], transaction_details: {} }) });
    await webhook(ctx.base, '987654321');
    assert.equal(ctx.store.docs.get('pagamentos/auto1').reconciliacao, 'INDISPONIVEL');
    ctx.fila.push({ status: 200, dados: pagamentoMp() });
    await webhook(ctx.base, '987654321');
    const doc = ctx.store.docs.get('pagamentos/auto1');
    assert.equal(doc.status, 'APROVADO');
    assert.equal(doc.reconciliacao, 'CONFERIDA');
  } finally { await encerrar(ctx.servidor); }
});

test('webhook com split: recebedor diferente, recusado, pendente e falhas do Mercado Pago', async () => {
  const outroColetor = await prepararSplit();
  try {
    outroColetor.fila.push({ status: 200, dados: pagamentoMp({ collector_id: 999 }) });
    await webhook(outroColetor.base, '987654321');
    assert.equal(outroColetor.store.docs.get('pagamentos/auto1').status, 'DIVERGENTE');
  } finally { await encerrar(outroColetor.servidor); }

  const recusado = await prepararSplit();
  try {
    recusado.fila.push({ status: 200, dados: pagamentoMp({ status: 'rejected', fee_details: [], transaction_details: {} }) });
    await webhook(recusado.base, '987654321');
    assert.equal(recusado.store.docs.get('pagamentos/auto1').status, 'RECUSADO');
  } finally { await encerrar(recusado.servidor); }

  const pendente = await prepararSplit();
  try {
    pendente.fila.push({ status: 200, dados: pagamentoMp({ status: 'pending', fee_details: [], transaction_details: {} }) });
    await webhook(pendente.base, '987654321');
    const doc = pendente.store.docs.get('pagamentos/auto1');
    assert.equal(doc.status, 'EM_PROCESSAMENTO');
    assert.equal(doc.reconciliacao, 'PENDENTE');
  } finally { await encerrar(pendente.servidor); }

  const fora = await prepararSplit();
  try {
    fora.fila.push({ status: 500, dados: {} });
    assert.equal((await webhook(fora.base, '987654321')).status, 502);
    assert.equal(fora.store.docs.get('pagamentos/auto1').status, 'PENDENTE');
    fora.fila.push({ erroRede: true });
    assert.equal((await webhook(fora.base, '987654321')).status, 502);
  } finally { await encerrar(fora.servidor); }
});

test('painel: linha financeira separa estimado e conferido sem expor identificadores de conta', () => {
  const linha = montarPagamento('auto1', {
    caronaId: 'carona1', status: 'APROVADO', split: true, somentePix: true, valorBaseCentavos: 2000, taxaCentavos: 500, totalCentavos: 2500,
    tarifaEstimadaCentavos: 25, marketplaceFeeCentavos: 475, motoristaPrevistoCentavos: 2000, reconciliacao: 'CONFERIDA',
    tarifaRealCentavos: 25, comissaoRealCentavos: 475, liquidoMotoristaCentavos: 2000, passageiroUid: 'pass1', motoristaUid: 'mot1', mpCollectorId: '555'
  });
  assert.equal(linha.totalCentavos, 2500);
  assert.equal(linha.liquidoMotoristaCentavos, 2000);
  assert.equal(linha.passageiroUid, undefined);
  assert.equal(linha.motoristaUid, undefined);
  assert.equal(linha.mpCollectorId, undefined);
  assert.equal(montarPagamento('x', null), null);
});

test('endpoint administrativo /api/pagamentos-admin: exige sessão de administrador, é somente leitura e oculta dados sensíveis', async () => {
  sqliteAdmin.prepare('INSERT OR IGNORE INTO admins VALUES(99,?,?,?)').run(
    'finadmin@example.com',
    'fin-salt',
    scryptSync('FinAdminPassword123', 'fin-salt', 64).toString('hex')
  );
  const base = await iniciar(servidorAdmin);
  try {
    assert.equal((await fetch(`${base}/api/pagamentos-admin`)).status, 401);
    assert.equal((await fetch(`${base}/api/pagamentos-admin`, { method: 'PUT' })).status, 405);

    const login = await fetch(`${base}/api/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'finadmin@example.com', password: 'FinAdminPassword123' })
    });
    assert.equal(login.status, 200);
    const cookie = login.headers.get('set-cookie').split(';')[0];

    // Mesmo autenticado, POST em /api/pagamentos-admin não é permitido (somente leitura GET)
    assert.equal((await fetch(`${base}/api/pagamentos-admin`, {
      method: 'POST',
      headers: { cookie, 'Content-Type': 'application/json' },
      body: '{}'
    })).status, 404);

    const fakeDocs = new Map([
      ['pagamentos/pag1', {
        caronaId: 'carona1', status: 'APROVADO', mpStatus: 'approved', ambiente: 'TESTE',
        split: true, somentePix: true, valorBaseCentavos: 2000, taxaCentavos: 500, totalCentavos: 2500,
        tarifaEstimadaCentavos: 25, tarifaEstimadaBp: 99, marketplaceFeeCentavos: 475, motoristaPrevistoCentavos: 2000,
        reconciliacao: 'CONFERIDA', tarifaRealCentavos: 25, comissaoRealCentavos: 475, liquidoMotoristaCentavos: 2000,
        diferencaMotoristaCentavos: 0, divergenciaFinanceira: false,
        passageiroUid: 'uid-secreto-passageiro', motoristaUid: 'uid-secreto-motorista', mpCollectorId: '999888',
        access_token: 'TOKEN-SECRETO-NAO-VAZAR', criadoEm: new Date('2026-11-18T10:00:00.000Z')
      }]
    ]);
    definirProvedorParaTeste(async () => ({
      collection(nome) {
        assert.equal(nome, 'pagamentos');
        const c = {
          orderBy() { return c; },
          limit() { return c; },
          async get() {
            return {
              docs: [...fakeDocs.entries()].map(([k, v]) => ({ id: k.split('/')[1], data: () => ({ ...v }) }))
            };
          }
        };
        return c;
      }
    }));

    const r = await fetch(`${base}/api/pagamentos-admin`, { headers: { cookie } });
    assert.equal(r.status, 200);
    const corpo = await r.json();
    assert.equal(corpo.disponivel, true);
    assert.equal(corpo.pagamentos.length, 1);
    assert.equal(corpo.pagamentos[0].id, 'pag1');
    assert.equal(corpo.pagamentos[0].totalCentavos, 2500);
    assert.equal(corpo.pagamentos[0].tarifaEstimadaCentavos, 25);
    assert.equal(corpo.pagamentos[0].liquidoMotoristaCentavos, 2000);
    const bruto = JSON.stringify(corpo);
    assert.doesNotMatch(bruto, /uid-secreto-passageiro|uid-secreto-motorista|999888|TOKEN-SECRETO-NAO-VAZAR/);
  } finally {
    definirProvedorParaTeste(null);
    await encerrar(servidorAdmin);
    sqliteAdmin.close();
    rmSync(tempSplit, { recursive: true, force: true });
  }
});

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, rmSync, readdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes, scryptSync } from 'node:crypto';

// Captura toda saída de log para provar que PDFs, Base64, tokens e chaves nunca são registrados.
const logs = [];
for (const nivel of ['log', 'warn', 'error']) console[nivel] = (...a) => { logs.push(a.map(String).join(' ')); };

const temp = mkdtempSync(join(tmpdir(), 'hh-cnh-api-'));
process.env.DB_PATH = join(temp, 'test.sqlite');
delete process.env.FIREBASE_SERVICE_ACCOUNT_PATH;
delete process.env.CNH_STORAGE_KEY;
const { db } = await import('../db.js');
const { server } = await import('../server.js');
const { definirProvedorParaTeste, definirAuditoriaParaTeste } = await import('../firebaseMotoristas.js');
const { criarArmazenamento, definirArmazenamentoParaTeste, sha256Hex } = await import('../cnhStorage.js');
const { definirTratadorParaTeste } = await import('../cnhMotoristaApi.js');
const { servicoDocumentos } = await import('../cnhDocumentos.js');
const { validarPdfCnh } = await import('../ocr.js');
const { fimDoDiaSP } = await import('../cnh.js');
const { criarFirestoreFalso } = await import('./helpers/firestoreFalso.js');

const fixture = nome => readFileSync(new URL(`./fixtures/${nome}`, import.meta.url));
const PDF = fixture('cnh-sintetica.pdf');
const PDF_RENOVADO = Buffer.concat([PDF, Buffer.from('\n% renovacao ficticia\n')]);
const CHAVE = randomBytes(32).toString('hex');
const DIR = join(temp, 'cnh_pdfs');
const LIMITES_FOLGADOS = { envio: { max: 1000, janelaMs: 3_600_000, intervaloMs: 0 }, consulta: { max: 10000, janelaMs: 60_000 } };

const TOKENS = {
  'token-ficticio-ana-0000000001': { uid: 'uidAna', email: 'ana@senacsp.edu.br', email_verified: true },
  'token-ficticio-bia-0000000002': { uid: 'uidBia', email: 'bia@sp.senac.br', email_verified: true },
  'token-ficticio-nao-verificado': { uid: 'uidNv', email: 'nv@senacsp.edu.br', email_verified: false },
  'token-ficticio-gmail-000000001': { uid: 'uidGm', email: 'gm@gmail.com', email_verified: true },
  'token-ficticio-inativo-0000001': { uid: 'uidIn', email: 'in@senacsp.edu.br', email_verified: true },
  'token-ficticio-sem-perfil-0001': { uid: 'uidSp', email: 'sp@senacsp.edu.br', email_verified: true },
  'token-ficticio-email-outro-001': { uid: 'uidEo', email: 'eo@senacsp.edu.br', email_verified: true }
};
const ANA = 'token-ficticio-ana-0000000001';
const BIA = 'token-ficticio-bia-0000000002';
const perfil = (uid, email, extra = {}) => [`usuarios/${uid}`, { nome: 'Pessoa Ficticia Teste', email, status: 'ATIVO', papelAtivo: 'PASSAGEIRO', condutorValidado: false, ...extra }];
const VEICULO = { placa: 'ABC1D23', modelo: 'Fit', cor: 'Prata' };

let fs_, base, cookie, armazenamento, auditoria;
async function reiniciar(extra = []) {
  await servicoDocumentos.aguardarPreAnalise();
  fs_ = criarFirestoreFalso([
    perfil('uidAna', 'ana@senacsp.edu.br'), perfil('uidBia', 'bia@sp.senac.br'), perfil('uidNv', 'nv@senacsp.edu.br'),
    perfil('uidGm', 'gm@gmail.com'), perfil('uidIn', 'in@senacsp.edu.br', { status: 'INATIVO' }),
    perfil('uidEo', 'outro@senacsp.edu.br'), ...extra
  ]);
  definirProvedorParaTeste(() => fs_);
  rmSync(DIR, { recursive: true, force: true });
  armazenamento = criarArmazenamento({ diretorio: DIR, chave: CHAVE });
  definirArmazenamentoParaTeste(armazenamento);
  auditoria = [];
  definirAuditoriaParaTeste((...a) => auditoria.push(a));
  definirTratadorParaTeste({ verificarToken: async t => { if (!TOKENS[t]) throw new Error('inválido'); return TOKENS[t]; }, limites: LIMITES_FOLGADOS });
}
const arquivosCifrados = () => {
  const lista = [];
  if (existsSync(DIR)) for (const p of readdirSync(DIR)) for (const n of readdirSync(join(DIR, p))) lista.push(join(DIR, p, n));
  return lista;
};
async function chamar(metodo, caminho, { token, body, tipo, headers = {}, sessao = false } = {}) {
  const cabecalhos = { ...headers };
  if (token) cabecalhos.Authorization = `Bearer ${token}`;
  if (sessao) cabecalhos.cookie = cookie;
  let corpo = body;
  if (body !== undefined && !Buffer.isBuffer(body) && typeof body !== 'string') { corpo = JSON.stringify(body); cabecalhos['Content-Type'] = 'application/json'; }
  if (tipo) cabecalhos['Content-Type'] = tipo;
  return fetch(base + caminho, { method: metodo, headers: cabecalhos, body: corpo });
}
const dataUrl = buffer => `data:application/pdf;base64,${buffer.toString('base64')}`;
const enviar = (token, pdf = PDF, extra = VEICULO) => chamar('POST', '/api/motoristas/cnh', { token, body: { pdf: dataUrl(pdf), ...extra } });
const registro = uid => fs_.docs.get(`cnh_admin/${uid}`);

before(async () => {
  db.prepare('INSERT INTO admins VALUES(1,?,?,?)').run('admin@example.com', 'salt-ficticio', scryptSync('SenhaAdminFicticia123', 'salt-ficticio', 64).toString('hex'));
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  const r = await fetch(`${base}/api/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: 'admin@example.com', password: 'SenhaAdminFicticia123' }) });
  assert.equal(r.status, 200);
  cookie = r.headers.get('set-cookie').split(';')[0];
});
after(async () => {
  definirTratadorParaTeste(null);
  definirProvedorParaTeste(null);
  await new Promise(r => server.close(r));
  db.close();
  rmSync(temp, { recursive: true, force: true });
});

test('autenticação: token ausente/inválido, e-mail não verificado ou não institucional, perfil inativo', async () => {
  await reiniciar();
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status')).status, 401);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: 'token-invalido-0000000000000' })).status, 401);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { headers: { Authorization: 'Basic abc' } })).status, 401);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: 'token-ficticio-nao-verificado' })).status, 403);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: 'token-ficticio-gmail-000000001' })).status, 403);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: 'token-ficticio-inativo-0000001' })).status, 403);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: 'token-ficticio-sem-perfil-0001' })).status, 403);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: 'token-ficticio-email-outro-001' })).status, 403, 'e-mail do perfil diferente do token');
  assert.equal((await chamar('POST', '/api/motoristas/cnh', { body: { pdf: dataUrl(PDF), ...VEICULO } })).status, 401);
  assert.equal((await chamar('DELETE', '/api/motoristas/cnh/arquivo')).status, 401);
  assert.equal((await chamar('PUT', '/api/motoristas/cnh', { token: ANA })).status, 405);
  assert.equal((await chamar('GET', '/api/motoristas/outra', { token: ANA })).status, 404);
  assert.equal(arquivosCifrados().length, 0);
});

test('upload autenticado: grava cifrado, abre solicitação PENDENTE e retira aprovação anterior', async () => {
  await reiniciar();
  fs_.docs.set('usuarios/uidAna', { ...fs_.docs.get('usuarios/uidAna'), condutorValidado: true, papelAtivo: 'MOTORISTA' });
  const r = await enviar(ANA, PDF, { ...VEICULO, uid: 'uidBia', motoristaUid: 'uidBia' });
  assert.equal(r.status, 201);
  assert.equal(r.headers.get('cache-control'), 'no-store');
  const corpo = await r.json();
  assert.equal(corpo.status, 'PENDENTE');
  assert.equal(corpo.versao, 1);
  assert.equal(corpo.aprovacaoRetirada, true);
  // O UID vem só do token: nada foi criado para o UID informado no corpo.
  assert.equal(fs_.docs.has('solicitacoes_motoristas/uidBia'), false);
  assert.equal(registro('uidBia'), undefined);
  const pedido = fs_.docs.get('solicitacoes_motoristas/uidAna');
  assert.equal(pedido.status, 'PENDENTE');
  assert.equal(pedido.motoristaUid, 'uidAna');
  assert.equal(pedido.placa, 'ABC1D23');
  assert.equal(pedido.cnhPdfRecebido, true);
  const usuario = fs_.docs.get('usuarios/uidAna');
  assert.equal(usuario.condutorValidado, false);
  assert.equal(usuario.papelAtivo, 'PASSAGEIRO');
  assert.equal(usuario.cnhRevisaoNecessaria, true);
  assert.equal(registro('uidAna').documento.sha256, sha256Hex(PDF));
  assert.equal(registro('uidAna').documento.bytes, PDF.length);
  assert.ok([...fs_.docs.keys()].some(k => k.startsWith('cnh_eventos/')));
  const [arquivo] = arquivosCifrados();
  assert.equal(arquivosCifrados().length, 1);
  assert.equal(readFileSync(arquivo).includes('%PDF-'), false, 'nada em texto claro no disco');
  const st = await (await chamar('GET', '/api/motoristas/cnh/status', { token: ANA })).json();
  assert.equal(st.solicitacao, 'PENDENTE');
  assert.equal(st.documento.recebido, true);
  assert.equal(st.documento.arquivoPresente, true);
  assert.equal(st.conferencia.situacao, 'aguardando_conferencia');
  assert.equal(st.condutorValidado, false);
});

test('upload binário (application/pdf) em solicitação existente e exigência de dados do veículo na criação', async () => {
  await reiniciar();
  assert.equal((await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: PDF, tipo: 'application/pdf' })).status, 400, 'sem solicitação e sem veículo');
  assert.equal(arquivosCifrados().length, 0, 'falha de regra não deixa arquivo órfão');
  assert.equal((await enviar(ANA, PDF, { placa: 'X', modelo: 'Fit', cor: 'Prata' })).status, 400);
  assert.equal((await enviar(ANA)).status, 201);
  const r = await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: PDF_RENOVADO, tipo: 'application/pdf' });
  assert.equal(r.status, 201);
  assert.equal((await r.json()).versao, 2);
});

test('validação do PDF: imagens, arquivos inválidos, senha, múltiplas páginas, sem QR e tamanho', async () => {
  await reiniciar();
  const png = fixture('ocr-sintetico.png');
  const casos = [
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: { pdf: `data:image/png;base64,${png.toString('base64')}`, ...VEICULO } }), 415],
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: { pdf: `data:image/jpeg;base64,${png.toString('base64')}`, ...VEICULO } }), 415],
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: png, tipo: 'image/png' }), 415],
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: png, tipo: 'text/plain' }), 415],
    [await enviar(ANA, png), 400], // imagem renomeada como PDF
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: Buffer.from('conteudo qualquer'), tipo: 'application/pdf' }), 400],
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: '{quebrado', tipo: 'application/json' }), 400],
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: { semPdf: true } }), 400],
    [await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: { pdf: 'data:application/pdf;base64,@@@' } }), 400],
    [await enviar(ANA, Buffer.from('%PDF-1.4\nlixo sem estrutura')), 422],
    [await enviar(ANA, fixture('cnh-sem-qr.pdf')), 422],
    [await enviar(ANA, fixture('pdf-duas-paginas.pdf')), 422],
    [await enviar(ANA, fixture('pdf-senha.pdf')), 422]
  ];
  casos.forEach(([resposta, esperado], i) => assert.equal(resposta.status, esperado, `caso ${i}`));
  const mensagens = await Promise.all(casos.slice(10).map(([r]) => r.json()));
  assert.match(mensagens[0].error, /QR Code/);
  assert.match(mensagens[1].error, /uma página/);
  assert.match(mensagens[2].error, /senha/);
  // Nada foi aceito: sem arquivo, sem solicitação, sem usuário alterado.
  assert.equal(arquivosCifrados().length, 0);
  assert.equal(fs_.docs.has('solicitacoes_motoristas/uidAna'), false);
  assert.equal(registro('uidAna'), undefined);

  // Limite de 5 MB: declarado no cabeçalho, recusado sem ler o corpo.
  const status = await new Promise((resolve, reject) => {
    const rq = http.request(`${base}/api/motoristas/cnh`, { method: 'POST', headers: { Authorization: `Bearer ${ANA}`, 'Content-Type': 'application/pdf', 'Content-Length': String(6 * 1024 * 1024) } }, res => { res.resume(); resolve(res.statusCode); });
    rq.on('error', reject);
    rq.flushHeaders();
  });
  assert.equal(status, 413);
  assert.equal(arquivosCifrados().length, 0);
});

test('acesso ao PDF: titular recebe o próprio arquivo; outro usuário e visitantes não', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const meu = await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.equal(meu.status, 200);
  assert.equal(meu.headers.get('content-type'), 'application/pdf');
  assert.match(meu.headers.get('cache-control'), /no-store/);
  assert.equal(meu.headers.get('x-content-type-options'), 'nosniff');
  assert.deepEqual(Buffer.from(await meu.arrayBuffer()), PDF);
  // Bia não possui documento e jamais recebe o de Ana, mesmo tentando informar o UID dela.
  const dela = await chamar('GET', '/api/motoristas/cnh/arquivo?uid=uidAna', { token: BIA, headers: { 'X-Uid': 'uidAna' } });
  assert.equal(dela.status, 404);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/arquivo/../../cnh/uidAna', { token: BIA })).status >= 400, true);
  assert.equal((await chamar('DELETE', '/api/motoristas/cnh/arquivo', { token: BIA })).status, 404);
  assert.equal(registro('uidAna').documento.arquivoPresente, true, 'a exclusão de Bia não afetou Ana');
  assert.equal((await chamar('GET', '/api/motoristas/cnh/arquivo')).status, 401);
  // Rotas administrativas não aceitam token Firebase nem visitantes.
  assert.equal((await chamar('GET', '/api/cnh/uidAna/arquivo', { token: ANA })).status, 401);
  assert.equal((await chamar('GET', '/api/cnh/uidAna/arquivo')).status, 401);
  assert.equal((await chamar('GET', '/api/cnh/uidAna/documento')).status, 401);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/ocr', { body: {} })).status, 401);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/arquivo/excluir', { body: { motivo: 'sem sessão' } })).status, 401);
  const status = await (await chamar('GET', '/api/motoristas/cnh/status', { token: BIA })).json();
  assert.equal(status.documento.recebido, false);
});

test('painel: abrir PDF, metadados, indicador na lista e OCR do documento armazenado (somente sugere)', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const aberto = await chamar('GET', '/api/cnh/uidAna/arquivo', { sessao: true });
  assert.equal(aberto.status, 200);
  assert.match(aberto.headers.get('cache-control'), /no-store/);
  assert.equal(aberto.headers.get('content-type'), 'application/pdf');
  assert.deepEqual(Buffer.from(await aberto.arrayBuffer()), PDF);
  assert.ok(auditoria.some(a => a[1] === 'documento CNH aberto' && a[2] === 'uidAna'));
  const meta = await (await chamar('GET', '/api/cnh/uidAna/documento', { sessao: true })).json();
  assert.equal(meta.recebido, true);
  assert.equal(meta.sha256, sha256Hex(PDF));
  const lista = await (await chamar('GET', '/api/cnh', { sessao: true })).json();
  const item = lista.itens.find(i => i.uid === 'uidAna');
  assert.equal(item.documentoRecebido, true);
  assert.equal(item.arquivoPresente, true);
  assert.equal(item.apta, false);
  assert.equal(item.conferenciaDesatualizada, true);
  const ocr = await chamar('POST', '/api/cnh/uidAna/ocr', { sessao: true, body: {} });
  assert.equal(ocr.status, 200);
  const r = await ocr.json();
  assert.equal(r.revisaoNecessaria, true);
  assert.equal(r.sugestoes.validade, '2030-11-18');
  assert.equal(r.sugestoes.categoria, 'B');
  assert.equal(r.pdfSha256, sha256Hex(PDF));
  assert.equal(r.pdfVersao, 1);
  // OCR nunca concede acesso nem conferência.
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, false);
  assert.equal(registro('uidAna').validade, undefined);
  assert.ok(auditoria.some(a => a[1] === 'OCR do PDF da CNH'));
  assert.equal((await chamar('GET', '/api/cnh/uidBia/arquivo', { sessao: true })).status, 404);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/documento', { sessao: true, body: {} })).status, 405);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/ocr', { sessao: true, body: {}, headers: { Origin: 'https://evil.example' } })).status, 403);
});

test('conferência vinculada ao PDF: hash/versão obrigatórios, aprovação grava o vínculo e cnhValidadeFimMs', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const conferencia = { nome: 'Pessoa Ficticia Teste', categoria: 'B', validade: '2099-12-31', documentoConferido: true, qrConferidoVio: true, observacao: 'Documento e QR conferidos em teste.' };
  const salvar = extra => chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...conferencia, ...extra } });
  assert.equal((await salvar({})).status, 409, 'sem o hash do PDF analisado');
  assert.equal((await salvar({ pdfSha256: 'f'.repeat(64), pdfVersao: 1 })).status, 409, 'hash de outro PDF');
  assert.equal((await salvar({ pdfSha256: sha256Hex(PDF), pdfVersao: 9 })).status, 409, 'versão errada');
  assert.equal((await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } })).status, 409, 'sem conferência não aprova');
  assert.equal((await salvar({ pdfSha256: sha256Hex(PDF), pdfVersao: 1 })).status, 200);
  const u = fs_.docs.get('usuarios/uidAna');
  assert.equal(u.cnhValidade, '2099-12-31');
  assert.equal(u.cnhValidadeFimMs, fimDoDiaSP('2099-12-31'));
  assert.equal(u.condutorValidado, false, 'conferir nunca concede acesso');
  assert.equal(registro('uidAna').pdfSha256, sha256Hex(PDF));
  assert.equal(registro('uidAna').documento.versao, 1, 'o registro do documento é preservado');
  const aprovar = await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } });
  assert.equal(aprovar.status, 200);
  const aprovado = fs_.docs.get('usuarios/uidAna');
  assert.equal(aprovado.condutorValidado, true);
  assert.equal(aprovado.papelAtivo, 'MOTORISTA');
  assert.equal(aprovado.cnhRevisaoNecessaria, false);
  assert.equal(aprovado.cnhValidadeFimMs, fimDoDiaSP('2099-12-31'));
  assert.equal(registro('uidAna').decisaoPdfSha256, sha256Hex(PDF));
  assert.equal(registro('uidAna').decisaoPdfVersao, 1);
});

test('renovação: novo PDF retira a aprovação e uma conferência antiga não aprova o documento novo', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const base_ = { nome: 'Pessoa Ficticia Teste', categoria: 'B', validade: '2099-12-31', documentoConferido: true, qrConferidoVio: true, observacao: 'Documento e QR conferidos em teste.' };
  await chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...base_, pdfSha256: sha256Hex(PDF), pdfVersao: 1 } });
  assert.equal((await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } })).status, 200);
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'APROVADA');
  const antigo = arquivosCifrados()[0];

  // Renovação com PDF diferente.
  const r = await enviar(ANA, PDF_RENOVADO, {});
  assert.equal(r.status, 201);
  const corpo = await r.json();
  assert.equal(corpo.versao, 2);
  assert.equal(corpo.aprovacaoRetirada, true);
  assert.equal(corpo.substituiu, true);
  const u = fs_.docs.get('usuarios/uidAna');
  assert.equal(u.condutorValidado, false);
  assert.equal(u.papelAtivo, 'PASSAGEIRO');
  assert.equal(u.cnhRevisaoNecessaria, true);
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'PENDENTE');
  assert.equal(registro('uidAna').documento.sha256, sha256Hex(PDF_RENOVADO));
  // Arquivo anterior removido depois do commit; apenas o novo permanece.
  assert.equal(arquivosCifrados().length, 1);
  assert.notEqual(arquivosCifrados()[0], antigo);
  // A conferência antiga (outro PDF) não aprova nem aparece como apta.
  const tentativa = await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } });
  assert.equal(tentativa.status, 409);
  assert.match((await tentativa.json()).error, /não corresponde ao PDF atual/);
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, false);
  const lista = await (await chamar('GET', '/api/cnh', { sessao: true })).json();
  assert.equal(lista.itens.find(i => i.uid === 'uidAna').apta, false);
  // Salvar uma conferência com o hash antigo também é recusado.
  assert.equal((await chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...base_, pdfSha256: sha256Hex(PDF), pdfVersao: 1 } })).status, 409);
  // Nova conferência do PDF novo + decisão explícita restauram o acesso.
  assert.equal((await chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...base_, pdfSha256: sha256Hex(PDF_RENOVADO), pdfVersao: 2 } })).status, 200);
  assert.equal((await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } })).status, 200);
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, true);
});

test('reenvio do MESMO arquivo também invalida a conferência (vínculo por versão)', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const conf = { nome: 'Pessoa Ficticia Teste', categoria: 'B', validade: '2099-12-31', documentoConferido: true, qrConferidoVio: true, observacao: 'Documento e QR conferidos em teste.' };
  await chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...conf, pdfSha256: sha256Hex(PDF), pdfVersao: 1 } });
  assert.equal((await enviar(ANA)).status, 201);
  assert.equal(registro('uidAna').documento.versao, 2);
  assert.equal((await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } })).status, 409);
  assert.equal(arquivosCifrados().length, 1);
});

test('solicitação recusada pode reenviar; status inesperado não', async () => {
  await reiniciar([['solicitacoes_motoristas/uidAna', { motoristaUid: 'uidAna', status: 'RECUSADA', placa: 'AAA1A11', modelo: 'Gol', cor: 'Azul' }], ['solicitacoes_motoristas/uidBia', { motoristaUid: 'uidBia', status: 'SUSPENSA' }]]);
  assert.equal((await chamar('POST', '/api/motoristas/cnh', { token: ANA, body: PDF, tipo: 'application/pdf' })).status, 201);
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'PENDENTE');
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').placa, 'AAA1A11', 'dados do veículo preservados');
  const r = await chamar('POST', '/api/motoristas/cnh', { token: BIA, body: PDF, tipo: 'application/pdf' });
  assert.equal(r.status, 409);
  assert.equal(arquivosCifrados().length, 1, 'rejeição na regra de negócio descarta o arquivo novo');
});

test('fluxo completo de recusa, motivo, sincronização de cnhRevisaoNecessaria e reenvio com nova pré-análise', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  await servicoDocumentos.aguardarPreAnalise('uidAna');
  assert.equal(fs_.docs.get('usuarios/uidAna').cnhRevisaoNecessaria, true);

  // Administrador recusa informando o motivo da recusa
  const recusa = await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', {
    sessao: true,
    body: { decisao: 'recusar', motivo: 'QR Code não validou no aplicativo oficial Vio.' }
  });
  assert.equal(recusa.status, 200);
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'RECUSADA');
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').motivoRecusa, 'QR Code não validou no aplicativo oficial Vio.');
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, false);
  assert.equal(fs_.docs.get('usuarios/uidAna').cnhRevisaoNecessaria, false, 'recusa limpa cnhRevisaoNecessaria para não sobrepor o status RECUSADA');

  // Consulta de status pelo motorista retorna RECUSADA, motivoRecusa e revisaoNecessaria: false
  const stRecusado = await (await chamar('GET', '/api/motoristas/cnh/status', { token: ANA })).json();
  assert.equal(stRecusado.solicitacao, 'RECUSADA');
  assert.equal(stRecusado.motivoRecusa, 'QR Code não validou no aplicativo oficial Vio.');
  assert.equal(stRecusado.revisaoNecessaria, false);
  assert.equal(stRecusado.condutorValidado, false);

  // Motorista envia novo PDF após a recusa: volta para PENDENTE, limpa motivoRecusa e executa nova pré-análise automática
  const reenvio = await enviar(ANA, PDF_RENOVADO, {});
  assert.equal(reenvio.status, 201);
  await servicoDocumentos.aguardarPreAnalise('uidAna');
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'PENDENTE');
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').motivoRecusa, null);
  assert.equal(registro('uidAna').documento.versao, 2);
  assert.equal(registro('uidAna').preAnalise.pdfVersao, 2);
  assert.equal(registro('uidAna').preAnalise.status, 'concluida');

  const stNovo = await (await chamar('GET', '/api/motoristas/cnh/status', { token: ANA })).json();
  assert.equal(stNovo.solicitacao, 'PENDENTE');
  assert.equal(stNovo.motivoRecusa, null);
  assert.equal(stNovo.revisaoNecessaria, true);
});

test('transições completas: cadastro antigo aprovado ("APROVADO"/"aprovado"), solicitação pendente sem PDF, bloqueio de duplicata em análise e reenvio solicitado pela administração', async () => {
  // 1. Motorista antigo aprovado no sistema legado (status 'APROVADO' ou 'aprovado', sem cnh_admin)
  await reiniciar([
    ['solicitacoes_motoristas/uidAna', { motoristaUid: 'uidAna', status: 'APROVADO', placa: 'OLD1A23', modelo: 'Onix', cor: 'Preto' }],
    ['usuarios/uidAna', { nome: 'Ana', email: 'ana@senacsp.edu.br', status: 'ATIVO', papelAtivo: 'MOTORISTA', condutorValidado: true }],
    // 2. Solicitação PENDENTE criada no app sem PDF armazenado (ou com status em minúsculas 'pendente')
    ['solicitacoes_motoristas/uidBia', { motoristaUid: 'uidBia', status: 'pendente', placa: 'PEN2B34', modelo: 'HB20', cor: 'Branco' }]
  ]);

  // Regularização do motorista aprovado pelo sistema antigo ('APROVADO')
  const reg = await enviar(ANA, PDF, { placa: 'OLD1A23', modelo: 'Onix', cor: 'Preto' });
  assert.equal(reg.status, 201, 'motorista antigo com status APROVADO consegue enviar CNH para regularização');
  const corpoReg = await reg.json();
  assert.equal(corpoReg.versao, 1);
  assert.equal(corpoReg.aprovacaoRetirada, true, 'aprovação antiga sem CNH é retirada até nova conferência');
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'PENDENTE');
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, false);
  assert.equal(fs_.docs.get('usuarios/uidAna').papelAtivo, 'PASSAGEIRO');

  // Também aceita status legado 'aprovado' em minúsculas
  fs_.docs.set('solicitacoes_motoristas/uidAna', { ...fs_.docs.get('solicitacoes_motoristas/uidAna'), status: 'aprovado' });
  assert.equal((await enviar(ANA, PDF_RENOVADO, {})).status, 201);
  assert.equal(registro('uidAna').documento.versao, 2);

  // Envio por solicitação pendente que ainda não possuía PDF armazenado
  const envPendente = await enviar(BIA, PDF, { placa: 'PEN2B34', modelo: 'HB20', cor: 'Branco' });
  assert.equal(envPendente.status, 201, 'solicitação pendente sem documento aceita o primeiro PDF');
  assert.equal(registro('uidBia').documento.versao, 1);

  // Bloqueio de envio duplicado do MESMO PDF enquanto já está PENDENTE aguardando análise administrativa
  const duplicado = await enviar(BIA, PDF, { placa: 'PEN2B34', modelo: 'HB20', cor: 'Branco' });
  assert.equal(duplicado.status, 409, 'bloqueia reenvio idêntico enquanto aguarda análise');
  assert.match((await duplicado.json()).error, /aguardando análise/);
  assert.equal(registro('uidBia').documento.versao, 1);

  // Quando a administração analisa e registra revisão necessária (ex.: documentoConferido: false),
  // o motorista pode reenviar (mesmo PDF ou outro) conforme solicitado pela administração.
  const revisaoAdmin = await chamar('POST', '/api/cnh/uidBia', {
    sessao: true,
    body: {
      nome: 'Bia Ficticia',
      categoria: 'B',
      validade: '2030-11-18',
      documentoConferido: false,
      qrConferidoVio: false,
      observacao: 'Solicitado reenvio pela administração para nova conferência.',
      pdfSha256: sha256Hex(PDF),
      pdfVersao: 1
    }
  });
  assert.equal(revisaoAdmin.status, 200);
  const reenvioAposRevisao = await enviar(BIA, PDF, { placa: 'PEN2B34', modelo: 'HB20', cor: 'Branco' });
  assert.equal(reenvioAposRevisao.status, 201, 'após revisão administrativa, o reenvio é permitido');
  assert.equal(registro('uidBia').documento.versao, 2);
});

test('pré-análise automática após upload: executa em background, compara nome com o Firebase, bloqueia divergência de identidade e nunca aprova sozinha', async () => {
  await reiniciar([
    // uidAna tem nome cadastrado com acentos e minúsculas compatível com 'PESSOA FICTICIA TESTE' do PDF sintético
    ['usuarios/uidAna', { nome: '  Pessoa   Fictícia  Teste ', email: 'ana@senacsp.edu.br', status: 'ATIVO', papelAtivo: 'PASSAGEIRO', condutorValidado: false }],
    // uidBia tem nome cadastrado divergente do PDF sintético
    ['usuarios/uidBia', { nome: 'Marina Oliveira', email: 'bia@sp.senac.br', status: 'ATIVO', papelAtivo: 'PASSAGEIRO', condutorValidado: false }]
  ]);

  // 1. Upload de Ana: retorna 201 imediatamente e conclui a pré-análise automática em segundo plano
  assert.equal((await enviar(ANA)).status, 201);
  await servicoDocumentos.aguardarPreAnalise('uidAna');
  const preAna = registro('uidAna').preAnalise;
  assert.equal(preAna.status, 'concluida');
  assert.equal(preAna.pdfSha256, sha256Hex(PDF));
  assert.equal(preAna.pdfVersao, 1);
  assert.equal(preAna.recomendacao, 'pre_analise_favoravel');
  assert.equal(preAna.divergenciaIdentidade, false);
  assert.equal(preAna.sugestoes.nome, 'PESSOA FICTICIA TESTE');
  assert.equal(preAna.sugestoes.categoria, 'B');
  assert.equal(preAna.sugestoes.validade, '2030-11-18');
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, false, 'pré-análise favorável NUNCA aprova automaticamente');

  // 2. Upload de Bia (nome cadastrado 'Marina Oliveira' != 'PESSOA FICTICIA TESTE' do PDF)
  assert.equal((await enviar(BIA)).status, 201);
  await servicoDocumentos.aguardarPreAnalise('uidBia');
  const preBia = registro('uidBia').preAnalise;
  assert.equal(preBia.status, 'concluida');
  assert.equal(preBia.recomendacao, 'inconsistencias_encontradas');
  assert.equal(preBia.divergenciaIdentidade, true);

  // Tentar salvar conferência marcando documentoConferido: true sem resolver a divergência de identidade falha com 409
  const confBia = {
    nome: 'PESSOA FICTICIA TESTE',
    categoria: 'B',
    validade: '2030-11-18',
    documentoConferido: true,
    qrConferidoVio: true,
    observacao: 'Tentativa sem resolver divergência de identidade.',
    pdfSha256: sha256Hex(PDF),
    pdfVersao: 1
  };
  const bloqueada = await chamar('POST', '/api/cnh/uidBia', { sessao: true, body: confBia });
  assert.equal(bloqueada.status, 409);
  assert.match((await bloqueada.json()).error, /Divergência de identidade/);

  // Com confirmação explícita da resolução manual de identidade, a conferência é aceita e permite aprovação posterior
  const resolvida = await chamar('POST', '/api/cnh/uidBia', {
    sessao: true,
    body: { ...confBia, divergenciaIdentidadeConfirmada: true, observacao: 'Identidade conferida e confirmada manualmente com documento oficial.' }
  });
  assert.equal(resolvida.status, 200);
  assert.equal((await chamar('POST', '/api/solicitacoes-motoristas/uidBia/decisao', { sessao: true, body: { decisao: 'aprovar' } })).status, 200);
  assert.equal(fs_.docs.get('usuarios/uidBia').condutorValidado, true);
});

test('exclusão pelo titular e pelo administrador; retenção não revoga aprovação já decidida', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const conf = { nome: 'Pessoa Ficticia Teste', categoria: 'B', validade: '2099-12-31', documentoConferido: true, qrConferidoVio: true, observacao: 'Documento e QR conferidos em teste.' };
  await chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...conf, pdfSha256: sha256Hex(PDF), pdfVersao: 1 } });
  assert.equal((await chamar('POST', '/api/solicitacoes-motoristas/uidAna/decisao', { sessao: true, body: { decisao: 'aprovar' } })).status, 200);
  // Administrador exclui após a decisão (política de retenção): exige motivo e é auditado.
  assert.equal((await chamar('POST', '/api/cnh/uidAna/arquivo/excluir', { sessao: true, body: {} })).status, 400);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/arquivo/excluir', { sessao: true, body: { motivo: 'curto' } })).status, 200);
  assert.ok(auditoria.some(a => a[1] === 'documento CNH excluído' && a[2] === 'uidAna'));
  assert.equal(arquivosCifrados().length, 0);
  assert.equal(registro('uidAna').documento.arquivoPresente, false);
  assert.equal(registro('uidAna').documento.sha256, sha256Hex(PDF), 'hash mantido para auditoria do vínculo');
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, true, 'excluir o arquivo não revoga a decisão');
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').cnhPdfRecebido, false);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA })).status, 404);
  assert.equal((await chamar('GET', '/api/cnh/uidAna/arquivo', { sessao: true })).status, 404);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/ocr', { sessao: true, body: {} })).status, 404);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/arquivo/excluir', { sessao: true, body: { motivo: 'repetida agora' } })).status, 404);
  assert.equal((await chamar('DELETE', '/api/motoristas/cnh/arquivo', { token: ANA })).status, 404);
  // Após a exclusão, nova conferência exige reenvio do PDF.
  assert.equal((await chamar('POST', '/api/cnh/uidAna', { sessao: true, body: { ...conf, pdfSha256: sha256Hex(PDF), pdfVersao: 1 } })).status, 409);
  // O titular reenvia (versão 2) e depois exclui por conta própria.
  assert.equal((await enviar(ANA)).status, 201);
  assert.equal(registro('uidAna').documento.versao, 2);
  assert.equal(registro('uidAna').documento.arquivoPresente, true);
  const del = await chamar('DELETE', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.equal(del.status, 200);
  assert.equal((await del.json()).limpezaPendente, false);
  assert.equal(arquivosCifrados().length, 0);
  const st = await (await chamar('GET', '/api/motoristas/cnh/status', { token: ANA })).json();
  assert.equal(st.documento.recebido, true);
  assert.equal(st.documento.arquivoPresente, false);
  const eventos = [...fs_.docs].filter(([k]) => k.startsWith('cnh_eventos/')).map(([, v]) => v.acao);
  assert.ok(eventos.includes('documento excluído'));
});

test('falha de armazenamento: nada é registrado e o documento anterior continua intacto', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  await servicoDocumentos.aguardarPreAnalise();
  const antes = structuredClone(registro('uidAna'));
  const original = armazenamento.salvar;
  armazenamento.salvar = async () => { throw Object.assign(new Error('disco cheio'), { codigo: 'gravacao' }); };
  const r = await enviar(ANA, PDF_RENOVADO, {});
  armazenamento.salvar = original;
  assert.equal(r.status, 503);
  assert.deepEqual(registro('uidAna'), antes);
  assert.equal(fs_.docs.get('solicitacoes_motoristas/uidAna').status, 'PENDENTE');
  const meu = await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.deepEqual(Buffer.from(await meu.arrayBuffer()), PDF);
});

test('falha do Firestore: arquivo novo descartado, anterior preservado e aprovação não muda', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  await servicoDocumentos.aguardarPreAnalise();
  const antes = structuredClone(registro('uidAna'));
  fs_.docs.set('usuarios/uidAna', { ...fs_.docs.get('usuarios/uidAna'), condutorValidado: true, papelAtivo: 'MOTORISTA' });
  fs_.falhaTransacao = 1;
  const r = await enviar(ANA, PDF_RENOVADO, {});
  assert.equal(r.status, 503);
  assert.equal(arquivosCifrados().length, 1, 'sem arquivo órfão');
  assert.deepEqual(registro('uidAna'), antes);
  assert.equal(fs_.docs.get('usuarios/uidAna').condutorValidado, true, 'nenhuma revogação parcial');
  const meu = await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.deepEqual(Buffer.from(await meu.arrayBuffer()), PDF);
  // Exclusão com Firestore indisponível: nada é apagado.
  fs_.falhaTransacao = 1;
  assert.equal((await chamar('DELETE', '/api/motoristas/cnh/arquivo', { token: ANA })).status, 503);
  assert.equal(arquivosCifrados().length, 1);
  assert.equal(registro('uidAna').documento.arquivoPresente, true);
  // Leitura indisponível no status.
  fs_.falhaLeitura = true;
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: ANA })).status, 503, 'sem validar o perfil nada é entregue');
  fs_.falhaLeitura = false;
});

test('falha ambígua após o commit nunca apaga o documento que o Firestore já referencia', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  await servicoDocumentos.aguardarPreAnalise();
  fs_.commitDepoisErro = true;
  const r = await enviar(ANA, PDF_RENOVADO, {});
  assert.equal(r.status, 503);
  assert.equal(registro('uidAna').documento.sha256, sha256Hex(PDF_RENOVADO), 'o commit foi aplicado');
  const meu = await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.equal(meu.status, 200);
  assert.deepEqual(Buffer.from(await meu.arrayBuffer()), PDF_RENOVADO, 'o arquivo referenciado continua legível');
});

test('falha ao limpar o arquivo antigo não derruba o envio; a reconciliação remove a sobra', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const original = armazenamento.limparExceto;
  armazenamento.limparExceto = async () => { throw new Error('falha simulada'); };
  const r = await enviar(ANA, PDF_RENOVADO, {});
  armazenamento.limparExceto = original;
  assert.equal(r.status, 201);
  assert.equal(arquivosCifrados().length, 2, 'sobra do arquivo anterior');
  const resultado = await servicoDocumentos.reconciliar();
  assert.equal(resultado.limpos, 1);
  assert.equal(arquivosCifrados().length, 1);
  const meu = await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.deepEqual(Buffer.from(await meu.arrayBuffer()), PDF_RENOVADO);
  // Exclusão cujo apagamento em disco falha: o acesso já fica bloqueado e a limpeza é sinalizada/reconciliada.
  armazenamento.limparExceto = async () => { throw new Error('falha simulada'); };
  const del = await chamar('DELETE', '/api/motoristas/cnh/arquivo', { token: ANA });
  armazenamento.limparExceto = original;
  assert.equal(del.status, 200);
  assert.equal((await del.json()).limpezaPendente, true);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA })).status, 404);
  await servicoDocumentos.reconciliar();
  assert.equal(arquivosCifrados().length, 0);
});

test('arquivo adulterado em disco não é entregue nem lido por OCR', async () => {
  await reiniciar();
  assert.equal((await enviar(ANA)).status, 201);
  const [arquivo] = arquivosCifrados();
  const bruto = readFileSync(arquivo);
  bruto[bruto.length - 5] ^= 0xff;
  writeFileSync(arquivo, bruto);
  const meu = await chamar('GET', '/api/motoristas/cnh/arquivo', { token: ANA });
  assert.equal(meu.status, 500);
  assert.equal((await meu.text()).includes('%PDF'), false);
  assert.equal((await chamar('GET', '/api/cnh/uidAna/arquivo', { sessao: true })).status, 500);
  assert.equal((await chamar('POST', '/api/cnh/uidAna/ocr', { sessao: true, body: {} })).status, 500);
});

test('sem CNH_STORAGE_KEY o envio fica indisponível e nada é gravado', async () => {
  await reiniciar();
  definirArmazenamentoParaTeste(criarArmazenamento({ diretorio: DIR, chave: undefined }));
  assert.equal((await enviar(ANA)).status, 503);
  assert.equal(arquivosCifrados().length, 0);
  assert.equal(fs_.docs.has('solicitacoes_motoristas/uidAna'), false);
});

test('limites: frequência de upload por usuário e limite de consultas', async () => {
  await reiniciar();
  definirTratadorParaTeste({
    verificarToken: async t => TOKENS[t],
    limites: { envio: { max: 2, janelaMs: 3_600_000, intervaloMs: 60_000 }, consulta: { max: 3, janelaMs: 60_000 } }
  });
  // Tentativas que falham na validação também contam (o processamento é o recurso caro).
  assert.equal((await enviar(ANA, Buffer.from('%PDF-1.4\nlixo'))).status, 422);
  const segunda = await enviar(ANA);
  assert.equal(segunda.status, 429);
  assert.ok(Number(segunda.headers.get('retry-after')) > 0);
  assert.equal(arquivosCifrados().length, 0);
  assert.equal((await enviar(BIA)).status, 201, 'o limite é por usuário');
  for (let i = 0; i < 3; i++) assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: BIA })).status, 200);
  assert.equal((await chamar('GET', '/api/motoristas/cnh/status', { token: BIA })).status, 429);
});

test('limites: corpos simultâneos e prazo de recebimento', async () => {
  await reiniciar();
  definirTratadorParaTeste({
    verificarToken: async t => TOKENS[t],
    limites: { envio: { max: 100, janelaMs: 3_600_000, intervaloMs: 0 }, consulta: { max: 100, janelaMs: 60_000 }, corposSimultaneos: 1, leituraMs: 600 }
  });
  const lento = new Promise((resolve, reject) => {
    const rq = http.request(`${base}/api/motoristas/cnh`, { method: 'POST', headers: { Authorization: `Bearer ${ANA}`, 'Content-Type': 'application/pdf', 'Content-Length': '1000' } }, res => { res.resume(); resolve(res.statusCode); });
    rq.on('error', reject);
    rq.write('%PDF-');
  });
  await new Promise(r => setTimeout(r, 200));
  const concorrente = await chamar('POST', '/api/motoristas/cnh', { token: BIA, body: PDF, tipo: 'application/pdf' });
  assert.equal(concorrente.status, 429, 'a única vaga de recebimento está ocupada');
  assert.equal(await lento, 408, 'corpo lento expira');
  assert.equal(arquivosCifrados().length, 0);
  assert.equal(fs_.docs.has('solicitacoes_motoristas/uidAna'), false);
});

test('validação e OCR compartilham uma única execução por vez (429 quando ocupado)', async () => {
  const promessa = validarPdfCnh(PDF);
  assert.throws(() => validarPdfCnh(PDF), e => e.status === 429);
  assert.throws(() => validarPdfCnh(PDF), e => /ocupad/i.test(e.message));
  const r = await promessa;
  assert.equal(r.validado, true);
  assert.equal(r.qrDetectado, true);
});

test('nenhum log contém PDF, Base64, tokens, chave ou conteúdo de documento', () => {
  const texto = logs.join('\n');
  assert.equal(texto.includes(CHAVE), false);
  assert.equal(texto.includes('JVBERi'), false, 'prefixo Base64 de %PDF');
  assert.equal(texto.includes('%PDF'), false);
  for (const token of Object.keys(TOKENS)) assert.equal(texto.includes(token), false);
  assert.equal(texto.includes(PDF.toString('base64').slice(0, 40)), false);
});

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readdirSync, readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { criarArmazenamento, interpretarChave, sha256Hex, armazenamentoPadrao } from '../cnhStorage.js';

const temp = mkdtempSync(join(tmpdir(), 'hh-cnh-armaz-'));
after(() => rmSync(temp, { recursive: true, force: true }));
const chaveHex = () => randomBytes(32).toString('hex');
const pdfFicticio = conteudo => Buffer.from(`%PDF-1.4\n% documento ficticio ${conteudo}\n`);
function arquivosDe(dir) {
  const lista = [];
  for (const pasta of existsSync(dir) ? readdirSync(dir) : []) for (const nome of readdirSync(join(dir, pasta))) lista.push(join(dir, pasta, nome));
  return lista;
}

test('chave: exige 32 bytes exclusivos, sem padrão nem chave fraca', () => {
  assert.ok(interpretarChave(undefined).erro);
  assert.ok(interpretarChave('').erro);
  assert.ok(interpretarChave('abc').erro);
  assert.ok(interpretarChave('a'.repeat(64)).erro, 'bytes repetidos são fracos');
  assert.ok(interpretarChave('00'.repeat(32)).erro);
  assert.ok(interpretarChave(randomBytes(16).toString('hex')).erro);
  assert.ok(interpretarChave(randomBytes(31).toString('base64')).erro);
  const hex = chaveHex();
  assert.equal(interpretarChave(hex).chave.length, 32);
  assert.equal(interpretarChave(randomBytes(32).toString('base64')).chave.length, 32);
  assert.ok(interpretarChave(hex, { MERCADOPAGO_WEBHOOK_SECRET: hex }).erro, 'não reutiliza outro segredo');
  assert.equal(criarArmazenamento({ diretorio: join(temp, 'x'), chave: undefined }).disponivel, false);
  assert.equal(armazenamentoPadrao().disponivel, false, 'sem CNH_STORAGE_KEY o armazenamento padrão fica desativado');
});

test('o diretório não pode ficar dentro de public/', () => {
  const dentro = criarArmazenamento({ diretorio: fileURLToPath(new URL('../public/cnh', import.meta.url)), chave: chaveHex() });
  assert.equal(dentro.disponivel, false);
});

test('grava somente cifrado (AES-256-GCM), com IV aleatório, e lê de volta', async () => {
  const dir = join(temp, 'cifra');
  const a = criarArmazenamento({ diretorio: dir, chave: chaveHex() });
  const pdf = pdfFicticio('conteudo-secreto-123');
  const r = await a.salvar('uidA', pdf);
  assert.equal(r.sha256, sha256Hex(pdf));
  assert.equal(r.criado, true);
  const [arquivo] = arquivosDe(dir);
  assert.match(arquivo, /[a-f0-9]{64}\.pdf\.enc$/);
  const bruto = readFileSync(arquivo);
  assert.equal(bruto.includes('%PDF-'), false);
  assert.equal(bruto.includes('conteudo-secreto-123'), false);
  assert.deepEqual(await a.ler('uidA', r.sha256), pdf);
  // Mesmo conteúdo para outro usuário: arquivo diferente (IV e contexto distintos), nunca texto claro.
  await a.salvar('uidB', pdf);
  const [x, y] = arquivosDe(dir).map(f => readFileSync(f));
  assert.notDeepEqual(x, y);
  assert.deepEqual((await a.salvar('uidA', pdf)).criado, false, 'reenvio idêntico é idempotente');
  assert.equal(arquivosDe(dir).filter(f => f.endsWith('.tmp')).length, 0);
});

test('integridade: adulteração, troca de usuário, chave errada e arquivo truncado são recusados', async () => {
  const dir = join(temp, 'integridade');
  const chave = chaveHex();
  const a = criarArmazenamento({ diretorio: dir, chave });
  const pdf = pdfFicticio('integridade');
  const { sha256 } = await a.salvar('uidA', pdf);
  const [arquivo] = arquivosDe(dir);
  const original = readFileSync(arquivo);

  const adulterado = Buffer.from(original);
  adulterado[adulterado.length - 3] ^= 0x01;
  writeFileSync(arquivo, adulterado);
  await assert.rejects(() => a.ler('uidA', sha256), e => e.codigo === 'integridade');

  writeFileSync(arquivo, original.subarray(0, 20));
  await assert.rejects(() => a.ler('uidA', sha256), e => e.codigo === 'integridade');

  writeFileSync(arquivo, original);
  assert.deepEqual(await a.ler('uidA', sha256), pdf);
  await assert.rejects(() => criarArmazenamento({ diretorio: dir, chave: chaveHex() }).ler('uidA', sha256), e => e.codigo === 'integridade');

  // Arquivo de A copiado para a pasta de B: o contexto autenticado (uid) não confere.
  const b = criarArmazenamento({ diretorio: dir, chave });
  await b.salvar('uidB', pdfFicticio('outro'));
  const pastaDeB = arquivosDe(dir).map(f => f.replace(/[\\/][^\\/]+$/, '')).find(p => p !== arquivo.replace(/[\\/][^\\/]+$/, ''));
  copyFileSync(arquivo, join(pastaDeB, `${sha256}.pdf.enc`));
  await assert.rejects(() => b.ler('uidB', sha256), e => e.codigo === 'integridade');
});

test('path traversal: UID e hash maliciosos nunca chegam ao sistema de arquivos', async () => {
  const dir = join(temp, 'traversal');
  mkdirSync(dir, { recursive: true });
  const a = criarArmazenamento({ diretorio: dir, chave: chaveHex() });
  const pdf = pdfFicticio('traversal');
  for (const uid of ['../fora', '..\\fora', 'a/b', '', 'x'.repeat(129), 'uid com espaço', '%2e%2e']) {
    await assert.rejects(() => a.salvar(uid, pdf), e => e.codigo === 'uid_invalido');
    await assert.rejects(() => a.ler(uid, sha256Hex(pdf)), e => e.codigo === 'uid_invalido');
  }
  for (const sha of ['../../etc/passwd', 'abc', 'G'.repeat(64), `${'a'.repeat(63)}/`]) {
    await assert.rejects(() => a.ler('uidA', sha), e => e.codigo === 'hash_invalido');
    await assert.rejects(() => a.remover('uidA', sha), e => e.codigo === 'hash_invalido');
  }
  assert.equal(existsSync(join(temp, 'fora')), false);
  // UIDs que só diferem por maiúsculas não colidem (nomes derivados de hash).
  await a.salvar('AbC', pdf);
  await a.salvar('aBc', pdf);
  assert.equal(arquivosDe(dir).length, 2);
});

test('limites, limpeza seletiva e remoção', async () => {
  const dir = join(temp, 'limpeza');
  const a = criarArmazenamento({ diretorio: dir, chave: chaveHex() });
  await assert.rejects(() => a.salvar('uidA', Buffer.alloc(0)), e => e.codigo === 'tamanho_invalido');
  await assert.rejects(() => a.salvar('uidA', Buffer.alloc(5 * 1024 * 1024 + 1, 1)), e => e.codigo === 'tamanho_invalido');
  const v1 = await a.salvar('uidA', pdfFicticio('v1'));
  const v2 = await a.salvar('uidA', pdfFicticio('v2'));
  assert.equal(await a.limparExceto('uidA', v2.sha256), 1);
  assert.equal(await a.existe('uidA', v1.sha256), false);
  assert.equal(await a.existe('uidA', v2.sha256), true);
  await assert.rejects(() => a.ler('uidA', v1.sha256), e => e.codigo === 'nao_encontrado');
  assert.equal(await a.limparExceto('uidA', null), 1);
  assert.equal(await a.existe('uidA', v2.sha256), false);
  assert.equal(await a.limparExceto('uidInexistente', null), 0);
});

// Armazenamento privado de PDFs de CNH.
// - Somente PDF criptografado (AES-256-GCM) em disco; nunca texto claro.
// - Chave exclusiva de 32 bytes em CNH_STORAGE_KEY; sem chave padrão, sem chave efêmera, sem fallback.
// - IV aleatório de 12 bytes por criptografia; o GCM autentica o conteúdo e o contexto (uid + hash),
//   então um arquivo adulterado, trocado de usuário ou renomeado é recusado na leitura.
// - Nomes de arquivo derivados apenas de hashes hexadecimais: sem path traversal e sem colisão de maiúsculas.
import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdir, open, readFile, readdir, rename, rm, rmdir, stat } from 'node:fs/promises';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const LIMITE_PDF_BYTES = 5 * 1024 * 1024;
const MAGIA = Buffer.from('HHCNH1');
const TAM_IV = 12;
const TAM_TAG = 16;
const ID_SEGURO = /^[A-Za-z0-9_-]{1,128}$/;
const SHA256 = /^[a-f0-9]{64}$/;
const PUBLICO = fileURLToPath(new URL('./public/', import.meta.url));
// Segredos de outros usos nunca podem ser reaproveitados como chave de armazenamento.
const OUTROS_SEGREDOS = ['MERCADOPAGO_ACCESS_TOKEN', 'MERCADOPAGO_WEBHOOK_SECRET', 'MERCADOPAGO_CLIENT_SECRET', 'MERCADOPAGO_OAUTH_STATE_SECRET'];

export function erroStorage(codigo, mensagem = codigo) {
  return Object.assign(new Error(mensagem), { codigo });
}

/** Converte CNH_STORAGE_KEY (64 hex ou base64 de 32 bytes) em Buffer. Retorna { chave } ou { erro }. */
export function interpretarChave(valor, ambiente = process.env) {
  if (typeof valor !== 'string' || !valor.trim()) return { erro: 'CNH_STORAGE_KEY não definida.' };
  const texto = valor.trim();
  let chave = null;
  if (/^[0-9a-fA-F]{64}$/.test(texto)) chave = Buffer.from(texto, 'hex');
  else if (/^[A-Za-z0-9+/_-]{43}=?$/.test(texto)) {
    const candidata = Buffer.from(texto.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
    if (candidata.length === 32) chave = candidata;
  }
  if (!chave) return { erro: 'CNH_STORAGE_KEY deve ter exatamente 32 bytes (64 caracteres hexadecimais ou base64).' };
  if (new Set(chave).size < 8) return { erro: 'CNH_STORAGE_KEY é fraca demais (pouca variação de bytes).' };
  for (const nome of OUTROS_SEGREDOS) {
    if (ambiente[nome] && ambiente[nome].trim() === texto) return { erro: `CNH_STORAGE_KEY deve ser exclusiva e diferente de ${nome}.` };
  }
  return { chave };
}

export function sha256Hex(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

function validarUid(uid) {
  if (typeof uid !== 'string' || !ID_SEGURO.test(uid)) throw erroStorage('uid_invalido', 'UID inválido.');
}
function validarSha(sha) {
  if (typeof sha !== 'string' || !SHA256.test(sha)) throw erroStorage('hash_invalido', 'Hash inválido.');
}

/** Cria o armazenamento. Sem chave válida o resultado é { disponivel:false, motivo } e nada é gravado. */
export function criarArmazenamento({ diretorio, chave: valorChave, ambiente = process.env } = {}) {
  const interpretada = interpretarChave(valorChave, ambiente);
  if (interpretada.erro) return { disponivel: false, motivo: interpretada.erro };
  const raiz = resolve(diretorio);
  const relativoAoPublico = relative(resolve(PUBLICO), raiz);
  if (relativoAoPublico === '' || (!relativoAoPublico.startsWith('..') && !relativoAoPublico.startsWith(sep) && !/^[A-Za-z]:/.test(relativoAoPublico))) {
    return { disponivel: false, motivo: 'O diretório de CNH não pode ficar dentro de public/.' };
  }
  const chave = interpretada.chave;

  const pastaDoUsuario = uid => {
    validarUid(uid);
    return join(raiz, createHash('sha256').update(`hitchhike-cnh-uid:${uid}`).digest('hex'));
  };
  const caminhoArquivo = (uid, sha) => {
    validarSha(sha);
    const caminho = join(pastaDoUsuario(uid), `${sha}.pdf.enc`);
    const rel = relative(raiz, caminho);
    if (rel.startsWith('..') || resolve(caminho) !== caminho) throw erroStorage('caminho_invalido', 'Caminho inválido.');
    return caminho;
  };
  const contexto = (uid, sha) => Buffer.from(`hhcnh1|${uid}|${sha}`);

  function cifrar(uid, sha, claro) {
    const iv = randomBytes(TAM_IV);
    const cifra = createCipheriv('aes-256-gcm', chave, iv);
    cifra.setAAD(contexto(uid, sha));
    const corpo = Buffer.concat([cifra.update(claro), cifra.final()]);
    return Buffer.concat([MAGIA, iv, cifra.getAuthTag(), corpo]);
  }
  function decifrar(uid, sha, arquivo) {
    if (arquivo.length < MAGIA.length + TAM_IV + TAM_TAG + 1 || !arquivo.subarray(0, MAGIA.length).equals(MAGIA)) {
      throw erroStorage('integridade', 'Documento corrompido.');
    }
    const iv = arquivo.subarray(MAGIA.length, MAGIA.length + TAM_IV);
    const tag = arquivo.subarray(MAGIA.length + TAM_IV, MAGIA.length + TAM_IV + TAM_TAG);
    const corpo = arquivo.subarray(MAGIA.length + TAM_IV + TAM_TAG);
    let claro;
    try {
      const decifra = createDecipheriv('aes-256-gcm', chave, iv);
      decifra.setAAD(contexto(uid, sha));
      decifra.setAuthTag(tag);
      claro = Buffer.concat([decifra.update(corpo), decifra.final()]);
    } catch {
      throw erroStorage('integridade', 'Documento corrompido ou adulterado.');
    }
    const calculado = Buffer.from(sha256Hex(claro), 'hex');
    if (!timingSafeEqual(calculado, Buffer.from(sha, 'hex'))) throw erroStorage('integridade', 'Documento corrompido ou adulterado.');
    return claro;
  }

  return {
    disponivel: true,
    diretorio: raiz,

    /** Grava (se ainda não existir) e devolve { sha256, bytes, criado }. A escrita é atômica (tmp + rename). */
    async salvar(uid, pdf) {
      if (!Buffer.isBuffer(pdf) || pdf.length === 0 || pdf.length > LIMITE_PDF_BYTES) throw erroStorage('tamanho_invalido', 'PDF fora do limite.');
      const sha = sha256Hex(pdf);
      const destino = caminhoArquivo(uid, sha);
      try {
        await stat(destino);
        // Já existe (mesmo conteúdo para o mesmo usuário): confirma que continua legível antes de reaproveitar.
        decifrar(uid, sha, await readFile(destino));
        return { sha256: sha, bytes: pdf.length, criado: false };
      } catch (erro) {
        if (erro.codigo === 'integridade') await rm(destino, { force: true });
        else if (erro.code !== 'ENOENT') throw erroStorage('gravacao', 'Falha ao ler o armazenamento.');
      }
      const temporario = `${destino}.${randomBytes(8).toString('hex')}.tmp`;
      try {
        await mkdir(dirname(destino), { recursive: true, mode: 0o700 });
        const arquivo = await open(temporario, 'wx', 0o600);
        try {
          await arquivo.writeFile(cifrar(uid, sha, pdf));
          await arquivo.sync();
        } finally {
          await arquivo.close();
        }
        await rename(temporario, destino);
      } catch {
        await rm(temporario, { force: true }).catch(() => {});
        throw erroStorage('gravacao', 'Falha ao gravar o documento.');
      }
      return { sha256: sha, bytes: pdf.length, criado: true };
    },

    async existe(uid, sha) {
      try { await stat(caminhoArquivo(uid, sha)); return true; } catch (erro) {
        if (erro.code === 'ENOENT') return false;
        throw erroStorage('leitura', 'Falha ao consultar o armazenamento.');
      }
    },

    /** Lê, autentica (GCM + contexto + hash) e devolve o PDF. */
    async ler(uid, sha) {
      const caminho = caminhoArquivo(uid, sha);
      let arquivo;
      try { arquivo = await readFile(caminho); } catch (erro) {
        if (erro.code === 'ENOENT') throw erroStorage('nao_encontrado', 'Documento não encontrado.');
        throw erroStorage('leitura', 'Falha ao ler o documento.');
      }
      return decifrar(uid, sha, arquivo);
    },

    async remover(uid, sha) {
      await rm(caminhoArquivo(uid, sha), { force: true });
    },

    /** Apaga todo arquivo do usuário, exceto o hash informado (ou todos, com null). Retorna quantos removeu. */
    async limparExceto(uid, manterSha) {
      if (manterSha !== null) validarSha(manterSha);
      const pasta = pastaDoUsuario(uid);
      let nomes;
      try { nomes = await readdir(pasta); } catch (erro) {
        if (erro.code === 'ENOENT') return 0;
        throw erroStorage('leitura', 'Falha ao listar o armazenamento.');
      }
      let removidos = 0;
      for (const nome of nomes) {
        if (manterSha !== null && nome === `${manterSha}.pdf.enc`) continue;
        await rm(join(pasta, nome), { force: true, recursive: false });
        removidos++;
      }
      if (manterSha === null) await rmdir(pasta).catch(() => {});
      return removidos;
    }
  };
}

let armazenamentoTeste = null;
let padrao = null;
let avisado = false;

export function definirArmazenamentoParaTeste(armazenamento) {
  if (!process.env.NODE_TEST_CONTEXT) throw new Error('Provedor de teste indisponível.');
  armazenamentoTeste = armazenamento;
}

/** Armazenamento configurado pelo ambiente (CNH_STORAGE_KEY, CNH_STORAGE_DIR). */
export function armazenamentoPadrao() {
  if (armazenamentoTeste) return armazenamentoTeste;
  if (!padrao) {
    padrao = criarArmazenamento({
      diretorio: process.env.CNH_STORAGE_DIR || resolve('data/cnh_pdfs'),
      chave: process.env.CNH_STORAGE_KEY
    });
  }
  return padrao;
}

/** Aviso único, sem valores secretos, na inicialização do servidor. */
export function avisarConfiguracaoCnh() {
  if (avisado) return;
  avisado = true;
  const a = armazenamentoPadrao();
  if (!a.disponivel) console.warn(`Envio de CNH pelo aplicativo desativado: ${a.motivo}`);
}

// API autenticada (Firebase ID Token) para o aplicativo Android enviar e gerenciar o PDF da própria CNH.
//   POST   /api/motoristas/cnh          envia/renova (application/pdf ou JSON { pdf: "data:application/pdf;base64,...", placa?, modelo?, cor? })
//   GET    /api/motoristas/cnh/status   situação do envio e da conferência
//   GET    /api/motoristas/cnh/arquivo  baixa o PDF do próprio titular
//   DELETE /api/motoristas/cnh/arquivo  exclui o PDF do próprio titular
// O UID vem exclusivamente do token verificado. Nada sensível é registrado em log.
import { getApps } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { banco as bancoPadrao } from './firebaseMotoristas.js';
import { validarPdfCnh } from './ocr.js';
import { servicoDocumentos } from './cnhDocumentos.js';
import { LIMITE_PDF_BYTES } from './cnhStorage.js';

const DOMINIOS_INSTITUCIONAIS = ['senacsp.edu.br', 'sp.senac.br', 'senac.br'];
const ID_SEGURO = /^[A-Za-z0-9_-]{1,128}$/;
const LIMITE_JSON_BYTES = 7_100_000;
const DATA_URL_PDF = /^data:application\/pdf;base64,[A-Za-z0-9+/=]+$/;

const falhar = (status, mensagem, extra = {}) => { throw Object.assign(new Error(mensagem), { status, ...extra }); };

function json(res, status, dados, extra = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...extra });
  res.end(JSON.stringify(dados));
}

export function emailInstitucional(email) {
  if (typeof email !== 'string') return false;
  const m = /^[^@\s]+@([^@\s]+)$/.exec(email.trim().toLowerCase());
  return Boolean(m) && DOMINIOS_INSTITUCIONAIS.includes(m[1]);
}

export function criarLimitador({ max, janelaMs, intervaloMs = 0, relogio = () => Date.now() }) {
  const registros = new Map();
  return {
    tentar(chave) {
      const agora = relogio();
      const recentes = (registros.get(chave) || []).filter(t => agora - t < janelaMs);
      const ultimo = recentes.at(-1);
      if (intervaloMs && ultimo !== undefined && agora - ultimo < intervaloMs) return { ok: false, espera: Math.ceil((intervaloMs - (agora - ultimo)) / 1000) };
      if (recentes.length >= max) return { ok: false, espera: Math.ceil((janelaMs - (agora - recentes[0])) / 1000) };
      recentes.push(agora);
      registros.set(chave, recentes);
      if (registros.size > 5000) for (const [k, v] of registros) if (!v.some(t => agora - t < janelaMs)) registros.delete(k);
      return { ok: true };
    }
  };
}

async function lerCorpo(req, limite, prazoMs) {
  const partes = [];
  let total = 0;
  let temporizador;
  const leitura = (async () => {
    for await (const pedaco of req) {
      total += pedaco.length;
      if (total > limite) falhar(413, 'Arquivo muito grande. O limite é 5 MB.');
      partes.push(pedaco);
    }
    return Buffer.concat(partes);
  })();
  leitura.catch(() => {});
  const prazo = new Promise((_, rejeitar) => { temporizador = setTimeout(() => rejeitar(Object.assign(new Error('Tempo esgotado ao receber o arquivo.'), { status: 408 })), prazoMs); });
  try { return await Promise.race([leitura, prazo]); } finally { clearTimeout(temporizador); }
}

export function criarTratadorCnhMotorista(deps = {}) {
  const d = {
    banco: () => bancoPadrao(),
    verificarToken: async token => {
      const app = getApps()[0];
      if (!app) throw new Error('Firebase Admin indisponível.');
      return getAuth(app).verifyIdToken(token, true);
    },
    validarPdf: validarPdfCnh,
    documentos: servicoDocumentos,
    relogio: () => Date.now(),
    limites: {},
    ...deps
  };
  const lim = {
    envio: { max: 5, janelaMs: 3_600_000, intervaloMs: 15_000 },
    consulta: { max: 120, janelaMs: 60_000 },
    corposSimultaneos: 3,
    leituraMs: 60_000,
    ...d.limites
  };
  const limiteEnvio = criarLimitador({ ...lim.envio, relogio: d.relogio });
  const limiteConsulta = criarLimitador({ ...lim.consulta, relogio: d.relogio });
  let recebendo = 0;

  async function autenticar(req) {
    const m = /^Bearer ([A-Za-z0-9._-]{20,4096})$/.exec(req.headers.authorization || '');
    if (!m) falhar(401, 'Faça login para continuar.');
    const db = await d.banco();
    if (!db) falhar(503, 'Integração com o Firebase indisponível.');
    let token;
    try { token = await d.verificarToken(m[1]); } catch { falhar(401, 'Sessão inválida ou expirada.'); }
    if (!token?.uid || !ID_SEGURO.test(token.uid)) falhar(401, 'Sessão inválida ou expirada.');
    if (token.email_verified !== true) falhar(403, 'Confirme seu e-mail institucional para continuar.');
    if (!emailInstitucional(token.email)) falhar(403, 'Use sua conta com e-mail institucional.');
    let perfil;
    try { perfil = await db.collection('usuarios').doc(token.uid).get(); } catch { falhar(503, 'Não foi possível validar seu cadastro agora.'); }
    const dados = perfil.exists ? perfil.data() || {} : null;
    if (!dados || dados.status !== 'ATIVO') falhar(403, 'Seu cadastro não está ativo.');
    if (typeof dados.email === 'string' && dados.email.trim().toLowerCase() !== token.email.trim().toLowerCase()) falhar(403, 'O cadastro não corresponde à conta autenticada.');
    return token.uid;
  }

  function limitar(limitador, uid) {
    const r = limitador.tentar(uid);
    if (!r.ok) falhar(429, 'Muitas tentativas. Aguarde um pouco e tente novamente.', { retryAfter: r.espera });
  }

  async function extrairPdf(req) {
    const tipo = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
    if (tipo !== 'application/pdf' && tipo !== 'application/json') falhar(415, 'Envie o PDF da CNH (application/pdf ou JSON). Imagens não são aceitas.');
    const limite = tipo === 'application/pdf' ? LIMITE_PDF_BYTES + 1 : LIMITE_JSON_BYTES;
    const declarado = Number(req.headers['content-length']);
    if (Number.isFinite(declarado) && declarado > limite) falhar(413, 'Arquivo muito grande. O limite é 5 MB.');
    if (recebendo >= lim.corposSimultaneos) falhar(429, 'Servidor ocupado recebendo outros arquivos. Tente novamente.', { retryAfter: 10 });
    recebendo++;
    let corpo;
    try { corpo = await lerCorpo(req, limite, lim.leituraMs); } finally { recebendo--; }
    if (tipo === 'application/pdf') return { pdf: corpo, veiculo: null };
    let dados;
    try { dados = JSON.parse(corpo.toString('utf8')); } catch { falhar(400, 'JSON inválido.'); }
    if (typeof dados?.pdf !== 'string') falhar(400, 'Envie o campo pdf com o arquivo em Base64.');
    if (!DATA_URL_PDF.test(dados.pdf)) {
      if (dados.pdf.startsWith('data:') && !dados.pdf.startsWith('data:application/pdf;')) falhar(415, 'Aceitamos somente PDF. Imagens não são aceitas.');
      falhar(400, 'Arquivo PDF inválido.');
    }
    const temVeiculo = ['placa', 'modelo', 'cor'].some(k => dados[k] !== undefined);
    return { pdf: Buffer.from(dados.pdf.slice(dados.pdf.indexOf(',') + 1), 'base64'), veiculo: temVeiculo ? { placa: dados.placa, modelo: dados.modelo, cor: dados.cor } : null };
  }

  async function enviar(req, res, uid) {
    limitar(limiteEnvio, uid); // antes de ler o corpo e de qualquer processamento
    const { pdf, veiculo } = await extrairPdf(req);
    await d.validarPdf(pdf); // PDF de 1 página, sem senha, com QR legível (mesmo código do OCR do painel)
    const r = await d.documentos.receber(uid, { pdf, veiculo });
    json(res, 201, { ok: true, status: 'PENDENTE', versao: r.versao, recebidoEm: r.recebidoEm, aprovacaoRetirada: r.aprovacaoRetirada, substituiu: r.substituiu });
  }

  return async function tratarCnhMotorista(req, res, url) {
    try {
      const caminho = url.pathname;
      const permitidos = { '/api/motoristas/cnh': ['POST'], '/api/motoristas/cnh/status': ['GET'], '/api/motoristas/cnh/arquivo': ['GET', 'DELETE'] }[caminho];
      if (!permitidos) falhar(404, 'Rota não encontrada.');
      if (!permitidos.includes(req.method)) falhar(405, 'Método não permitido.', { allow: permitidos.join(', ') });
      const uid = await autenticar(req);
      if (caminho === '/api/motoristas/cnh') {
        await enviar(req, res, uid);
      } else {
        limitar(limiteConsulta, uid);
        if (caminho.endsWith('/status')) {
          json(res, 200, await d.documentos.status(uid));
        } else if (req.method === 'GET') {
          const { pdf } = await d.documentos.lerArquivo(uid);
          res.writeHead(200, { 'Content-Type': 'application/pdf', 'Content-Length': pdf.length, 'Content-Disposition': 'inline; filename="cnh.pdf"', 'Cache-Control': 'no-store', Pragma: 'no-cache', 'X-Content-Type-Options': 'nosniff' });
          res.end(pdf);
        } else {
          const r = await d.documentos.excluir(uid, { ator: 'titular', motivo: 'Solicitação do titular' });
          json(res, 200, { ok: true, limpezaPendente: r.limpezaPendente });
        }
      }
    } catch (erro) {
      if (erro.status) {
        const extra = {};
        if (erro.retryAfter) extra['Retry-After'] = String(erro.retryAfter);
        else if (erro.status === 429) extra['Retry-After'] = '5';
        if (erro.allow) extra.Allow = erro.allow;
        if (erro.status === 408 || erro.status === 413) { extra.Connection = 'close'; res.once('finish', () => req.destroy()); }
        json(res, erro.status, { error: erro.message }, extra);
      } else {
        console.error(`Falha inesperada no módulo de CNH do motorista (${erro.name}).`);
        json(res, 500, { error: 'Erro interno. Tente novamente.' });
      }
    }
    return true;
  };
}

let instancia = null;

/** Somente em testes: substitui o tratador (verificador de token, limites, validador). */
export function definirTratadorParaTeste(deps) {
  if (!process.env.NODE_TEST_CONTEXT) throw new Error('Provedor de teste indisponível.');
  instancia = deps ? criarTratadorCnhMotorista(deps) : null;
}

export function tratarCnhMotorista(req, res, url) {
  instancia ||= criarTratadorCnhMotorista();
  return instancia(req, res, url);
}

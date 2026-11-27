import { existsSync, readFileSync } from 'node:fs';
import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { FieldPath, getFirestore } from 'firebase-admin/firestore';
import { avaliarRegistroCnh, conferenciaVinculada, fimDoDiaSP, identidadeDivergenteNaoResolvida } from './cnh.js';

const PROJETO = 'hitchhike-9568f';
const CAMPOS_SOLICITACAO = ['placa', 'modelo', 'cor', 'status'];
const CAMPOS_USUARIO = ['nome', 'email', 'curso', 'instituicao'];
const MENSAGEM_PADRAO = 'Integração com o Firebase indisponível. O painel local continua ativo.';

let firestore = null;
let detalhe = MENSAGEM_PADRAO;
let provedorTeste = null;
let auditoriaTeste = null;

function erroDecisao(codigo) {
  const erro = new Error(codigo);
  erro.codigoDecisao = codigo;
  return erro;
}

function indisponivel(mensagem = detalhe) {
  return { disponivel: false, mensagem, solicitacoes: [] };
}

export function textoSeguro(valor, max = 300) {
  if (typeof valor !== 'string') return '';
  return valor.trim().slice(0, max);
}

export function dataIso(valor) {
  try {
    const data = typeof valor?.toDate === 'function' ? valor.toDate() : valor;
    if (data instanceof Date && !Number.isNaN(data.getTime())) return data.toISOString();
  } catch { /* timestamp inválido permanece vazio */ }
  return '';
}

export function montarSolicitacao(uid, solicitacao, usuario) {
  if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) return null;
  const item = { uid };
  for (const campo of CAMPOS_SOLICITACAO) item[campo] = textoSeguro(solicitacao?.[campo], campo === 'status' ? 40 : 100);
  item.criadoEm = dataIso(solicitacao?.criadoEm);
  for (const campo of CAMPOS_USUARIO) item[campo] = textoSeguro(usuario?.[campo], campo === 'email' ? 254 : 200);
  return item;
}

export function definirProvedorParaTeste(provedor) {
  if (!process.env.NODE_TEST_CONTEXT) throw new Error('Provedor de teste indisponível.');
  provedorTeste = provedor;
}

export function definirAuditoriaParaTeste(fn) {
  if (!process.env.NODE_TEST_CONTEXT) throw new Error('Provedor de teste indisponível.');
  auditoriaTeste = fn;
}

export function auditoriaDeTeste() {
  return auditoriaTeste;
}

export async function banco() {
  if (provedorTeste) return provedorTeste();
  return obterFirestore();
}

export function obterFirestore() {
  if (firestore) return firestore;
  const caminho = process.env.FIREBASE_SERVICE_ACCOUNT_PATH;
  if (!caminho) {
    detalhe = 'A variável FIREBASE_SERVICE_ACCOUNT_PATH não está definida. O painel local continua ativo.';
    return null;
  }
  if (!existsSync(caminho)) {
    detalhe = 'O arquivo indicado por FIREBASE_SERVICE_ACCOUNT_PATH não foi encontrado. O painel local continua ativo.';
    return null;
  }
  let conta;
  try {
    conta = JSON.parse(readFileSync(caminho, 'utf8'));
  } catch {
    detalhe = 'Não foi possível ler o arquivo de credenciais do Firebase. O painel local continua ativo.';
    return null;
  }
  if (conta?.project_id !== PROJETO || typeof conta.private_key !== 'string' || typeof conta.client_email !== 'string') {
    detalhe = 'A credencial não corresponde ao projeto hitchhike-9568f. O painel local continua ativo.';
    return null;
  }
  try {
    const app = getApps().length ? getApps()[0] : initializeApp({ credential: cert(conta), projectId: PROJETO });
    firestore = getFirestore(app);
    return firestore;
  } catch {
    detalhe = 'Não foi possível iniciar o Firebase Admin SDK. O painel local continua ativo.';
    return null;
  }
}

async function lerSolicitacoes(db) {
  try {
    const consulta = await db.collection('solicitacoes_motoristas').limit(100).get();
    const docs = consulta.docs || [];
    const usuarios = docs.length ? await db.getAll(...docs.map(doc => db.collection('usuarios').doc(doc.id))) : [];
    const porUid = new Map(usuarios.map(doc => [doc.id, doc.exists ? doc.data() : {}]));
    const solicitacoes = docs
      .map(doc => montarSolicitacao(doc.id, doc.data(), porUid.get(doc.id)))
      .filter(Boolean)
      .sort((a, b) => (b.criadoEm || '').localeCompare(a.criadoEm || ''));
    return { disponivel: true, mensagem: '', solicitacoes };
  } catch {
    console.error('Falha ao consultar Firestore.');
    return indisponivel('Não foi possível consultar as solicitações no Firestore. O painel local continua ativo.');
  }
}

export async function listarSolicitacoesMotoristas() {
  const db = await banco();
  if (!db) return indisponivel();
  return lerSolicitacoes(db);
}

export function montarUsuario(uid, dados) {
  if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) return null;
  return {
    uid,
    nome: textoSeguro(dados?.nome, 200),
    email: textoSeguro(dados?.email, 254),
    curso: textoSeguro(dados?.curso, 200),
    instituicao: textoSeguro(dados?.instituicao, 200),
    papelAtivo: textoSeguro(dados?.papelAtivo, 40),
    status: textoSeguro(dados?.status, 40),
    condutorValidado: dados?.condutorValidado === true
  };
}

function semUsuarios(mensagem = detalhe) {
  return { disponivel: false, mensagem, limitado: false, usuarios: [] };
}

export async function listarUsuarios() {
  const db = await banco();
  if (!db) return semUsuarios();
  try {
    const consulta = await db.collection('usuarios').orderBy(FieldPath.documentId()).limit(101).get();
    const docs = consulta.docs || [];
    const usuarios = docs
      .slice(0, 100)
      .map(doc => montarUsuario(doc.id, doc.data()))
      .filter(Boolean)
      .sort((a, b) => a.nome.localeCompare(b.nome, 'pt'));
    return { disponivel: true, mensagem: '', limitado: docs.length > 100, usuarios };
  } catch {
    console.error('Falha ao consultar usuários no Firestore.');
    return semUsuarios('Não foi possível consultar os usuários no Firestore. O painel local continua ativo.');
  }
}

function numeroFinito(valor) {
  return typeof valor === 'number' && Number.isFinite(valor) ? valor : null;
}

function millisSaida(valor) {
  try {
    const data = typeof valor?.toDate === 'function' ? valor.toDate() : null;
    if (data instanceof Date && !Number.isNaN(data.getTime())) return data.getTime();
  } catch { /* timestamp ausente não vira data */ }
  return null;
}

export function statusExibicaoCarona(statusArmazenado, saidaMillis, vagasDisponiveis, agoraMillis) {
  if (statusArmazenado === 'CANCELADA') return 'CANCELADA';
  if (typeof saidaMillis === 'number' && saidaMillis <= agoraMillis) return 'ENCERRADA';
  const vagas = typeof vagasDisponiveis === 'number' ? vagasDisponiveis : 0;
  if (vagas <= 0 || statusArmazenado === 'LOTADA') return 'LOTADA';
  return 'ABERTA';
}

export function montarCarona(id, dados, nomes, agoraMillis) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(id)) return null;
  const saidaMillis = millisSaida(dados?.dataHoraSaida);
  const vagasTotais = numeroFinito(dados?.vagasTotais);
  const vagasDisponiveis = numeroFinito(dados?.vagasDisponiveis);
  const valor = numeroFinito(dados?.valor);
  const uid = typeof dados?.motoristaUid === 'string' ? dados.motoristaUid : '';
  const statusArmazenado = textoSeguro(dados?.status, 40);
  return {
    id,
    motorista: nomes.get(uid) || '',
    origem: textoSeguro(dados?.origem, 200),
    destino: textoSeguro(dados?.destino, 200),
    saida: saidaMillis === null ? '' : new Date(saidaMillis).toISOString(),
    vagasTotais,
    vagasDisponiveis,
    vagasOcupadas: vagasTotais === null || vagasDisponiveis === null ? null : Math.max(0, vagasTotais - vagasDisponiveis),
    valor,
    statusArmazenado,
    statusExibicao: statusExibicaoCarona(statusArmazenado, saidaMillis, vagasDisponiveis, agoraMillis)
  };
}

function semCaronas(mensagem = detalhe) {
  return { disponivel: false, mensagem, limitado: false, indicadores: { abertas: 0, lotadas: 0, encerradas: 0, canceladas: 0 }, caronas: [] };
}

function indicadoresCaronas(caronas) {
  const contar = tipo => caronas.filter(carona => carona.statusExibicao === tipo).length;
  return { abertas: contar('ABERTA'), lotadas: contar('LOTADA'), encerradas: contar('ENCERRADA'), canceladas: contar('CANCELADA') };
}

export async function listarCaronas(agoraMillis = Date.now()) {
  const db = await banco();
  if (!db) return semCaronas();
  try {
    const consulta = await db.collection('caronas').orderBy(FieldPath.documentId()).limit(101).get();
    const docs = consulta.docs || [];
    const selecionados = docs.slice(0, 100);
    const uids = [...new Set(selecionados.map(doc => doc.data()?.motoristaUid).filter(uid => typeof uid === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(uid)))];
    const perfis = uids.length ? await db.getAll(...uids.map(uid => db.collection('usuarios').doc(uid))) : [];
    const nomes = new Map(perfis.map(doc => [doc.id, textoSeguro(doc.exists ? doc.data()?.nome : '', 200)]));
    const caronas = selecionados
      .map(doc => montarCarona(doc.id, doc.data(), nomes, agoraMillis))
      .filter(Boolean)
      .sort((a, b) => (b.saida || '').localeCompare(a.saida || ''));
    return { disponivel: true, mensagem: '', limitado: docs.length > 100, indicadores: indicadoresCaronas(caronas), caronas };
  } catch {
    console.error('Falha ao consultar caronas no Firestore.');
    return semCaronas('Não foi possível consultar as caronas no Firestore. O painel local continua ativo.');
  }
}

const ERROS_DECISAO = {
  cnh_documento_divergente: [409, 'A conferência registrada não corresponde ao PDF atual do motorista. Reabra a conferência e analise o documento enviado.'],
  cnh_identidade_divergente: [409, 'Há divergência de identidade não resolvida entre o documento e o cadastro do usuário. Confira e resolva a divergência antes de aprovar.'],
  cnh_pendente: [409, 'Confira a CNH e o QR no Vio, com categoria compatível e validade vigente, antes de aprovar.'],
  nao_encontrada: [404, 'Solicitação não encontrada.'],
  ja_analisada: [409, 'Esta solicitação já foi analisada.'],
  uid_divergente: [409, 'A solicitação não pertence a este usuário.'],
  usuario_inativo: [409, 'Só é possível aprovar uma solicitação pendente de um usuário ativo.'],
  aprovacao_anterior: [409, 'Esta conta já possui aprovação de motorista. A recusa foi interrompida para não desfazer essa aprovação.']
};

export async function decidirSolicitacaoMotorista(uid, decisao, motivoInformado = '') {
  if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) {
    return { ok: false, status: 400, error: 'UID inválido.' };
  }
  if (decisao !== 'aprovar' && decisao !== 'recusar') {
    return { ok: false, status: 400, error: 'Decisão inválida.' };
  }
  const db = await banco();
  if (!db) {
    return { ok: false, status: 503, error: 'Integração com o Firebase indisponível. Nenhuma decisão foi aplicada.' };
  }
  try {
    const aplicado = await db.runTransaction(async tx => {
      const solicitacaoRef = db.collection('solicitacoes_motoristas').doc(uid);
      const usuarioRef = db.collection('usuarios').doc(uid);
      const conferenciaRef = db.collection('cnh_admin').doc(uid);
      const solicitacaoDoc = await tx.get(solicitacaoRef);
      const usuarioDoc = await tx.get(usuarioRef);
      if (!solicitacaoDoc.exists) throw erroDecisao('nao_encontrada');
      const solicitacao = solicitacaoDoc.data() || {};
      if (Object.hasOwn(solicitacao, 'motoristaUid') && solicitacao.motoristaUid !== uid) throw erroDecisao('uid_divergente');
      const usuario = usuarioDoc.exists ? usuarioDoc.data() || {} : null;
      if (solicitacao.status !== 'PENDENTE' && !(decisao==='aprovar' && solicitacao.status==='APROVADA' && usuario?.condutorValidado===false)) throw erroDecisao('ja_analisada');
      const conferencia = await tx.get(conferenciaRef);
      const registro = conferencia.exists ? conferencia.data() : null;
      if (decisao === 'aprovar') {
        if (!usuario || usuario.status !== 'ATIVO') throw erroDecisao('usuario_inativo');
        if(registro&&!conferenciaVinculada(registro))throw erroDecisao('cnh_documento_divergente');if(registro&&identidadeDivergenteNaoResolvida(registro,usuario.nome||''))throw erroDecisao('cnh_identidade_divergente');if(!registro||!avaliarRegistroCnh(registro,undefined,usuario.nome||'').apta)throw erroDecisao('cnh_pendente');
        tx.update(usuarioRef, { condutorValidado: true, papelAtivo: 'MOTORISTA', cnhValidade: registro.validade, cnhValidadeFimMs: fimDoDiaSP(registro.validade), cnhRevisaoNecessaria: false });
        tx.update(conferenciaRef, { decisaoPdfSha256: registro.pdfSha256 ?? null, decisaoPdfVersao: registro.pdfVersao ?? null, aprovadoEm: new Date().toISOString() });
        tx.update(solicitacaoRef, { status: 'APROVADA', motivoRecusa: null });
        return { statusSolicitacao: 'APROVADA', acao: 'motorista aprovado', motivo: 'Solicitação aprovada no Firestore' };
      }
      if (usuario?.condutorValidado === true) throw erroDecisao('aprovacao_anterior');
      const motivoExplicito = textoSeguro(motivoInformado, 500);
      const parecerVinculado = registro && conferenciaVinculada(registro) ? textoSeguro(registro.observacao, 500) : '';
      const motivoRecusa = motivoExplicito || parecerVinculado || null;
      const camposRecusa = motivoRecusa ? { status: 'RECUSADA', motivoRecusa, recusadoEm: new Date().toISOString() } : { status: 'RECUSADA' };
      tx.update(solicitacaoRef, camposRecusa);
      if (usuario) {
        tx.update(usuarioRef, { condutorValidado: false, cnhRevisaoNecessaria: false, ...(usuario.papelAtivo === 'MOTORISTA' ? { papelAtivo: 'PASSAGEIRO' } : {}) });
      }
      return { statusSolicitacao: 'RECUSADA', acao: 'motorista recusado', motivo: motivoRecusa ? `Solicitação recusada no Firestore: ${motivoRecusa}` : 'Solicitação recusada no Firestore', motivoRecusa };
    });
    return { ok: true, uid, ...aplicado };
  } catch (erro) {
    const tratado = ERROS_DECISAO[erro.codigoDecisao];
    if (tratado) return { ok: false, status: tratado[0], error: tratado[1] };
    console.error('Falha ao decidir solicitação no Firestore.');
    return { ok: false, status: 503, error: 'Não foi possível registrar a decisão no Firestore. Nenhuma alteração foi confirmada.' };
  }
}

/** Monta a linha financeira do painel. Valores estimados e reconciliados ficam separados. */
export function montarPagamento(id, dados) {
  if (!dados || typeof dados !== 'object') return null;
  const c = valor => (Number.isSafeInteger(valor) ? valor : null);
  return {
    id: textoSeguro(id, 128),
    caronaId: textoSeguro(dados.caronaId, 128),
    status: textoSeguro(dados.status, 30),
    mpStatus: textoSeguro(dados.mpStatus, 30),
    ambiente: textoSeguro(dados.ambiente, 20),
    split: dados.split === true,
    somentePix: dados.somentePix === true,
    valorBaseCentavos: c(dados.valorBaseCentavos),
    taxaCentavos: c(dados.taxaCentavos),
    totalCentavos: c(dados.totalCentavos),
    // Estimativas calculadas na criação da preferência (não são repasse comprovado).
    tarifaEstimadaCentavos: c(dados.tarifaEstimadaCentavos),
    tarifaEstimadaBp: c(dados.tarifaEstimadaBp),
    marketplaceFeeCentavos: c(dados.marketplaceFeeCentavos),
    motoristaPrevistoCentavos: c(dados.motoristaPrevistoCentavos),
    // Valores reais, só preenchidos após conferência com a API do Mercado Pago.
    reconciliacao: textoSeguro(dados.reconciliacao, 20),
    tarifaRealCentavos: c(dados.tarifaRealCentavos),
    comissaoRealCentavos: c(dados.comissaoRealCentavos),
    liquidoMotoristaCentavos: c(dados.liquidoMotoristaCentavos),
    diferencaMotoristaCentavos: c(dados.diferencaMotoristaCentavos),
    divergenciaFinanceira: dados.divergenciaFinanceira === true,
    criadoEm: dataIso(dados.criadoEm)
  };
}

export async function listarPagamentos() {
  const vazio = mensagem => ({ disponivel: false, mensagem, limitado: false, pagamentos: [] });
  const db = await banco();
  if (!db) return vazio(detalhe || 'Integração com o Firebase indisponível.');
  try {
    const consulta = await db.collection('pagamentos').orderBy(FieldPath.documentId()).limit(101).get();
    const docs = consulta.docs || [];
    const pagamentos = docs
      .slice(0, 100)
      .map(doc => montarPagamento(doc.id, doc.data()))
      .filter(Boolean)
      .sort((a, b) => (b.criadoEm || '').localeCompare(a.criadoEm || ''));
    return { disponivel: true, mensagem: '', limitado: docs.length > 100, pagamentos };
  } catch {
    console.error('Falha ao consultar pagamentos no Firestore.');
    return vazio('Não foi possível consultar os pagamentos no Firestore. O painel local continua ativo.');
  }
}

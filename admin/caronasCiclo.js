// Regras puras do ciclo de vida das caronas, reputação e privacidade (sem acesso a banco ou rede).
// Tudo que decide estado, elegibilidade, liberação de avaliações e nomes exibidos fica aqui para ser testado isoladamente.
import { fimDoDiaSP } from './cnh.js';

export const MINUTO = 60_000;
export const HORA = 60 * MINUTO;
export const DIA = 24 * HORA;

export const JANELA_INICIO_ANTES_MS = 30 * MINUTO; // início permitido a partir de 30 min antes da saída
export const EXPIRA_APOS_SAIDA_MS = 6 * HORA; // sem início até 6 h após a saída → EXPIRADA
export const EXPIRA_EM_ANDAMENTO_MS = 24 * HORA; // sem conclusão até 24 h após o início → EXPIRADA
export const JANELA_CONFIRMACAO_MS = 14 * DIA; // prazo do passageiro para confirmar participação/conclusão
export const LIBERACAO_AVALIACAO_MS = 48 * HORA; // revelação automática 48 h após a conclusão confirmada
export const PRAZO_AVALIAR_MS = 14 * DIA; // prazo para avaliar após a conclusão confirmada

export const STATUS_CARONA = Object.freeze(['ABERTA', 'LOTADA', 'EM_ANDAMENTO', 'CONCLUIDA', 'CANCELADA', 'EXPIRADA']);
export const STATUS_FINAIS = Object.freeze(['CONCLUIDA', 'CANCELADA', 'EXPIRADA']);

/** Transições permitidas no armazenamento (nunca retroativas). */
const TRANSICOES = {
  ABERTA: ['LOTADA', 'EM_ANDAMENTO', 'CANCELADA', 'EXPIRADA'],
  LOTADA: ['EM_ANDAMENTO', 'CANCELADA', 'EXPIRADA'],
  EM_ANDAMENTO: ['CONCLUIDA', 'EXPIRADA'],
  CONCLUIDA: [],
  CANCELADA: [],
  EXPIRADA: []
};

export function transicaoPermitida(de, para) {
  return Array.isArray(TRANSICOES[de]) && TRANSICOES[de].includes(para);
}

export function millis(valor) {
  if (valor === null || valor === undefined) return null;
  if (typeof valor === 'number') return Number.isFinite(valor) ? valor : null;
  if (valor instanceof Date) return Number.isNaN(valor.getTime()) ? null : valor.getTime();
  try {
    if (typeof valor.toMillis === 'function') {
      const n = valor.toMillis();
      return Number.isFinite(n) ? n : null;
    }
    if (typeof valor.toDate === 'function') {
      const d = valor.toDate();
      return d instanceof Date && !Number.isNaN(d.getTime()) ? d.getTime() : null;
    }
  } catch { /* valor inválido não vira data */ }
  return null;
}

export function iso(valor) {
  const ms = millis(valor);
  return ms === null ? null : new Date(ms).toISOString();
}

/**
 * Estado exibido/efetivo. O armazenado manda; o tempo só expira estados abertos.
 * Registros antigos (ABERTA/LOTADA no passado) nunca viram CONCLUIDA: no máximo AGUARDANDO_INICIO ou EXPIRADA.
 */
export function statusExibicao({ status, saidaMs, vagasDisponiveis, iniciadaEmMs = null }, agoraMs) {
  if (status === 'CANCELADA' || status === 'CONCLUIDA' || status === 'EXPIRADA') return status;
  if (status === 'EM_ANDAMENTO') {
    if (iniciadaEmMs !== null && agoraMs > iniciadaEmMs + EXPIRA_EM_ANDAMENTO_MS) return 'EXPIRADA';
    return 'EM_ANDAMENTO';
  }
  if (status === 'ABERTA' || status === 'LOTADA' || status === 'ENCERRADA') {
    if (typeof saidaMs !== 'number') return 'EXPIRADA';
    if (agoraMs > saidaMs + EXPIRA_APOS_SAIDA_MS) return 'EXPIRADA';
    if (agoraMs >= saidaMs) return 'AGUARDANDO_INICIO';
    const vagas = typeof vagasDisponiveis === 'number' ? vagasDisponiveis : 0;
    return status === 'LOTADA' || vagas <= 0 ? 'LOTADA' : 'ABERTA';
  }
  return 'EXPIRADA';
}

export function exibicaoDeCarona(carona, agoraMs) {
  return statusExibicao({
    status: carona?.status,
    saidaMs: millis(carona?.dataHoraSaida),
    vagasDisponiveis: carona?.vagasDisponiveis,
    iniciadaEmMs: millis(carona?.iniciadaEm)
  }, agoraMs);
}

/** CNH vigente e motorista aprovado, avaliados no servidor com o relógio do servidor. */
export function motoristaApto(usuario, agoraMs) {
  if (!usuario || usuario.status !== 'ATIVO') return false;
  if (usuario.condutorValidado !== true) return false;
  if (usuario.cnhRevisaoNecessaria === true) return false;
  let fim = typeof usuario.cnhValidadeFimMs === 'number' && Number.isFinite(usuario.cnhValidadeFimMs) ? usuario.cnhValidadeFimMs : null;
  if (fim === null && typeof usuario.cnhValidade === 'string' && usuario.cnhValidade) {
    try { fim = fimDoDiaSP(usuario.cnhValidade); } catch { fim = null; }
  }
  return typeof fim === 'number' && Number.isFinite(fim) && fim >= agoraMs;
}

/** Passageiro elegível para histórico compartilhado e avaliações: aceite + participação + conclusão confirmadas. */
export function passageiroElegivel(solicitacao, carona) {
  return carona?.status === 'CONCLUIDA'
    && solicitacao?.status === 'ACEITA'
    && millis(solicitacao.participacaoConfirmadaEm) !== null
    && millis(solicitacao.conclusaoConfirmadaEm) !== null;
}

export function dentroDoPrazoDeAvaliar(solicitacao, agoraMs) {
  const confirmada = millis(solicitacao?.conclusaoConfirmadaEm);
  return confirmada !== null && agoraMs <= confirmada + PRAZO_AVALIAR_MS;
}

export function liberaEmMs(solicitacao) {
  const confirmada = millis(solicitacao?.conclusaoConfirmadaEm);
  return confirmada === null ? null : confirmada + LIBERACAO_AVALIACAO_MS;
}

/** Uma nota só é pública com o par completo ou após 48 h da conclusão confirmada. */
export function avaliacaoRevelada(avaliacao, agoraMs) {
  if (avaliacao?.parCompleto === true) return true;
  const libera = millis(avaliacao?.liberaEm);
  return libera !== null && agoraMs >= libera;
}

export function notaValida(nota) {
  return Number.isInteger(nota) && nota >= 1 && nota <= 5;
}

/** Reputação a partir das avaliações recebidas; usuários sem avaliações reveladas têm media null (nunca zero). */
export function calcularReputacao(avaliacoes, agoraMs) {
  const notas = (avaliacoes || [])
    .filter(a => notaValida(a?.nota) && avaliacaoRevelada(a, agoraMs))
    .map(a => a.nota);
  if (!notas.length) return { media: null, quantidade: 0 };
  const media = notas.reduce((soma, n) => soma + n, 0) / notas.length;
  return { media: Math.round(media * 10) / 10, quantidade: notas.length };
}

/** Primeiro nome + inicial do último sobrenome. Nome único → só ele. Nunca devolve e-mail ou número. */
export function abreviarNome(nomeCompleto) {
  const nome = typeof nomeCompleto === 'string' ? nomeCompleto.trim().replace(/\s+/g, ' ') : '';
  if (!nome || nome.includes('@') || (nome.match(/\d/g) || []).length >= 8) return null;
  const partes = nome.split(' ');
  const primeiro = partes[0];
  if (partes.length === 1) return primeiro;
  const inicial = [...partes[partes.length - 1]][0];
  if (!inicial || !/\p{L}/u.test(inicial)) return null;
  return `${primeiro} ${inicial.toLocaleUpperCase('pt-BR')}.`;
}

/** Bloqueio financeiro do cancelamento a partir dos pagamentos da carona. */
export function bloqueioFinanceiro(pagamentos) {
  const lista = pagamentos || [];
  if (lista.some(p => p?.status === 'APROVADO')) {
    return { codigo: 'PAGAMENTO_APROVADO', mensagem: 'Há pagamento aprovado nesta carona. O cancelamento fica bloqueado até a regularização (estornos ainda não estão disponíveis).' };
  }
  if (lista.some(p => p?.status === 'EM_PROCESSAMENTO' || p?.status === 'DIVERGENTE')) {
    return { codigo: 'PAGAMENTO_EM_ANDAMENTO', mensagem: 'Há pagamento em processamento ou em conferência nesta carona. Aguarde a conclusão para cancelar.' };
  }
  if (lista.some(p => p?.status === 'PENDENTE')) {
    return { codigo: 'CHECKOUT_ABERTO', mensagem: 'Há um checkout de pagamento ainda aberto nesta carona. Cancelar agora poderia deixar um pagamento sem corrida.' };
  }
  return null;
}

/** Uma carona aceita pagamento apenas enquanto o estado efetivo permite. */
export function caronaAceitaPagamento(carona, agoraMs) {
  const status = carona?.status;
  if (status === 'CANCELADA' || status === 'CONCLUIDA' || status === 'EXPIRADA') return false;
  if (millis(carona?.dataHoraSaida) === null && (status === 'ABERTA' || status === 'LOTADA')) return true;
  const estado = exibicaoDeCarona(carona, agoraMs);
  return estado === 'ABERTA' || estado === 'LOTADA' || estado === 'AGUARDANDO_INICIO' || estado === 'EM_ANDAMENTO';
}

// API autenticada (Firebase ID Token) do ciclo de vida das caronas, avaliações às cegas e reputação.
// O aplicativo NÃO escreve mais em caronas/solicitações/avaliações após a criação: toda transição passa por aqui,
// com transações no Firestore e relógio do servidor. Contrato: docs/CARONAS_API.md.
import { getApps } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { FieldValue } from 'firebase-admin/firestore';
import { obterFirestore } from './firebaseMotoristas.js';
import {
  JANELA_CONFIRMACAO_MS, JANELA_INICIO_ANTES_MS, abreviarNome, avaliacaoRevelada, bloqueioFinanceiro,
  calcularReputacao, dentroDoPrazoDeAvaliar, exibicaoDeCarona, iso, liberaEmMs, millis, motoristaApto,
  notaValida, passageiroElegivel, EXPIRA_APOS_SAIDA_MS, EXPIRA_EM_ANDAMENTO_MS
} from './caronasCiclo.js';

const ID_SEGURO = /^[A-Za-z0-9_-]{1,128}$/;
const EMAIL_INSTITUCIONAL = /^[^@\s]+@(senacsp\.edu\.br|sp\.senac\.br|senac\.br)$/i;
const MAX_IDS = 30;
const LIMITE_REQUISICOES = { max: 120, janelaMs: 60_000 };

function falhar(status, codigo, mensagem) {
  throw Object.assign(new Error(mensagem), { status, codigo });
}

function json(res, status, dados) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(dados));
}

async function lerJson(req, limite = 8192) {
  let texto = '';
  for await (const pedaco of req) {
    texto += pedaco;
    if (Buffer.byteLength(texto) > limite) falhar(413, 'REQUISICAO_GRANDE', 'Requisição muito grande.');
  }
  if (!texto.trim()) return {};
  try { return JSON.parse(texto); } catch { return falhar(400, 'JSON_INVALIDO', 'JSON inválido.'); }
}

const idValido = valor => typeof valor === 'string' && ID_SEGURO.test(valor);
const exigirId = (valor, nome = 'identificador') => { if (!idValido(valor)) falhar(400, 'ID_INVALIDO', `O ${nome} informado é inválido.`); return valor; };

const MENSAGEM_ESTADO = {
  CANCELADA: 'Esta carona foi cancelada.',
  CONCLUIDA: 'Esta carona já foi concluída.',
  EXPIRADA: 'O prazo desta carona expirou.',
  EM_ANDAMENTO: 'Esta carona já está em andamento.',
  AGUARDANDO_INICIO: 'O horário de saída desta carona já passou.',
  LOTADA: 'Esta carona não possui mais vagas.',
  ABERTA: 'Esta carona está aberta.'
};

export function criarServicoCaronas(deps = {}) {
  const d = {
    firestore: () => obterFirestore(),
    agoraMs: () => Date.now(),
    serverTimestamp: () => FieldValue.serverTimestamp(),
    ...deps
  };
  const db = () => d.firestore() || falhar(503, 'INDISPONIVEL', 'Integração com o Firebase indisponível.');
  const col = nome => db().collection(nome);
  const solicitacaoId = (caronaId, passageiroUid) => `${caronaId}_${passageiroUid}`;
  const avaliacaoId = (caronaId, avaliador, avaliado) => `${caronaId}_${avaliador}_${avaliado}`;

  async function carregarCarona(tx, caronaId) {
    const doc = await tx.get(col('caronas').doc(caronaId));
    if (!doc.exists) falhar(404, 'CARONA_INEXISTENTE', 'Carona não encontrada.');
    return { ref: col('caronas').doc(caronaId), dados: doc.data() || {} };
  }

  function exigirMotoristaDono(carona, uid) {
    if (!carona.dados.motoristaUid || carona.dados.motoristaUid !== uid) {
      falhar(403, 'NAO_EH_MOTORISTA', 'Somente o motorista responsável por esta carona pode realizar esta ação.');
    }
  }

  async function exigirMotoristaApto(tx, uid, agora) {
    const doc = await tx.get(col('usuarios').doc(uid));
    if (!doc.exists || !motoristaApto(doc.data(), agora)) {
      falhar(409, 'MOTORISTA_NAO_APTO', 'Sua aprovação de motorista ou a validade da CNH não permite esta ação. Verifique a situação da sua CNH.');
    }
  }

  function exigirPedidoDaCarona(pedidoDoc, caronaId, passageiroUid, carona) {
    if (!pedidoDoc.exists) falhar(404, 'CARONA_INEXISTENTE', 'Solicitação não encontrada.');
    const p = pedidoDoc.data() || {};
    if (p.caronaId !== caronaId || p.passageiroUid !== passageiroUid
      || !p.motoristaUid || p.motoristaUid !== carona.dados.motoristaUid || p.passageiroUid === carona.dados.motoristaUid) {
      falhar(409, 'TRANSICAO_INVALIDA', 'Solicitação inconsistente com a carona.');
    }
    return p;
  }

  // ───────────────────────── Solicitações ─────────────────────────

  async function aceitar(uid, caronaId, passageiroUid) {
    exigirId(caronaId, 'identificador da carona'); exigirId(passageiroUid, 'identificador do passageiro');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      const pedidoRef = col('solicitacoes_caronas').doc(solicitacaoId(caronaId, passageiroUid));
      const pedidoDoc = await tx.get(pedidoRef);
      exigirMotoristaDono(carona, uid);
      const pedido = exigirPedidoDaCarona(pedidoDoc, caronaId, passageiroUid, carona);
      if (pedido.status !== 'PENDENTE') falhar(409, 'TRANSICAO_INVALIDA', 'Esta solicitação já foi respondida.');
      const estado = exibicaoDeCarona(carona.dados, agora);
      if (estado !== 'ABERTA') {
        const codigo = estado === 'LOTADA' ? 'SEM_VAGAS' : estado === 'AGUARDANDO_INICIO' ? 'CARONA_VENCIDA' : 'TRANSICAO_INVALIDA';
        falhar(409, codigo, MENSAGEM_ESTADO[estado] || 'A carona não aceita novos passageiros.');
      }
      const vagas = carona.dados.vagasDisponiveis;
      if (!Number.isInteger(vagas) || vagas < 1) falhar(409, 'SEM_VAGAS', MENSAGEM_ESTADO.LOTADA);
      await exigirMotoristaApto(tx, uid, agora);
      const passageiroDoc = await tx.get(col('usuarios').doc(passageiroUid));
      if (!passageiroDoc.exists || (passageiroDoc.data() || {}).status !== 'ATIVO') {
        falhar(409, 'PASSAGEIRO_INATIVO', 'O passageiro nao possui um perfil ativo.');
      }

      const novasVagas = vagas - 1;
      const novoStatus = novasVagas === 0 ? 'LOTADA' : 'ABERTA';
      tx.update(carona.ref, { vagasDisponiveis: novasVagas, status: novoStatus, ultimaSolicitacaoAceitaId: pedidoRef.id, atualizadoEm: d.serverTimestamp() });
      tx.update(pedidoRef, { status: 'ACEITA', aceitaEm: d.serverTimestamp() });
      return { status: 'ACEITA', vagasDisponiveis: novasVagas, caronaStatus: novoStatus };
    });
  }

  async function recusar(uid, caronaId, passageiroUid) {
    exigirId(caronaId, 'identificador da carona'); exigirId(passageiroUid, 'identificador do passageiro');
    return db().runTransaction(async tx => {
      const carona = await carregarCarona(tx, caronaId);
      const pedidoRef = col('solicitacoes_caronas').doc(solicitacaoId(caronaId, passageiroUid));
      const pedidoDoc = await tx.get(pedidoRef);
      exigirMotoristaDono(carona, uid);
      const pedido = exigirPedidoDaCarona(pedidoDoc, caronaId, passageiroUid, carona);
      if (pedido.status !== 'PENDENTE') falhar(409, 'TRANSICAO_INVALIDA', 'Esta solicitação já foi respondida.');
      tx.update(pedidoRef, { status: 'RECUSADA', recusadaEm: d.serverTimestamp() });
      return { status: 'RECUSADA' };
    });
  }

  // ───────────────────────── Ciclo de vida ─────────────────────────

  async function iniciar(uid, caronaId) {
    exigirId(caronaId, 'identificador da carona');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      const pedidos = await tx.get(col('solicitacoes_caronas').where('caronaId', '==', caronaId));
      exigirMotoristaDono(carona, uid);
      const estado = exibicaoDeCarona(carona.dados, agora);
      if (carona.dados.status !== 'ABERTA' && carona.dados.status !== 'LOTADA') {
        falhar(409, 'TRANSICAO_INVALIDA', MENSAGEM_ESTADO[estado] || 'A viagem não pode ser iniciada neste estado.');
      }
      if (estado === 'EXPIRADA') falhar(409, 'CARONA_VENCIDA', MENSAGEM_ESTADO.EXPIRADA);
      const saida = millis(carona.dados.dataHoraSaida);
      if (agora < saida - JANELA_INICIO_ANTES_MS) {
        falhar(409, 'FORA_DA_JANELA', 'A viagem só pode ser iniciada a partir de 30 minutos antes do horário previsto.');
      }
      const docs = pedidos.docs || [];
      const aceitos = docs.filter(p => (p.data() || {}).status === 'ACEITA');
      let ativos = 0;
      for (const p of aceitos) {
        const u = await tx.get(col('usuarios').doc((p.data() || {}).passageiroUid));
        if (u.exists && (u.data() || {}).status === 'ATIVO') ativos++;
      }
      if (ativos < 1) falhar(409, 'SEM_PASSAGEIROS', 'É preciso ao menos um passageiro aceito e ativo para iniciar a viagem.');
      await exigirMotoristaApto(tx, uid, agora);
      tx.update(carona.ref, { status: 'EM_ANDAMENTO', iniciadaEm: d.serverTimestamp(), atualizadoEm: d.serverTimestamp() });
      for (const p of docs) {
        if ((p.data() || {}).status === 'PENDENTE') {
          tx.update(p.ref, { status: 'CANCELADA', statusAnterior: 'PENDENTE', motivoEncerramento: 'VIAGEM_INICIADA', canceladaEm: d.serverTimestamp() });
        }
      }
      return { status: 'EM_ANDAMENTO', passageirosAceitos: ativos };
    });
  }

  async function concluir(uid, caronaId) {
    exigirId(caronaId, 'identificador da carona');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      exigirMotoristaDono(carona, uid);
      if (carona.dados.status !== 'EM_ANDAMENTO') {
        falhar(409, 'TRANSICAO_INVALIDA', carona.dados.status === 'CONCLUIDA' ? MENSAGEM_ESTADO.CONCLUIDA : 'A viagem precisa ser iniciada antes de ser concluída.');
      }
      if (exibicaoDeCarona(carona.dados, agora) === 'EXPIRADA') falhar(409, 'FORA_DA_JANELA', MENSAGEM_ESTADO.EXPIRADA);
      tx.update(carona.ref, { status: 'CONCLUIDA', concluidaEm: d.serverTimestamp(), atualizadoEm: d.serverTimestamp() });
      return { status: 'CONCLUIDA' };
    });
  }

  async function pagamentosDaCarona(leitor, caronaId) {
    const consulta = col('pagamentos').where('caronaId', '==', caronaId);
    const resultado = await leitor.get(consulta);
    return (resultado.docs || []).map(p => p.data() || {});
  }

  async function cancelar(uid, caronaId) {
    exigirId(caronaId, 'identificador da carona');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      const pedidos = await tx.get(col('solicitacoes_caronas').where('caronaId', '==', caronaId));
      const pagamentos = await pagamentosDaCarona(tx, caronaId);
      exigirMotoristaDono(carona, uid);
      if (carona.dados.status !== 'ABERTA' && carona.dados.status !== 'LOTADA') {
        falhar(409, 'TRANSICAO_INVALIDA', MENSAGEM_ESTADO[exibicaoDeCarona(carona.dados, agora)] || 'Esta carona não pode mais ser cancelada.');
      }
      const saida = millis(carona.dados.dataHoraSaida);
      if (typeof saida !== 'number' || agora >= saida) {
        falhar(409, 'FORA_DA_JANELA', 'Só é possível cancelar caronas futuras que ainda não iniciaram.');
      }
      const bloqueio = bloqueioFinanceiro(pagamentos);
      if (bloqueio) falhar(409, bloqueio.codigo, bloqueio.mensagem);
      let canceladas = 0;
      for (const p of pedidos.docs || []) {
        const dados = p.data() || {};
        if (dados.status === 'PENDENTE' || dados.status === 'ACEITA') {
          tx.update(p.ref, { status: 'CANCELADA', statusAnterior: dados.status, motivoEncerramento: 'CARONA_CANCELADA', canceladaEm: d.serverTimestamp() });
          canceladas++;
        }
      }
      tx.update(carona.ref, { status: 'CANCELADA', canceladaEm: d.serverTimestamp(), canceladaPor: uid, atualizadoEm: d.serverTimestamp() });
      return { status: 'CANCELADA', solicitacoesCanceladas: canceladas };
    });
  }

  async function confirmarParticipacao(uid, caronaId) {
    exigirId(caronaId, 'identificador da carona');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      const pedidoRef = col('solicitacoes_caronas').doc(solicitacaoId(caronaId, uid));
      const pedidoDoc = await tx.get(pedidoRef);
      const pedido = pedidoDoc.exists ? pedidoDoc.data() || {} : null;
      if (!pedido || pedido.passageiroUid !== uid || pedido.status !== 'ACEITA' || pedido.motoristaUid !== carona.dados.motoristaUid) {
        falhar(403, 'NAO_EH_PASSAGEIRO_ACEITO', 'Somente passageiros aceitos nesta carona podem confirmar participação.');
      }
      if (millis(pedido.participacaoConfirmadaEm) !== null) falhar(409, 'JA_CONFIRMADO', 'Sua participação já foi confirmada.');
      const estado = exibicaoDeCarona(carona.dados, agora);
      const concluidaEm = millis(carona.dados.concluidaEm);
      const emAndamento = estado === 'EM_ANDAMENTO';
      const concluidaNoPrazo = carona.dados.status === 'CONCLUIDA' && concluidaEm !== null && agora <= concluidaEm + JANELA_CONFIRMACAO_MS;
      if (!emAndamento && !concluidaNoPrazo) {
        falhar(409, 'TRANSICAO_INVALIDA', 'A participação só pode ser confirmada com a viagem em andamento ou recém-concluída.');
      }
      tx.update(pedidoRef, { participacaoConfirmadaEm: d.serverTimestamp() });
      return { confirmado: true };
    });
  }

  async function confirmarConclusao(uid, caronaId) {
    exigirId(caronaId, 'identificador da carona');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      const pedidoRef = col('solicitacoes_caronas').doc(solicitacaoId(caronaId, uid));
      const pedidoDoc = await tx.get(pedidoRef);
      const pedido = pedidoDoc.exists ? pedidoDoc.data() || {} : null;
      if (!pedido || pedido.passageiroUid !== uid || pedido.status !== 'ACEITA' || pedido.motoristaUid !== carona.dados.motoristaUid) {
        falhar(403, 'NAO_EH_PASSAGEIRO_ACEITO', 'Somente passageiros aceitos nesta carona podem confirmar a conclusão.');
      }
      if (millis(pedido.conclusaoConfirmadaEm) !== null) falhar(409, 'JA_CONFIRMADO', 'A conclusão já foi confirmada.');
      const concluidaEm = millis(carona.dados.concluidaEm);
      if (carona.dados.status !== 'CONCLUIDA' || concluidaEm === null) {
        falhar(409, 'TRANSICAO_INVALIDA', 'A conclusão só pode ser confirmada depois que o motorista concluir a viagem.');
      }
      if (agora > concluidaEm + JANELA_CONFIRMACAO_MS) falhar(409, 'FORA_DA_JANELA', 'O prazo para confirmar a conclusão terminou.');
      if (millis(pedido.participacaoConfirmadaEm) === null) {
        falhar(409, 'NAO_ELEGIVEL', 'Confirme primeiro a sua participação na viagem.');
      }
      tx.update(pedidoRef, { conclusaoConfirmadaEm: d.serverTimestamp() });
      return { confirmado: true };
    });
  }

  // ───────────────────────── Reputação ─────────────────────────

  async function reputacaoDe(uids) {
    const agora = d.agoraMs();
    const saida = {};
    await Promise.all([...new Set(uids)].map(async uid => {
      const [avaliacoes, comoMotorista, comoPassageiro, perfil] = await Promise.all([
        col('avaliacoes').where('avaliadoUid', '==', uid).get(),
        col('solicitacoes_caronas').where('motoristaUid', '==', uid).get(),
        col('solicitacoes_caronas').where('passageiroUid', '==', uid).get(),
        col('usuarios').doc(uid).get()
      ]);
      const reputacao = calcularReputacao((avaliacoes.docs || []).map(a => a.data() || {}), agora);
      const realizadas = lista => new Set((lista.docs || []).map(s => s.data() || {})
        .filter(s => s.status === 'ACEITA' && millis(s.participacaoConfirmadaEm) !== null && millis(s.conclusaoConfirmadaEm) !== null)
        .map(s => s.caronaId));
      const viagensComoMotorista = realizadas(comoMotorista).size;
      const viagensComoPassageiro = realizadas(comoPassageiro).size;
      saida[uid] = {
        media: reputacao.media,
        quantidade: reputacao.quantidade,
        viagens: viagensComoMotorista + viagensComoPassageiro,
        viagensComoMotorista,
        viagensComoPassageiro,
        cnhValidada: perfil.exists ? motoristaApto(perfil.data(), agora) : false
      };
    }));
    return saida;
  }

  const reputacaoPublica = r => ({ media: r.media, quantidade: r.quantidade, viagens: r.viagens, cnhValidada: r.cnhValidada });

  async function consultarReputacoes(uids) {
    if (!Array.isArray(uids) || !uids.length || uids.length > MAX_IDS || !uids.every(idValido)) {
      falhar(400, 'ID_INVALIDO', `Informe de 1 a ${MAX_IDS} identificadores válidos.`);
    }
    const r = await reputacaoDe(uids);
    return { reputacoes: Object.fromEntries(Object.entries(r).map(([uid, v]) => [uid, reputacaoPublica(v)])) };
  }

  async function minhaReputacao(uid) {
    return (await reputacaoDe([uid]))[uid];
  }

  // ───────────────────────── Situação, participantes e avaliações ─────────────────────────

  async function pedidosDaCarona(caronaId) {
    return ((await col('solicitacoes_caronas').where('caronaId', '==', caronaId).get()).docs || []).map(p => p.data() || {});
  }

  async function avaliacaoExiste(caronaId, avaliador, avaliado) {
    return (await col('avaliacoes').doc(avaliacaoId(caronaId, avaliador, avaliado)).get()).exists;
  }

  async function situacao(uid, caronaId, { leve = false } = {}) {
    exigirId(caronaId, 'identificador da carona');
    const agora = d.agoraMs();
    const caronaDoc = await col('caronas').doc(caronaId).get();
    if (!caronaDoc.exists) falhar(404, 'CARONA_INEXISTENTE', 'Carona não encontrada.');
    const carona = caronaDoc.data() || {};
    const estado = exibicaoDeCarona(carona, agora);
    const dono = carona.motoristaUid === uid;
    const saida = millis(carona.dataHoraSaida);
    const acoes = { iniciar: false, concluir: false, cancelar: false, confirmarParticipacao: false, confirmarConclusao: false, avaliar: false };
    const bloqueios = {};
    let papel = 'NENHUM';
    let minhaSolicitacao = null;
    let veiculo = null;

    if (dono) {
      papel = 'MOTORISTA';
      const pedidos = await pedidosDaCarona(caronaId);
      const aceitos = pedidos.filter(p => p.status === 'ACEITA');
      const perfil = await col('usuarios').doc(uid).get();
      const apto = perfil.exists && motoristaApto(perfil.data(), agora);
      if ((carona.status === 'ABERTA' || carona.status === 'LOTADA') && estado !== 'EXPIRADA') {
        const cedo = agora < saida - JANELA_INICIO_ANTES_MS;
        if (cedo) bloqueios.iniciar = 'A viagem só pode ser iniciada a partir de 30 minutos antes do horário previsto.';
        else if (!aceitos.length) bloqueios.iniciar = 'É preciso ao menos um passageiro aceito para iniciar a viagem.';
        else if (!apto) bloqueios.iniciar = 'Sua CNH ou aprovação de motorista não está vigente.';
        acoes.iniciar = !cedo && aceitos.length > 0 && apto;
        if (agora < saida) {
          acoes.cancelar = true;
          if (!leve) {
            const bloqueio = bloqueioFinanceiro(await pagamentosDaCarona({ get: consulta => consulta.get() }, caronaId));
            if (bloqueio) bloqueios.cancelar = bloqueio.mensagem;
          }
        }
      }
      acoes.concluir = estado === 'EM_ANDAMENTO';
      if (carona.status === 'CONCLUIDA') {
        for (const p of pedidos.filter(x => passageiroElegivel(x, carona) && dentroDoPrazoDeAvaliar(x, agora))) {
          if (!(await avaliacaoExiste(caronaId, uid, p.passageiroUid))) { acoes.avaliar = true; break; }
        }
      }
    } else {
      const pedidoDoc = await col('solicitacoes_caronas').doc(solicitacaoId(caronaId, uid)).get();
      const pedido = pedidoDoc.exists ? pedidoDoc.data() || {} : null;
      if (pedido && pedido.passageiroUid === uid) {
        papel = 'PASSAGEIRO';
        minhaSolicitacao = {
          status: pedido.status,
          participacaoConfirmadaEm: iso(pedido.participacaoConfirmadaEm),
          conclusaoConfirmadaEm: iso(pedido.conclusaoConfirmadaEm)
        };
        const concluidaEm = millis(carona.concluidaEm);
        const aceito = pedido.status === 'ACEITA' && pedido.motoristaUid === carona.motoristaUid;
        if (aceito) {
          const semParticipacao = millis(pedido.participacaoConfirmadaEm) === null;
          acoes.confirmarParticipacao = semParticipacao && (estado === 'EM_ANDAMENTO'
            || (carona.status === 'CONCLUIDA' && concluidaEm !== null && agora <= concluidaEm + JANELA_CONFIRMACAO_MS));
          acoes.confirmarConclusao = carona.status === 'CONCLUIDA' && !semParticipacao
            && millis(pedido.conclusaoConfirmadaEm) === null && concluidaEm !== null && agora <= concluidaEm + JANELA_CONFIRMACAO_MS;
          if (['ABERTA', 'LOTADA', 'AGUARDANDO_INICIO', 'EM_ANDAMENTO'].includes(estado) && !leve) {
            const v = await col('solicitacoes_motoristas').doc(carona.motoristaUid).get();
            const dv = v.exists ? v.data() || {} : null;
            if (dv && (dv.placa || dv.modelo || dv.cor)) {
              veiculo = { modelo: String(dv.modelo ?? ''), cor: String(dv.cor ?? ''), placa: String(dv.placa ?? '') };
            }
          }
          if (passageiroElegivel(pedido, carona) && dentroDoPrazoDeAvaliar(pedido, agora)) {
            acoes.avaliar = !(await avaliacaoExiste(caronaId, uid, carona.motoristaUid));
          }
        }
      }
    }

    const resposta = { caronaId, statusExibicao: estado, papel, acoes, minhaSolicitacao };
    if (!leve) {
      resposta.bloqueios = bloqueios;
      resposta.veiculo = veiculo;
      resposta.iniciadaEm = iso(carona.iniciadaEm);
      resposta.concluidaEm = iso(carona.concluidaEm);
      if (idValido(carona.motoristaUid)) {
        const r = (await reputacaoDe([carona.motoristaUid]))[carona.motoristaUid];
        resposta.motorista = reputacaoPublica(r);
      }
    }
    return resposta;
  }

  async function situacoes(uid, ids) {
    if (!Array.isArray(ids) || !ids.length || ids.length > MAX_IDS || !ids.every(idValido)) {
      falhar(400, 'ID_INVALIDO', `Informe de 1 a ${MAX_IDS} identificadores válidos.`);
    }
    const mapa = {};
    await Promise.all([...new Set(ids)].map(async id => {
      try {
        const s = await situacao(uid, id, { leve: true });
        mapa[id] = { statusExibicao: s.statusExibicao, papel: s.papel, acoes: s.acoes };
      } catch (erro) {
        if (erro.status !== 404) throw erro;
      }
    }));
    return { situacoes: mapa };
  }

  async function nomeAbreviadoDe(uid) {
    const doc = await col('usuarios').doc(uid).get();
    return doc.exists ? abreviarNome((doc.data() || {}).nome) : null;
  }

  async function participantes(uid, caronaId) {
    exigirId(caronaId, 'identificador da carona');
    const agora = d.agoraMs();
    const caronaDoc = await col('caronas').doc(caronaId).get();
    if (!caronaDoc.exists) falhar(404, 'CARONA_INEXISTENTE', 'Carona não encontrada.');
    const carona = caronaDoc.data() || {};
    if (carona.status !== 'CONCLUIDA') {
      falhar(403, 'NAO_ELEGIVEL', 'A lista de participantes só existe para viagens concluídas e confirmadas.');
    }
    const pedidos = await pedidosDaCarona(caronaId);
    const elegiveis = pedidos.filter(p => passageiroElegivel(p, carona));
    let alvos;
    if (carona.motoristaUid === uid) {
      alvos = elegiveis.map(p => ({ uid: p.passageiroUid, papel: 'PASSAGEIRO', pedido: p }));
      if (!alvos.length) falhar(403, 'NAO_ELEGIVEL', 'Nenhum passageiro desta viagem confirmou participação e conclusão.');
    } else {
      const meu = elegiveis.find(p => p.passageiroUid === uid);
      if (!meu) falhar(403, 'NAO_ELEGIVEL', 'Somente participantes com participação e conclusão confirmadas acessam esta viagem.');
      alvos = [{ uid: carona.motoristaUid, papel: 'MOTORISTA', pedido: meu }];
    }
    const reputacoes = await reputacaoDe(alvos.map(a => a.uid));
    const lista = [];
    for (const a of alvos) {
      const jaAvaliou = await avaliacaoExiste(caronaId, uid, a.uid);
      const nome = await nomeAbreviadoDe(a.uid);
      lista.push({
        uid: a.uid,
        papel: a.papel,
        nomeExibicao: nome || (a.papel === 'MOTORISTA' ? 'Motorista' : 'Passageiro'),
        reputacao: { media: reputacoes[a.uid].media, quantidade: reputacoes[a.uid].quantidade, viagens: reputacoes[a.uid].viagens },
        podeAvaliar: !jaAvaliou && dentroDoPrazoDeAvaliar(a.pedido, agora),
        jaAvaliou
      });
    }
    return { participantes: lista };
  }

  async function avaliar(uid, corpo) {
    const caronaId = exigirId(corpo?.caronaId, 'identificador da carona');
    const avaliadoUid = exigirId(corpo?.avaliadoUid, 'identificador do avaliado');
    if (!notaValida(corpo?.nota)) falhar(400, 'NOTA_INVALIDA', 'A nota precisa ser um número inteiro de 1 a 5.');
    if (avaliadoUid === uid) falhar(400, 'AUTOAVALIACAO', 'Você não pode avaliar a si mesmo.');
    return db().runTransaction(async tx => {
      const agora = d.agoraMs();
      const carona = await carregarCarona(tx, caronaId);
      if (carona.dados.status !== 'CONCLUIDA') {
        falhar(409, 'NAO_ELEGIVEL', 'Só é possível avaliar viagens concluídas e confirmadas.');
      }
      const motoristaUid = carona.dados.motoristaUid;
      const passageiroUid = uid === motoristaUid ? avaliadoUid : uid;
      if (uid !== motoristaUid && avaliadoUid !== motoristaUid) {
        falhar(403, 'PAR_INVALIDO', 'Passageiros só avaliam o motorista da viagem, e motoristas só avaliam seus passageiros.');
      }
      if (uid === motoristaUid && avaliadoUid === motoristaUid) falhar(400, 'AUTOAVALIACAO', 'Você não pode avaliar a si mesmo.');
      const pedidoDoc = await tx.get(col('solicitacoes_caronas').doc(solicitacaoId(caronaId, passageiroUid)));
      const pedido = pedidoDoc.exists ? pedidoDoc.data() || {} : null;
      if (!pedido || pedido.passageiroUid !== passageiroUid || pedido.motoristaUid !== motoristaUid || !passageiroElegivel(pedido, carona.dados)) {
        falhar(403, 'NAO_ELEGIVEL', 'Só participantes com aceite, participação e conclusão confirmadas podem avaliar e ser avaliados.');
      }
      if (!dentroDoPrazoDeAvaliar(pedido, agora)) falhar(409, 'FORA_DA_JANELA', 'O prazo para avaliar esta viagem terminou.');
      const propriaRef = col('avaliacoes').doc(avaliacaoId(caronaId, uid, avaliadoUid));
      const parRef = col('avaliacoes').doc(avaliacaoId(caronaId, avaliadoUid, uid));
      const propria = await tx.get(propriaRef);
      const par = await tx.get(parRef);
      if (propria.exists) falhar(409, 'AVALIACAO_DUPLICADA', 'Você já avaliou esta pessoa nesta viagem.');
      const libera = liberaEmMs(pedido);
      tx.set(propriaRef, {
        caronaId, avaliadorUid: uid, avaliadoUid, motoristaUid, passageiroUid,
        papelAvaliador: uid === motoristaUid ? 'MOTORISTA' : 'PASSAGEIRO',
        nota: corpo.nota,
        criadoEm: d.serverTimestamp(),
        liberaEm: new Date(libera),
        parCompleto: par.exists
      });
      if (par.exists) tx.update(parRef, { parCompleto: true });
      return { revelada: par.exists || agora >= libera };
    });
  }

  // ───────────────────────── Expiração persistida ─────────────────────────

  /** Persiste EXPIRADA em caronas vencidas e encerra solicitações pendentes. Idempotente e seguro após reinícios. */
  async function varrerExpiradas(limite = 200) {
    const agora = d.agoraMs();
    const candidatas = await col('caronas').where('status', 'in', ['ABERTA', 'LOTADA', 'EM_ANDAMENTO']).limit(limite).get();
    let expiradas = 0;
    for (const doc of candidatas.docs || []) {
      if (exibicaoDeCarona(doc.data() || {}, agora) !== 'EXPIRADA') continue;
      try {
        await db().runTransaction(async tx => {
          const atual = await tx.get(doc.ref);
          if (!atual.exists) return;
          const dados = atual.data() || {};
          if (!['ABERTA', 'LOTADA', 'EM_ANDAMENTO'].includes(dados.status) || exibicaoDeCarona(dados, d.agoraMs()) !== 'EXPIRADA') return;
          const pedidos = await tx.get(col('solicitacoes_caronas').where('caronaId', '==', doc.id));
          tx.update(doc.ref, { status: 'EXPIRADA', expiradaEm: d.serverTimestamp(), atualizadoEm: d.serverTimestamp() });
          for (const p of pedidos.docs || []) {
            if ((p.data() || {}).status === 'PENDENTE') {
              tx.update(p.ref, { status: 'CANCELADA', statusAnterior: 'PENDENTE', motivoEncerramento: 'CARONA_EXPIRADA', canceladaEm: d.serverTimestamp() });
            }
          }
          expiradas++;
        });
      } catch { /* a próxima varredura tenta de novo */ }
    }
    return { expiradas };
  }

  return {
    aceitar, recusar, iniciar, concluir, cancelar, confirmarParticipacao, confirmarConclusao,
    situacao, situacoes, participantes, avaliar, consultarReputacoes, minhaReputacao, varrerExpiradas
  };
}

// ───────────────────────── HTTP ─────────────────────────

export function criarTratadorCaronas(deps = {}) {
  const servico = deps.servico || criarServicoCaronas(deps);
  const verificarToken = deps.verificarToken || (async token => {
    const app = getApps()[0];
    if (!app) throw new Error('Firebase Admin indisponível.');
    return getAuth(app).verifyIdToken(token, true);
  });
  const firestore = deps.firestore || (() => obterFirestore());
  const agoraMs = deps.agoraMs || (() => Date.now());
  const requisicoes = new Map();

  function limitar(uid) {
    const agora = agoraMs();
    const recentes = (requisicoes.get(uid) || []).filter(t => agora - t < LIMITE_REQUISICOES.janelaMs);
    if (recentes.length >= LIMITE_REQUISICOES.max) falhar(429, 'LIMITE_REQUISICOES', 'Muitas requisições. Aguarde um instante.');
    recentes.push(agora);
    requisicoes.set(uid, recentes);
  }

  async function autenticar(req) {
    const db = firestore() || falhar(503, 'INDISPONIVEL', 'Integração com o Firebase indisponível.');
    const m = /^Bearer ([A-Za-z0-9._-]{20,4096})$/.exec(req.headers.authorization || '');
    if (!m) falhar(401, 'NAO_AUTENTICADO', 'Faça login para continuar.');
    let token;
    try { token = await verificarToken(m[1]); } catch { return falhar(401, 'NAO_AUTENTICADO', 'Sessão inválida ou expirada.'); }
    if (!token?.uid || !idValido(token.uid)) falhar(401, 'NAO_AUTENTICADO', 'Sessão inválida ou expirada.');
    if (token.email_verified !== true || typeof token.email !== 'string' || !EMAIL_INSTITUCIONAL.test(token.email)) {
      falhar(403, 'NAO_AUTORIZADO', 'Confirme seu e-mail institucional para continuar.');
    }
    const perfil = await db.collection('usuarios').doc(token.uid).get();
    if (!perfil.exists || (perfil.data() || {}).status !== 'ATIVO') falhar(403, 'NAO_AUTORIZADO', 'Seu cadastro não está ativo.');
    limitar(token.uid);
    return token.uid;
  }

  const ROTAS = [
    [/^\/api\/caronas\/situacoes$/, 'POST', async (uid, m, req) => servico.situacoes(uid, (await lerJson(req)).ids)],
    [/^\/api\/caronas\/([^/]+)\/situacao$/, 'GET', async (uid, m) => servico.situacao(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/participantes$/, 'GET', async (uid, m) => servico.participantes(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/iniciar$/, 'POST', async (uid, m) => servico.iniciar(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/concluir$/, 'POST', async (uid, m) => servico.concluir(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/cancelar$/, 'POST', async (uid, m) => servico.cancelar(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/participacao\/confirmar$/, 'POST', async (uid, m) => servico.confirmarParticipacao(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/conclusao\/confirmar$/, 'POST', async (uid, m) => servico.confirmarConclusao(uid, m[1])],
    [/^\/api\/caronas\/([^/]+)\/solicitacoes\/([^/]+)\/aceitar$/, 'POST', async (uid, m) => servico.aceitar(uid, m[1], m[2])],
    [/^\/api\/caronas\/([^/]+)\/solicitacoes\/([^/]+)\/recusar$/, 'POST', async (uid, m) => servico.recusar(uid, m[1], m[2])],
    [/^\/api\/avaliacoes$/, 'POST', async (uid, m, req) => ({ __status: 201, ...(await servico.avaliar(uid, await lerJson(req))) })],
    [/^\/api\/reputacao\/consultar$/, 'POST', async (uid, m, req) => servico.consultarReputacoes((await lerJson(req)).uids)],
    [/^\/api\/reputacao\/minha$/, 'GET', async uid => servico.minhaReputacao(uid)]
  ];

  const tratador = async function tratarCaronas(req, res, url) {
    const caminho = url.pathname;
    const rota = ROTAS.map(([re, metodo, fn]) => [re.exec(caminho), metodo, fn]).find(([m]) => m);
    if (!rota) return false;
    try {
      const [m, metodo, fn] = rota;
      if (req.method !== metodo) falhar(405, 'METODO', 'Método não permitido.');
      const uid = await autenticar(req);
      const resultado = await fn(uid, m, req);
      const status = resultado?.__status || 200;
      if (resultado && '__status' in resultado) delete resultado.__status;
      json(res, status, resultado);
    } catch (erro) {
      if (erro.status) json(res, erro.status, { error: erro.message, codigo: erro.codigo || 'ERRO' });
      else {
        console.error(`Falha inesperada no módulo de caronas (${erro.name}).`);
        json(res, 500, { error: 'Erro interno. Tente novamente.', codigo: 'ERRO_INTERNO' });
      }
    }
    return true;
  };
  tratador.servico = servico;
  return tratador;
}

let tratadorAtual = null;
export const tratarCaronas = (req, res, url) => (tratadorAtual ||= criarTratadorCaronas())(req, res, url);
export const servicoCaronas = () => (tratadorAtual ||= criarTratadorCaronas()).servico;

export function definirTratadorCaronasParaTeste(tratador) {
  if (!process.env.NODE_TEST_CONTEXT) throw new Error('Provedor de teste indisponível.');
  tratadorAtual = tratador;
}

export { EXPIRA_APOS_SAIDA_MS, EXPIRA_EM_ANDAMENTO_MS };

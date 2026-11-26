// Ciclo de vida do PDF da CNH: recebe, consulta, entrega, exclui e executa a pré-análise automática (OCR)
// sem bloquear a resposta do upload, mantendo o armazenamento privado e o Firestore coerentes.
//
// Fonte da verdade: cnh_admin/{uid}.documento (somente o backend lê/grava). O arquivo em disco é derivado:
//  - o arquivo novo é gravado ANTES e só passa a valer quando a transação do Firestore o referencia;
//  - se a transação falha, o arquivo novo é descartado (e o anterior continua intacto);
//  - o arquivo anterior só é apagado DEPOIS do commit; sobras são varridas por limparExceto/reconciliar;
//  - após o commit do envio, o OCR automático roda em segundo plano (com fila/retentativa se o worker estiver ocupado)
//    e grava preAnalise vinculada exatamente a (pdfSha256, pdfVersao), sem jamais aprovar automaticamente.
import { FieldPath } from 'firebase-admin/firestore';
import { banco as bancoPadrao } from './firebaseMotoristas.js';
import { armazenamentoPadrao, sha256Hex } from './cnhStorage.js';
import { avaliarRegistroCnh, conferenciaVinculada, construirPreAnalise, hojeSP, preAnaliseVinculada } from './cnh.js';
import { lerCnhBuffer } from './ocr.js';

const erro = (status, mensagem) => Object.assign(new Error(mensagem), { status });
const ID_SEGURO = /^[A-Za-z0-9_-]{1,128}$/;
const log = (mensagem, e) => console.error(`${mensagem} (${e?.codigo || e?.name || 'erro'}).`);

// Normaliza status de solicitações de motorista (incluindo registros antigos aprovados como 'APROVADO' ou em minúsculas).
export function normalizarStatusSolicitacao(status) {
  if (typeof status !== 'string') return '';
  const limpo = status.trim().toUpperCase();
  if (!limpo) return 'PENDENTE';
  if (limpo === 'APROVADO' || limpo === 'APROVADA') return 'APROVADA';
  if (limpo === 'RECUSADO' || limpo === 'RECUSADA' || limpo === 'REJEITADO' || limpo === 'REJEITADA') return 'RECUSADA';
  if (limpo === 'PENDENTE' || limpo === 'EM_ANALISE' || limpo === 'REVISAO' || limpo === 'REVISAO_NECESSARIA') return 'PENDENTE';
  return limpo;
}

export function validarVeiculo(v) {
  const placa = String(v?.placa ?? '').trim().toUpperCase();
  const modelo = String(v?.modelo ?? '').trim();
  const cor = String(v?.cor ?? '').trim();
  if (placa.length < 7 || placa.length > 8 || !/^[A-Z0-9-]+$/.test(placa)) throw erro(400, 'Informe uma placa válida.');
  if (!modelo || !cor) throw erro(400, 'Preencha os dados do veículo.');
  if (modelo.length > 100 || cor.length > 50) throw erro(400, 'Os dados do veículo ultrapassam o limite permitido.');
  return { placa, modelo, cor };
}

export function criarServicoDocumentos(deps = {}) {
  const d = {
    banco: () => bancoPadrao(),
    armazenamento: () => armazenamentoPadrao(),
    lerOcr: (buffer, opcoes) => lerCnhBuffer(buffer, opcoes),
    agora: () => new Date(),
    atrasoRetentativaMs: 250,
    maxTentativasOcr: 4,
    ...deps
  };
  const travas = new Map();
  const analisesEmAndamento = new Map();

  /** Serializa operações do mesmo usuário neste processo (envio, exclusão e varredura). */
  async function comTrava(uid, fn) {
    const anterior = travas.get(uid) || Promise.resolve();
    let liberar;
    const atual = new Promise(r => { liberar = r; });
    const encadeada = anterior.then(() => atual);
    travas.set(uid, encadeada);
    await anterior;
    try { return await fn(); } finally {
      liberar();
      if (travas.get(uid) === encadeada) travas.delete(uid);
    }
  }

  async function recursos() {
    const armazenamento = await d.armazenamento();
    if (!armazenamento?.disponivel) throw erro(503, 'Armazenamento de documentos não configurado.');
    const db = await d.banco();
    if (!db) throw erro(503, 'Integração com o Firebase indisponível.');
    return { armazenamento, db };
  }
  const refs = (db, uid) => ({
    pedido: db.collection('solicitacoes_motoristas').doc(uid),
    usuario: db.collection('usuarios').doc(uid),
    cnh: db.collection('cnh_admin').doc(uid)
  });

  async function receber(uid, { pdf, veiculo }) {
    if (!ID_SEGURO.test(uid)) throw erro(400, 'UID inválido.');
    const { armazenamento, db } = await recursos();
    const dadosVeiculo = veiculo ? validarVeiculo(veiculo) : null;
    const recebido = await comTrava(uid, async () => {
      const sha = sha256Hex(pdf);
      let salvo;
      try { salvo = await armazenamento.salvar(uid, pdf); } catch (e) {
        log('Falha ao gravar documento de CNH', e);
        throw erro(503, 'Não foi possível armazenar o documento. Tente novamente.');
      }
      const r = refs(db, uid);
      let resultado;
      try {
        resultado = await db.runTransaction(async tx => {
          const [pedido, usuario, cnh] = [await tx.get(r.pedido), await tx.get(r.usuario), await tx.get(r.cnh)];
          const perfil = usuario.exists ? usuario.data() || {} : null;
          if (!perfil || perfil.status !== 'ATIVO') throw erro(403, 'Seu cadastro não está ativo.');
          const solicitacao = pedido.exists ? pedido.data() || {} : null;
          const registro = cnh.exists ? cnh.data() || {} : {};
          if (solicitacao) {
            if (Object.hasOwn(solicitacao, 'motoristaUid') && solicitacao.motoristaUid !== uid) throw erro(409, 'A solicitação não pertence a este usuário.');
            const statusNorm = normalizarStatusSolicitacao(solicitacao.status);
            if (!['PENDENTE', 'APROVADA', 'RECUSADA'].includes(statusNorm)) throw erro(409, 'A solicitação atual não permite novo envio.');
            // Bloqueia apenas envio duplicado do MESMO PDF enquanto a solicitação já está PENDENTE com o documento armazenado
            // e nenhuma revisão foi registrada pela administração sobre esta versão.
            const docAtual = registro.documento;
            const jaConferiuEstaVersao = Boolean(registro.conferidoEm) && conferenciaVinculada(registro);
            if (statusNorm === 'PENDENTE' && docAtual?.arquivoPresente === true && docAtual?.sha256 === sha && !jaConferiuEstaVersao) {
              throw erro(409, 'Este PDF já foi enviado e está aguardando análise administrativa.');
            }
          } else if (!dadosVeiculo) {
            throw erro(400, 'Informe placa, modelo e cor do veículo para abrir a solicitação.');
          }
          const versao = (Number.isInteger(registro.documento?.versao) ? registro.documento.versao : 0) + 1;
          const instante = d.agora();
          const iso = instante.toISOString();
          const documento = { sha256: sha, bytes: pdf.length, recebidoEm: iso, versao, arquivoPresente: true, excluidoEm: null, motivoExclusao: null };
          const preAnaliseInicial = {
            status: 'em_andamento',
            pdfSha256: sha,
            pdfVersao: versao,
            iniciadoEm: iso,
            nomeCadastrado: typeof perfil.nome === 'string' ? perfil.nome.trim() : '',
            recomendacao: 'revisao_necessaria',
            resumo: 'Pré-análise automática (OCR) em andamento no servidor.',
            checklist: [],
            revisaoHumanaObrigatoria: true
          };
          // Documento novo: invalida pré-análises e conferências anteriores (vínculo por hash + versão).
          tx.set(r.cnh, { ...registro, documento, preAnalise: preAnaliseInicial });
          if (solicitacao) {
            tx.update(r.pedido, { status: 'PENDENTE', cnhPdfRecebido: true, cnhPdfAtualizadoEm: iso, motivoRecusa: null, ...(dadosVeiculo || {}) });
          } else {
            tx.set(r.pedido, { motoristaUid: uid, ...dadosVeiculo, status: 'PENDENTE', criadoEm: instante, cnhPdfRecebido: true, cnhPdfAtualizadoEm: iso, motivoRecusa: null });
          }
          // Retira a aprovação anterior e o papel de motorista até nova conferência e decisão.
          tx.update(r.usuario, { condutorValidado: false, cnhRevisaoNecessaria: true, ...(perfil.papelAtivo === 'MOTORISTA' ? { papelAtivo: 'PASSAGEIRO' } : {}) });
          tx.set(db.collection('cnh_eventos').doc(), { uid, acao: registro.documento ? 'documento substituído pelo titular' : 'documento enviado pelo titular', administrador: 'titular', versao, criadoEm: iso });
          return { versao, recebidoEm: iso, aprovacaoRetirada: perfil.condutorValidado === true, substituiu: Boolean(registro.documento) };
        });
      } catch (e) {
        if (salvo.criado) await descartarSeNaoReferenciado(db, armazenamento, uid, sha);
        if (e.status) throw e;
        log('Falha ao registrar envio de CNH no Firestore', e);
        throw erro(503, 'Não foi possível registrar o envio. Tente novamente.');
      }
      let limpezaPendente = false;
      try { await armazenamento.limparExceto(uid, sha); } catch (e) { limpezaPendente = true; log('Falha ao limpar documentos anteriores de CNH', e); }
      return { ...resultado, bytes: pdf.length, sha256: sha, limpezaPendente };
    });

    // Dispara a pré-análise automática de OCR em background sem bloquear a resposta de upload ao motorista.
    agendarPreAnaliseAutomatica(uid, { sha256: recebido.sha256, versao: recebido.versao, pdf });
    return recebido;
  }

  function agendarPreAnaliseAutomatica(uid, { sha256, versao, pdf } = {}) {
    const tarefa = (async () => {
      try {
        return await executarPreAnalise(uid, { sha256Esperado: sha256, versaoEsperada: versao, pdfEmMemoria: pdf });
      } catch (e) {
        log('Pré-análise automática de CNH não concluída', e);
        return null;
      }
    })();
    analisesEmAndamento.set(uid, tarefa);
    tarefa.finally(() => {
      if (analisesEmAndamento.get(uid) === tarefa) analisesEmAndamento.delete(uid);
    });
    return tarefa;
  }

  /**
   * Executa o OCR sobre o PDF armazenado (automaticamente após upload ou sob demanda do administrador),
   * compara nome extraído com o nome do usuário no Firestore, avalia categoria e validade,
   * persiste o resultado vinculado a (pdfSha256, pdfVersao) e NUNCA aprova automaticamente.
   */
  async function executarPreAnalise(uid, { sha256Esperado = null, versaoEsperada = null, pdfEmMemoria = null } = {}) {
    if (!ID_SEGURO.test(uid)) throw erro(400, 'UID inválido.');
    const { armazenamento, db } = await recursos();
    const r = refs(db, uid);

    const [cnhSnap, usuarioSnap] = await Promise.all([r.cnh.get(), r.usuario.get()]);
    const registroInicial = cnhSnap.exists ? cnhSnap.data() || {} : null;
    const doc = registroInicial?.documento;
    if (!doc || doc.arquivoPresente !== true) throw erro(404, 'Nenhum documento armazenado.');
    if ((sha256Esperado && doc.sha256 !== sha256Esperado) || (versaoEsperada !== null && doc.versao !== versaoEsperada)) {
      return null; // O documento já foi substituído por uma versão mais nova.
    }

    const pdf = Buffer.isBuffer(pdfEmMemoria) ? pdfEmMemoria : await lerArquivoBuffer(armazenamento, uid, doc.sha256);
    const nomeCadastrado = usuarioSnap.exists && typeof usuarioSnap.data()?.nome === 'string' ? usuarioSnap.data().nome.trim() : '';

    let ocrResultado = null;
    let ultimoErro = null;
    const emBackground = sha256Esperado !== null;
    for (let tentativa = 1; tentativa <= d.maxTentativasOcr; tentativa++) {
      try {
        ocrResultado = await d.lerOcr(pdf, { background: emBackground });
        ultimoErro = null;
        break;
      } catch (e) {
        ultimoErro = e;
        if (e?.status === 429 && tentativa < d.maxTentativasOcr) {
          await new Promise(res => setTimeout(res, d.atrasoRetentativaMs * tentativa));
          continue;
        }
        break;
      }
    }

    const agoraIso = d.agora().toISOString();
    const preAnalise = ocrResultado
      ? construirPreAnalise({
          ocrResultado,
          nomeCadastrado,
          pdfSha256: doc.sha256,
          pdfVersao: doc.versao,
          hoje: hojeSP(d.agora()),
          analisadoEm: agoraIso
        })
      : {
          status: 'falha',
          pdfSha256: doc.sha256,
          pdfVersao: doc.versao,
          analisadoEm: agoraIso,
          erro: ultimoErro?.message || 'Leitura automática inconclusiva. Tente executar o OCR novamente ou preencha os campos manualmente.',
          nomeCadastrado,
          recomendacao: 'revisao_necessaria',
          resumo: 'A leitura automática do PDF falhou ou ficou inconclusiva. Você pode tentar executar o OCR novamente ou preencher os dados manualmente.',
          checklist: [
            { item: 'ocr', status: 'erro', titulo: 'Leitura automática não concluída', detalhe: ultimoErro?.message || 'Use o botão "Executar OCR novamente" ou confira visualmente o PDF.' }
          ],
          revisaoHumanaObrigatoria: true
        };

    // Persiste em transação somente se o documento atual ainda for a mesma versão analisada.
    const gravado = await db.runTransaction(async tx => {
      const atualSnap = await tx.get(r.cnh);
      if (!atualSnap.exists) return null;
      const atual = atualSnap.data() || {};
      if (!atual.documento || atual.documento.sha256 !== doc.sha256 || atual.documento.versao !== doc.versao) {
        return null; // Substituído durante o OCR; descarta resultado antigo.
      }
      tx.set(r.cnh, { ...atual, preAnalise });
      return preAnalise;
    });

    if (ultimoErro && !ocrResultado && sha256Esperado === null) {
      // Quando acionado manualmente pelo botão no painel, propaga o erro após registrar o estado de falha.
      throw erro(ultimoErro.status || 422, preAnalise.erro);
    }

    return gravado ? { ...ocrResultado, preAnalise: gravado, pdfSha256: doc.sha256, pdfVersao: doc.versao } : null;
  }

  async function lerArquivoBuffer(armazenamento, uid, sha) {
    try {
      return await armazenamento.ler(uid, sha);
    } catch (e) {
      log('Falha ao ler documento de CNH', e);
      if (e.codigo === 'nao_encontrado') throw erro(404, 'Documento indisponível. Peça o reenvio do PDF.');
      throw erro(500, 'Documento indisponível. Peça o reenvio do PDF.');
    }
  }

  /** Para testes e sincronização: aguarda a pré-análise automática pendente de um usuário (ou todas). */
  async function aguardarPreAnalise(uid = null) {
    if (uid) {
      const p = analisesEmAndamento.get(uid);
      if (p) await p;
      return;
    }
    await Promise.all([...analisesEmAndamento.values()]);
  }

  // Só apaga o arquivo recém-criado se o Firestore confirmar que ele NÃO é referenciado
  // (uma falha ambígua após o commit nunca pode apagar o documento vigente).
  async function descartarSeNaoReferenciado(db, armazenamento, uid, sha) {
    try {
      const atual = await db.collection('cnh_admin').doc(uid).get();
      const doc = atual.exists ? atual.data()?.documento : null;
      if (doc?.sha256 === sha && doc?.arquivoPresente === true) return;
      await armazenamento.remover(uid, sha);
    } catch (e) { log('Documento recém-gravado mantido para verificação posterior', e); }
  }

  async function lerRegistros(db, uid) {
    const r = refs(db, uid);
    const [pedido, usuario, cnh] = await Promise.all([r.pedido.get(), r.usuario.get(), r.cnh.get()]);
    return { pedido: pedido.exists ? pedido.data() : null, usuario: usuario.exists ? usuario.data() : null, cnh: cnh.exists ? cnh.data() : null };
  }

  function resumoDocumento(doc) {
    if (!doc) return { recebido: false, arquivoPresente: false };
    return { recebido: true, arquivoPresente: doc.arquivoPresente === true, recebidoEm: doc.recebidoEm || null, bytes: doc.bytes ?? null, versao: doc.versao ?? null, sha256: doc.sha256 || null, excluidoEm: doc.excluidoEm || null };
  }

  /** Situação para o titular (sem parecer interno do administrador). */
  async function status(uid) {
    if (!ID_SEGURO.test(uid)) throw erro(400, 'UID inválido.');
    const db = await d.banco();
    if (!db) throw erro(503, 'Integração com o Firebase indisponível.');
    let dados;
    try { dados = await lerRegistros(db, uid); } catch (e) { log('Falha ao consultar status de CNH', e); throw erro(503, 'Não foi possível consultar a situação agora.'); }
    const { pedido, usuario, cnh } = dados;
    let conferencia = { situacao: cnh?.documento ? 'aguardando_conferencia' : 'sem_documento' };
    if (cnh?.validade) {
      const a = avaliarRegistroCnh(cnh, hojeSP(d.agora()), usuario?.nome || '');
      if (!a.documentoDivergente) conferencia = { situacao: a.situacao, validade: cnh.validade, dias: a.dias };
    }
    const statusSolicitacao = pedido ? normalizarStatusSolicitacao(pedido.status) : null;
    const motivoRecusa = statusSolicitacao === 'RECUSADA'
      ? (typeof pedido?.motivoRecusa === 'string' && pedido.motivoRecusa.trim()
          ? pedido.motivoRecusa.trim().slice(0, 500)
          : (cnh && conferenciaVinculada(cnh) && typeof cnh.observacao === 'string' && cnh.observacao.trim()
              ? cnh.observacao.trim().slice(0, 500)
              : null))
      : null;
    return {
      solicitacao: statusSolicitacao,
      motivoRecusa,
      documento: resumoDocumento(cnh?.documento),
      conferencia,
      condutorValidado: usuario?.condutorValidado === true,
      papelAtivo: usuario?.papelAtivo ?? null,
      revisaoNecessaria: statusSolicitacao === 'RECUSADA' ? false : usuario?.cnhRevisaoNecessaria === true
    };
  }

  /** Metadados do documento e pré-análise vinculada (painel administrativo). */
  async function metadados(uid) {
    if (!ID_SEGURO.test(uid)) throw erro(400, 'UID inválido.');
    const db = await d.banco();
    if (!db) throw erro(503, 'Integração com o Firebase indisponível.');
    let doc;
    try { doc = (await refs(db, uid).cnh.get()); } catch (e) { log('Falha ao consultar documento de CNH', e); throw erro(503, 'Não foi possível consultar o documento.'); }
    const registro = doc.exists ? doc.data() : null;
    const preAnalise = preAnaliseVinculada(registro) ? registro.preAnalise : null;
    return { ...resumoDocumento(registro?.documento), preAnalise };
  }

  /** Devolve o PDF decifrado. A autorização (titular ou administrador) é feita por quem chama. */
  async function lerArquivo(uid) {
    if (!ID_SEGURO.test(uid)) throw erro(400, 'UID inválido.');
    const { armazenamento, db } = await recursos();
    let registro;
    try { const doc = await refs(db, uid).cnh.get(); registro = doc.exists ? doc.data() : null; } catch (e) {
      log('Falha ao consultar documento de CNH', e);
      throw erro(503, 'Não foi possível consultar o documento.');
    }
    const documento = registro?.documento;
    if (!documento || documento.arquivoPresente !== true) throw erro(404, 'Nenhum documento armazenado.');
    const pdf = await lerArquivoBuffer(armazenamento, uid, documento.sha256);
    return { pdf, sha256: documento.sha256, versao: documento.versao };
  }

  /** Exclui o documento (titular ou administrador). O vínculo por hash é mantido para auditoria. */
  async function excluir(uid, { ator, motivo }) {
    if (!ID_SEGURO.test(uid)) throw erro(400, 'UID inválido.');
    const { armazenamento, db } = await recursos();
    return comTrava(uid, async () => {
      const r = refs(db, uid);
      try {
        await db.runTransaction(async tx => {
          const [pedido, cnh] = [await tx.get(r.pedido), await tx.get(r.cnh)];
          const registro = cnh.exists ? cnh.data() || {} : null;
          if (!registro?.documento || registro.documento.arquivoPresente !== true) throw erro(404, 'Nenhum documento armazenado.');
          const iso = d.agora().toISOString();
          tx.set(r.cnh, { ...registro, documento: { ...registro.documento, arquivoPresente: false, excluidoEm: iso, motivoExclusao: String(motivo).slice(0, 500) } });
          if (pedido.exists) tx.update(r.pedido, { cnhPdfRecebido: false, cnhPdfAtualizadoEm: iso });
          tx.set(db.collection('cnh_eventos').doc(), { uid, acao: 'documento excluído', administrador: ator, versao: registro.documento.versao ?? null, criadoEm: iso });
        });
      } catch (e) {
        if (e.status) throw e;
        log('Falha ao registrar exclusão de CNH no Firestore', e);
        throw erro(503, 'Não foi possível excluir agora. Nenhuma alteração foi confirmada.');
      }
      let limpezaPendente = false;
      try { await armazenamento.limparExceto(uid, null); } catch (e) { limpezaPendente = true; log('Falha ao remover arquivo de CNH do disco', e); }
      return { ok: true, limpezaPendente };
    });
  }

  /** Varre cnh_admin e remove do disco o que o Firestore não referencia mais (sobras de falhas anteriores). */
  async function reconciliar() {
    const armazenamento = await d.armazenamento();
    const db = await d.banco();
    if (!armazenamento?.disponivel || !db) return { disponivel: false, limpos: 0 };
    let cursor = null;
    let limpos = 0;
    while (true) {
      let consulta = db.collection('cnh_admin').orderBy(FieldPath.documentId()).limit(100);
      if (cursor) consulta = consulta.startAfter(cursor);
      const docs = (await consulta.get()).docs;
      if (!docs.length) break;
      for (const doc of docs) {
        if (!ID_SEGURO.test(doc.id)) continue;
        await comTrava(doc.id, async () => {
          // Relê já sob a trava: um envio concluído desde a listagem não pode ter o arquivo novo apagado.
          try {
            const recente = await db.collection('cnh_admin').doc(doc.id).get();
            const atual = recente.exists ? recente.data()?.documento : null;
            limpos += await armazenamento.limparExceto(doc.id, atual?.arquivoPresente === true ? atual.sha256 : null);
          } catch (e) { log('Falha ao reconciliar armazenamento de CNH', e); }
        });
      }
      if (docs.length < 100) break;
      cursor = docs.at(-1);
    }
    return { disponivel: true, limpos };
  }

  return { receber, status, metadados, lerArquivo, excluir, reconciliar, executarPreAnalise, aguardarPreAnalise, comTrava };
}

export const servicoDocumentos = criarServicoDocumentos();

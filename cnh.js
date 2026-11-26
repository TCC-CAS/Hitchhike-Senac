// Regras da plataforma; não são uma consulta ao Detran.
export const CATEGORIAS_VALIDAS = ['A', 'B', 'AB', 'C', 'AC', 'D', 'AD', 'E', 'AE', 'ACC'];
export const CATEGORIAS_CARONA_AUTOMOVEL = ['B', 'AB', 'C', 'AC', 'D', 'AD', 'E', 'AE'];

export function hojeSP(agora = new Date()) {
  const p = new Intl.DateTimeFormat('en-CA', {timeZone:'America/Sao_Paulo',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(agora);
  const v = tipo => p.find(x=>x.type===tipo).value;
  return `${v('year')}-${v('month')}-${v('day')}`;
}
export function dataValida(v) {
  return typeof v==='string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v+'T12:00:00Z')) && new Date(v+'T12:00:00Z').toISOString().slice(0,10)===v;
}
export function normalizarNomeIdentidade(nome) {
  if (typeof nome !== 'string') return '';
  return nome
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toUpperCase()
    .replace(/[^A-Z\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
export function compararNomesIdentidade(nomeExtraido, nomeCadastrado) {
  const extraido = normalizarNomeIdentidade(nomeExtraido);
  const cadastrado = normalizarNomeIdentidade(nomeCadastrado);
  if (!extraido) return { status: 'ausente', compativel: false, divergente: false, extraido, cadastrado };
  if (!cadastrado) return { status: 'cadastro_ausente', compativel: false, divergente: false, extraido, cadastrado };
  if (extraido === cadastrado) return { status: 'compativel', compativel: true, divergente: false, extraido, cadastrado };
  return { status: 'divergente', compativel: false, divergente: true, extraido, cadastrado };
}
export function avaliarCnh(cnh, hoje=hojeSP()) {
  if (!cnh || !dataValida(cnh.validade)) return {situacao:'revisao',dias:null,apta:false};
  const dias=Math.round((Date.parse(cnh.validade+'T12:00:00Z')-Date.parse(hoje+'T12:00:00Z'))/86400000);
  if(dias<0)return {situacao:'vencida',dias,apta:false};
  const apta=cnh.documentoConferido===true&&cnh.qrConferidoVio===true&&CATEGORIAS_CARONA_AUTOMOVEL.includes(cnh.categoria);
  return {situacao:!apta?'revisao':dias<=30?'vence_em_breve':'conferida',dias,apta};
}
export function validarConferencia(b) {
  if(typeof b.nome!=='string'||b.nome.trim().length<3||b.nome.length>200)throw Object.assign(Error('Informe o nome conferido no documento.'),{status:400});
  if(!dataValida(b.validade))throw Object.assign(Error('Data de validade inválida.'),{status:400});
  if(!CATEGORIAS_VALIDAS.includes(b.categoria))throw Object.assign(Error('Categoria inválida.'),{status:400});
  if(typeof b.observacao!=='string'||b.observacao.trim().length<5||b.observacao.length>1000)throw Object.assign(Error('Informe o parecer com 5 a 1.000 caracteres.'),{status:400});
  return {nome:b.nome.trim(),categoria:b.categoria,validade:b.validade,documentoConferido:b.documentoConferido===true,qrConferidoVio:b.qrConferidoVio===true,observacao:b.observacao.trim(),divergenciaIdentidadeConfirmada:b.divergenciaIdentidadeConfirmada===true};
}
export function sugerirCnh(texto,confidence=0) {
  const linhas=String(texto).normalize('NFD').replace(/[\u0300-\u036f]/g,'').toUpperCase().split(/\r?\n/).map(x=>x.trim());
  const proximo=(regex)=>{const i=linhas.findIndex(x=>regex.test(x));return i<0?'':linhas.slice(i,i+3).join(' ');};
  // Não varrer as três linhas seguintes: elas podem pertencer à emissão ou ao nascimento.
  const indices=linhas.map((x,i)=>/\bVALIDADE\b/.test(x)?i:-1).filter(i=>i>=0);
  const trecho=indices.length===1?linhas[indices[0]]:'';
  const outrosRotulos=/NASCIMENTO|EMISSAO|EXPEDICAO|HABILITACAO|1[ªA.]?\s*HAB|\bCPF\b|\bREGISTRO\b/;
  const dataRegex=/\b(\d{2})[\/.-](\d{2})[\/.-](\d{4})\b/g;
  let candidato='';
  if(trecho&&!outrosRotulos.test(trecho)){
    const depois=trecho.replace(/^.*?\bVALIDADE\b\s*[:.-]?\s*/,'');
    if(/^(?:\d{2}[\/.-]\d{2}[\/.-]\d{4})\s*$/.test(depois))candidato=depois;
    else if(!depois&&/^\d{2}[\/.-]\d{2}[\/.-]\d{4}$/.test(linhas[indices[0]+1]||''))candidato=linhas[indices[0]+1];
  }
  const datas=[...candidato.matchAll(dataRegex)].map(m=>`${m[3]}-${m[2]}-${m[1]}`).filter(dataValida);
  const nomeLinha=linhas.findIndex(x=>/\bNOME\b/.test(x));
  const nome=nomeLinha<0?'':linhas[nomeLinha].replace(/^.*?\bNOME\b\s*[:.-]?\s*/,'').replace(/E SOBRENOME.*/,'').trim()||linhas[nomeLinha+1]||'';
  const categoria=proximo(/CAT(?:EGORIA)?\.?\s*(?:HAB|HABILITACAO)/).match(/(?:HAB(?:ILITACAO)?\.?\s*[:.-]?\s*)(AB|AC|AD|AE|ACC|A|B|C|D|E)\b/)?.[1]||'';
  const confianca=Number.isFinite(confidence)?confidence:0;
  const baixaConfianca=confianca<70;
  const nomeLimpo=/^[A-Z][A-Z '\-]{2,199}$/.test(nome)?nome:'';
  const sugestoes=baixaConfianca?{nome:'',categoria:'',validade:''}:{nome:nomeLimpo,categoria,validade:datas.length===1?datas[0]:''};
  return {sugestoes,confianca,revisaoNecessaria:true,mensagem:(baixaConfianca?'Leitura inconclusiva: confiança baixa; preencha os campos manualmente. ':'Sugestões de OCR. ')+(sugestoes.validade?'Confira a validade sugerida no documento. ':'Validade não identificada com segurança; confira e preencha manualmente. ')+'Confira documento e QR no Vio. OCR não comprova autenticidade.'};
}

// Instante (ms) do último milissegundo do dia de validade em America/Sao_Paulo.
// O deslocamento é obtido do fuso (Intl), sem fixar -03:00, para não errar datas com horário de verão.
function deslocamentoSP(instanteMs) {
  const p = new Intl.DateTimeFormat('en-US', { timeZone: 'America/Sao_Paulo', hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).formatToParts(new Date(instanteMs));
  const v = tipo => Number(p.find(x => x.type === tipo).value);
  return Date.UTC(v('year'), v('month') - 1, v('day'), v('hour'), v('minute'), v('second')) - Math.floor(instanteMs / 1000) * 1000;
}
export function fimDoDiaSP(validade) {
  if (!dataValida(validade)) return null;
  const [a, m, d] = validade.split('-').map(Number);
  const meiaNoiteLocal = Date.UTC(a, m - 1, d + 1, 0, 0, 0);
  let instante = meiaNoiteLocal - deslocamentoSP(meiaNoiteLocal + 3 * 3600000);
  instante = meiaNoiteLocal - deslocamentoSP(instante);
  return instante - 1;
}
// A conferência só vale para a versão exata do PDF armazenado. Sem documento recebido pelo app
// (fluxo administrativo anterior), não há vínculo a exigir.
export function conferenciaVinculada(registro) {
  const doc = registro?.documento;
  if (!doc) return true;
  return typeof doc.sha256 === 'string' && registro.pdfSha256 === doc.sha256 && registro.pdfVersao === doc.versao;
}
export function preAnaliseVinculada(registro) {
  const doc = registro?.documento;
  const pre = registro?.preAnalise;
  if (!doc || !pre) return false;
  return typeof doc.sha256 === 'string' && pre.pdfSha256 === doc.sha256 && pre.pdfVersao === doc.versao;
}
// Monta o checklist automático e a recomendação de pré-análise a partir do resultado do OCR e do nome cadastrado.
// Importante: presença de QR Code e pré-análise favorável NÃO significam autenticidade nem aprovam automaticamente.
export function construirPreAnalise({ ocrResultado, nomeCadastrado = '', pdfSha256 = null, pdfVersao = null, hoje = hojeSP(), analisadoEm = new Date().toISOString() }) {
  const sug = ocrResultado?.sugestoes || {};
  const nomeExtraido = typeof sug.nome === 'string' ? sug.nome.trim() : '';
  const categoriaExtraida = typeof sug.categoria === 'string' ? sug.categoria.trim().toUpperCase() : '';
  const validadeExtraida = typeof sug.validade === 'string' ? sug.validade.trim() : '';
  const confianca = Number.isFinite(ocrResultado?.confianca) ? Math.round(ocrResultado.confianca) : 0;
  const qrDetectado = ocrResultado?.qrDetectado === true;

  const compNome = compararNomesIdentidade(nomeExtraido, nomeCadastrado);
  const itemNome = compNome.status === 'compativel'
    ? { item: 'identidade', status: 'ok', titulo: 'Identidade compatível com o cadastro', detalhe: `Extraído "${nomeExtraido}" compatível com "${nomeCadastrado.trim()}".` }
    : compNome.status === 'divergente'
      ? { item: 'identidade', status: 'erro', titulo: 'Divergência de identidade detectada', detalhe: `Nome extraído no PDF ("${nomeExtraido}") diverge do nome cadastrado ("${nomeCadastrado.trim()}").` }
      : { item: 'identidade', status: 'pendente', titulo: 'Nome não extraído automaticamente', detalhe: 'Não foi possível ler o nome com segurança; confira visualmente no PDF.' };

  const catRegistrada = CATEGORIAS_VALIDAS.includes(categoriaExtraida);
  const catCompativel = CATEGORIAS_CARONA_AUTOMOVEL.includes(categoriaExtraida);
  const itemCategoria = !catRegistrada
    ? { item: 'categoria', status: 'pendente', titulo: 'Categoria não identificada pelo OCR', detalhe: 'Preencha e confira a categoria manualmente no documento.' }
    : catCompativel
      ? { item: 'categoria', status: 'ok', titulo: `Categoria ${categoriaExtraida} compatível`, detalhe: 'Habilitação compatível com caronas em automóvel na plataforma.' }
      : { item: 'categoria', status: 'erro', titulo: `Categoria ${categoriaExtraida} incompatível`, detalhe: 'A plataforma exige categoria compatível com automóvel (B, AB, C, AC, D, AD, E ou AE).' };

  let diasValidade = null;
  let validadeVigente = false;
  if (dataValida(validadeExtraida)) {
    diasValidade = Math.round((Date.parse(validadeExtraida + 'T12:00:00Z') - Date.parse(hoje + 'T12:00:00Z')) / 86400000);
    validadeVigente = diasValidade >= 0;
  }
  const itemValidade = diasValidade === null
    ? { item: 'validade', status: 'pendente', titulo: 'Validade não identificada pelo OCR', detalhe: 'Confira o campo 4b no PDF e informe a data de validade.' }
    : validadeVigente
      ? { item: 'validade', status: 'ok', titulo: `Validade vigente (${validadeExtraida})`, detalhe: diasValidade <= 30 ? `Vence em ${diasValidade} dia(s).` : `Faltam ${diasValidade} dias para o vencimento.` }
      : { item: 'validade', status: 'erro', titulo: `CNH vencida (${validadeExtraida})`, detalhe: `Vencida há ${Math.abs(diasValidade)} dia(s).` };

  const itemQr = qrDetectado
    ? { item: 'qr_vio', status: 'pendente_humano', titulo: 'QR Code detectado no PDF (autenticidade não comprovada)', detalhe: 'Conferência humana obrigatória no aplicativo oficial Vio.' }
    : { item: 'qr_vio', status: 'erro', titulo: 'QR Code não detectado', detalhe: 'Exija o PDF original da CNH Digital com QR Code legível.' };

  const checklist = [itemNome, itemCategoria, itemValidade, itemQr];
  const temInconsistencia = compNome.divergente || (catRegistrada && !catCompativel) || (diasValidade !== null && !validadeVigente) || !qrDetectado;
  const todosAutomaticosOk = compNome.compativel && catCompativel && validadeVigente && qrDetectado && confianca >= 70;

  const recomendacao = temInconsistencia
    ? 'inconsistencias_encontradas'
    : todosAutomaticosOk
      ? 'pre_analise_favoravel'
      : 'revisao_necessaria';

  const resumo = recomendacao === 'pre_analise_favoravel'
    ? 'Pré-análise favorável: nome compatível com o cadastro, categoria apta e validade vigente. Confira a identidade e valide o QR Code no app Vio antes de aprovar.'
    : recomendacao === 'inconsistencias_encontradas'
      ? 'Inconsistências encontradas na pré-análise automática. Verifique os itens destacados antes de tomar qualquer decisão.'
      : 'Revisão necessária: um ou mais campos não puderam ser extraídos com segurança pelo OCR. Preencha e confira manualmente o PDF e o QR no Vio.';

  return {
    status: 'concluida',
    pdfSha256,
    pdfVersao,
    analisadoEm,
    confianca,
    metodo: ocrResultado?.metodo || 'pdf',
    qrDetectado,
    sugestoes: { nome: nomeExtraido, categoria: categoriaExtraida, validade: validadeExtraida },
    nomeCadastrado: typeof nomeCadastrado === 'string' ? nomeCadastrado.trim() : '',
    identidadeStatus: compNome.status,
    divergenciaIdentidade: compNome.divergente,
    categoriaStatus: !catRegistrada ? 'ausente' : catCompativel ? 'compativel' : 'incompativel',
    validadeStatus: diasValidade === null ? 'ausente' : validadeVigente ? (diasValidade <= 30 ? 'vence_em_breve' : 'vigente') : 'vencida',
    diasValidade,
    recomendacao,
    resumo,
    checklist,
    revisaoHumanaObrigatoria: true
  };
}
export function identidadeDivergenteNaoResolvida(registro, nomeCadastrado = '') {
  if (!registro) return false;
  const pre = preAnaliseVinculada(registro) ? registro.preAnalise : null;
  const refCadastro = nomeCadastrado || pre?.nomeCadastrado || '';
  const compSalvo = refCadastro && registro.nome ? compararNomesIdentidade(registro.nome, refCadastro) : null;
  if (compSalvo?.divergente && registro.divergenciaIdentidadeConfirmada !== true) return true;
  if (pre?.divergenciaIdentidade === true && registro.divergenciaIdentidadeConfirmada !== true) return true;
  return false;
}
export function avaliarRegistroCnh(registro, hoje = hojeSP(), nomeCadastrado = '') {
  const base = avaliarCnh(registro, hoje);
  if (!conferenciaVinculada(registro)) {
    return { ...base, apta: false, situacao: base.situacao === 'vencida' ? 'vencida' : 'revisao', documentoDivergente: true };
  }
  if (identidadeDivergenteNaoResolvida(registro, nomeCadastrado)) {
    return { ...base, apta: false, situacao: base.situacao === 'vencida' ? 'vencida' : 'revisao', divergenciaIdentidade: true };
  }
  return base;
}

// O PDF recebido do motorista fica armazenado de forma privada e criptografada no servidor.
// O OCR é executado automaticamente após o recebimento, gerando checklist e recomendação de pré-análise
// vinculados à versão atual do PDF, sem jamais aprovar automaticamente.
const ROTULOS_RECOMENDACAO = {
 pre_analise_favoravel: { texto: 'Pré-análise favorável', classe: 'ativo' },
 inconsistencias_encontradas: { texto: 'Inconsistências encontradas', classe: 'suspenso' },
 revisao_necessaria: { texto: 'Revisão necessária', classe: 'pendente' }
};
function normalizarNomeUi(s){
 return String(s??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toUpperCase().replace(/[^A-Z\s]/g,' ').replace(/\s+/g,' ').trim();
}
function etiquetaPreAnalise(pre){
 if(!pre)return '';
 if(pre.status==='em_andamento')return '<span class="badge em_analise">OCR automático em andamento</span>';
 if(pre.status==='falha')return '<span class="badge pendente">OCR inconclusivo (revisar)</span>';
 const info=ROTULOS_RECOMENDACAO[pre.recomendacao]||ROTULOS_RECOMENDACAO.revisao_necessaria;
 return `<span class="badge ${info.classe}">${esc(info.texto)}</span>${pre.divergenciaIdentidade?'<span class="sub cnh-alerta-id">Divergência de identidade</span>':''}`;
}
function indicadorCnh(uid){
 const c=(state.cnh?.itens||[]).find(x=>x.uid===uid);
 if(!c?.documentoRecebido)return '';
 const doc=c.arquivoPresente?'CNH recebida (PDF)':'PDF excluído';
 const rec=c.preAnalise?.status==='concluida'?' · '+(ROTULOS_RECOMENDACAO[c.preAnalise.recomendacao]?.texto||'Revisão necessária'):c.preAnalise?.status==='em_andamento'?' · OCR em andamento':'';
 return `<span class="sub">${esc(doc+rec)}</span>`;
}
function painelCnh(){
 const lista=state.cnh?.itens||[];
 const nomes={revisao:'Revisão necessária',vencida:'Vencida',vence_em_breve:'Vence em até 30 dias',conferida:'Conferida manualmente'};
 const situacao=c=>c.documentoRecebido&&c.conferenciaDesatualizada&&c.situacao!=='vencida'?'Aguardando conferência do PDF recebido':(nomes[c.situacao]||'Revisão necessária');
 const documento=c=>c.documentoRecebido?`<span class="sub">${c.arquivoPresente?'Documento v'+esc(c.pdfVersao)+' recebido em '+date(String(c.documentoRecebidoEm||'').slice(0,10)):'PDF excluído (reenvio necessário)'}</span>`:'';
 return `<section class="panel cnh-panel"><div class="panel-head"><div><h2>CNH · conferência e vencimentos</h2><p>OCR automático gera pré-análise e checklist. Documento e QR precisam de conferência humana no Vio.</p></div></div><p class="note cnh-note">Sem consulta ao Detran: esta conferência não verifica suspensão ou cassação. A presença de QR Code e a pré-análise favorável nunca aprovam automaticamente. O controle de vencimento roda a cada hora enquanto o servidor está ligado.</p>${state.cnh?.disponivel?table(['Motorista','Categoria','Validade','Pré-análise / Conferência','Ações'],lista.map(c=>`<tr><td>${esc(c.nome||c.preAnalise?.sugestoes?.nome||'—')}<span class="sub">${esc(c.uid)}</span></td><td>${esc(c.categoria||c.preAnalise?.sugestoes?.categoria||'—')}</td><td>${(c.validade||c.preAnalise?.sugestoes?.validade)?date(c.validade||c.preAnalise.sugestoes.validade):'—'}${c.dias!==null&&c.dias!==undefined?`<span class="sub">${c.dias<0?'Vencida há '+Math.abs(c.dias):'Faltam '+c.dias} dias</span>`:''}</td><td>${etiquetaPreAnalise(c.preAnalise)}<div>${esc(situacao(c))}</div>${documento(c)}</td><td><button class="secondary" data-cnh="${esc(c.uid)}">Revisar / renovar</button>${c.apta?`<button class="primary" data-uid="${esc(c.uid)}" data-decisao="aprovar">Aprovar após conferência</button>`:''}</td></tr>`)):'<p class="note cnh-note">Consulta de CNH indisponível. Configure o Firebase e as permissões da coleção administrativa.</p>'}${state.cnh?.limitado?'<p class="note">Exibindo as primeiras 100 conferências.</p>':''}</section>`;
}
function renderChecklistPreAnalise(pre,nomeCadastrado){
 if(!pre)return '<div class="cnh-preanalise"><p class="note">Nenhuma pré-análise automática disponível para este registro.</p></div>';
 if(pre.status==='em_andamento')return '<div class="cnh-preanalise"><span class="badge em_analise">Pré-análise automática em andamento</span><p class="note">O servidor está executando o OCR automático do PDF recebido. Clique em "Executar OCR novamente" para atualizar.</p></div>';
 const rec=ROTULOS_RECOMENDACAO[pre.recomendacao]||ROTULOS_RECOMENDACAO.revisao_necessaria;
 const icone=st=>st==='ok'?'✓':st==='erro'?'✕':'!';
 const classeItem=st=>st==='ok'?'cnh-item-ok':st==='erro'?'cnh-item-erro':'cnh-item-pendente';
 const itens=(pre.checklist||[]).map(i=>`<li class="${classeItem(i.status)}"><span class="cnh-icone">${icone(i.status)}</span><div><strong>${esc(i.titulo)}</strong><span class="sub">${esc(i.detalhe)}</span></div></li>`).join('');
 const alertaId=pre.divergenciaIdentidade?`<div class="cnh-divergencia-box" role="alert"><strong>Divergência de identidade detectada</strong><p>O nome lido no documento (<strong>${esc(pre.sugestoes?.nome||'—')}</strong>) difere do nome cadastrado no Firebase (<strong>${esc(pre.nomeCadastrado||nomeCadastrado||'—')}</strong>). A aprovação fica bloqueada enquanto esta divergência não for conferida e resolvida.</p></div>`:'';
 return `<div class="cnh-preanalise"><div class="cnh-preanalise-topo"><div><strong>Recomendação automática:</strong> <span class="badge ${rec.classe}">${esc(rec.texto)}</span></div>${Number.isFinite(pre.confianca)?`<span class="sub">Confiança OCR: ${Math.round(pre.confianca)}% · PDF v${esc(pre.pdfVersao)}</span>`:''}</div><p class="note">${esc(pre.resumo)}</p>${alertaId}${itens?`<ul class="cnh-checklist">${itens}</ul>`:''}</div>`;
}
function abrirCnh(uid){
 const c=state.cnh?.itens.find(x=>x.uid===uid)||{};
 const sol=(state.firebase?.solicitacoes||[]).find(x=>x.uid===uid)||{};
 const usr=(state.usuariosFirebase?.usuarios||[]).find(x=>x.uid===uid)||{};
 const nomeCadastrado=c.preAnalise?.nomeCadastrado||sol.nome||usr.nome||'';
 const recebido=c.documentoRecebido===true,presente=recebido&&c.arquivoPresente===true;
 const usarSugestoes=recebido&&c.conferenciaDesatualizada&&c.preAnalise?.status==='concluida';
 const nomeInicial=usarSugestoes?(c.preAnalise.sugestoes?.nome||c.nome||''):(c.nome||c.preAnalise?.sugestoes?.nome||'');
 const catInicial=usarSugestoes?(c.preAnalise.sugestoes?.categoria||c.categoria||''):(c.categoria||c.preAnalise?.sugestoes?.categoria||'');
 const valInicial=usarSugestoes?(c.preAnalise.sugestoes?.validade||c.validade||''):(c.validade||c.preAnalise?.sugestoes?.validade||'');

 const blocoDoc=recebido?`<div class="cnh-doc"><p class="note"><strong>Documento recebido do motorista</strong> (versão ${esc(c.pdfVersao)}) em ${date(String(c.documentoRecebidoEm||'').slice(0,10))}. ${presente?'O PDF está armazenado de forma privada e criptografada; a pré-análise e a conferência ficam vinculadas a esta versão.':'O PDF foi excluído. Peça o reenvio antes de conferir.'}</p><div class="cnh-doc-actions"><button type="button" class="secondary" id="cnh-abrir" ${presente?'':'disabled'}>Abrir CNH enviada pelo motorista</button> <button type="button" class="secondary" id="cnh-ocr-armazenado" ${presente?'':'disabled'}>Executar OCR novamente</button> <button type="button" class="secondary" id="cnh-excluir" ${presente?'':'disabled'}>Excluir documento</button></div><input type="hidden" name="pdfSha256" value="${esc(c.pdfSha256||'')}"><input type="hidden" name="pdfVersao" value="${esc(c.pdfVersao??'')}"></div>`:'';

 const blocoPreAnalise=recebido?`<div id="cnh-preanalise-wrap">${renderChecklistPreAnalise(c.preAnalise,nomeCadastrado)}</div>`:'';

 const blocoManual=`<div class="cnh-manual-box"><p class="note">Envie somente o PDF original completo da CNH, exportado pelo aplicativo oficial, contendo frente, verso e QR Code legível, em uma página e com até 5 MB. Fotos, prints, PNG e JPG não são aceitos. O arquivo manual não é armazenado.</p><label>Documento da CNH (envio manual)<input id="cnh-imagem" type="file" accept="application/pdf,.pdf"></label><button type="button" class="secondary" id="cnh-ocr">Ler documento</button></div>`;
 const secaoManual=recebido?`<details class="cnh-manual-details"><summary>Envio manual alternativo (documento recebido pelo atendimento)</summary>${blocoManual}</details>`:blocoManual;

 dialog('Conferir CNH',`${blocoDoc}${blocoPreAnalise}${secaoManual}<p id="cnh-ocr-status" role="status" class="note"></p>${nomeCadastrado?`<p class="note"><strong>Nome cadastrado no Firebase:</strong> ${esc(nomeCadastrado)}</p>`:''}<label>Nome conforme documento<input name="nome" required minlength="3" maxlength="200" value="${esc(nomeInicial)}"></label><label>Categoria<select name="categoria" required><option value="">Selecione</option>${['A','B','AB','C','AC','D','AD','E','AE','ACC'].map(x=>`<option ${catInicial===x?'selected':''}>${x}</option>`).join('')}</select></label><label>Validade<input type="date" name="validade" required value="${esc(valInicial)}"></label><label class="cnh-check"><input type="checkbox" name="documentoConferido" ${c.documentoConferido&&!c.conferenciaDesatualizada?'checked':''}> Conferi visualmente os dados e a identidade com o documento apresentado</label><label class="cnh-check"><input type="checkbox" name="qrConferidoVio" ${c.qrConferidoVio&&!c.conferenciaDesatualizada?'checked':''}> Conferi a autenticidade do QR Code no aplicativo oficial Vio e comparei os dados</label><label class="cnh-check cnh-check-divergencia" id="cnh-divergencia-wrap" hidden><input type="checkbox" name="divergenciaIdentidadeConfirmada" ${c.divergenciaIdentidadeConfirmada&&!c.conferenciaDesatualizada?'checked':''}> Confirmo que verifiquei manualmente a divergência de nome e que o documento pertence ao titular cadastrado</label><label>Parecer / pendências<textarea name="observacao" required minlength="5" maxlength="1000" placeholder="Informe o que foi conferido ou o motivo da revisão."></textarea></label><p class="note">A pré-análise automática e a detecção de QR Code nunca aprovam automaticamente. Salvar retira qualquer aprovação anterior até nova decisão explícita. Sem as conferências obrigatórias ou com divergência de identidade não resolvida, o cadastro permanece em revisão.</p>`,async b=>api('cnh/'+encodeURIComponent(uid),{...b,documentoConferido:b.documentoConferido==='on',qrConferidoVio:b.qrConferidoVio==='on',divergenciaIdentidadeConfirmada:b.divergenciaIdentidadeConfirmada==='on',pdfVersao:b.pdfVersao===undefined||b.pdfVersao===''?undefined:Number(b.pdfVersao),pdfSha256:b.pdfSha256||undefined}));

 let preAtual=c.preAnalise||null;
 const atualizarAvisoDivergencia=()=>{
  const form=$('#dialog-form'),wrap=$('#cnh-divergencia-wrap');
  if(!form||!wrap)return;
  const nomeDigitado=form.elements.namedItem('nome')?.value||'';
  const divDigitada=Boolean(nomeCadastrado&&nomeDigitado.trim().length>=3&&normalizarNomeUi(nomeDigitado)!==normalizarNomeUi(nomeCadastrado));
  const divPre=preAtual?.divergenciaIdentidade===true;
  wrap.hidden=!(divDigitada||divPre);
 };
 atualizarAvisoDivergencia();
 $('#dialog-form')?.elements.namedItem('nome')?.addEventListener('input',atualizarAvisoDivergencia);

 const preencher=(r,msg)=>{
  for(const [k,v] of Object.entries(r.sugestoes||{})){const campo=$('#dialog-form').elements.namedItem(k);if(campo&&v)campo.value=v;}
  if(r.preAnalise){
   preAtual=r.preAnalise;
   const wrap=$('#cnh-preanalise-wrap');
   if(wrap)wrap.innerHTML=renderChecklistPreAnalise(r.preAnalise,nomeCadastrado);
  }
  atualizarAvisoDivergencia();
  msg.textContent=(r.mensagem||r.preAnalise?.resumo||'Leitura concluída.')+' Qualidade estimada do texto: '+Math.round(r.confianca??r.preAnalise?.confianca??0)+'%.';
 };
 if($('#cnh-abrir'))$('#cnh-abrir').onclick=()=>{window.open('/api/cnh/'+encodeURIComponent(uid)+'/arquivo','_blank','noopener');};
 if($('#cnh-ocr-armazenado'))$('#cnh-ocr-armazenado').onclick=async()=>{
  const msg=$('#cnh-ocr-status'),button=$('#cnh-ocr-armazenado'),form=$('#dialog-form');
  button.disabled=true;msg.textContent='Executando OCR e pré-análise no PDF armazenado. Aguarde até 60 segundos…';
  try{const r=await api('cnh/'+encodeURIComponent(uid)+'/ocr',{});
   if(!$('#dialog').open)return;
   if(r.pdfSha256!==form.elements.namedItem('pdfSha256').value||String(r.pdfVersao)!==form.elements.namedItem('pdfVersao').value){msg.textContent='O motorista enviou outro documento. Feche e reabra a conferência para analisar o PDF atual.';return;}
   preencher(r,msg);
  }catch(err){msg.textContent='Leitura inconclusiva. Preencha manualmente e registre as pendências. '+err.message;}finally{button.disabled=false;}
 };
 if($('#cnh-excluir'))$('#cnh-excluir').onclick=async()=>{
  const motivo=prompt('Motivo da exclusão do PDF (mínimo de 5 caracteres):','Retenção concluída após a decisão administrativa');
  if(!motivo||!confirm('Excluir definitivamente o PDF armazenado desta CNH? O motorista precisará reenviar para uma nova conferência.'))return;
  const button=$('#cnh-excluir');button.disabled=true;
  try{await api('cnh/'+encodeURIComponent(uid)+'/arquivo/excluir',{motivo});$('#dialog').close();await load();toast('Documento excluído.');}catch(err){$('#dialog-error').textContent=err.message;button.disabled=false;}
 };
 $('#cnh-ocr').onclick=async()=>{
  const f=$('#cnh-imagem').files[0],msg=$('#cnh-ocr-status'),button=$('#cnh-ocr');
  if(!f||f.size>5*1024*1024||(f.type!=='application/pdf'||!f.name.toLowerCase().endsWith('.pdf'))){msg.textContent='Aceitamos somente PDF original da CNH com QR Code legível, até 5 MB.';return;}
  button.disabled=true;msg.textContent='Lendo documento em memória no servidor. Aguarde até 60 segundos…';
  try{const imagem=await new Promise((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(r.result);r.onerror=reject;r.readAsDataURL(f);});const r=await api('cnh/ocr',{imagem});
   if(!$('#dialog').open)return;
   preencher(r,msg);
  }catch(err){msg.textContent='Leitura inconclusiva. Preencha manualmente e registre as pendências. '+err.message;}finally{button.disabled=false;}
 };
}

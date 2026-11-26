import { FieldPath } from 'firebase-admin/firestore';
import { banco } from './firebaseMotoristas.js';
import { avaliarCnh, avaliarRegistroCnh, compararNomesIdentidade, conferenciaVinculada, fimDoDiaSP, preAnaliseVinculada, validarConferencia } from './cnh.js';
import { normalizarStatusSolicitacao, servicoDocumentos } from './cnhDocumentos.js';
const erro=(msg,status=400)=>Object.assign(Error(msg),{status});
export async function salvarConferencia(uid,b,admin){
 const dados=validarConferencia(b),db=await banco();if(!db)throw erro('Firebase indisponível.',503);
 return db.runTransaction(async tx=>{
  const pedido=db.collection('solicitacoes_motoristas').doc(uid),usuario=db.collection('usuarios').doc(uid),ref=db.collection('cnh_admin').doc(uid);
  const [p,u,c]=await Promise.all([tx.get(pedido),tx.get(usuario),tx.get(ref)]);
  if(!p.exists||!u.exists)throw erro('Cadastro ou solicitação não encontrado.',404);
  if(p.data().motoristaUid&&p.data().motoristaUid!==uid)throw erro('UID divergente.',409);
  if(!['PENDENTE','APROVADA'].includes(normalizarStatusSolicitacao(p.data().status)))throw erro('Solicitação encerrada.',409);
  // Toda nova conferência exige decisão explícita posterior. Nunca concede acesso.
  const usuarioDados=u.data()||{};
  const registroAtual=c.exists?c.data()||{}:{};
  // Se o motorista enviou PDF pelo app, a conferência vale somente para a versão exata analisada (hash + versão).
  const documento=registroAtual.documento;let vinculo={};
  if(documento){
   if(b.pdfSha256!==documento.sha256||b.pdfVersao!==documento.versao)throw erro('O motorista enviou o PDF novamente. Reabra a conferência e analise o documento atual.',409);
   if(documento.arquivoPresente!==true)throw erro('O PDF foi excluído. Peça ao motorista que reenvie o documento antes de conferir.',409);
   vinculo={pdfSha256:documento.sha256,pdfVersao:documento.versao,documento};
   if(preAnaliseVinculada(registroAtual))vinculo.preAnalise=registroAtual.preAnalise;
  }
  const nomeCadastrado=typeof usuarioDados.nome==='string'?usuarioDados.nome.trim():'';
  const compSalvo=nomeCadastrado?compararNomesIdentidade(dados.nome,nomeCadastrado):null;
  const divergenciaPreAnalise=vinculo.preAnalise?.divergenciaIdentidade===true;
  if((compSalvo?.divergente||divergenciaPreAnalise)&&dados.documentoConferido===true&&dados.divergenciaIdentidadeConfirmada!==true){
   throw erro('Divergência de identidade entre o documento e o nome cadastrado. Para confirmar o documento, marque a verificação manual de resolução de identidade ou salve sem marcar para manter em revisão.',409);
  }
  const novoRegistro={...dados,...vinculo,nomeCadastrado,conferidoPor:admin,conferidoEm:new Date().toISOString(),bloqueioAplicado:false};
  const avaliacao=avaliarRegistroCnh(novoRegistro,undefined,nomeCadastrado);
  tx.set(ref,novoRegistro);
  tx.update(usuario,{condutorValidado:false,...(usuarioDados.papelAtivo==='MOTORISTA'?{papelAtivo:'PASSAGEIRO'}:{}),cnhValidade:dados.validade,cnhValidadeFimMs:fimDoDiaSP(dados.validade),cnhRevisaoNecessaria:!avaliacao.apta});
  tx.set(db.collection('cnh_eventos').doc(),{uid,acao:'conferencia registrada',administrador:admin,criadoEm:new Date().toISOString()});
  return {ok:true,...avaliacao};
 });
}
export async function listarCnh(){
 const db=await banco();if(!db)return {disponivel:false,itens:[],limitado:false};
 const docs=(await db.collection('cnh_admin').orderBy(FieldPath.documentId()).limit(101).get()).docs;
 return {disponivel:true,limitado:docs.length>100,itens:docs.slice(0,100).map(d=>{const c=d.data();const preAnalise=preAnaliseVinculada(c)?c.preAnalise:null;return {uid:d.id,nome:c.nome,categoria:c.categoria,validade:c.validade,documentoConferido:c.documentoConferido===true,qrConferidoVio:c.qrConferidoVio===true,divergenciaIdentidadeConfirmada:c.divergenciaIdentidadeConfirmada===true,...avaliarRegistroCnh(c,undefined,c.nomeCadastrado||preAnalise?.nomeCadastrado||''),documentoRecebido:Boolean(c.documento),arquivoPresente:c.documento?.arquivoPresente===true,documentoRecebidoEm:c.documento?.recebidoEm||null,pdfSha256:c.documento?.sha256||null,pdfVersao:c.documento?.versao??null,conferenciaDesatualizada:Boolean(c.documento)&&!conferenciaVinculada(c),preAnalise};})};
}
// Percorre todas as páginas. Cada documento é relido na transação para não bloquear uma renovação concorrente.
export async function processarVencimentos(){
 const db=await banco();if(!db)return {disponivel:false,bloqueados:0};
 let cursor=null,bloqueados=0;
 while(true){let q=db.collection('cnh_admin').orderBy(FieldPath.documentId()).limit(100);if(cursor)q=q.startAfter(cursor);const docs=(await q.get()).docs;if(!docs.length)break;
  for(const doc of docs){if(avaliarCnh(doc.data()).situacao!=='vencida'||doc.data().bloqueioAplicado===true)continue;
   const alterado=await db.runTransaction(async tx=>{
    const ref=db.collection('cnh_admin').doc(doc.id),user=db.collection('usuarios').doc(doc.id);
    const [c,u]=await Promise.all([tx.get(ref),tx.get(user)]);
    if(!c.exists||!u.exists||avaliarCnh(c.data()).situacao!=='vencida'||c.data().bloqueioAplicado===true)return false;
    tx.update(user,{condutorValidado:false,cnhRevisaoNecessaria:true,...(u.data().papelAtivo==='MOTORISTA'?{papelAtivo:'PASSAGEIRO'}:{})});
    tx.update(ref,{bloqueioAplicado:true});
    tx.set(db.collection('cnh_eventos').doc(),{uid:doc.id,acao:'acesso de motorista retirado por vencimento',criadoEm:new Date().toISOString(),administrador:'sistema'});
    return true;
   });if(alterado)bloqueados++;
  }if(docs.length<100)break;cursor=docs.at(-1);
 }return {disponivel:true,bloqueados};
}
export function iniciarControleCnh(){let executando=false;const executar=async()=>{if(executando)return;executando=true;try{await processarVencimentos();}catch{console.error('Controle de CNH não concluído; nova tentativa em uma hora.');}try{await servicoDocumentos.reconciliar();}catch{console.error('Reconciliação dos documentos de CNH não concluída; nova tentativa em uma hora.');}finally{executando=false;}};void executar();const timer=setInterval(executar,3600000);timer.unref();return timer;}

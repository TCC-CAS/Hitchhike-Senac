import { Worker } from 'node:worker_threads';
// Uma única leitura/validação por vez. Se um OCR automático estiver rodando em background quando chegar
// um novo upload para validar, o OCR de background é interrompido imediatamente (ocupado=false) e reagendado.
let ocupado=false;
let tarefaBackground=null;
const LIMITE_BYTES=5*1024*1024;
const erroHttp=(mensagem,status)=>Object.assign(Error(mensagem),{status});
export function validarBufferPdf(buffer){
 if(!Buffer.isBuffer(buffer)||buffer.length===0||buffer.length>LIMITE_BYTES||buffer.subarray(0,5).toString()!=='%PDF-')throw erroHttp('Arquivo PDF inválido. Renomear uma imagem não a transforma em PDF.',400);
}
// modo 'ocr': renderiza, exige QR e lê nome/validade/categoria (sugestões). modo 'validar': renderiza e exige uma página + QR, sem OCR.
function processar(buffer,modo,{background=false}={}){
 ocupado=true;
 return new Promise((resolve,reject)=>{
  const worker=new Worker(new URL('./ocr-worker.js',import.meta.url),{workerData:{buffer,pdf:true,modo},resourceLimits:{maxOldGenerationSizeMb:256}});
  let finalizado=false;
  const encerrar=(err,data,statusErro=422,preemptado=false)=>{
   if(finalizado)return;
   finalizado=true;
   clearTimeout(timer);
   if(tarefaBackground?.worker===worker)tarefaBackground=null;
   if(preemptado){
    ocupado=false;
    worker.terminate().catch(()=>{});
    reject(Object.assign(Error(err),{status:statusErro}));
    return;
   }
   worker.terminate().finally(()=>{
    ocupado=false;
    err?reject(Object.assign(Error(err),{status:statusErro})):resolve(data);
   });
  };
  if(background){
   tarefaBackground={worker,preemptar:()=>encerrar('OCR automático adiado para priorizar novo envio.',null,429,true)};
  }
  const timer=setTimeout(()=>encerrar('Leitura inconclusiva: limite de 60 segundos. Preencha manualmente para revisão.'),60000);
  worker.once('message',r=>r.erro?encerrar(r.mensagem||'Leitura inconclusiva. Tente um arquivo mais nítido ou preencha manualmente.'):encerrar(null,r));
  worker.once('error',()=>encerrar('Não foi possível ler a imagem. Preencha manualmente para revisão.'));
  worker.once('exit',()=>{if(!finalizado)encerrar('Leitura interrompida. Preencha manualmente.');});
 });
}
function liberarBackgroundSeNecessario(){
 if(ocupado&&tarefaBackground){
  tarefaBackground.preemptar();
 }
}
export function ocrOcupado(){return ocupado;}
// OCR de um PDF já em memória (ex.: PDF armazenado de forma privada). Apenas sugere dados.
export function lerCnhBuffer(buffer,{background=false}={}) {
 if(!background)liberarBackgroundSeNecessario();
 if(ocupado)throw erroHttp('OCR ocupado. Aguarde a leitura atual.',429);
 validarBufferPdf(buffer);
 return processar(buffer,'ocr',{background});
}
// Validação estrutural do envio pelo app: PDF de uma página, legível, com QR Code detectável.
export function validarPdfCnh(buffer) {
 liberarBackgroundSeNecessario();
 if(ocupado)throw erroHttp('Validação ocupada. Tente novamente em instantes.',429);
 validarBufferPdf(buffer);
 return processar(buffer,'validar');
}
export function lerCnh(imagem) {
 liberarBackgroundSeNecessario();
 if(ocupado)throw erroHttp('OCR ocupado. Aguarde a leitura atual.',429);
 if(typeof imagem!=='string'||imagem.length>7000000)throw erroHttp('Envie o PDF original da CNH de até 5 MB.',400);
 if(!imagem.startsWith('data:application/pdf;'))throw erroHttp('Aceitamos somente PDF da CNH com frente, verso e QR Code legível. Imagens não são aceitas.',415);
 if(!/^data:application\/pdf;base64,[A-Za-z0-9+/=]+$/.test(imagem))throw erroHttp('Arquivo PDF inválido.',400);
 const buffer=Buffer.from(imagem.split(',')[1],'base64');
 validarBufferPdf(buffer);
 return processar(buffer,'ocr');
}

# CNH: OCR gratuito, armazenamento privado e conferência administrativa

## Política vigente: somente PDF completo com QR Code

Aceitamos somente o **PDF original da CNH Digital** (exportado pelo aplicativo oficial Carteira Digital de Trânsito), com frente, verso e QR Code legível, em **uma página** e com **até 5 MB**. Fotos, prints, PNG e JPEG são recusados no aplicativo Android Kotlin, no formulário administrativo e na API (`HTTP 415`). Uma imagem renomeada para `.pdf` ou declarada como `application/pdf` é recusada pela verificação de cabeçalho (`%PDF-`) e estrutura (`HTTP 400`/`422`). PDF protegido por senha, com mais de uma página ou sem QR Code detectável/legível é recusado (`HTTP 422`).

A detecção genérica de QR é feita localmente e retorna apenas `qrDetectado: true`; o payload não é armazenado nem devolvido. Encontrar um QR não demonstra que ele seja um QR Vio legítimo, que corresponda à CNH, nem que frente/verso estejam completos. O administrador deve conferir a documentação completa e comparar os dados no aplicativo oficial Vio. PDFs com impressão degradada ou QR incompatível com o leitor local podem ser rejeitados e devem ser reenviados a partir do original.

## Leitura de PDF por regiões e OCR local

O PDF é renderizado em memória com `pdfjs-dist` e `@napi-rs/canvas`, além do `tesseract.js` com modelo local em português (`@tesseract.js-data/por`). No layout de exportação com imagens separadas da frente e verso, o sistema localiza a frente pela geometria e confirma o layout pelos rótulos: nome, 4b (validade) e 9 (categoria). Cada valor é lido separadamente do rótulo, com ampliação e binarização. A data de emissão fica fora do recorte de validade.

PDF sem esse layout passa pela leitura conservadora da página e pode exigir preenchimento manual. Não há garantia de compatibilidade com todos os modelos, versões, rotações ou PDFs digitalizados. O limite de processamento é de 60 segundos e uma leitura/validação por vez.

A confiança mínima de 70 é uma regra de triagem, não um limiar estatístico de segurança validado. Na leitura da página, baixa confiança deixa todas as sugestões vazias. Na leitura por regiões, o limiar é aplicado a cada campo e os rótulos esperados precisam ser reconhecidos. **O OCR apenas sugere os dados: a conferência humana continua obrigatória mesmo com resultado completo.** Renderizar um PDF não verifica sua assinatura digital, sua autenticidade nem a situação atual no Detran.

Os PDFs de teste em `tests/fixtures/` são inteiramente fictícios. Nenhum documento real foi incluído no repositório. Não há consulta ao Detran nem API paga: o OCR executa no próprio servidor, sem enviar documentos a terceiros.

## Armazenamento privado e criptografia (AES-256-GCM)

Quando o motorista envia o PDF pelo aplicativo Android Kotlin:

1. O arquivo passa pela validação estrutural (1 página, até 5 MB, cabeçalho `%PDF-`, sem senha e com QR Code legível).
2. O PDF é criptografado com **AES-256-GCM** (`cnhStorage.js`) antes de tocar o disco:
   - Utiliza a chave exclusiva de 32 bytes definida em `CNH_STORAGE_KEY` (64 caracteres hexadecimais ou Base64).
   - Não há chave padrão, fallback inseguro nem chave efêmera: sem `CNH_STORAGE_KEY` válida e exclusiva, o recebimento de documentos fica desativado (`HTTP 503`).
   - Cada gravação gera um IV aleatório de 12 bytes e autentica como contexto adicional (AAD) o `uid` do titular e o hash `SHA-256` do PDF, impedindo adulteração, substituição ou troca de arquivo entre usuários.
   - Os arquivos criptografados (`.pdf.enc`) ficam em `data/cnh_pdfs/` (fora de `public/` e ignorados pelo Git), em diretórios derivados de hash hexadecimal para impedir *path traversal*.
3. Não existem links públicos para os PDFs. O arquivo só é decifrado em memória para o próprio titular autenticado ou para administradores com sessão ativa, sempre com `Cache-Control: no-store`.
4. Logs do servidor nunca registram PDFs, Base64, tokens, CPF, conteúdo do QR ou chaves.

## Instalação e uso

1. Atualize o código e execute `npm install` (Node.js 24+).
2. Configure `FIREBASE_SERVICE_ACCOUNT_PATH` e `CNH_STORAGE_KEY` no ambiente (`.env` local, nunca versionado no Git). Gere uma chave exclusiva de 32 bytes com:
   ```bash
   node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
   ```
3. **Antes de ativar em ambiente real, proteja as coleções `cnh_admin` e `cnh_eventos` nas regras do Firestore**: clientes do app não podem ler/gravar essas coleções. Somente o backend Firebase Admin, com IAM restrito, deve acessá-las. As permissões de `usuarios` também devem impedir o próprio usuário de alterar `condutorValidado`, `papelAtivo`, `cnhValidade`, `cnhValidadeFimMs` e `cnhRevisaoNecessaria`. Um `allow` amplo sobreposto invalida um `deny`; revise as regras completas do projeto.
4. Execute `npm start`, entre no painel administrativo e abra **Motoristas → Conferir CNH / OCR** na solicitação desejada.
5. Caso o motorista tenha enviado o PDF pelo aplicativo Android Kotlin:
   - A lista exibe o indicador **CNH recebida (PDF)**.
   - No modal de conferência, utilize **Abrir CNH enviada pelo motorista** para inspecionar o PDF completo e **Executar OCR no PDF armazenado** para preencher sugestões editáveis de nome, categoria e validade.
   - O envio manual de arquivo PDF no formulário continua disponível apenas para documentos recebidos pelo atendimento (lidos em memória, sem armazenamento).
6. Compare o documento com o cadastro e confira o QR pelo aplicativo oficial Vio no celular. Marque as duas confirmações **após realizar as verificações** e registre o parecer. Não há integração automática com o Vio.
7. Salve a conferência. Depois use **Aprovar** na solicitação ou **Aprovar após conferência**. Sem as duas confirmações, validade vigente, categoria compatível (`B`, `AB`, `C`, `AC`, `D`, `AD`, `E` ou `AE`) e vínculo com a versão atual do PDF, o servidor recusa a aprovação.

## Revisão, renovação e vínculo com a versão do PDF

- **Vínculo estrito por hash e versão:** Cada envio pelo aplicativo incrementa a `versao` e registra o `sha256` do PDF em `cnh_admin/{uid}.documento`. A conferência administrativa e a decisão de aprovação ficam vinculadas ao par `(pdfSha256, pdfVersao)`. Se o motorista enviar um novo PDF (ou reenviar após conferência), qualquer conferência anterior deixa de ser apta para aprovação até que o administrador analise o novo documento.
- **Revogação automática ao substituir ou revisar:** Enviar um novo PDF pelo aplicativo ou salvar uma nova conferência retira `condutorValidado` (`false`), marca `cnhRevisaoNecessaria: true`, retorna `solicitacoes_motoristas/{uid}.status` para `PENDENTE` e passa `papelAtivo` de `MOTORISTA` para `PASSAGEIRO`. Só uma nova decisão explícita de aprovação restaura o acesso.
- **Fuso horário e fim do dia (`cnhValidadeFimMs`):** Datas seguem `America/Sao_Paulo`. A validade é considerada vigente até o último milissegundo do dia informado (`23:59:59.999` no fuso de São Paulo, gravado em `cnhValidadeFimMs` e `cnhValidade`).
- **Controle automático e reconciliação:** Na inicialização e a cada hora com o servidor ligado, o processo percorre todas as conferências em páginas de 100, retira em transação o acesso de CNHs vencidas e reconcilia o diretório `data/cnh_pdfs/` removendo eventuais arquivos antigos não referenciados pelo Firestore.
- **Verificação no aplicativo Android Kotlin e nas regras do Firestore:** O aplicativo Android Kotlin e as regras do Firestore devem conferir `condutorValidado`, `status`, `cnhRevisaoNecessaria`, `cnhValidade` / `cnhValidadeFimMs` e a data atual **em cada operação de oferecer ou confirmar carona**, garantindo o bloqueio mesmo quando o servidor administrativo estiver desligado.

## Política de retenção e exclusão de documentos

- **Substituição por renovação ou reenvio:** Ao confirmar no Firestore o recebimento de uma nova versão do PDF, o arquivo criptografado anterior é removido imediatamente do disco. Se ocorrer falha transitória de limpeza, a rotina periódica de reconciliação remove o arquivo antigo.
- **Exclusão após decisão administrativa:** Uma vez concluída a conferência e registrada a decisão (`APROVADA` ou `RECUSADA`), o administrador pode excluir o PDF armazenado pelo botão **Excluir documento** no painel (`POST /api/cnh/{uid}/arquivo/excluir`), mantendo apenas os metadados auditáveis da conferência e o hash em `cnh_admin/{uid}` sem reter a cópia do documento. A exclusão por retenção após aprovação não revoga a aprovação já concedida, mas exige novo envio caso uma conferência futura seja necessária.
- **Exclusão a pedido do titular:** O próprio motorista autenticado pode excluir seu PDF armazenado a qualquer momento pelo endpoint `DELETE /api/motoristas/cnh/arquivo`.

## Dados e rotas

`cnh_admin/{uid}` guarda nome conferido, categoria, validade, confirmações, parecer, administrador, instante da conferência e metadados/vínculo do arquivo (`documento`, `pdfSha256`, `pdfVersao`). Não guarda número da CNH, CPF, PDF em texto claro nem texto bruto do OCR. `cnh_eventos` registra envios, substituições, exclusões, conferências e bloqueios automáticos dentro da transação do Firestore. A aprovação e as ações administrativas sobre o PDF também são registradas na auditoria SQLite.

### Rotas do motorista (Android Kotlin — autenticadas via Firebase ID Token + e-mail institucional verificado)

- `POST /api/motoristas/cnh`: recebe o PDF de 1 página e até 5 MB (`application/pdf` binário ou JSON `{ "pdf": "data:application/pdf;base64,...", "placa": "...", "modelo": "...", "cor": "..." }`), valida estrutura e QR Code, armazena criptografado com AES-256-GCM e registra/atualiza `solicitacoes_motoristas/{uid}` como `PENDENTE`.
- `GET /api/motoristas/cnh/status`: retorna o status da solicitação, metadados do documento enviado e situação da conferência (sem expor parecer interno administrativo).
- `GET /api/motoristas/cnh/arquivo`: devolve o PDF próprio do titular autenticado (`application/pdf`, `Cache-Control: no-store`).
- `DELETE /api/motoristas/cnh/arquivo`: exclui o PDF armazenado do próprio titular.

### Rotas administrativas (painel web — autenticadas por sessão `hh_session` e controle de origem)

- `POST /api/cnh/ocr`: `{ "imagem": "data:application/pdf;base64,..." }` para leitura em memória de PDF selecionado manualmente no navegador; sugestões editáveis, sempre `revisaoNecessaria: true`.
- `GET /api/cnh`: primeiras 100 conferências, situação calculada e indicadores de documento recebido (`documentoRecebido`, `arquivoPresente`, `pdfVersao`, `conferenciaDesatualizada`).
- `GET /api/cnh/{uid}/documento`: metadados do PDF enviado pelo motorista.
- `GET /api/cnh/{uid}/arquivo`: abre o PDF enviado pelo motorista (`Cache-Control: no-store`), com registro na auditoria.
- `POST /api/cnh/{uid}/ocr`: executa o OCR sobre o PDF armazenado do motorista e devolve sugestões acompanhadas de `pdfSha256` e `pdfVersao`.
- `POST /api/cnh/{uid}/arquivo/excluir`: `{ "motivo": "..." }` exclui o PDF armazenado conforme política de retenção e registra na auditoria.
- `POST /api/cnh/{uid}`: `{ "nome": "...", "categoria": "B", "validade": "2030-11-18", "documentoConferido": true, "qrConferidoVio": true, "observacao": "...", "pdfSha256": "...", "pdfVersao": 1 }`. Salva em transação, vincula ao PDF analisado e não concede acesso.
- `POST /api/solicitacoes-motoristas/{uid}/decisao`: exige conferência apta e vinculada ao PDF atual para aprovar.

## Fontes e testes

OCR extrai texto; QR conferido não consulta suspensão ou cassação atual. Fontes: [Tesseract.js](https://github.com/naptha/tesseract.js), [aplicativo e contratação Vio](https://centraldeajuda.serpro.gov.br/vio/comocontratar/), [leitura do QR](https://portalservicos.denatran.serpro.gov.br/static/carteiradigital/tutoriais/html/demo_25.html).

Execute `npm test` para validar OCR com PDF sintético, criptografia AES-256-GCM, upload autenticado, controle de acesso do titular e do administrador, vínculo por hash/versão, renovação, exclusão, validação de datas, vencimentos paginados e pagamentos Mercado Pago.

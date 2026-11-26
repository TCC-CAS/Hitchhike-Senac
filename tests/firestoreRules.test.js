import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rulesPath = path.resolve(__dirname, '..', 'firestore.rules');
const rulesText = fs.readFileSync(rulesPath, 'utf8');

test('firestore.rules utiliza rules_version 2 e valida e-mail institucional verificado nos 3 domínios do Senac', () => {
  assert.match(rulesText, /rules_version\s*=\s*'2';/);
  assert.match(rulesText, /function\s+emailInstitucionalVerificado\(\)/);
  assert.match(rulesText, /request\.auth\.token\.email_verified\s*==\s*true/);
  assert.ok(rulesText.includes('(senacsp\\\\.edu\\\\.br|sp\\\\.senac\\\\.br|senac\\\\.br)'));
});

test('firestore.rules bloqueia integralmente coleções administrativas, pagamentos, nomes_abreviados e avaliacoes para clientes mobile', () => {
  for (const colecao of [
    'cnh_admin/{uid}',
    'cnh_eventos/{eventoId}',
    'pagamentos/{pagamentoId}',
    'motoristas_mp/{uid}',
    'nomes_abreviados/{docId}',
    'avaliacoes/{avaliacaoId}'
  ]) {
    const escaped = colecao.replace(/[{}]/g, '\\$&');
    const regex = new RegExp(`match\\s+\\/${escaped}\\s*\\{[\\s\\S]*?allow\\s+read,\\s*write:\\s*if\\s+false;`);
    assert.match(rulesText, regex, `Coleção ${colecao} deve bloquear read e write de clientes`);
  }
});

test('firestore.rules impede autoaprovação de motorista, leitura de perfil de terceiros e manipulação de campos administrativos em usuarios/{uid}', () => {
  assert.match(rulesText, /match\s+\/usuarios\/\{uid\}/);
  assert.match(rulesText, /allow\s+read:\s*if\s+emailInstitucionalVerificado\(\)\s*&&\s*request\.auth\.uid\s*==\s*uid;/);
  assert.match(rulesText, /request\.resource\.data\.condutorValidado\s*==\s*false/);
  assert.match(rulesText, /request\.resource\.data\.papelAtivo\s*==\s*'PASSAGEIRO'/);
  assert.match(
    rulesText,
    /hasAny\(\['condutorValidado',\s*'status',\s*'cnhValidade',\s*'cnhValidadeFimMs',\s*'cnhRevisaoNecessaria',\s*'email'\]\)/
  );
  assert.match(rulesText, /resource\.data\.get\('cnhValidadeFimMs',\s*0\)\s*>=\s*request\.time\.toMillis\(\)/);
});

test('firestore.rules protege solicitacoes_motoristas (placa e veículo) contra leitura de terceiros', () => {
  assert.match(
    rulesText,
    /match\s+\/solicitacoes_motoristas\/\{uid\}\s*\{[\s\S]*?allow\s+read:\s*if\s+emailInstitucionalVerificado\(\)\s*&&\s*request\.auth\.uid\s*==\s*uid;[\s\S]*?allow\s+write:\s*if\s+false;/
  );
});

test('firestore.rules exige motoristaApto na publicação e vincula atomicamente (getAfter) o decremento de vaga ao aceite da solicitação', () => {
  assert.match(rulesText, /function\s+motoristaApto\(uid\)/);
  assert.match(rulesText, /perfil\.condutorValidado\s*==\s*true/);
  assert.match(rulesText, /perfil\.papelAtivo\s*==\s*'MOTORISTA'/);
  assert.match(rulesText, /perfil\.get\('cnhRevisaoNecessaria',\s*false\)\s*==\s*false/);
  assert.match(rulesText, /perfil\.get\('cnhValidadeFimMs',\s*0\)\s*>=\s*request\.time\.toMillis\(\)/);
  assert.match(rulesText, /request\.resource\.data\.vagasDisponiveis\s*==\s*resource\.data\.vagasDisponiveis\s*-\s*1/);
  assert.match(rulesText, /request\.resource\.data\.vagasDisponiveis\s*==\s*0\s*&&\s*request\.resource\.data\.status\s*==\s*'LOTADA'/);
  assert.match(
    rulesText,
    /getAfter\(\/databases\/\$\(database\)\/documents\/solicitacoes_caronas\/\$\(request\.resource\.data\.ultimaSolicitacaoAceitaId\)\)\.data\.status\s*==\s*'ACEITA'/
  );
});

test('firestore.rules protege solicitacoes_caronas contra acesso de terceiros, duplicidade, autosolicitação e aceite sem decremento de vaga (getAfter)', () => {
  assert.match(rulesText, /match\s+\/solicitacoes_caronas\/\{solicitacaoId\}/);
  assert.match(rulesText, /resource\.data\.passageiroUid\s*==\s*request\.auth\.uid/);
  assert.match(rulesText, /resource\.data\.motoristaUid\s*==\s*request\.auth\.uid/);
  assert.match(rulesText, /solicitacaoId\s*==\s*request\.resource\.data\.caronaId\s*\+\s*'_'\s*\+\s*request\.auth\.uid/);
  assert.match(rulesText, /!exists\(\/databases\/\$\(database\)\/documents\/solicitacoes_caronas\/\$\(solicitacaoId\)\)/);
  assert.match(rulesText, /request\.resource\.data\.motoristaUid\s*!=\s*request\.auth\.uid/);
  assert.match(rulesText, /resource\.data\.status\s*==\s*'PENDENTE'/);
  assert.match(
    rulesText,
    /getAfter\(\/databases\/\$\(database\)\/documents\/caronas\/\$\(resource\.data\.caronaId\)\)\.data\.vagasDisponiveis[\s\S]*?==\s*get\(\/databases\/\$\(database\)\/documents\/caronas\/\$\(resource\.data\.caronaId\)\)\.data\.vagasDisponiveis\s*-\s*1/
  );
  assert.match(
    rulesText,
    /getAfter\(\/databases\/\$\(database\)\/documents\/caronas\/\$\(resource\.data\.caronaId\)\)\.data\.ultimaSolicitacaoAceitaId\s*==\s*solicitacaoId/
  );
});

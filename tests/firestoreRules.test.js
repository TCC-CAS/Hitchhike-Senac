
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const rulesText = fs.readFileSync(
  new URL('../firestore.rules', import.meta.url),
  'utf8'
);

function bloco(colecao) {
  const inicio = rulesText.indexOf(`match /${colecao} {`);
  assert.ok(inicio >= 0, `Colecao ausente: ${colecao}`);

  const proximo = rulesText.indexOf('\n    match /', inicio + 1);

  return rulesText.slice(
    inicio,
    proximo === -1 ? undefined : proximo
  );
}

test('Exige autenticacao institucional verificada', () => {
  assert.match(rulesText, /rules_version\s*=\s*'2';/);
  assert.match(rulesText, /function\s+emailInstitucionalVerificado\(\)/);
  assert.match(rulesText, /request\.auth\.token\.email_verified\s*==\s*true/);
  assert.ok(rulesText.includes('(senacsp\\\\.edu\\\\.br|sp\\\\.senac\\\\.br|senac\\\\.br)'));
});

test('Protege colecoes administrativas e privadas', () => {
  for (const colecao of [
    'cnh_admin/{uid}',
    'cnh_eventos/{eventoId}',
    'pagamentos/{pagamentoId}',
    'motoristas_mp/{uid}',
    'nomes_abreviados/{docId}',
    'avaliacoes/{avaliacaoId}'
  ]) {
    assert.match(
      bloco(colecao),
      /allow\s+read,\s*write:\s*if\s+false;/
    );
  }
});

test('Protege perfil e impede autoaprovacao', () => {
  const b = bloco('usuarios/{uid}');

  assert.match(b, /allow\s+read:\s*if\s+emailInstitucionalVerificado\(\)\s*&&\s*request\.auth\.uid\s*==\s*uid;/);
  assert.match(b, /request\.resource\.data\.keys\(\)\.hasOnly/);
  assert.match(b, /request\.resource\.data\.condutorValidado\s*==\s*false/);
  assert.match(b, /request\.resource\.data\.papelAtivo\s*==\s*'PASSAGEIRO'/);
  assert.match(b, /resource\.data\.status\s*==\s*'ATIVO'/);
  assert.match(b, /affectedKeys\(\)\s*\.hasOnly\(\['papelAtivo'\]\)/);
  assert.match(b, /resource\.data\.get\('cnhValidadeFimMs',\s*0\)\s*>=\s*request\.time\.toMillis\(\)/);
});

test('Protege dados privados do veiculo', () => {
  const b = bloco('solicitacoes_motoristas/{uid}');

  assert.match(b, /allow\s+read:\s*if\s+emailInstitucionalVerificado\(\)\s*&&\s*request\.auth\.uid\s*==\s*uid;/);
  assert.match(b, /allow\s+write:\s*if\s+false;/);
});

test('Exige motorista apto e reserva atualizacoes ao backend', () => {
  const b = bloco('caronas/{caronaId}');

  assert.match(rulesText, /function\s+motoristaApto\(uid\)/);
  assert.match(rulesText, /perfil\.condutorValidado\s*==\s*true/);
  assert.match(rulesText, /perfil\.papelAtivo\s*==\s*'MOTORISTA'/);
  assert.match(rulesText, /perfil\.get\('cnhRevisaoNecessaria',\s*false\)\s*==\s*false/);
  assert.match(rulesText, /perfil\.get\('cnhValidadeFimMs',\s*0\)\s*>=\s*request\.time\.toMillis\(\)/);
  assert.match(b, /motoristaApto\(request\.auth\.uid\)/);
  assert.match(b, /allow\s+read:\s*if\s+emailInstitucionalVerificado\(\)\s*&&\s*usuarioAtivo\(request\.auth\.uid\);/);
  assert.match(b, /allow\s+update:\s*if\s+false;/);
  assert.match(b, /allow\s+delete:\s*if\s+false;/);
});

test('Protege solicitacoes e reserva aceite e recusa ao backend', () => {
  const b = bloco('solicitacoes_caronas/{solicitacaoId}');

  assert.match(b, /resource\.data\.passageiroUid\s*==\s*request\.auth\.uid/);
  assert.match(b, /resource\.data\.motoristaUid\s*==\s*request\.auth\.uid/);
  assert.match(b, /solicitacaoId\s*==\s*request\.resource\.data\.caronaId\s*\+\s*'_'\s*\+\s*request\.auth\.uid/);
  assert.match(b, /!exists\(\/databases\/\$\(database\)\/documents\/solicitacoes_caronas\/\$\(solicitacaoId\)\)/);
  assert.match(b, /request\.resource\.data\.motoristaUid\s*!=\s*request\.auth\.uid/);
  assert.match(b, /request\.resource\.data\.status\s*==\s*'PENDENTE'/);
  assert.match(b, /usuarioAtivo\(request\.auth\.uid\)/);
  assert.match(b, /allow\s+update:\s*if\s+false;/);
  assert.match(b, /allow\s+delete:\s*if\s+false;/);
});

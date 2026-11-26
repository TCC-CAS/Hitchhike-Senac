
import test, { before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} from '@firebase/rules-unit-testing';

import {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  writeBatch,
  Timestamp
} from 'firebase/firestore';

let env;

const projectId = 'demo-hitchhike';

function usuario(uid, email, verificado = true) {
  return env.authenticatedContext(uid, {
    email,
    email_verified: verificado
  }).firestore();
}

function motorista() {
  return usuario('motorista', 'motorista@senacsp.edu.br');
}

function passageiro() {
  return usuario('passageiro', 'passageiro@senacsp.edu.br');
}

function terceiro() {
  return usuario('terceiro', 'terceiro@senacsp.edu.br');
}

function perfil(uid, extra = {}) {
  return {
    nome: 'Usuario Teste',
    email: `${uid}@senacsp.edu.br`,
    curso: 'Sistemas de Informacao',
    instituicao: 'Senac',
    papelAtivo: 'PASSAGEIRO',
    status: 'ATIVO',
    condutorValidado: false,
    ...extra
  };
}

function carona(id = 'carona1', uid = 'motorista') {
  return {
    id,
    motoristaUid: uid,
    origem: 'Senac',
    destino: 'Estacao',
    dataHoraSaida: Timestamp.fromMillis(Date.now() + 3600000),
    vagasTotais: 1,
    vagasDisponiveis: 1,
    valor: 10,
    status: 'ABERTA',
    paradas: []
  };
}

function solicitacao(uid = 'passageiro') {
  return {
    id: `carona1_${uid}`,
    caronaId: 'carona1',
    motoristaUid: 'motorista',
    passageiroUid: uid,
    status: 'PENDENTE',
    criadoEm: Timestamp.now()
  };
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: '127.0.0.1',
      port: 8080,
      rules: readFileSync(
        new URL('../firestore.rules', import.meta.url),
        'utf8'
      )
    }
  });
});

after(async () => {
  if (env) await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();

  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();

    await Promise.all([
      setDoc(doc(db, 'usuarios', 'motorista'),
        perfil('motorista', {
          papelAtivo: 'MOTORISTA',
          condutorValidado: true,
          cnhRevisaoNecessaria: false,
          cnhValidadeFimMs: Date.now() + 86400000
        })),

      setDoc(doc(db, 'usuarios', 'passageiro'),
        perfil('passageiro')),

      setDoc(doc(db, 'usuarios', 'terceiro'),
        perfil('terceiro')),

      setDoc(doc(db, 'usuarios', 'vencido'),
        perfil('vencido', {
          papelAtivo: 'MOTORISTA',
          condutorValidado: true,
          cnhValidadeFimMs: Date.now() - 86400000
        })),

      setDoc(doc(db, 'caronas', 'carona1'),
        carona()),

      setDoc(doc(db, 'solicitacoes_motoristas', 'motorista'), {
        placa: 'AAA0A00',
        modelo: 'Veiculo ficticio'
      }),

      ...[
        'cnh_admin',
        'cnh_eventos',
        'pagamentos',
        'motoristas_mp',
        'nomes_abreviados',
        'avaliacoes'
      ].map(colecao =>
        setDoc(doc(db, colecao, 'teste'), {
          ficticio: true
        })
      )
    ]);
  });
});

test('Bloqueia visitante sem autenticacao', async () => {
  const db = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db, 'caronas', 'carona1')));
});

test('Bloqueia email externo', async () => {
  const db = usuario('externo', 'teste@gmail.com');
  await assertFails(getDoc(doc(db, 'caronas', 'carona1')));
});

test('Bloqueia email institucional nao verificado', async () => {
  const db = usuario('teste', 'teste@senacsp.edu.br', false);
  await assertFails(getDoc(doc(db, 'caronas', 'carona1')));
});

test('Permite os tres dominios institucionais configurados', async () => {
  for (const dominio of [
    'senacsp.edu.br',
    'sp.senac.br',
    'senac.br'
  ]) {
    const db = usuario('teste', `teste@${dominio}`);
    await assertSucceeds(getDoc(doc(db, 'caronas', 'carona1')));
  }
});

test('Permite leitura do proprio perfil', async () => {
  await assertSucceeds(
    getDoc(doc(passageiro(), 'usuarios', 'passageiro'))
  );
});

test('Bloqueia leitura do perfil de terceiros', async () => {
  await assertFails(
    getDoc(doc(passageiro(), 'usuarios', 'motorista'))
  );
});

test('Permite cadastro legitimo de passageiro', async () => {
  const db = usuario('novo', 'novo@senacsp.edu.br');
  await assertSucceeds(
    setDoc(doc(db, 'usuarios', 'novo'), perfil('novo'))
  );
});

test('Bloqueia cadastro com autoaprovacao', async () => {
  const db = usuario('novo', 'novo@senacsp.edu.br');
  await assertFails(
    setDoc(doc(db, 'usuarios', 'novo'), perfil('novo', {
      condutorValidado: true,
      papelAtivo: 'MOTORISTA'
    }))
  );
});

test('Bloqueia autoaprovacao posterior', async () => {
  await assertFails(
    updateDoc(doc(passageiro(), 'usuarios', 'passageiro'), {
      condutorValidado: true
    })
  );
});

test('Bloqueia alteracao da validade da CNH', async () => {
  await assertFails(
    updateDoc(doc(motorista(), 'usuarios', 'motorista'), {
      cnhValidadeFimMs: Date.now() + 999999999
    })
  );
});

test('Bloqueia passageiro tentando virar motorista', async () => {
  await assertFails(
    updateDoc(doc(passageiro(), 'usuarios', 'passageiro'), {
      papelAtivo: 'MOTORISTA'
    })
  );
});

test('Protege dados privados do veiculo', async () => {
  await assertFails(
    getDoc(doc(passageiro(), 'solicitacoes_motoristas', 'motorista'))
  );

  await assertSucceeds(
    getDoc(doc(motorista(), 'solicitacoes_motoristas', 'motorista'))
  );
});

test('Permite motorista apto publicar carona', async () => {
  await assertSucceeds(
    setDoc(doc(motorista(), 'caronas', 'nova'), carona('nova'))
  );
});

test('Bloqueia publicacao com CNH vencida', async () => {
  const db = usuario('vencido', 'vencido@senacsp.edu.br');

  await assertFails(
    setDoc(doc(db, 'caronas', 'nova'), carona('nova', 'vencido'))
  );
});

test('Bloqueia passageiro publicando carona', async () => {
  await assertFails(
    setDoc(doc(passageiro(), 'caronas', 'nova'),
      carona('nova', 'passageiro'))
  );
});

test('Bloqueia dados pessoais extras na carona', async () => {
  await assertFails(
    setDoc(doc(motorista(), 'caronas', 'nova'), {
      ...carona('nova'),
      nomeCompleto: 'Nome Ficticio',
      placa: 'AAA0A00'
    })
  );
});

test('Permite solicitacao legitima', async () => {
  await assertSucceeds(
    setDoc(doc(passageiro(), 'solicitacoes_caronas',
      'carona1_passageiro'), solicitacao())
  );
});

test('Bloqueia solicitacao duplicada', async () => {
  const ref = doc(passageiro(), 'solicitacoes_caronas',
    'carona1_passageiro');

  await assertSucceeds(setDoc(ref, solicitacao()));
  await assertFails(setDoc(ref, solicitacao()));
});

test('Bloqueia autosolicitacao do motorista', async () => {
  await assertFails(
    setDoc(doc(motorista(), 'solicitacoes_caronas',
      'carona1_motorista'), solicitacao('motorista'))
  );
});

test('Bloqueia leitura da solicitacao por terceiro', async () => {
  await assertSucceeds(
    setDoc(doc(passageiro(), 'solicitacoes_caronas',
      'carona1_passageiro'), solicitacao())
  );

  await assertFails(
    getDoc(doc(terceiro(), 'solicitacoes_caronas',
      'carona1_passageiro'))
  );
});

test('Bloqueia decremento isolado de vaga', async () => {
  await assertFails(
    updateDoc(doc(motorista(), 'caronas', 'carona1'), {
      vagasDisponiveis: 0,
      status: 'LOTADA',
      ultimaSolicitacaoAceitaId: 'carona1_passageiro'
    })
  );
});

test('Bloqueia aceite sem decremento de vaga', async () => {
  await assertSucceeds(
    setDoc(doc(passageiro(), 'solicitacoes_caronas',
      'carona1_passageiro'), solicitacao())
  );

  await assertFails(
    updateDoc(doc(motorista(), 'solicitacoes_caronas',
      'carona1_passageiro'), {
      status: 'ACEITA'
    })
  );
});

test('Verifica aceite atomico permitido pelas regras atuais', async () => {
  await assertSucceeds(
    setDoc(doc(passageiro(), 'solicitacoes_caronas',
      'carona1_passageiro'), solicitacao())
  );

  const db = motorista();
  const batch = writeBatch(db);

  batch.update(doc(db, 'caronas', 'carona1'), {
    vagasDisponiveis: 0,
    status: 'LOTADA',
    ultimaSolicitacaoAceitaId: 'carona1_passageiro'
  });

  batch.update(doc(db, 'solicitacoes_caronas',
    'carona1_passageiro'), {
    status: 'ACEITA'
  });

  await assertSucceeds(batch.commit());
});

for (const colecao of [
  'cnh_admin',
  'cnh_eventos',
  'pagamentos',
  'motoristas_mp',
  'nomes_abreviados',
  'avaliacoes'
]) {
  test(`Bloqueia leitura e escrita em ${colecao}`, async () => {
    const ref = doc(motorista(), colecao, 'teste');

    await assertFails(getDoc(ref));
    await assertFails(setDoc(ref, { adulterado: true }));
  });
}

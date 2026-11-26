// Firestore em memória apenas para testes (dados fictícios). Rejeita undefined, como o Firestore real.
function semUndefined(valor, caminho = 'doc') {
  if (valor === undefined) throw new Error(`Valor undefined não permitido em ${caminho}`);
  if (valor && typeof valor === 'object' && !(valor instanceof Date)) for (const [k, v] of Object.entries(valor)) semUndefined(v, `${caminho}.${k}`);
}

export function criarFirestoreFalso(inicial = []) {
  const docs = new Map(inicial);
  let cadeia = Promise.resolve();
  let sequencia = 0;
  const api = {
    docs,
    falhaTransacao: 0, // número de próximas transações que falham antes de gravar
    commitDepoisErro: false, // grava e depois devolve erro (falha ambígua)
    falhaLeitura: false,
    collection(nome) {
      const filtros = [];
      const consulta = {
        limite: Infinity,
        cursor: '',
        doc(id) {
          const identificador = id || `auto${++sequencia}`;
          const path = `${nome}/${identificador}`;
          return {
            path,
            id: identificador,
            async get() {
              if (api.falhaLeitura) throw new Error('falha de leitura simulada');
              const valor = docs.get(path);
              return { id: identificador, ref: this, exists: valor !== undefined, data: () => (valor === undefined ? undefined : structuredClone(valor)) };
            },
            async set(v) {
              semUndefined(v, path);
              docs.set(path, structuredClone(v));
            },
            async update(v) {
              const base = docs.get(path);
              if (base === undefined) throw new Error('update em documento inexistente');
              semUndefined(v, path);
              docs.set(path, { ...structuredClone(base), ...structuredClone(v) });
            }
          };
        },
        where(campo, op, valor) { filtros.push([campo, op, valor]); return consulta; },
        orderBy() { return consulta; },
        limit(n) { consulta.limite = n; return consulta; },
        startAfter(doc) { consulta.cursor = doc.id; return consulta; },
        async get() {
          if (api.falhaLeitura) throw new Error('falha de leitura simulada');
          const atende = v => filtros.every(([campo, op, valor]) => {
            if (op === '==') return v?.[campo] === valor;
            if (op === 'in' && Array.isArray(valor)) return valor.includes(v?.[campo]);
            return true;
          });
          const lista = [...docs]
            .filter(([p, v]) => p.startsWith(`${nome}/`) && p.split('/')[1] > consulta.cursor && atende(v))
            .sort(([a], [b]) => a.localeCompare(b))
            .slice(0, consulta.limite)
            .map(([p, v]) => {
              const id = p.split('/')[1];
              return { id, ref: { path: p, id }, exists: true, data: () => structuredClone(v) };
            });
          return { docs: lista };
        }
      };
      return consulta;
    },
    runTransaction(fn) {
      const executar = async () => {
        if (api.falhaTransacao > 0) { api.falhaTransacao--; throw new Error('falha de transação simulada'); }
        const staged = new Map();
        const atual = path => (staged.has(path) ? staged.get(path) : docs.get(path));
        const tx = {
          async get(refOrQuery) {
            if (typeof refOrQuery?.get === 'function' && !refOrQuery.path) {
              return refOrQuery.get();
            }
            const v = atual(refOrQuery.path);
            return { id: refOrQuery.id, ref: refOrQuery, exists: v !== undefined, data: () => (v === undefined ? undefined : structuredClone(v)) };
          },
          set(ref, v) { semUndefined(v, ref.path); staged.set(ref.path, structuredClone(v)); },
          update(ref, v) {
            const base = atual(ref.path);
            if (base === undefined) throw new Error('update em documento inexistente');
            semUndefined(v, ref.path);
            staged.set(ref.path, { ...structuredClone(base), ...structuredClone(v) });
          }
        };
        const resultado = await fn(tx);
        for (const [k, v] of staged) docs.set(k, v);
        if (api.commitDepoisErro) { api.commitDepoisErro = false; throw new Error('erro após o commit simulado'); }
        return resultado;
      };
      const proxima = cadeia.then(executar, executar);
      cadeia = proxima.then(() => {}, () => {});
      return proxima;
    }
  };
  return api;
}

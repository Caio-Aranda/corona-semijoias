// Funções puras do "npm run setup" (sem acesso a arquivos nem ao terminal), para poderem ser testadas.
import { randomBytes } from 'node:crypto';

/** "26.13.0" ou "v26.13.0" -> [26, 13, 0]. Devolve null se não for um número de versão. */
export function lerVersao(texto) {
  const m = /(\d+)\.(\d+)(?:\.(\d+))?/.exec(String(texto ?? ''));
  return m ? [Number(m[1]), Number(m[2]), Number(m[3] ?? 0)] : null;
}

/** Negativo se a < b, zero se iguais, positivo se a > b. */
export function compararVersoes(a, b) {
  for (let i = 0; i < 3; i += 1) {
    const d = (a[i] ?? 0) - (b[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/** Nomes das variáveis de um arquivo .env (linhas "NOME=valor", sem comentários). */
export function nomesDasVariaveis(conteudo) {
  const nomes = [];
  for (const linha of conteudo.split(/\r?\n/)) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=/.exec(linha);
    if (m) nomes.push(m[1]);
  }
  return nomes;
}

/** Variáveis que o exemplo tem e o .env não tem (só os nomes, nunca os valores). */
export function variaveisFaltando(exemplo, atual) {
  const existentes = new Set(nomesDasVariaveis(atual));
  return nomesDasVariaveis(exemplo).filter((n) => !existentes.has(n));
}

const SEGREDOS_BASE64 = ['JWT_CHAVE_ATUAL', 'SESSAO_SEGREDO_RENOVACAO'];
const SENHAS = [
  'POSTGRES_PASSWORD',
  'DB_MIGRACAO_PASSWORD',
  'DB_APP_PASSWORD',
  'ADMIN_SENHA',
  'DEMO_VENDEDOR_SENHA',
];
const USUARIOS = {
  POSTGRES_USER: 'corona_admin',
  DB_MIGRACAO_USER: 'corona_migracao',
  DB_APP_USER: 'corona_app',
  ADMIN_NOME: 'Administradora de Teste',
  ADMIN_LOGIN: 'admin.teste',
};

function senhaAleatoria() {
  // Letras e números (com letra e número garantidos); sem $, barra ou aspas, que o Docker Compose não aceita.
  return `Cs${randomBytes(12).toString('hex')}7`;
}

/**
 * Cria o conteúdo de um .env novo a partir do exemplo, trocando os "troque-isto" por valores de
 * desenvolvimento aleatórios. Não imprime nada. `sorteio` existe para os testes.
 */
export function criarEnvNovo(
  exemplo,
  sorteio = { base64: () => randomBytes(32).toString('base64'), senha: senhaAleatoria },
) {
  return exemplo
    .split(/\r?\n/)
    .map((linha) => {
      const m = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(linha);
      if (!m) return linha;
      const [, nome, valor] = m;
      if (SEGREDOS_BASE64.includes(nome)) return `${nome}=${sorteio.base64()}`;
      if (SENHAS.includes(nome)) return `${nome}=${sorteio.senha()}`;
      if (nome in USUARIOS) return `${nome}=${USUARIOS[nome]}`;
      return `${nome}=${valor}`;
    })
    .join('\n');
}

/** Lê a saída de "java -version" e devolve o texto da versão (ex.: "21.0.12") ou null. */
export function versaoDoJava(saida) {
  const m = /version "([^"]+)"/.exec(saida);
  return m ? m[1] : null;
}

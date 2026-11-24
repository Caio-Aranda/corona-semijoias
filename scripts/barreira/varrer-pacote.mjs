// Camada 2 (e 3) da barreira do ADR-0003: procura marcadores proibidos num pacote já construído.
//
// Uso:  npm run barreira:varrer                      (acha o pacote do Corona Vendas sozinho)
//       node scripts/barreira/varrer-pacote.mjs <app.asar | pasta> [mais caminhos...]
// Sem argumentos, procura apps/seller-app/release/*-unpacked/resources/app.asar (Windows ou Linux;
// com os dois, o mais novo) e varre ele mais as pastas compartilhadas, como o CI faz.
// Um arquivo .asar é desempacotado numa pasta temporária e varrido; uma pasta é varrida direto.
// Sai com código 1 se achar qualquer marcador da lista (scripts/marcadores-proibidos.json),
// seja no conteúdo de um arquivo, seja no nome ou no caminho dele.
import { extractAll } from '@electron/asar';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { lerMarcadores } from './marcadores.mjs';

const PASTAS_IGNORADAS = new Set(['node_modules', '.git']);

/** Pastas de código que o Corona Vendas usa e que o CI varre junto com o pacote. */
export const PASTAS_COMPARTILHADAS = ['packages/shared', 'packages/ui', 'packages/conexao-segura'];

/** Raiz do repositório (a variável só existe para os testes). */
const RAIZ_DO_REPOSITORIO =
  process.env.CORONA_RAIZ_DO_REPOSITORIO ??
  join(dirname(fileURLToPath(import.meta.url)), '..', '..');

/**
 * Procura o app.asar do Corona Vendas em apps/seller-app/release/*-unpacked/resources.
 * Com mais de um (Windows e Linux), fica com o mais novo.
 * @returns {string | null}
 */
export function acharPacoteDoVendas(raiz = RAIZ_DO_REPOSITORIO) {
  const release = join(raiz, 'apps', 'seller-app', 'release');
  if (!existsSync(release)) return null;
  const achados = readdirSync(release, { withFileTypes: true })
    .filter((e) => e.isDirectory() && e.name.endsWith('-unpacked'))
    .map((e) => join(release, e.name, 'resources', 'app.asar'))
    .filter((arquivo) => existsSync(arquivo))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs);
  return achados[0] ?? null;
}

/**
 * Os caminhos dados ou, sem nenhum, o pacote do Corona Vendas mais as pastas compartilhadas.
 * @param {string[]} argumentos
 * @returns {string[]}
 */
export function caminhosParaVarrer(argumentos, raiz = RAIZ_DO_REPOSITORIO) {
  if (argumentos.length > 0) return argumentos;
  const pacote = acharPacoteDoVendas(raiz);
  if (!pacote) {
    throw new Error(
      'Não achei o pacote do Corona Vendas (apps/seller-app/release/*-unpacked/resources/app.asar). ' +
        'Rode antes "npm run seller:empacotar" e depois "npm run barreira:varrer" de novo.',
    );
  }
  return [pacote, ...PASTAS_COMPARTILHADAS.map((p) => join(raiz, p))];
}

function listarArquivos(pasta) {
  return readdirSync(pasta, { withFileTypes: true }).flatMap((entrada) => {
    if (PASTAS_IGNORADAS.has(entrada.name)) return [];
    const caminho = join(pasta, entrada.name);
    return entrada.isDirectory() ? listarArquivos(caminho) : [caminho];
  });
}

/**
 * Varre uma pasta (ou um arquivo só) atrás dos marcadores.
 * @param {string} caminho pasta ou arquivo
 * @param {string[]} marcadores textos proibidos
 * @param {{ ignorar?: (arquivo: string) => boolean }} [opcoes]
 * @returns {{ arquivo: string, marcador: string, onde: 'conteudo' | 'nome' }[]}
 */
export function varrerPasta(caminho, marcadores, opcoes = {}) {
  const raiz = statSync(caminho).isDirectory() ? caminho : join(caminho, '..');
  const arquivos = statSync(caminho).isDirectory() ? listarArquivos(caminho) : [caminho];
  const achados = [];
  for (const arquivo of arquivos) {
    if (opcoes.ignorar?.(arquivo)) continue;
    const nome = relative(raiz, arquivo).replaceAll('\\', '/');
    const conteudo = readFileSync(arquivo);
    for (const marcador of marcadores) {
      if (nome.includes(marcador)) achados.push({ arquivo: nome, marcador, onde: 'nome' });
      // Buffer.includes procura os bytes do texto: serve para código, mapas e arquivos binários.
      if (conteudo.includes(marcador)) achados.push({ arquivo: nome, marcador, onde: 'conteudo' });
    }
  }
  return achados;
}

/** Desempacota o .asar numa pasta temporária, varre e apaga a pasta. */
export function varrerAsar(arquivoAsar, marcadores) {
  const destino = mkdtempSync(join(tmpdir(), 'corona-asar-'));
  try {
    extractAll(arquivoAsar, destino);
    return varrerPasta(destino, marcadores);
  } finally {
    rmSync(destino, { recursive: true, force: true });
  }
}

/** Varre cada caminho dado (.asar ou pasta). */
export function varrer(caminhos, marcadores) {
  return caminhos.flatMap((caminho) =>
    caminho.endsWith('.asar')
      ? varrerAsar(caminho, marcadores).map((a) => ({ ...a, arquivo: `${caminho}!/${a.arquivo}` }))
      : varrerPasta(caminho, marcadores).map((a) => ({ ...a, arquivo: join(caminho, a.arquivo) })),
  );
}

function principal() {
  let caminhos;
  try {
    caminhos = caminhosParaVarrer(process.argv.slice(2));
  } catch (erro) {
    console.error(erro.message);
    process.exit(1);
  }
  const { marcadores } = lerMarcadores();
  const achados = varrer(caminhos, marcadores);
  if (achados.length > 0) {
    for (const a of achados) {
      console.error(
        `PROIBIDO: "${a.marcador}" no ${a.onde === 'nome' ? 'nome de' : 'conteúdo de'} ${a.arquivo}`,
      );
    }
    console.error(
      `::error::A varredura achou ${achados.length} marcador(es) proibido(s) (ADR-0003). Código da dona não pode entrar no Corona Vendas.`,
    );
    process.exit(1);
  }
  console.log(
    `Varredura limpa: nenhum dos ${marcadores.length} marcadores em ${caminhos.join(', ')}.`,
  );
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) principal();

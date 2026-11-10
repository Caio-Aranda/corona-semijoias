// Camada 2 (e 3) da barreira do ADR-0003: procura marcadores proibidos num pacote já construído.
//
// Uso:  node scripts/barreira/varrer-pacote.mjs <app.asar | pasta> [mais caminhos...]
// Um arquivo .asar é desempacotado numa pasta temporária e varrido; uma pasta é varrida direto.
// Sai com código 1 se achar qualquer marcador da lista (scripts/marcadores-proibidos.json),
// seja no conteúdo de um arquivo, seja no nome ou no caminho dele.
import asar from '@electron/asar';
import { mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { lerMarcadores } from './marcadores.mjs';

const PASTAS_IGNORADAS = new Set(['node_modules', '.git']);

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
    asar.extractAll(arquivoAsar, destino);
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
  const caminhos = process.argv.slice(2);
  if (caminhos.length === 0) {
    console.error('Uso: node scripts/barreira/varrer-pacote.mjs <app.asar | pasta> [...]');
    process.exit(2);
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

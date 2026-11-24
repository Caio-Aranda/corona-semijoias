import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

/**
 * Camada 2 da barreira (ADR-0003): a varredura do pacote falha quando um marcador proibido é plantado.
 * O script é do monorepo (fora da pasta do app); carregado por caminho calculado.
 */
const raizDoRepositorio = join(import.meta.dirname, '..', '..', '..', '..');

interface Achado {
  arquivo: string;
  marcador: string;
  onde: 'conteudo' | 'nome';
}
interface Varredura {
  varrer(caminhos: string[], marcadores: string[]): Achado[];
}
interface Marcadores {
  lerMarcadores(): { marcadores: string[]; importacoesProibidas: string[] };
}
interface Asar {
  createPackage(origem: string, destino: string): Promise<void>;
}

async function carregar<T>(...partes: string[]): Promise<T> {
  const url = pathToFileURL(join(raizDoRepositorio, ...partes));
  return (await import(/* @vite-ignore */ url.href)) as T;
}

let pasta: string;
beforeEach(() => {
  pasta = mkdtempSync(join(tmpdir(), 'varredura-teste-'));
});
afterEach(() => {
  rmSync(pasta, { recursive: true, force: true });
});

function montarPacote(arquivos: Record<string, string>): string {
  const origem = join(pasta, 'conteudo');
  for (const [nome, texto] of Object.entries(arquivos)) {
    const destino = join(origem, nome);
    mkdirSync(join(destino, '..'), { recursive: true });
    writeFileSync(destino, texto);
  }
  return origem;
}

describe('varredura do pacote do Corona Vendas (ADR-0003, camada 2)', () => {
  it('a lista de marcadores tem os seis do ADR-0003 e os nomes de custo (M5), em um único arquivo', async () => {
    const { lerMarcadores } = await carregar<Marcadores>('scripts', 'barreira', 'marcadores.mjs');
    expect(lerMarcadores().marcadores).toEqual([
      '/api/dona',
      'preco_custo',
      'precoCusto',
      'custoUnitario',
      'custoAtual',
      'custoAnterior',
      'valorACusto',
      'historicoDeCusto',
      'produtos_custos',
      'api-client-dona',
      'owner-app',
      'Corona Gestão',
    ]);
  });

  it('pacote limpo passa', async () => {
    const { varrer } = await carregar<Varredura>('scripts', 'barreira', 'varrer-pacote.mjs');
    const { lerMarcadores } = await carregar<Marcadores>('scripts', 'barreira', 'marcadores.mjs');
    const origem = montarPacote({
      'package.json': '{"name":"@corona/seller-app"}',
      'out/main/index.cjs': 'console.log("/api/vendedor/produtos");',
    });
    expect(varrer([origem], lerMarcadores().marcadores)).toEqual([]);
  });

  it('falha para cada marcador plantado no conteúdo de um arquivo', async () => {
    const { varrer } = await carregar<Varredura>('scripts', 'barreira', 'varrer-pacote.mjs');
    const { lerMarcadores } = await carregar<Marcadores>('scripts', 'barreira', 'marcadores.mjs');
    const { marcadores } = lerMarcadores();
    for (const marcador of marcadores) {
      const origem = montarPacote({ 'out/main/index.cjs': `fetch("x${marcador}y");` });
      const achados = varrer([origem], marcadores);
      expect(achados.map((a) => a.marcador)).toContain(marcador);
      rmSync(origem, { recursive: true, force: true });
    }
  });

  it('falha também quando o marcador está no nome do arquivo', async () => {
    const { varrer } = await carregar<Varredura>('scripts', 'barreira', 'varrer-pacote.mjs');
    const origem = montarPacote({ 'out/owner-app-resto.js': 'ok' });
    expect(varrer([origem], ['owner-app'])).toMatchObject([
      { marcador: 'owner-app', onde: 'nome' },
    ]);
  });

  it('desempacota um app.asar de verdade e falha se houver marcador dentro dele', async () => {
    const { varrer } = await carregar<Varredura>('scripts', 'barreira', 'varrer-pacote.mjs');
    const { lerMarcadores } = await carregar<Marcadores>('scripts', 'barreira', 'marcadores.mjs');
    // O @electron/asar 4 exporta as funções direto; o 3 as punha em `default`. Vale qualquer um dos dois.
    const modulo = await carregar<Asar & { default?: Asar }>(
      'node_modules',
      '@electron',
      'asar',
      'lib',
      'asar.js',
    );
    const asar: Asar = modulo.default ?? modulo;
    const { marcadores } = lerMarcadores();

    const limpo = montarPacote({
      'package.json': '{}',
      'out/renderer/index.html': '<html></html>',
    });
    await asar.createPackage(limpo, join(pasta, 'limpo.asar'));
    expect(varrer([join(pasta, 'limpo.asar')], marcadores)).toEqual([]);

    const sujo = join(pasta, 'sujo');
    mkdirSync(join(sujo, 'out'), { recursive: true });
    writeFileSync(join(sujo, 'out', 'index.cjs'), 'const rota = "/api/dona/produtos";');
    await asar.createPackage(sujo, join(pasta, 'sujo.asar'));
    const achados = varrer([join(pasta, 'sujo.asar')], marcadores);
    expect(achados).toHaveLength(1);
    expect(achados[0]).toMatchObject({ marcador: '/api/dona', onde: 'conteudo' });
  });
});

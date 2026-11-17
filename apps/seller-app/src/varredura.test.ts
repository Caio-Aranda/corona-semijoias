import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const raizDoApp = join(dirname(fileURLToPath(import.meta.url)), '..');
const raizDoRepositorio = join(raizDoApp, '..', '..');

function arquivos(pasta: string): string[] {
  return readdirSync(pasta).flatMap((nome) => {
    const caminho = join(pasta, nome);
    if (['node_modules', 'out', 'release'].includes(nome)) return [];
    return statSync(caminho).isDirectory() ? arquivos(caminho) : [caminho];
  });
}

const todos = arquivos(raizDoApp).filter((a) => /\.(ts|tsx|json|css|html|mjs|yml)$/.test(a));
/** Os testes podem citar os marcadores (é o que eles provam); o que vai para o pacote, não. */
const doPacote = todos.filter((a) => !/\.test\.tsx?$/.test(a));
const nome = (a: string) => relative(raizDoApp, a).replaceAll('\\', '/');

const { marcadores } = JSON.parse(
  readFileSync(join(raizDoRepositorio, 'scripts', 'marcadores-proibidos.json'), 'utf8'),
) as { marcadores: string[] };

describe('o Corona Vendas não usa nada do Corona Gestão (ADR-0003)', () => {
  it('há arquivos para conferir', () => {
    expect(doPacote.length).toBeGreaterThan(20);
  });

  it('a lista cobre todos os nomes de custo da API da dona (auditoria M5)', () => {
    for (const nomeDeCusto of [
      'preco_custo',
      'precoCusto',
      'custoUnitario',
      'custoAtual',
      'custoAnterior',
      'valorACusto',
      'historicoDeCusto',
      'produtos_custos',
    ]) {
      expect(marcadores).toContain(nomeDeCusto);
    }
  });

  it.each(marcadores)('nenhum arquivo do app (fora dos testes) contém "%s"', (marcador) => {
    const achados = doPacote.filter((a) => readFileSync(a, 'utf8').includes(marcador)).map(nome);
    expect(achados).toEqual([]);
  });

  it('o package.json só depende do cliente do vendedor entre os clientes da API', () => {
    const pacote = JSON.parse(readFileSync(join(raizDoApp, 'package.json'), 'utf8')) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const todas = Object.keys({ ...pacote.dependencies, ...pacote.devDependencies });
    expect(todas.filter((n) => n.startsWith('@corona/api-client-'))).toEqual([
      '@corona/api-client-vendedor',
    ]);
    expect(todas.filter((n) => n.includes('owner'))).toEqual([]);
  });

  it('o build do pacote não gera mapas de código (source maps)', () => {
    const config = readFileSync(join(raizDoApp, 'electron.vite.config.ts'), 'utf8');
    expect(config.match(/sourcemap: false/g)).toHaveLength(3);
    expect(config).not.toMatch(/sourcemap: (true|'inline'|'hidden')/);
    expect(readFileSync(join(raizDoApp, 'electron-builder.yml'), 'utf8')).toContain("'!**/*.map'");
  });
});

describe('tokens e aparelho fora da tela', () => {
  const daTela = todos.filter((a) => nome(a).startsWith('src/renderer/'));

  it('a tela não usa localStorage, sessionStorage, cookies nem IndexedDB', () => {
    const proibidos = /localStorage|sessionStorage|document\.cookie|indexedDB/;
    const achados = daTela
      .filter((a) => !a.endsWith('.test.tsx'))
      .filter((a) => proibidos.test(readFileSync(a, 'utf8')))
      .map(nome);
    expect(achados).toEqual([]);
  });

  it('a tela não importa o cliente da API nem o processo principal', () => {
    const achados = daTela
      .filter((a) => /\.(ts|tsx)$/.test(a) && !a.endsWith('.test.tsx'))
      .filter((a) =>
        /from ['"](@corona\/api-client|electron|\.\.\/main)/.test(readFileSync(a, 'utf8')),
      )
      .map(nome);
    expect(achados).toEqual([]);
  });

  it('a tela e a ponte nunca falam em dispositivoId (o identificador fica no processo principal)', () => {
    const achados = todos
      .filter((a) => /^src\/(renderer|preload|shared)\//.test(nome(a)))
      .filter((a) => /dispositivo/i.test(readFileSync(a, 'utf8')))
      .map(nome);
    expect(achados).toEqual([]);
  });
});

describe('janela do Electron', () => {
  const principal = readFileSync(join(raizDoApp, 'src/main/index.ts'), 'utf8');

  it('liga o isolamento, desliga a integração com o Node e liga o sandbox', () => {
    expect(principal).toContain('contextIsolation: true');
    expect(principal).toContain('nodeIntegration: false');
    expect(principal).toContain('sandbox: true');
    expect(principal).toContain('webviewTag: false');
    expect(principal).not.toMatch(/enableRemoteModule|@electron\/remote/);
  });

  it('bloqueia janelas novas e navegação para fora', () => {
    expect(principal).toContain("setWindowOpenHandler(() => ({ action: 'deny' }))");
    expect(principal).toContain("'will-navigate'");
    expect(principal).toContain('setPermissionRequestHandler');
  });
});

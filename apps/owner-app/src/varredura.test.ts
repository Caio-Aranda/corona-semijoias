import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const raizDoApp = join(dirname(fileURLToPath(import.meta.url)), '..');

function arquivos(pasta: string): string[] {
  return readdirSync(pasta).flatMap((nome) => {
    const caminho = join(pasta, nome);
    if (nome === 'node_modules' || nome === 'out') return [];
    return statSync(caminho).isDirectory() ? arquivos(caminho) : [caminho];
  });
}

const doApp = arquivos(raizDoApp).filter(
  (a) => !a.endsWith('varredura.test.ts') && /\.(ts|tsx|json|css|html|mjs)$/.test(a),
);

describe('o Corona Gestão não usa nada do Corona Vendas (ADR-0003)', () => {
  it('há arquivos para conferir', () => {
    expect(doApp.length).toBeGreaterThan(10);
  });

  it('nenhum arquivo cita seller-app, api-client-vendedor ou o Corona Vendas', () => {
    const proibidos = /seller-app|api-client-vendedor|Corona Vendas/;
    const achados = doApp
      .filter((a) => !a.endsWith('package-lock.json'))
      .filter((a) => proibidos.test(readFileSync(a, 'utf8')))
      .map((a) => relative(raizDoApp, a));
    expect(achados).toEqual([]);
  });

  it('o package.json só depende do cliente da dona entre os clientes da API', () => {
    const pacote = JSON.parse(readFileSync(join(raizDoApp, 'package.json'), 'utf8')) as {
      dependencies?: Record<string, string>;
      devDependencies?: Record<string, string>;
    };
    const todas = Object.keys({ ...pacote.dependencies, ...pacote.devDependencies });
    expect(todas.filter((n) => n.startsWith('@corona/api-client-'))).toEqual([
      '@corona/api-client-dona',
    ]);
    expect(todas).not.toContain('@corona/seller-app');
  });
});

describe('tokens fora da tela', () => {
  const daTela = doApp.filter((a) =>
    relative(raizDoApp, a).replaceAll('\\', '/').startsWith('src/renderer/'),
  );

  it('a tela não usa localStorage, sessionStorage, cookies nem IndexedDB', () => {
    const proibidos = /localStorage|sessionStorage|document\.cookie|indexedDB/;
    const achados = daTela
      .filter((a) => !a.endsWith('.test.tsx'))
      .filter((a) => proibidos.test(readFileSync(a, 'utf8')))
      .map((a) => relative(raizDoApp, a));
    expect(achados).toEqual([]);
  });

  it('a tela não importa o cliente da API nem o processo principal', () => {
    const achados = daTela
      .filter((a) => /\.(ts|tsx)$/.test(a) && !a.endsWith('.test.tsx'))
      .filter((a) =>
        /from ['"](@corona\/api-client|electron|\.\.\/main)/.test(readFileSync(a, 'utf8')),
      )
      .map((a) => relative(raizDoApp, a));
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

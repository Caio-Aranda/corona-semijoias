import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import tseslint from 'typescript-eslint';
import { ESLint, type Linter } from 'eslint';
import { describe, expect, it } from 'vitest';

/**
 * Camada 1 da barreira (ADR-0003): a regra de importações restritas pega de verdade.
 * Cada exemplo é um arquivo "de mentira" que viola a regra; o ESLint tem que reprovar.
 *
 * A configuração mora fora da pasta do app (é do monorepo), então ela é carregada por um caminho
 * calculado e não por `import` com `../`, que a própria regra proibiria.
 */
const raizDoRepositorio = join(import.meta.dirname, '..', '..', '..', '..');

async function carregarBarreira(): Promise<Linter.Config[]> {
  const url = pathToFileURL(
    join(raizDoRepositorio, 'scripts', 'barreira', 'configuracao-eslint.mjs'),
  );
  const modulo = (await import(/* @vite-ignore */ url.href)) as {
    configuracaoDaBarreira: () => Linter.Config[];
  };
  return modulo.configuracaoDaBarreira();
}

async function verificar(arquivo: string, codigo: string): Promise<string[]> {
  const eslint = new ESLint({
    cwd: raizDoRepositorio,
    overrideConfigFile: true,
    overrideConfig: [
      { files: ['**/*.{ts,tsx}'], languageOptions: { parser: tseslint.parser } },
      ...(await carregarBarreira()),
    ],
  });
  const [resultado] = await eslint.lintText(codigo, { filePath: join(raizDoRepositorio, arquivo) });
  return (resultado?.messages ?? []).map((m) => `${m.ruleId ?? 'erro'}: ${m.message}`);
}

const NO_VENDAS = 'apps/seller-app/src/main/exemplo.ts';

describe('lint do Corona Vendas: importações proibidas (ADR-0003, camada 1)', () => {
  it.each([
    ['o cliente da API da dona', "import { entrar } from '@corona/api-client-dona';"],
    ['um caminho do cliente da dona', "import { x } from '@corona/api-client-dona/src/cliente';"],
    ['o cliente da dona por caminho de pasta', "import { x } from 'packages/api-client-dona/src';"],
    ['o app da dona', "import { Tela } from '@corona/owner-app';"],
    ['um caminho do app da dona', "export * from '../../../owner-app/src/renderer/App';"],
    ['o app da dona por import dinâmico', "const m = await import('../../../owner-app/src/main');"],
    ['o app da dona por require', "const m = require('../../../owner-app/src/main');"],
  ])('reprova importar %s', async (_nome, codigo) => {
    const mensagens = await verificar(NO_VENDAS, codigo);
    expect(mensagens.length).toBeGreaterThan(0);
  });

  it('a mensagem do cliente da dona é em português e cita a decisão', async () => {
    const [mensagem] = await verificar(NO_VENDAS, "import { x } from '@corona/api-client-dona';");
    expect(mensagem).toContain('O Corona Vendas não pode usar código da dona');
    expect(mensagem).toContain('ADR-0003');
  });

  it('reprova qualquer caminho que saia da pasta do app por "../"', async () => {
    const mensagens = await verificar(
      'apps/seller-app/src/main/exemplo.ts',
      "import { x } from '../../../../packages/ui/src/index';\nimport { y } from '../../../outra-pasta/y';",
    );
    expect(mensagens).toHaveLength(2);
    expect(mensagens[0]).toContain('fora da pasta do aplicativo');
  });

  it('reprova caminho absoluto', async () => {
    const mensagens = await verificar(NO_VENDAS, "import { x } from 'C:/Projetos/qualquer/coisa';");
    expect(mensagens).toHaveLength(1);
    expect(mensagens[0]).toContain('caminho absoluto');
  });

  it('aceita o que é do próprio app e os pacotes compartilhados', async () => {
    const mensagens = await verificar(
      NO_VENDAS,
      [
        "import { CANAIS } from '../shared/ponte';",
        "import { x } from './api';",
        "import { Botao } from '@corona/ui';",
        "import { entrar } from '@corona/api-client-vendedor';",
        "import { readFile } from 'node:fs/promises';",
        "import { app } from 'electron';",
      ].join('\n'),
    );
    expect(mensagens).toEqual([]);
  });

  it('o arquivo de configuração do app (na raiz da pasta) também não sai da pasta', async () => {
    const mensagens = await verificar(
      'apps/seller-app/electron.vite.config.ts',
      "import { x } from '../owner-app/electron.vite.config';",
    );
    expect(mensagens.length).toBeGreaterThan(0);
  });
});

describe('lint dos pacotes compartilhados: nenhum api-client-* (ADR-0003, camada 1)', () => {
  it.each([
    ['packages/ui/src/exemplo.ts', "import { entrar } from '@corona/api-client-dona';"],
    ['packages/ui/src/exemplo.ts', "import { entrar } from '@corona/api-client-vendedor';"],
    [
      'packages/shared/src/exemplo.ts',
      "import { x } from '../../api-client-vendedor/src/cliente';",
    ],
    ['packages/shared/src/exemplo.ts', "import { x } from '@corona/owner-app';"],
    ['packages/ui/src/exemplo.ts', "import { x } from '../../../apps/seller-app/src/main/api';"],
  ])('reprova em %s: %s', async (arquivo, codigo) => {
    const mensagens = await verificar(arquivo, codigo);
    expect(mensagens.length).toBeGreaterThan(0);
    expect(mensagens[0]).toContain('pacotes compartilhados');
  });

  it('aceita importações normais dos pacotes', async () => {
    const mensagens = await verificar(
      'packages/ui/src/exemplo.ts',
      "import { useState } from 'react';\nimport { x } from './componentes/Botao';",
    );
    expect(mensagens).toEqual([]);
  });
});

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TABELAS_SINCRONIZAVEIS } from '../banco/migracoes';
import { COLUNAS_DO_PULL, colunasGravaveis, MARCADORES_PROIBIDOS } from './colunas';
import { type BancoDeTeste, criarBancoDeTeste } from './testes/banco-de-teste';

/**
 * A lista de colunas que o app aceita do pull (garantia 2 da aprovação da 5.6): nenhuma tabela local aceita coluna de
 * custo, fornecedor ou estoque da loja, e a lista bate com o esquema local.
 */
let b: BancoDeTeste;
beforeEach(() => {
  b = criarBancoDeTeste();
});
afterEach(() => b.fechar());

describe('colunas aceitas do pull', () => {
  it('há uma lista para cada tabela sincronizável, e só para elas', () => {
    expect(Object.keys(COLUNAS_DO_PULL).sort()).toEqual([...TABELAS_SINCRONIZAVEIS].sort());
  });

  it('nenhuma tabela aceita coluna de custo, fornecedor ou estoque', () => {
    for (const tabela of TABELAS_SINCRONIZAVEIS) {
      for (const coluna of colunasGravaveis(tabela)) {
        for (const m of MARCADORES_PROIBIDOS)
          expect(coluna, `${tabela}.${coluna}`).not.toContain(m);
      }
    }
    expect(MARCADORES_PROIBIDOS).toContain('custo');
  });

  it('o esquema local não tem coluna de custo e tem todas as colunas da lista', () => {
    for (const tabela of TABELAS_SINCRONIZAVEIS) {
      const locais = (b.db.prepare(`PRAGMA table_info(${tabela})`).all() as { name: string }[]).map(
        (c) => c.name,
      );
      expect(locais.join(' ').toLowerCase(), tabela).not.toMatch(/custo|fornecedor|estoque/);
      for (const coluna of colunasGravaveis(tabela)) expect(locais, tabela).toContain(coluna);
      // E nada no esquema além da lista e das colunas de controle.
      const esperadas = new Set([
        'id',
        'version',
        'deleted_at',
        'origem_local',
        ...colunasGravaveis(tabela),
      ]);
      for (const coluna of locais) expect(esperadas.has(coluna), `${tabela}.${coluna}`).toBe(true);
    }
  });
});

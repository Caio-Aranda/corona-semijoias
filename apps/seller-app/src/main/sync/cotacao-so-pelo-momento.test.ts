import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Teste de arquitetura (ADR-0006, item 15): a cotação da tela é escolhida pelo MOMENTO do lançamento, num lugar só
 * (`cotacaoVigenteEm`, em `consignado-comum.ts`). Nenhum outro arquivo do app compara `data_cotacao` com um dia.
 */
function arquivos(pasta: string): string[] {
  return readdirSync(pasta).flatMap((nome) => {
    const caminho = join(pasta, nome);
    if (statSync(caminho).isDirectory()) return arquivos(caminho);
    return /\.(ts|tsx)$/.test(nome) && !/\.test\./.test(nome) ? [caminho] : [];
  });
}

describe('a cotação é escolhida só pelo momento', () => {
  it('nenhum arquivo fora de consignado-comum.ts compara data_cotacao com um dia', () => {
    const raiz = join(__dirname, '..', '..');
    const infratores = arquivos(raiz).filter((f) => {
      if (f.endsWith('consignado-comum.ts')) return false;
      return /data_cotacao\s*(<=|<|>=|>)/.test(readFileSync(f, 'utf8'));
    });
    expect(infratores).toEqual([]);
  });
});

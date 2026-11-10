import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';

const ID = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';

function chamar(parametros: unknown) {
  return validarChamada({ operacao: 'relatorios.cadastros', parametros });
}

describe('relatórios de cadastro na ponte', () => {
  it('aceita o nome do relatório e os filtros, com página e tamanho padrão', () => {
    const r = chamar({ relatorio: 'produtos', busca: 'anel', situacao: 'TODOS', categoriaId: ID });
    expect(r).not.toBeNull();
    expect(r?.parametros).toMatchObject({ relatorio: 'produtos' });
  });

  it.each([
    [{ relatorio: 'financeiro' }],
    [{}],
    [{ relatorio: 'produtos', situacao: 'QUALQUER' }],
    [{ relatorio: 'produtos', categoriaId: 'nao-e-id' }],
    [{ relatorio: 'produtos', tamanho: 101 }],
    [{ relatorio: 'produtos', pagina: -1 }],
    [{ relatorio: 'aniversariantes', mes: 13 }],
    [{ relatorio: 'aniversariantes', mes: '3' }],
    [{ relatorio: 'produtos', de: '10/11/2026' }],
    [{ relatorio: 'produtos', recorte: 'a b' }],
    [{ relatorio: 'produtos', busca: 'x'.repeat(201) }],
    [{ relatorio: 'produtos', ativo: true }],
  ])('recusa dados fora do formato: %j', (dados) => {
    expect(chamar(dados)).toBeNull();
  });
});

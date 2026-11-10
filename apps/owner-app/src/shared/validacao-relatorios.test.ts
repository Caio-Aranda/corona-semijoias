import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';
import { conferirRelatorio } from './validacao-relatorios';

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

  it('aceita os filtros novos da tarefa 1.13', () => {
    const r = chamar({
      relatorio: 'produtos',
      estoque: 'ABAIXO_DO_MINIMO',
      precoDe: '10',
      precoAte: '99.90',
      foto: 'SEM',
    });
    expect(r?.parametros).toMatchObject({
      estoque: 'ABAIXO_DO_MINIMO',
      precoDe: '10',
      precoAte: '99.90',
      foto: 'SEM',
    });
    expect(conferirRelatorio({ relatorio: 'produtos', estoque: 'ZERADO' })?.consulta).toMatchObject(
      {
        estoque: 'ZERADO',
      },
    );
    expect(
      chamar({
        relatorio: 'vendedores',
        limiteDe: '100',
        limiteAte: '500.50',
        comRestricao: 'SIM',
      }),
    ).not.toBeNull();
    expect(
      chamar({ relatorio: 'resultado-das-importacoes', tipo: 'FOTOS', resultado: 'RECUSADO' }),
    ).not.toBeNull();
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
    [{ relatorio: 'produtos', estoque: 'MUITO' }],
    [{ relatorio: 'produtos', foto: 'talvez' }],
    [{ relatorio: 'produtos', precoDe: '-1' }],
    [{ relatorio: 'produtos', precoAte: '1,50' }],
    [{ relatorio: 'vendedores', limiteDe: 'abc' }],
    [{ relatorio: 'vendedores', comRestricao: 'TALVEZ' }],
    [{ relatorio: 'fornecedores', comProdutos: true }],
    [{ relatorio: 'clientes', comVendedor: 'sim' }],
    [{ relatorio: 'resultado-das-importacoes', tipo: 'OUTRO' }],
    [{ relatorio: 'resultado-das-importacoes', resultado: 'FEITO' }],
  ])('recusa dados fora do formato: %j', (dados) => {
    expect(chamar(dados)).toBeNull();
  });
});

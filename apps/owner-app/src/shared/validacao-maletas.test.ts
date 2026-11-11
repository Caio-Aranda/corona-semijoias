import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const OUTRO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';

describe('conferência dos dados das maletas', () => {
  it('criar aceita só vendedor, semana, praça, tipo e o id da tela', () => {
    expect(
      conferirParametros('maletas.criar', {
        id: ID,
        vendedorId: OUTRO,
        semanaId: ID,
        tipo: 'NOVA',
      }),
    ).not.toBeNull();
    expect(
      conferirParametros('maletas.criar', {
        id: ID,
        vendedorId: OUTRO,
        semanaId: ID,
        pracaId: '',
        tipo: 'REPOSICAO',
      }),
    ).not.toBeNull();
    for (const ruim of [
      { id: 'x', vendedorId: OUTRO, semanaId: ID, tipo: 'NOVA' },
      { id: ID, vendedorId: OUTRO, semanaId: ID, tipo: 'OUTRA' },
      { id: ID, vendedorId: OUTRO, semanaId: ID, tipo: 'NOVA', precoCusto: 1 },
      { id: ID, vendedorId: OUTRO, semanaId: ID, tipo: 'NOVA', pracaId: 'x' },
    ]) {
      expect(conferirParametros('maletas.criar', ruim)).toBeNull();
    }
  });

  it('colocar peça pede quantidade inteira de 1 em diante e a versão', () => {
    const base = { id: ID, produtoId: OUTRO, version: 2 };
    expect(conferirParametros('maletas.colocarPeca', { ...base, quantidade: 3 })).not.toBeNull();
    for (const quantidade of [0, -1, 1.5, '3', 100_001]) {
      expect(conferirParametros('maletas.colocarPeca', { ...base, quantidade })).toBeNull();
    }
    expect(
      conferirParametros('maletas.colocarPeca', { id: ID, produtoId: OUTRO, quantidade: 1 }),
    ).toBeNull();
    expect(
      conferirParametros('maletas.colocarPeca', { ...base, quantidade: 1, preco: 5 }),
    ).toBeNull();
  });

  it('tirar, excluir, ver, enviar e em aberto conferem os identificadores', () => {
    expect(
      conferirParametros('maletas.tirarPeca', { id: ID, produtoId: OUTRO, version: 0 }),
    ).not.toBeNull();
    expect(
      conferirParametros('maletas.tirarPeca', { id: ID, produtoId: 'x', version: 0 }),
    ).toBeNull();
    expect(conferirParametros('maletas.excluir', { id: ID, version: 1 })).not.toBeNull();
    expect(conferirParametros('maletas.excluir', { id: ID })).toBeNull();
    expect(conferirParametros('maletas.ver', { id: ID })).not.toBeNull();
    expect(conferirParametros('maletas.enviar', { id: 'x' })).toBeNull();
    expect(conferirParametros('maletas.emAberto', { vendedorId: ID })).not.toBeNull();
    expect(conferirParametros('maletas.emAberto', {})).toBeNull();
  });

  it('listar aceita filtro de situação conhecida e tamanho até 200', () => {
    expect(
      conferirParametros('maletas.listar', { status: 'RASCUNHO', pagina: 0, tamanho: 100 }),
    ).not.toBeNull();
    expect(conferirParametros('maletas.listar', { status: 'QUALQUER' })).toBeNull();
    expect(conferirParametros('maletas.listar', { tamanho: 201 })).toBeNull();
  });
});

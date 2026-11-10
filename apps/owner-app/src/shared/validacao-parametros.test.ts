import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';

describe('conferência dos dados de cada operação', () => {
  it('lista de usuários: padrão, filtros e limites', () => {
    expect(conferirParametros('usuarios.listar', undefined)).toEqual({
      operacao: 'usuarios.listar',
      lista: { pagina: 0, tamanho: 20 },
    });
    expect(
      conferirParametros('usuarios.listar', {
        busca: 'maria',
        perfil: 'ADMIN',
        ativo: false,
        pagina: 2,
        tamanho: 50,
      }),
    ).toEqual({
      operacao: 'usuarios.listar',
      lista: { busca: 'maria', perfil: 'ADMIN', ativo: false, pagina: 2, tamanho: 50 },
    });
    for (const ruim of [
      { tamanho: 101 },
      { tamanho: 0 },
      { pagina: -1 },
      { pagina: 1.5 },
      { perfil: 'ROOT' },
      { ativo: 'sim' },
      { busca: 'x'.repeat(201) },
    ]) {
      expect(conferirParametros('usuarios.listar', ruim)).toBeNull();
    }
  });

  it('criar e editar usuário', () => {
    expect(
      conferirParametros('usuarios.criar', { nome: 'Ana', login: 'ana', perfil: 'ADMIN' }),
    ).toEqual({ operacao: 'usuarios.criar', novo: { nome: 'Ana', login: 'ana', perfil: 'ADMIN' } });
    expect(
      conferirParametros('usuarios.editar', {
        id: ID,
        nome: 'Ana',
        perfil: 'VENDEDOR',
        vendedorId: ID,
        version: 3,
      }),
    ).toEqual({
      operacao: 'usuarios.editar',
      editado: { id: ID, nome: 'Ana', perfil: 'VENDEDOR', vendedorId: ID, version: 3 },
    });
    expect(
      conferirParametros('usuarios.editar', { id: ID, nome: 'Ana', perfil: 'ADMIN' }),
    ).toBeNull();
    expect(
      conferirParametros('usuarios.criar', {
        nome: 'Ana',
        login: 'ana',
        perfil: 'ADMIN',
        vendedorId: 'x',
      }),
    ).toBeNull();
  });

  it('ações por identificador', () => {
    for (const op of [
      'usuarios.desativar',
      'usuarios.reativar',
      'usuarios.redefinirSenha',
    ] as const) {
      expect(conferirParametros(op, { id: ID })).toEqual({ operacao: op, id: ID });
      expect(conferirParametros(op, { id: 'abc' })).toBeNull();
      expect(conferirParametros(op, undefined)).toBeNull();
    }
  });

  it('conversão: valor em reais como texto decimal com ponto, nunca número', () => {
    expect(conferirParametros('cotacao.converter', { valorEmReais: '1234.56' })).toEqual({
      operacao: 'cotacao.converter',
      valorEmReais: '1234.56',
    });
    expect(
      conferirParametros('cotacao.converter', { valorEmReais: '10.00', data: '2026-11-06' }),
    ).toEqual({ operacao: 'cotacao.converter', valorEmReais: '10.00', data: '2026-11-06' });
    for (const ruim of [
      { valorEmReais: 10 },
      { valorEmReais: '10,00' },
      { valorEmReais: '1.234' },
      { valorEmReais: '10.00', data: '06/11/2026' },
    ]) {
      expect(conferirParametros('cotacao.converter', ruim)).toBeNull();
    }
  });

  it('consultas sem dados aceitam só objeto (ou nada)', () => {
    expect(conferirParametros('avisos.listar', undefined)).toEqual({ operacao: 'avisos.listar' });
    expect(conferirParametros('cotacao.hoje', {})).toEqual({ operacao: 'cotacao.hoje' });
    expect(conferirParametros('vendedores.escolha', 'texto')).toBeNull();
    expect(conferirParametros('vendedores.escolha', [])).toBeNull();
  });
});

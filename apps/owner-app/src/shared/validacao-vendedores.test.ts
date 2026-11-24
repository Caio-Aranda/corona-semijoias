import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';
import { OPERACOES_DA_API } from './ponte';
import { OPERACOES_DE_VENDEDORES } from './vendedores';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const OUTRO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const bytes = (n: number) => new Uint8Array(n).fill(1);

describe('conferência dos dados dos representantes', () => {
  it('todas as operações de representantes estão na lista fixa da ponte', () => {
    for (const o of OPERACOES_DE_VENDEDORES) expect(OPERACOES_DA_API).toContain(o);
  });

  it('lista: padrão, filtros e limites', () => {
    expect(conferirParametros('vendedores.listar', undefined)).toEqual({
      operacao: 'vendedores.listar',
      lista: { pagina: 0, tamanho: 20 },
    });
    expect(
      conferirParametros('vendedores.listar', {
        busca: 'joana',
        situacao: 'ATIVOS',
        pagina: 1,
        tamanho: 100,
      }),
    ).toEqual({
      operacao: 'vendedores.listar',
      lista: { busca: 'joana', pagina: 1, tamanho: 100 },
      situacao: 'ATIVOS',
    });
    for (const ruim of [
      { tamanho: 101 },
      { pagina: -1 },
      { situacao: 'QUALQUER' },
      { busca: 'x'.repeat(201) },
      { pracaId: ID },
    ]) {
      expect(conferirParametros('vendedores.listar', ruim)).toBeNull();
    }
  });

  it('criar: limite como texto decimal (a comissão não é mais do representante), campo desconhecido recusa tudo', () => {
    expect(
      conferirParametros('vendedores.criar', {
        nome: 'Joana',
        sexo: 'FEMININO',
        estrangeiro: false,
        limiteConsignacao: '1500.00',
        pracaId: ID,
        restricaoId: OUTRO,
        dataNascimento: '1990-05-17',
      }),
    ).toEqual({
      operacao: 'vendedores.criar',
      corpo: {
        nome: 'Joana',
        sexo: 'FEMININO',
        estrangeiro: false,
        limiteConsignacao: '1500.00',
        pracaId: ID,
        restricaoId: OUTRO,
        dataNascimento: '1990-05-17',
      },
    });
    for (const ruim of [
      { nome: 'J', ativo: false },
      { nome: 'J', precoDeCusto: '1.00' },
      { nome: 'J', limiteConsignacao: '-1.00' },
      { nome: 'J', limiteConsignacao: 1500 },
      { nome: 'J', limiteConsignacao: '1.234' },
      { nome: 'J', comissaoPercentual: '12.50' },
      { nome: 'J', sexo: 'XYZ' },
      { nome: 'J', pracaId: 'nao-e-id' },
      { nome: 'J', dataNascimento: '17/05/1990' },
      { nome: 'x'.repeat(201) },
      { nome: 'J', version: 1 },
    ]) {
      expect(conferirParametros('vendedores.criar', ruim)).toBeNull();
    }
    expect(
      conferirParametros('vendedores.criar', { nome: 'J', comissaoPercentual: '100' }),
    ).toBeNull();
  });

  it('editar exige o id e a versão lida', () => {
    expect(conferirParametros('vendedores.editar', { id: ID, nome: 'J', version: 4 })).toEqual({
      operacao: 'vendedores.editar',
      id: ID,
      corpo: { nome: 'J', version: 4 },
    });
    expect(conferirParametros('vendedores.editar', { id: ID, nome: 'J' })).toBeNull();
    expect(conferirParametros('vendedores.editar', { nome: 'J', version: 1 })).toBeNull();
    expect(conferirParametros('vendedores.editar', { id: 'x', nome: 'J', version: 1 })).toBeNull();
    expect(conferirParametros('vendedores.editar', { id: ID, nome: 'J', version: -1 })).toBeNull();
  });

  it('ver, inativar e reativar só aceitam o id', () => {
    for (const op of ['vendedores.ver', 'vendedores.inativar', 'vendedores.reativar'] as const) {
      expect(conferirParametros(op, { id: ID })).toEqual({ operacao: op, id: ID });
      expect(conferirParametros(op, { id: ID, extra: 1 })).toBeNull();
      expect(conferirParametros(op, { id: 'x' })).toBeNull();
      expect(conferirParametros(op, undefined)).toBeNull();
    }
  });

  it('abas: só as seis conhecidas, cada uma com os seus campos e a versão', () => {
    expect(conferirParametros('vendedores.aba.ver', { id: ID, aba: 'endereco' })).toEqual({
      operacao: 'vendedores.aba.ver',
      id: ID,
      aba: 'endereco',
    });
    expect(conferirParametros('vendedores.aba.ver', { id: ID, aba: 'usuarios' })).toBeNull();
    expect(
      conferirParametros('vendedores.aba.gravar', {
        id: ID,
        aba: 'trabalho',
        empresa: 'Loja',
        renda: '2500.00',
        dataAdmissao: '2020-01-02',
        version: 0,
      }),
    ).toEqual({
      operacao: 'vendedores.aba.gravar',
      id: ID,
      aba: 'trabalho',
      corpo: { empresa: 'Loja', renda: '2500.00', dataAdmissao: '2020-01-02', version: 0 },
    });
    // Campo de outra aba, dinheiro negativo e falta de versão recusam.
    expect(
      conferirParametros('vendedores.aba.gravar', {
        id: ID,
        aba: 'trabalho',
        cnpj: '1',
        version: 0,
      }),
    ).toBeNull();
    expect(
      conferirParametros('vendedores.aba.gravar', {
        id: ID,
        aba: 'conjuge',
        renda: '-1.00',
        version: 0,
      }),
    ).toBeNull();
    expect(
      conferirParametros('vendedores.aba.gravar', { id: ID, aba: 'mei', cnpj: '123' }),
    ).toBeNull();
    expect(
      conferirParametros('vendedores.aba.gravar', {
        id: ID,
        aba: 'observacoes',
        texto: 'x'.repeat(4001),
        version: 1,
      }),
    ).toBeNull();
  });

  it('referências: criar sem versão, editar com versão, excluir só com os dois ids', () => {
    expect(
      conferirParametros('vendedores.referencias.criar', { id: ID, nome: 'Maria', telefone: '1' }),
    ).toEqual({
      operacao: 'vendedores.referencias.criar',
      id: ID,
      corpo: { nome: 'Maria', telefone: '1' },
    });
    expect(
      conferirParametros('vendedores.referencias.criar', { id: ID, nome: 'Maria', version: 0 }),
    ).toBeNull();
    expect(
      conferirParametros('vendedores.referencias.editar', {
        id: ID,
        referenciaId: OUTRO,
        nome: 'Maria',
        version: 2,
      }),
    ).toEqual({
      operacao: 'vendedores.referencias.editar',
      id: ID,
      referenciaId: OUTRO,
      corpo: { nome: 'Maria', version: 2 },
    });
    expect(
      conferirParametros('vendedores.referencias.editar', { id: ID, nome: 'Maria', version: 2 }),
    ).toBeNull();
    expect(
      conferirParametros('vendedores.referencias.excluir', { id: ID, referenciaId: OUTRO }),
    ).toEqual({ operacao: 'vendedores.referencias.excluir', id: ID, referenciaId: OUTRO });
    expect(conferirParametros('vendedores.referencias.excluir', { id: ID })).toBeNull();
  });

  it('documentos: enviar confere tipo, tamanho e forma do arquivo', () => {
    const arquivo = { nome: 'rg.PDF', bytes: bytes(10) };
    expect(
      conferirParametros('vendedores.documentos.enviar', { id: ID, descricao: 'RG', arquivo }),
    ).toEqual({ operacao: 'vendedores.documentos.enviar', id: ID, descricao: 'RG', arquivo });
    expect(conferirParametros('vendedores.documentos.enviar', { id: ID, arquivo })).toEqual({
      operacao: 'vendedores.documentos.enviar',
      id: ID,
      arquivo,
    });
    for (const ruim of [
      { id: ID, arquivo: { nome: 'rg.exe', bytes: bytes(10) } },
      { id: ID, arquivo: { nome: 'rg', bytes: bytes(10) } },
      { id: ID, arquivo: { nome: 'rg.pdf', bytes: bytes(0) } },
      { id: ID, arquivo: { nome: 'rg.pdf', bytes: bytes(10 * 1024 * 1024 + 1) } },
      { id: ID, arquivo: { nome: 'rg.pdf', bytes: 'aGVsbG8=' } },
      { id: ID, arquivo: { nome: 'rg.pdf', bytes: [1, 2, 3] } },
      { id: ID, arquivo: { nome: 'rg.pdf', bytes: bytes(10), caminho: 'C:\\x' } },
      { id: ID, arquivo: { nome: 'a'.repeat(256) + '.pdf', bytes: bytes(10) } },
      { id: ID },
      { id: 'x', arquivo },
    ]) {
      expect(conferirParametros('vendedores.documentos.enviar', ruim)).toBeNull();
    }
    expect(
      conferirParametros('vendedores.documentos.enviar', {
        id: ID,
        arquivo: { nome: 'foto.webp', bytes: bytes(10 * 1024 * 1024) },
      }),
    ).not.toBeNull();
  });

  it('documentos: listar, baixar e excluir', () => {
    expect(conferirParametros('vendedores.documentos.listar', { id: ID })).toEqual({
      operacao: 'vendedores.documentos.listar',
      id: ID,
    });
    for (const op of ['vendedores.documentos.baixar', 'vendedores.documentos.excluir'] as const) {
      expect(conferirParametros(op, { id: ID, documentoId: OUTRO })).toEqual({
        operacao: op,
        id: ID,
        documentoId: OUTRO,
      });
      expect(conferirParametros(op, { id: ID })).toBeNull();
      expect(conferirParametros(op, { id: ID, documentoId: 'x' })).toBeNull();
      expect(conferirParametros(op, { id: ID, documentoId: OUTRO, url: 'http://x' })).toBeNull();
    }
  });
});

import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const CAT = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const HASH = 'a'.repeat(64);
const foto = (nome = 'a.jpg', n = 10) => ({ nome, bytes: new Uint8Array(n) });

describe('conferência dos dados de produtos', () => {
  it('lista: só campos conhecidos, tamanho limitado', () => {
    expect(conferirParametros('produtos.listar', { busca: 'anel', ativo: true })).toMatchObject({
      lista: { busca: 'anel', ativo: true, pagina: 0, tamanho: 20 },
    });
    expect(conferirParametros('produtos.listar', { custo: '1' })).toBeNull();
    expect(conferirParametros('produtos.listar', { tamanho: 101 })).toBeNull();
    expect(conferirParametros('produtos.listar', { categoriaId: 'x' })).toBeNull();
  });

  it('criar: dinheiro como texto, código com tipo da lista, campo desconhecido recusa tudo', () => {
    const base = {
      nome: 'Anel',
      categoriaId: CAT,
      precoVenda: '70.00',
      precoCusto: '10.00',
      codigos: [{ codigo: '0012', tipo: 'LOJA', principal: true }],
    };
    expect(conferirParametros('produtos.criar', base)).not.toBeNull();
    expect(conferirParametros('produtos.criar', { ...base, estoqueAtual: 5 })).toBeNull();
    expect(conferirParametros('produtos.criar', { ...base, precoVenda: 70 })).toBeNull();
    expect(conferirParametros('produtos.criar', { ...base, precoCusto: '1,5' })).toBeNull();
    expect(
      conferirParametros('produtos.criar', { ...base, codigos: [{ codigo: '1', tipo: 'QR' }] }),
    ).toBeNull();
    expect(conferirParametros('produtos.criar', { ...base, version: 1 })).toBeNull();
  });

  it('editar exige a versão e não leva códigos', () => {
    const base = { id: ID, nome: 'Anel', categoriaId: CAT, precoVenda: '1', precoCusto: '1' };
    expect(conferirParametros('produtos.editar', base)).toBeNull();
    expect(conferirParametros('produtos.editar', { ...base, version: 2 })).not.toBeNull();
    expect(conferirParametros('produtos.editar', { ...base, version: 2, codigos: [] })).toBeNull();
  });

  it('estoque mínimo: só id, versão e um inteiro de zero para cima', () => {
    const ok = { id: ID, version: 3, estoqueMinimo: 5 };
    expect(conferirParametros('produtos.estoqueMinimo', ok)).toEqual({
      operacao: 'produtos.estoqueMinimo',
      id: ID,
      corpo: { version: 3, estoqueMinimo: 5 },
    });
    expect(
      conferirParametros('produtos.estoqueMinimo', { ...ok, estoqueMinimo: 0 }),
    ).not.toBeNull();
    for (const ruim of [
      { ...ok, estoqueMinimo: -1 },
      { ...ok, estoqueMinimo: 1.5 },
      { ...ok, estoqueMinimo: '5' },
      { ...ok, estoqueMinimo: 2_000_000 },
      { ...ok, version: undefined },
      { ...ok, id: 'x' },
      { ...ok, nome: 'Anel' },
    ]) {
      expect(conferirParametros('produtos.estoqueMinimo', ruim)).toBeNull();
    }
  });

  it('códigos: versão obrigatória e código vazio recusado', () => {
    expect(
      conferirParametros('produtos.codigos.gravar', {
        id: ID,
        version: 1,
        codigos: [{ codigo: ' ', tipo: 'LOJA' }],
      }),
    ).toBeNull();
    expect(
      conferirParametros('produtos.codigos.gravar', {
        id: ID,
        version: 1,
        codigos: [{ codigo: '7891234567895', tipo: 'EAN13' }],
      }),
    ).not.toBeNull();
  });

  it('por código tira os espaços das pontas', () => {
    expect(conferirParametros('produtos.porCodigo', { codigo: '  A-1 ' })).toMatchObject({
      codigo: 'A-1',
    });
    expect(conferirParametros('produtos.porCodigo', { codigo: '  ' })).toBeNull();
  });

  it('foto: só JPG, PNG e WebP, até 10 MB, nunca vazia', () => {
    expect(conferirParametros('produtos.fotos.enviar', { id: ID, arquivo: foto() })).not.toBeNull();
    expect(
      conferirParametros('produtos.fotos.enviar', { id: ID, arquivo: foto('a.pdf') }),
    ).toBeNull();
    expect(
      conferirParametros('produtos.fotos.enviar', { id: ID, arquivo: foto('a.jpg', 0) }),
    ).toBeNull();
    expect(
      conferirParametros('produtos.fotos.enviar', {
        id: ID,
        arquivo: foto('a.jpg', 10 * 1024 * 1024 + 1),
      }),
    ).toBeNull();
    expect(
      conferirParametros('produtos.fotos.enviar', { id: ID, arquivo: { ...foto(), tipo: 'x' } }),
    ).toBeNull();
  });

  it('imagem: hash de 64 letras e números e tamanho da lista', () => {
    expect(
      conferirParametros('produtos.fotos.imagem', { hash: HASH, tamanho: 'miniatura' }),
    ).not.toBeNull();
    expect(
      conferirParametros('produtos.fotos.imagem', { hash: '../x', tamanho: 'media' }),
    ).toBeNull();
    expect(
      conferirParametros('produtos.fotos.imagem', { hash: HASH, tamanho: 'enorme' }),
    ).toBeNull();
  });

  it('importação: prévia só com nomes; confirmar até 10 fotos', () => {
    expect(conferirParametros('produtos.importacao.previa', { nomes: ['1.jpg'] })).not.toBeNull();
    expect(conferirParametros('produtos.importacao.previa', { nomes: [1] })).toBeNull();
    expect(
      conferirParametros('produtos.importacao.confirmar', {
        arquivos: Array.from({ length: 10 }, () => foto()),
      }),
    ).not.toBeNull();
    expect(
      conferirParametros('produtos.importacao.confirmar', {
        arquivos: Array.from({ length: 11 }, () => foto()),
      }),
    ).toBeNull();
    expect(conferirParametros('produtos.importacao.confirmar', { arquivos: [] })).toBeNull();
  });

  it('importação: o lote é conferido e o id do lote é opcional nas fotos', () => {
    const lote = {
      tipo: 'PLANILHA',
      arquivoNome: 'produtos.xlsx',
      linhas: [{ referencia: 'Linha 2', resultado: 'RECUSADO', motivo: 'Preço inválido' }],
    };
    expect(
      conferirParametros('importacoes.registrar', { importacaoId: ID, importacao: lote }),
    ).not.toBeNull();
    for (const ruim of [
      { importacaoId: 'x', importacao: lote },
      { importacaoId: ID, importacao: { ...lote, tipo: 'OUTRO' } },
      { importacaoId: ID, importacao: { ...lote, linhas: [] } },
      {
        importacaoId: ID,
        importacao: { ...lote, linhas: Array.from({ length: 501 }, () => lote.linhas[0]) },
      },
      {
        importacaoId: ID,
        importacao: { ...lote, linhas: [{ referencia: 'x', resultado: 'FEITO' }] },
      },
      {
        importacaoId: ID,
        importacao: { ...lote, linhas: [{ referencia: ' ', resultado: 'CRIADO' }] },
      },
      { importacaoId: ID, importacao: { ...lote, extra: 1 } },
    ]) {
      expect(conferirParametros('importacoes.registrar', ruim)).toBeNull();
    }
    expect(
      conferirParametros('produtos.importacao.confirmar', { arquivos: [foto()], importacaoId: ID }),
    ).not.toBeNull();
    expect(
      conferirParametros('produtos.importacao.confirmar', {
        arquivos: [foto()],
        importacaoId: 'x',
      }),
    ).toBeNull();
  });

  it('as demais operações exigem id válido', () => {
    expect(conferirParametros('produtos.inativar', { id: 'x' })).toBeNull();
    expect(conferirParametros('produtos.codigoProximo', {})).not.toBeNull();
    expect(conferirParametros('produtos.codigoProximo', { a: 1 })).toBeNull();
  });
});

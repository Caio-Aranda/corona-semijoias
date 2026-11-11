import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';

const ID = '3f2b8c1e-5d4a-4b7e-9c1d-2a6f8e0b4c11';
const OUTRO = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b';
const HASH = 'a'.repeat(64);

describe('o que a tela pode pedir ao processo principal', () => {
  it('aceita cada operação no formato certo e devolve uma cópia limpa', () => {
    const certos = [
      { operacao: 'eu' },
      { operacao: 'maletas.listar' },
      { operacao: 'maletas.ver', id: ID },
      { operacao: 'maletas.peca', id: ID, codigo: 'A-1' },
      {
        operacao: 'maletas.conferir',
        id: ID,
        conferenciaId: OUTRO,
        itens: [{ produtoId: ID, contada: 0 }],
      },
      { operacao: 'maletas.verConferencia', id: ID },
      {
        operacao: 'vendas.vender',
        venda: {
          id: ID,
          remessaId: OUTRO,
          clienteId: ID,
          itens: [{ produtoId: OUTRO, quantidade: 2, desconto: '5.00' }],
          pagamentos: [{ id: OUTRO, valor: '95.00', forma: 'PIX' }],
          totalConferido: '95.00',
        },
      },
      { operacao: 'vendas.listar', de: '2026-11-01', ate: '2026-11-30', status: 'CONCLUIDA' },
      { operacao: 'vendas.ver', id: ID },
      { operacao: 'vendas.estornar', id: ID, estornoId: OUTRO, motivo: 'Engano' },
      { operacao: 'clientes.listar', busca: 'maria' },
      { operacao: 'clientes.ver', id: ID },
      {
        operacao: 'clientes.cadastrar',
        cliente: { id: ID, nome: 'Maria', dataNascimento: '1990-05-01' },
      },
      { operacao: 'cotacao.hoje' },
      { operacao: 'fotos.ver', hash: HASH, tamanho: 'grande' },
      { operacao: 'relatorios.vendas', agrupar: 'peca', de: '2026-11-01' },
      { operacao: 'relatorios.recebimentos' },
      { operacao: 'relatorios.aReceber' },
    ];
    for (const pedido of certos) expect(validarChamada(pedido)).toEqual(pedido);
  });

  it('descarta campo a mais (a ponte não repassa o que não conhece)', () => {
    const limpo = validarChamada({
      operacao: 'maletas.ver',
      id: ID,
      token: 'x',
      vendedorId: OUTRO,
    });
    expect(limpo).toEqual({ operacao: 'maletas.ver', id: ID });
    const venda = validarChamada({
      operacao: 'vendas.vender',
      venda: {
        id: ID,
        remessaId: OUTRO,
        clienteId: ID,
        itens: [{ produtoId: OUTRO, quantidade: 1, precoUnitario: '1.00' }],
        pagamentos: [],
        total: '1.00',
        vendedorId: OUTRO,
      },
    });
    expect(JSON.stringify(venda)).not.toMatch(/precoUnitario|total"|vendedorId/);
  });

  it('recusa operação desconhecida, tipo errado, id que não é UUID e tamanho absurdo', () => {
    const errados: unknown[] = [
      null,
      'eu',
      {},
      { operacao: 'admin.apagarTudo' },
      { operacao: 'maletas.ver', id: 'abc' },
      { operacao: 'maletas.ver' },
      { operacao: 'maletas.peca', id: ID, codigo: 'x'.repeat(101) },
      {
        operacao: 'maletas.conferir',
        id: ID,
        conferenciaId: OUTRO,
        itens: [{ produtoId: ID, contada: -1 }],
      },
      {
        operacao: 'maletas.conferir',
        id: ID,
        conferenciaId: OUTRO,
        itens: [{ produtoId: ID, contada: 1.5 }],
      },
      { operacao: 'fotos.ver', hash: '../../etc/passwd', tamanho: 'grande' },
      { operacao: 'fotos.ver', hash: HASH, tamanho: 'original' },
      { operacao: 'vendas.listar', de: '01/11/2026' },
      { operacao: 'vendas.listar', status: 'APAGADA' },
      { operacao: 'vendas.estornar', id: ID, estornoId: OUTRO, motivo: '   ' },
      { operacao: 'vendas.estornar', id: ID, estornoId: OUTRO, motivo: 'x'.repeat(401) },
      { operacao: 'relatorios.vendas', agrupar: 'custo' },
      { operacao: 'clientes.cadastrar', cliente: { id: ID, nome: '' } },
      { operacao: 'clientes.cadastrar', cliente: { id: ID, nome: 'Ana', dataNascimento: 'ontem' } },
      { operacao: 'clientes.listar', busca: 'x'.repeat(101) },
    ];
    for (const pedido of errados) expect(validarChamada(pedido)).toBeNull();
  });

  it('a venda só passa com itens, quantidade inteira positiva e dinheiro com até duas casas', () => {
    const base = { id: ID, remessaId: OUTRO, clienteId: ID, pagamentos: [] };
    const venda = (extra: Record<string, unknown>) =>
      validarChamada({ operacao: 'vendas.vender', venda: { ...base, ...extra } });
    expect(venda({ itens: [] })).toBeNull();
    expect(venda({ itens: [{ produtoId: OUTRO, quantidade: 0 }] })).toBeNull();
    expect(venda({ itens: [{ produtoId: OUTRO, quantidade: 1.5 }] })).toBeNull();
    expect(venda({ itens: [{ produtoId: OUTRO, quantidade: 1, desconto: '1.234' }] })).toBeNull();
    expect(venda({ itens: [{ produtoId: OUTRO, quantidade: 1, desconto: '-1.00' }] })).toBeNull();
    expect(
      venda({
        itens: [{ produtoId: OUTRO, quantidade: 1 }],
        pagamentos: [{ id: ID, valor: '10', forma: 'FIADO' }],
      }),
    ).toBeNull();
    expect(venda({ itens: [{ produtoId: OUTRO, quantidade: 1 }] })).not.toBeNull();
  });
});

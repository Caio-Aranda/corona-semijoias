import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const FORN = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const PEDIDO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';

const compra = (extra: Record<string, unknown> = {}) => ({
  id: ID,
  fornecedorId: FORN,
  dataCompra: '2026-11-10',
  itens: [{ produtoId: PEDIDO, quantidade: 2, custoUnitario: '12.50' }],
  ...extra,
});

describe('conferência dos dados de compras', () => {
  it('as operações de compra passam pela validação da ponte', () => {
    expect(validarChamada({ operacao: 'compras.ver', parametros: { id: ID } })).not.toBeNull();
    expect(validarChamada({ operacao: 'compras.ver', parametros: { id: 'x' } })).toBeNull();
  });

  it('receber: item por produto OU por código, quantidade inteira, custo como texto com 2 casas', () => {
    expect(conferirParametros('compras.receber', compra())).not.toBeNull();
    expect(
      conferirParametros(
        'compras.receber',
        compra({ itens: [{ codigo: ' A-1 ', quantidade: 1, custoUnitario: '0.50' }] }),
      ),
    ).not.toBeNull();
    const item = (extra: Record<string, unknown>) =>
      compra({ itens: [{ produtoId: PEDIDO, quantidade: 1, custoUnitario: '1.00', ...extra }] });
    expect(conferirParametros('compras.receber', item({ codigo: 'A' }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ quantidade: 0 }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ quantidade: 1.5 }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ quantidade: 1_000_001 }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ custoUnitario: 12.5 }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ custoUnitario: '12,50' }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ custoUnitario: '1.005' }))).toBeNull();
    expect(conferirParametros('compras.receber', item({ total: '10.00' }))).toBeNull();
  });

  it('receber: o total enviado pelo app não passa, nem campo desconhecido, nem compra sem itens', () => {
    expect(conferirParametros('compras.receber', compra({ total: '25.00' }))).toBeNull();
    expect(conferirParametros('compras.receber', compra({ itens: [] }))).toBeNull();
    expect(conferirParametros('compras.receber', compra({ id: 'x' }))).toBeNull();
    expect(conferirParametros('compras.receber', compra({ dataCompra: '10/11/2026' }))).toBeNull();
    expect(conferirParametros('compras.receber', compra({ numero: 'x'.repeat(61) }))).toBeNull();
    expect(
      conferirParametros(
        'compras.receber',
        compra({
          itens: Array(501).fill({ produtoId: PEDIDO, quantidade: 1, custoUnitario: '1.00' }),
        }),
      ),
    ).toBeNull();
  });

  it('listar: período, fornecedor e situação conhecidos; tamanho limitado', () => {
    expect(
      conferirParametros('compras.listar', {
        de: '2026-11-01',
        ate: '2026-11-30',
        fornecedorId: FORN,
        situacao: 'ESTORNADA',
      }),
    ).toMatchObject({ lista: { pagina: 0, tamanho: 20, situacao: 'ESTORNADA' } });
    expect(conferirParametros('compras.listar', { situacao: 'QUALQUER' })).toBeNull();
    expect(conferirParametros('compras.listar', { de: 'ontem' })).toBeNull();
    expect(conferirParametros('compras.listar', { tamanho: 101 })).toBeNull();
    expect(conferirParametros('compras.listar', { custo: '1' })).toBeNull();
  });

  it('desfazer: o motivo é obrigatório e o pedido tem identificador próprio', () => {
    expect(
      conferirParametros('compras.desfazer', { id: ID, pedidoId: PEDIDO, motivo: ' Veio errado ' }),
    ).toMatchObject({ motivo: 'Veio errado' });
    expect(
      conferirParametros('compras.desfazer', { id: ID, pedidoId: PEDIDO, motivo: '  ' }),
    ).toBeNull();
    expect(conferirParametros('compras.desfazer', { id: ID, motivo: 'x' })).toBeNull();
  });
});

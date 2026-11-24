import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { aplicarLote } from './aplicador-do-pull';
import { sobraAAplicarDoCliente } from './consignado-comum';
import { type BancoDeTeste, criarBancoDeTeste } from './testes/banco-de-teste';

const idDe = (n: number): string => `00000000-0000-7000-8000-${n.toString(16).padStart(12, '0')}`;

let b: BancoDeTeste;
const cliente = idDe(3);
const semana = idDe(4);

function recibo(n: number, total: string, extra: Record<string, unknown> = {}) {
  return {
    tabela: 'recibos_consignado',
    id: idDe(20 + n),
    version: 1,
    deletedAt: null,
    campos: {
      cliente_id: cliente,
      semana_id: semana,
      sequencia: n,
      pago_em: `2026-11-0${n}T12:00:00.000Z`,
      total,
      ...extra,
    },
  };
}

function aplicacao(n: number, reciboN: number, valor: string) {
  return {
    tabela: 'aplicacoes_recibo',
    id: idDe(40 + n),
    version: 1,
    deletedAt: null,
    campos: { recibo_id: idDe(20 + reciboN), acerto_id: idDe(60), valor },
  };
}

beforeEach(() => {
  b = criarBancoDeTeste();
});
afterEach(() => b.fechar());

describe('sobraAAplicarDoCliente', () => {
  it('sem recibo nenhum, não há sobra', () => {
    expect(sobraAAplicarDoCliente(b.db, cliente)).toBe(0n);
  });

  it('soma, recibo por recibo, o que sobrou depois das aplicações parciais', () => {
    aplicarLote(
      b.db,
      [
        recibo(1, '100.00'),
        recibo(2, '50.00'),
        aplicacao(1, 1, '70.00'),
        aplicacao(2, 1, '5.00'),
        aplicacao(3, 2, '20.00'),
      ],
      { cursor: 'c-1' },
    );
    // Recibo 1: 100 - 75 = 25; recibo 2: 50 - 20 = 30.
    expect(sobraAAplicarDoCliente(b.db, cliente)).toBe(5500n);
  });

  it('recibo totalmente aplicado não deixa sobra; aplicação apagada volta a ser sobra', () => {
    aplicarLote(b.db, [recibo(1, '40.00'), aplicacao(1, 1, '40.00')], { cursor: 'c-1' });
    expect(sobraAAplicarDoCliente(b.db, cliente)).toBe(0n);
    aplicarLote(
      b.db,
      [{ ...aplicacao(1, 1, '40.00'), version: 2, deletedAt: '2026-11-05T00:00:00.000Z' }],
      {
        cursor: 'c-2',
      },
    );
    expect(sobraAAplicarDoCliente(b.db, cliente)).toBe(4000n);
  });

  it('recibo estornado ou apagado não conta', () => {
    aplicarLote(
      b.db,
      [
        recibo(1, '100.00', { estornado_em: '2026-11-04T12:00:00.000Z' }),
        recibo(2, '30.00'),
        { ...recibo(3, '999.00'), deletedAt: '2026-11-04T12:00:00.000Z' },
      ],
      { cursor: 'c-1' },
    );
    expect(sobraAAplicarDoCliente(b.db, cliente)).toBe(3000n);
  });
});

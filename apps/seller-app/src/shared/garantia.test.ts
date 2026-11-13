import { describe, expect, it } from 'vitest';
import { diaEmBrasilia, garantiaAte, lerPrazos, prazoDoMotivo, dentroDoPrazo } from './garantia';

/**
 * Prazo da garantia no computador (tarefa 6.8; ADR-0014, item 4): dias do calendário de Brasília, como o
 * `RegrasDaDevolucao` do servidor; `DEFEITO` pelo prazo do defeito e os outros pelo do arrependimento. É prévia: o
 * servidor confere de novo pela hora corrigida e manda para a revisão da dona se discordar. Escrito antes do código.
 */
const PRAZOS = { arrependimento: 7, defeito: 30 };

describe('prazo da garantia pelo relógio do computador', () => {
  it('venda às 10:00 de Brasília: no 7º dia às 23:59 passa; às 00:00 do 8º não', () => {
    const venda = '2026-11-02T13:00:00.000Z'; // 10:00 em Brasília (UTC−3)
    expect(
      dentroDoPrazo(venda, new Date('2026-11-10T02:59:00.000Z'), 'ARREPENDIMENTO', PRAZOS),
    ).toBe(true); // 09/11 23:59
    expect(
      dentroDoPrazo(venda, new Date('2026-11-10T03:00:00.000Z'), 'ARREPENDIMENTO', PRAZOS),
    ).toBe(false); // 10/11 00:00
    expect(garantiaAte(venda, 7)).toBe('2026-11-09');
  });

  it('venda às 23:30 de Brasília (02:30 UTC do dia seguinte) conta pelo dia de Brasília', () => {
    const venda = '2026-11-03T02:30:00.000Z'; // 02/11 23:30 em Brasília
    expect(diaEmBrasilia(venda)).toBe('2026-11-02');
    expect(garantiaAte(venda, 7)).toBe('2026-11-09');
    expect(dentroDoPrazo(venda, new Date('2026-11-10T02:59:00.000Z'), 'TAMANHO', PRAZOS)).toBe(
      true,
    );
    expect(dentroDoPrazo(venda, new Date('2026-11-10T03:00:00.000Z'), 'TAMANHO', PRAZOS)).toBe(
      false,
    );
  });

  it('defeito no 30º dia passa e no 31º não; os outros motivos usam o prazo da desistência', () => {
    const venda = '2026-11-02T13:00:00.000Z';
    expect(dentroDoPrazo(venda, new Date('2026-12-02T15:00:00.000Z'), 'DEFEITO', PRAZOS)).toBe(
      true,
    );
    expect(dentroDoPrazo(venda, new Date('2026-12-03T15:00:00.000Z'), 'DEFEITO', PRAZOS)).toBe(
      false,
    );
    expect(prazoDoMotivo('DEFEITO', PRAZOS)).toBe(30);
    for (const m of ['ARREPENDIMENTO', 'TAMANHO', 'OUTRO'] as const)
      expect(prazoDoMotivo(m, PRAZOS)).toBe(7);
  });

  it('os prazos vêm dos parâmetros guardados (mudam na hora) e, sem eles, 7 e 30', () => {
    expect(lerPrazos(JSON.stringify({ prazoArrependimentoDias: 3, prazoDefeitoDias: 10 }))).toEqual(
      { arrependimento: 3, defeito: 10 },
    );
    expect(lerPrazos(null)).toEqual({ arrependimento: 7, defeito: 30 });
    expect(lerPrazos('{}')).toEqual({ arrependimento: 7, defeito: 30 });
    expect(lerPrazos('não é json')).toEqual({ arrependimento: 7, defeito: 30 });
    expect(
      lerPrazos(JSON.stringify({ prazoArrependimentoDias: 2.5, prazoDefeitoDias: -1 })),
    ).toEqual({ arrependimento: 7, defeito: 30 });
    const venda = '2026-11-02T13:00:00.000Z';
    const curtos = lerPrazos(JSON.stringify({ prazoArrependimentoDias: 3, prazoDefeitoDias: 10 }));
    expect(
      dentroDoPrazo(venda, new Date('2026-11-06T15:00:00.000Z'), 'ARREPENDIMENTO', curtos),
    ).toBe(false);
  });

  it('virada de mês e de ano', () => {
    expect(garantiaAte('2026-12-28T13:00:00.000Z', 7)).toBe('2027-01-04');
  });
});

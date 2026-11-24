import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { aplicarLote } from './aplicador-do-pull';
import { cotacaoVigenteEm } from './consignado-comum';
import { type BancoDeTeste, criarBancoDeTeste, idDeTeste } from './testes/banco-de-teste';

/**
 * Qual cotação vale no Corona Vendas (ADR-0006, item 15): a de maior `vigente_desde` menor ou igual ao instante, empate pelo
 * maior id. A do Banco Central vale desde 00:00 do dia em Brasília (03:00 UTC); a da dona, desde a hora em que foi gravada.
 * É a mesma tabela de casos do servidor (`CotacaoVigenteTest`). Só tela: o valor gravado é sempre Real.
 */
let b: BancoDeTeste;
beforeEach(() => {
  b = criarBancoDeTeste();
});
afterEach(() => b.fechar());

function bcb(dia: string, usd: string, pyg: string): string {
  const id = idDeTeste();
  aplicarLote(
    b.db,
    [
      {
        tabela: 'cotacoes',
        id,
        version: 1,
        deletedAt: null,
        campos: {
          data_cotacao: dia,
          usd_venda: usd,
          pyg_venda: pyg,
          origem: 'BANCO_CENTRAL',
          vigente_desde: `${dia}T03:00:00Z`,
        },
      },
    ],
    { cursor: `c-${idDeTeste()}` },
  );
  return id;
}

function dona(vigenteDesde: string, usd: string, guaranisPorReal: string, id?: string): string {
  const i = id ?? idDeTeste();
  const dia = new Date(new Date(vigenteDesde).getTime() - 3 * 3600_000).toISOString().slice(0, 10);
  const pyg = (1 / Number(guaranisPorReal)).toFixed(8);
  aplicarLote(
    b.db,
    [
      {
        tabela: 'cotacoes',
        id: i,
        version: 1,
        deletedAt: null,
        campos: {
          data_cotacao: dia,
          usd_venda: usd,
          pyg_venda: pyg,
          origem: 'DONA',
          vigente_desde: vigenteDesde,
          guaranis_por_real: guaranisPorReal,
        },
      },
    ],
    { cursor: `c-${idDeTeste()}` },
  );
  return i;
}

describe('cotacaoVigenteEm: só com o Banco Central é a regra de sempre', () => {
  beforeEach(() => {
    bcb('2026-10-30', '5.42120000', '0.00092960');
    bcb('2026-11-05', '5.46210000', '0.00088770');
    bcb('2026-11-06', '5.50130000', '0.00089500');
  });

  it.each([
    ['2026-11-06T15:00:00Z', '2026-11-06', 'no dia da cotação, a própria'],
    ['2026-11-07T15:00:00Z', '2026-11-06', 'sábado usa a sexta'],
    ['2026-11-08T15:00:00Z', '2026-11-06', 'domingo usa a sexta'],
    ['2026-11-07T02:30:00Z', '2026-11-06', 'sexta 23:30 em Brasília, mesmo sendo sábado em UTC'],
    ['2026-11-05T02:30:00Z', '2026-10-30', 'quarta 23:30 em Brasília ainda é a de 30/10'],
    [
      '2026-11-09T03:00:00Z',
      '2026-11-06',
      'segunda 00:00 em Brasília, sem a de segunda: a de sexta',
    ],
    ['2026-11-02T15:00:00Z', '2026-10-30', 'Finados usa a última antes do feriado'],
  ])('%s → %s (%s)', (instante, esperada) => {
    expect(cotacaoVigenteEm(b.db, instante)?.data).toBe(esperada);
  });

  it('antes da primeira cotação não há cotação', () => {
    expect(cotacaoVigenteEm(b.db, '2026-10-29T15:00:00Z')).toBeNull();
  });

  it('o instante pode vir com frações de segundo', () => {
    expect(cotacaoVigenteEm(b.db, '2026-11-06T03:00:00.000Z')?.data).toBe('2026-11-06');
    expect(cotacaoVigenteEm(b.db, '2026-11-06T02:59:59.999Z')?.data).toBe('2026-11-05');
  });
});

describe('cotacaoVigenteEm: a cotação da dona no meio do dia', () => {
  it('BCB do dia antes das 10:00, a da dona depois, e a dela vale até o BCB do dia seguinte', () => {
    bcb('2026-11-20', '5.50000000', '0.00089000');
    const doDia = bcb('2026-11-23', '5.51000000', '0.00089100');
    const daDona = dona('2026-11-23T13:00:00Z', '5.80000000', '1350.00000000');

    expect(cotacaoVigenteEm(b.db, '2026-11-23T12:59:00Z')?.id).toBe(doDia);
    expect(cotacaoVigenteEm(b.db, '2026-11-23T13:00:00.123Z')?.id).toBe(daDona);
    expect(cotacaoVigenteEm(b.db, '2026-11-23T18:00:00Z')?.id).toBe(daDona);
    // A dela vale até o Banco Central do dia seguinte chegar.
    expect(cotacaoVigenteEm(b.db, '2026-11-24T12:00:00Z')?.id).toBe(daDona);
    bcb('2026-11-24', '5.52000000', '0.00089200');
    expect(cotacaoVigenteEm(b.db, '2026-11-24T12:00:00Z')?.data).toBe('2026-11-24');
  });

  it('a da dona traz a origem e "1 real = Gs." como ela digitou', () => {
    dona('2026-11-23T13:15:00Z', '5.80000000', '1350.00000000');
    const c = cotacaoVigenteEm(b.db, '2026-11-23T14:00:00Z');
    expect(c).toMatchObject({
      origem: 'DONA',
      vigenteDesde: '2026-11-23T13:15:00Z',
      guaranisPorReal: '1350.00000000',
      dolar: '5.80000000',
    });
  });

  it('empate de vigente_desde: vale a de maior id', () => {
    const a = dona('2026-11-23T13:00:00Z', '5.80000000', '1350.00000000', idDeTeste());
    const z = dona('2026-11-23T13:00:00Z', '5.90000000', '1360.00000000', idDeTeste());
    expect(a < z).toBe(true);
    expect(cotacaoVigenteEm(b.db, '2026-11-23T13:00:00Z')?.id).toBe(z);
  });

  it('o instante vem de qualquer formato ISO com Z (frações de segundo não atrapalham a comparação)', () => {
    const daDona = dona('2026-11-23T13:00:00.123Z', '5.80000000', '1350.00000000');
    bcb('2026-11-23', '5.51000000', '0.00089100');
    expect(cotacaoVigenteEm(b.db, '2026-11-23T13:00:00.123Z')?.id).toBe(daDona);
    expect(cotacaoVigenteEm(b.db, '2026-11-23T13:00:01Z')?.id).toBe(daDona);
    expect(cotacaoVigenteEm(b.db, '2026-11-23T13:00:00Z')?.origem).toBe('BANCO_CENTRAL');
    expect(cotacaoVigenteEm(b.db, '2026-11-23T13:00:00.122Z')?.origem).toBe('BANCO_CENTRAL');
  });

  it('cotação já gravada antes da migração 13 (sem vigente_desde) vale desde 00:00 do dia em Brasília', () => {
    aplicarLote(
      b.db,
      [
        {
          tabela: 'cotacoes',
          id: idDeTeste(),
          version: 1,
          deletedAt: null,
          campos: { data_cotacao: '2026-11-06', usd_venda: '5.50130000', pyg_venda: '0.00089500' },
        },
      ],
      { cursor: 'c-antiga' },
    );
    expect(cotacaoVigenteEm(b.db, '2026-11-06T03:00:00Z')?.data).toBe('2026-11-06');
    expect(cotacaoVigenteEm(b.db, '2026-11-06T02:59:00Z')).toBeNull();
  });
});

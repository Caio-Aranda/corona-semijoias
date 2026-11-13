import { describe, expect, it } from 'vitest';
import {
  faltaParaAViradaDeBrasilia,
  meioDiaDeBrasiliaHa,
  sqlDeEnvelhecerVenda,
} from './maquina-do-tempo';

const utc = (s: string): number => Date.parse(s);

describe('máquina do tempo: contas de data (Brasília = UTC-3, sem horário de verão)', () => {
  it('meio-dia de Brasília de N dias atrás, para qualquer hora do dia', () => {
    // 15:00 UTC é meio-dia em Brasília.
    expect(new Date(meioDiaDeBrasiliaHa(7, utc('2026-11-13T12:00:00Z'))).toISOString()).toBe(
      '2026-11-06T15:00:00.000Z',
    );
    // 02:59 UTC ainda é o dia anterior em Brasília (23:59); 03:00 UTC já é o dia novo.
    expect(new Date(meioDiaDeBrasiliaHa(1, utc('2026-11-13T02:59:00Z'))).toISOString()).toBe(
      '2026-11-11T15:00:00.000Z',
    );
    expect(new Date(meioDiaDeBrasiliaHa(1, utc('2026-11-13T03:00:00Z'))).toISOString()).toBe(
      '2026-11-12T15:00:00.000Z',
    );
    // Virada de mês e de ano.
    expect(new Date(meioDiaDeBrasiliaHa(30, utc('2027-01-05T10:00:00Z'))).toISOString()).toBe(
      '2026-12-06T15:00:00.000Z',
    );
    expect(new Date(meioDiaDeBrasiliaHa(0, utc('2026-11-13T10:00:00Z'))).toISOString()).toBe(
      '2026-11-13T15:00:00.000Z',
    );
  });

  it('quanto falta para a meia-noite de Brasília (03:00 UTC)', () => {
    expect(faltaParaAViradaDeBrasilia(utc('2026-11-13T02:55:00Z'))).toBe(5 * 60_000);
    expect(faltaParaAViradaDeBrasilia(utc('2026-11-13T03:00:00Z'))).toBe(24 * 3_600_000);
    expect(faltaParaAViradaDeBrasilia(utc('2026-11-13T12:00:00Z'))).toBe(15 * 3_600_000);
  });

  it('o SQL desliga só o gatilho de conteúdo da venda, dentro de uma transação, e liga de novo', () => {
    const sql = sqlDeEnvelhecerVenda(
      '7f0c6d2e-0000-4000-8000-000000000001',
      new Date('2026-11-06T15:00:00Z'),
    );
    expect(sql.startsWith('BEGIN;')).toBe(true);
    expect(sql.trimEnd().endsWith('COMMIT;')).toBe(true);
    expect(sql).toContain('DISABLE TRIGGER vendas_conteudo_imutavel');
    expect(sql).toContain('ENABLE TRIGGER vendas_conteudo_imutavel');
    expect(sql).not.toMatch(/DISABLE TRIGGER (ALL|USER)/i);
    expect(sql).toContain("vendida_em = '2026-11-06T15:00:00.000Z'");
    expect(sql).toContain("id = '7f0c6d2e-0000-4000-8000-000000000001'");
    expect(() => sqlDeEnvelhecerVenda("x'; DROP TABLE corona.vendas; --", new Date())).toThrow();
  });
});

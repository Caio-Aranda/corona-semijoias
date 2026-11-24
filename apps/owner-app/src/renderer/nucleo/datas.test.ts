import { afterEach, describe, expect, it, vi } from 'vitest';
import { hojeEmBrasilia } from './datas';
import { hojeEmTexto } from '../telas/compras/CabecalhoDaCompra';

describe('hoje em Brasília (revisão de 23/11/2026)', () => {
  afterEach(() => vi.useRealTimers());

  it('às 22h30 de Brasília (01h30 UTC do dia seguinte) ainda é o mesmo dia', () => {
    expect(hojeEmBrasilia(new Date('2026-11-24T01:30:00Z'))).toBe('2026-11-23');
  });

  it('logo depois da meia-noite de Brasília já é o dia novo', () => {
    expect(hojeEmBrasilia(new Date('2026-11-24T03:01:00Z'))).toBe('2026-11-24');
  });

  it('a data da compra que já vem preenchida é a de Brasília, não a de UTC', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-24T01:30:00Z'));
    expect(hojeEmTexto()).toBe('2026-11-23');
  });
});

import { describe, expect, it, vi } from 'vitest';
import {
  Agenda,
  AGRUPAR_GRAVACOES_MS,
  ESPERA_MAXIMA_MS,
  esperaDepoisDaFalha,
  INTERVALO_COM_INTERNET_MS,
} from './agenda';
import type { DesfechoDoCiclo } from './ciclo';
import { assentar, RelogioFalso } from './testes/relogio-falso';

/** Gatilhos e esperas do motor (ADR-0013, item 19). */
describe('espera crescente', () => {
  it('2, 4, 8 s... com teto de 5 min', () => {
    const meio = () => 0.5; // sem variação
    expect([1, 2, 3, 4].map((n) => esperaDepoisDaFalha(n, meio))).toEqual([
      2000, 4000, 8000, 16000,
    ]);
    expect(esperaDepoisDaFalha(9, meio)).toBe(ESPERA_MAXIMA_MS);
    expect(esperaDepoisDaFalha(50, meio)).toBe(ESPERA_MAXIMA_MS);
  });

  it('a variação sorteada fica entre 75% e 125% e nunca passa de 5 min', () => {
    expect(esperaDepoisDaFalha(1, () => 0)).toBe(1500);
    expect(esperaDepoisDaFalha(1, () => 0.999999)).toBeLessThanOrEqual(2500);
    for (let n = 1; n < 40; n++) {
      for (const s of [0, 0.3, 0.7, 0.999]) {
        expect(esperaDepoisDaFalha(n, () => s)).toBeLessThanOrEqual(ESPERA_MAXIMA_MS);
      }
    }
  });
});

function montar(desfechos: DesfechoDoCiclo[]) {
  const relogio = new RelogioFalso();
  let resolverAtual: ((d: DesfechoDoCiclo) => void) | null = null;
  const executar = vi.fn(
    () =>
      new Promise<DesfechoDoCiclo>((r) => {
        resolverAtual = r;
      }),
  );
  const terminar = async () => {
    const r = resolverAtual;
    resolverAtual = null;
    r?.(desfechos.shift() ?? 'ok');
    await assentar();
  };
  const aoParar = vi.fn();
  const agenda = new Agenda({ executar, relogio, sorteio: () => 0.5, aoParar });
  return { relogio, executar, terminar, agenda, aoParar };
}

describe('agenda', () => {
  it('roda ao ligar e depois a cada 60 s com tudo certo', async () => {
    const t = montar([]);
    t.agenda.ligar();
    expect(t.executar).toHaveBeenCalledTimes(1);
    await t.terminar();
    expect(t.relogio.pendentes()).toEqual([INTERVALO_COM_INTERNET_MS]);
    await t.relogio.andar(INTERVALO_COM_INTERNET_MS);
    expect(t.executar).toHaveBeenCalledTimes(2);
  });

  it('depois de falhas, espera 2, 4, 8 s', async () => {
    const t = montar(['falha', 'sem-internet', 'falha', 'ok']);
    t.agenda.ligar();
    await t.terminar();
    expect(t.relogio.pendentes()).toEqual([2000]);
    await t.relogio.andar(2000);
    await t.terminar();
    expect(t.relogio.pendentes()).toEqual([4000]);
    await t.relogio.andar(4000);
    await t.terminar();
    expect(t.relogio.pendentes()).toEqual([8000]);
    await t.relogio.andar(8000);
    await t.terminar();
    expect(t.relogio.pendentes()).toEqual([INTERVALO_COM_INTERNET_MS]);
  });

  it('gravações seguidas viram um ciclo só, 2 s depois da primeira', async () => {
    const t = montar([]);
    t.agenda.ligar();
    await t.terminar();
    t.agenda.gravou();
    await t.relogio.andar(500);
    t.agenda.gravou();
    t.agenda.gravou();
    await t.relogio.andar(AGRUPAR_GRAVACOES_MS - 500);
    expect(t.executar).toHaveBeenCalledTimes(2);
    await t.terminar();
    await t.relogio.andar(AGRUPAR_GRAVACOES_MS);
    expect(t.executar).toHaveBeenCalledTimes(2);
  });

  it('um pedido durante o ciclo vira um ciclo logo depois, nunca dois juntos', async () => {
    const t = montar([]);
    t.agenda.ligar();
    t.agenda.internetVoltou();
    t.agenda.internetVoltou();
    expect(t.executar).toHaveBeenCalledTimes(1);
    await t.terminar();
    expect(t.executar).toHaveBeenCalledTimes(2);
    await t.terminar();
    expect(t.executar).toHaveBeenCalledTimes(2);
  });

  it('ordem do servidor: desliga e avisa; desligada, nada roda', async () => {
    const t = montar(['parado']);
    t.agenda.ligar();
    await t.terminar();
    expect(t.aoParar).toHaveBeenCalledTimes(1);
    expect(t.agenda.estaLigada).toBe(false);
    expect(t.relogio.pendentes()).toEqual([]);
    t.agenda.gravou();
    t.agenda.internetVoltou();
    await t.relogio.andar(10 * 60_000);
    expect(t.executar).toHaveBeenCalledTimes(1);
  });
});

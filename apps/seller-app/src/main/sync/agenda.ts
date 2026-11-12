import type { Relogio } from '../tipos';
import type { DesfechoDoCiclo } from './ciclo';

/** Gatilhos e esperas do motor (ADR-0013, item 19). */
export const INTERVALO_COM_INTERNET_MS = 60_000;
export const AGRUPAR_GRAVACOES_MS = 2_000;
export const PRIMEIRA_ESPERA_MS = 2_000;
export const ESPERA_MAXIMA_MS = 5 * 60_000;
/** Variação sorteada de ±25% em cada espera, para muitos computadores não voltarem todos juntos. */
const VARIACAO = 0.25;

/**
 * Espera depois da N-ésima falha seguida: 2, 4, 8 s... até 5 min, com variação sorteada (`sorteio` em [0, 1)). Nunca
 * passa de 5 min.
 */
export function esperaDepoisDaFalha(falhas: number, sorteio: () => number = Math.random): number {
  const n = Math.max(1, Math.floor(falhas));
  const base = Math.min(PRIMEIRA_ESPERA_MS * 2 ** Math.min(n - 1, 30), ESPERA_MAXIMA_MS);
  const fator = 1 - VARIACAO + 2 * VARIACAO * Math.min(Math.max(sorteio(), 0), 1);
  return Math.min(Math.round(base * fator), ESPERA_MAXIMA_MS);
}

export interface OpcoesDaAgenda {
  /** Roda um ciclo (o próprio ciclo garante um por vez). */
  executar: () => Promise<DesfechoDoCiclo>;
  relogio: Relogio;
  sorteio?: () => number;
  /** O ciclo terminou em "parado" (ordem do servidor): a agenda já se desligou. */
  aoParar?: () => void;
}

/**
 * Quando rodar o próximo ciclo: ao ligar (abrir o app), quando a internet volta, a cada 60 s com tudo certo, 2 s depois
 * de uma gravação (as gravações desses 2 s viram um ciclo só) e, depois de uma falha, com espera crescente. Um pedido
 * que chega durante um ciclo vira um ciclo logo depois dele, nunca dois ao mesmo tempo.
 */
export class Agenda {
  private ligada = false;
  private rodando = false;
  private pedidoDuranteOCiclo = false;
  private falhas = 0;
  private cancelarProximo: (() => void) | null = null;
  private cancelarAgrupamento: (() => void) | null = null;

  constructor(private readonly o: OpcoesDaAgenda) {}

  get estaLigada(): boolean {
    return this.ligada;
  }

  ligar(): void {
    if (this.ligada) return;
    this.ligada = true;
    this.falhas = 0;
    this.disparar();
  }

  desligar(): void {
    this.ligada = false;
    this.pedidoDuranteOCiclo = false;
    this.cancelarProximo?.();
    this.cancelarProximo = null;
    this.cancelarAgrupamento?.();
    this.cancelarAgrupamento = null;
  }

  /** A internet voltou: roda já e esquece as falhas de antes. */
  internetVoltou(): void {
    this.falhas = 0;
    this.disparar();
  }

  /** Uma operação foi gravada: roda 2 s depois, juntando as gravações desse intervalo. */
  gravou(): void {
    if (!this.ligada || this.cancelarAgrupamento) return;
    this.cancelarAgrupamento = this.o.relogio.agendar(AGRUPAR_GRAVACOES_MS, () => {
      this.cancelarAgrupamento = null;
      this.disparar();
    });
  }

  private disparar(): void {
    if (!this.ligada) return;
    if (this.rodando) {
      this.pedidoDuranteOCiclo = true;
      return;
    }
    this.cancelarProximo?.();
    this.cancelarProximo = null;
    this.rodando = true;
    void this.o
      .executar()
      .catch((): DesfechoDoCiclo => 'falha')
      .then((desfecho) => {
        this.rodando = false;
        this.depoisDoCiclo(desfecho);
      });
  }

  private depoisDoCiclo(desfecho: DesfechoDoCiclo): void {
    if (!this.ligada) return;
    if (desfecho === 'parado') {
      this.desligar();
      this.o.aoParar?.();
      return;
    }
    let espera: number;
    if (desfecho === 'ok') {
      this.falhas = 0;
      if (this.pedidoDuranteOCiclo) {
        this.pedidoDuranteOCiclo = false;
        this.disparar();
        return;
      }
      espera = INTERVALO_COM_INTERNET_MS;
    } else {
      this.falhas += 1;
      this.pedidoDuranteOCiclo = false;
      espera = esperaDepoisDaFalha(this.falhas, this.o.sorteio);
    }
    this.cancelarProximo = this.o.relogio.agendar(espera, () => {
      this.cancelarProximo = null;
      this.disparar();
    });
  }
}

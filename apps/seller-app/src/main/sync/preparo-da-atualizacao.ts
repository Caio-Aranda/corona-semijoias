import type { Relogio } from '../tipos';
import type { DesfechoDoCiclo } from './ciclo';

/** Quanto o preparo espera o envio em andamento, e depois o "mais um envio" (ADR-0013, item 17). */
export const ESPERA_MAXIMA_DO_ENVIO_MS = 30_000;

/** O que o preparo precisa do motor (o `MotorDeSincronizacao` serve). */
export interface MotorParaOPreparo {
  desligar(): void;
  envioEmAndamento(): Promise<DesfechoDoCiclo> | null;
  aguardandoEnvio(): number;
  enviarAntesDeFechar(): Promise<DesfechoDoCiclo>;
}

export interface OpcoesDoPreparo {
  motor: MotorParaOPreparo;
  /** Fecha o banco local limpo (o `ServicoDoBanco.fechar`). */
  fecharBanco: () => void;
  relogio: Relogio;
  /** Para os testes; o padrão é 30 s. */
  esperaMaximaMs?: number;
  /** Registro técnico (nunca vai para a tela). */
  registrar?: (mensagem: string) => void;
}

export interface ResultadoDoPreparo {
  /** O envio em andamento: não havia, terminou, ou passou dos 30 s (o preparo seguiu sem ele). */
  esperou: 'nada' | 'terminou' | 'tempo-esgotado';
  /** O "mais um envio": o desfecho dele, ou por que não houve. */
  maisUm: DesfechoDoCiclo | 'nao-precisou' | 'nao-tentou' | 'tempo-esgotado';
  /** Quantas operações ficaram guardadas no banco para a versão nova enviar. */
  aguardandoAoFechar: number;
}

const ESGOTOU = Symbol('esgotou');

/**
 * Antes de instalar a atualização do Corona Vendas (tarefa 5.13; ADR-0013, item 17; ADR-0009, item 3):
 *
 * 1. para o motor (nenhum ciclo novo começa);
 * 2. espera o envio em andamento terminar, no máximo 30 s;
 * 3. se ele terminou e ainda há o que enviar, tenta mais um envio (só o envio, sem receber), também no máximo 30 s;
 * 4. fecha o banco limpo.
 *
 * A outbox atravessa a atualização no próprio arquivo do banco: se um envio ficar no meio (o tempo acabou, a rede
 * caiu, o processo morreu), a operação continua AGUARDANDO e a versão nova manda de novo; o servidor responde
 * JA_RECEBIDO pelo id, sem duplicar. Por isso nenhum erro aqui impede de fechar o banco e instalar. Pedido duas vezes,
 * prepara uma vez só.
 */
export class PreparoDaAtualizacao {
  private feito: Promise<ResultadoDoPreparo> | null = null;

  constructor(private readonly o: OpcoesDoPreparo) {}

  preparar(): Promise<ResultadoDoPreparo> {
    this.feito ??= this.executar();
    return this.feito;
  }

  private async executar(): Promise<ResultadoDoPreparo> {
    const r: ResultadoDoPreparo = { esperou: 'nada', maisUm: 'nao-tentou', aguardandoAoFechar: 0 };
    try {
      this.o.motor.desligar();
      const emCurso = this.o.motor.envioEmAndamento();
      if (emCurso) {
        const fim = await this.comTeto(emCurso);
        r.esperou = fim === ESGOTOU ? 'tempo-esgotado' : 'terminou';
      }
      // Se o envio anterior ainda está no ar, outro não começa (um por vez): segue para fechar.
      if (r.esperou !== 'tempo-esgotado') {
        if (this.o.motor.aguardandoEnvio() === 0) {
          r.maisUm = 'nao-precisou';
        } else {
          const fim = await this.comTeto(this.o.motor.enviarAntesDeFechar());
          r.maisUm = fim === ESGOTOU ? 'tempo-esgotado' : fim;
        }
      }
    } catch (erro) {
      this.o.registrar?.(
        `Preparo da atualização: ${erro instanceof Error ? erro.message : String(erro)}`,
      );
    }
    try {
      r.aguardandoAoFechar = this.o.motor.aguardandoEnvio();
    } catch {
      /* só para o registro */
    }
    try {
      this.o.fecharBanco();
    } catch (erro) {
      this.o.registrar?.(
        `Preparo da atualização, ao fechar o banco: ${erro instanceof Error ? erro.message : String(erro)}`,
      );
    }
    return r;
  }

  /** A promessa ou o fim do prazo, o que vier primeiro (erro da promessa conta como fim). */
  private comTeto<T>(p: Promise<T>): Promise<T | typeof ESGOTOU | 'falha'> {
    return new Promise((resolver) => {
      const cancelar = this.o.relogio.agendar(
        this.o.esperaMaximaMs ?? ESPERA_MAXIMA_DO_ENVIO_MS,
        () => resolver(ESGOTOU),
      );
      p.then(
        (v) => {
          cancelar();
          resolver(v);
        },
        () => {
          cancelar();
          resolver('falha');
        },
      );
    });
  }
}

/** O pedaço do `app` do Electron que o fechamento usa (facilita o teste). */
export interface AppQueFecha {
  on(evento: 'before-quit', ouvinte: (e: { preventDefault(): void }) => void): unknown;
  quit(): void;
}

/**
 * Segura o fechamento do Corona Vendas quando há versão nova baixada (o `electron-updater` instala ao fechar,
 * `autoInstallOnAppQuit`): primeiro o preparo, depois fecha de verdade e o instalador roda. Sem versão nova, fecha
 * como sempre.
 */
export function prepararAntesDeInstalar(
  app: AppQueFecha,
  preparo: { preparar(): Promise<unknown> },
  haVersaoNova: () => boolean,
): void {
  let liberado = false;
  let preparando = false;
  app.on('before-quit', (e) => {
    if (liberado || !haVersaoNova()) return;
    e.preventDefault();
    if (preparando) return;
    preparando = true;
    void preparo
      .preparar()
      .catch(() => undefined)
      .finally(() => {
        liberado = true;
        app.quit();
      });
  });
}

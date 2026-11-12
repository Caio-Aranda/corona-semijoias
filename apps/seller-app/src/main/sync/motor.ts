import type { BancoAberto } from '../banco/banco-local';
import { type EstadoDaSincronizacao, limparEstadoDaSincronizacao } from '../../shared/ponte';
import type { Relogio } from '../tipos';
import { Agenda } from './agenda';
import {
  CicloDeSincronizacao,
  type ComToken,
  type OrdemDoServidor,
  TENTATIVAS_PARA_AVISAR,
} from './ciclo';
import { CHAVES, lerEstado } from './estado-sync';
import type { TransporteDoSync } from './transporte-do-sync';

export interface OpcoesDoMotor {
  banco: () => BancoAberto | null;
  transporte: TransporteDoSync;
  comToken: ComToken;
  relogio: Relogio;
  sorteio?: () => number;
  /** O estado que a tela mostra mudou (já limpo: só contadores, sim/não e datas). */
  aoMudarEstado?: (estado: EstadoDaSincronizacao) => void;
  /** Ponto de extensão da tarefa 5.8: o servidor mandou parar (apagar dados, app desatualizado, sessão). */
  aoReceberOrdem?: (ordem: OrdemDoServidor) => void;
  registrar?: (mensagem: string) => void;
}

/**
 * O motor de envio e recebimento do Corona Vendas, no processo principal (tarefa 5.6). Junta o ciclo e a agenda e
 * calcula o único estado que atravessa a ponte. Só liga com o banco aberto e a sessão válida; desliga ao sair, ao
 * perder a sessão e por ordem do servidor.
 */
export class MotorDeSincronizacao {
  private readonly ciclo: CicloDeSincronizacao;
  private readonly agenda: Agenda;
  private ultimoEstado = '';

  constructor(private readonly o: OpcoesDoMotor) {
    this.ciclo = new CicloDeSincronizacao({
      banco: o.banco,
      transporte: o.transporte,
      comToken: o.comToken,
      agora: () => new Date(o.relogio.agora()),
      esperar: (ms) =>
        new Promise((r) => {
          o.relogio.agendar(ms, r);
        }),
      aoMudar: () => this.avisar(),
      aoReceberOrdem: (ordem) => o.aoReceberOrdem?.(ordem),
      ...(o.registrar ? { registrar: o.registrar } : {}),
    });
    this.agenda = new Agenda({
      executar: () => this.ciclo.executar(),
      relogio: o.relogio,
      ...(o.sorteio ? { sorteio: o.sorteio } : {}),
      aoParar: () => this.avisar(),
    });
  }

  get ligado(): boolean {
    return this.agenda.estaLigada;
  }

  /** Liga se o banco estiver aberto (a sessão válida é conferida por quem chama). */
  ligar(): void {
    if (!this.o.banco()) return;
    this.agenda.ligar();
    this.avisar();
  }

  desligar(): void {
    this.agenda.desligar();
  }

  /** Avisa a tela se o estado mudou (por exemplo, depois do "Entendi"). */
  atualizarEstado(): void {
    this.avisar();
  }

  /** Uma operação foi gravada no computador. */
  gravou(): void {
    this.agenda.gravou();
    this.avisar();
  }

  internetVoltou(): void {
    this.agenda.internetVoltou();
  }

  /** O estado para a tela: só contadores, sim/não e datas. */
  estado(): EstadoDaSincronizacao {
    const db = this.o.banco();
    const base = {
      comInternet: this.ciclo.comInternet,
      enviando: this.ciclo.enviando,
    };
    if (!db) return limparEstadoDaSincronizacao(base);
    const contagem = db
      .prepare(
        `SELECT
           sum(situacao = 'AGUARDANDO') AS aguardando,
           sum(situacao = 'AGUARDANDO' AND tipo = 'VENDA') AS vendas,
           sum(situacao IN ('RECUSADO', 'CONFLITO') AND entendido_em IS NULL) AS recusadas,
           sum(situacao = 'AGUARDANDO' AND tentativas >= ?) AS presas
         FROM outbox`,
      )
      .get(TENTATIVAS_PARA_AVISAR) as {
      aguardando: number | null;
      vendas: number | null;
      recusadas: number | null;
      presas: number | null;
    };
    return limparEstadoDaSincronizacao({
      ...base,
      aguardando: contagem.aguardando ?? 0,
      vendasAguardando: contagem.vendas ?? 0,
      recusadas: contagem.recusadas ?? 0,
      dadosAte: lerEstado(db, CHAVES.dadosAte),
      naoConseguiuEnviar: (contagem.presas ?? 0) > 0,
    });
  }

  private avisar(): void {
    if (!this.o.aoMudarEstado) return;
    const e = this.estado();
    const texto = JSON.stringify(e);
    if (texto === this.ultimoEstado) return;
    this.ultimoEstado = texto;
    this.o.aoMudarEstado(e);
  }
}

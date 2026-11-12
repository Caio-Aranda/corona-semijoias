import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import type { BancoAberto } from '../banco/banco-local';
import { lerAguardando, type LinhaDaOutbox } from '../sync/outbox';
import { MAXIMO_DE_OPERACOES, montarLote } from '../sync/ciclo';
import type { RespostaDoServidor } from '../sync/transporte-do-sync';
import type { Relogio, Resultado } from '../tipos';

/**
 * Quarentena e apagar os dados do computador desligado pela dona (tarefa 5.8; ADR-0013, item 14).
 *
 * Ordem: o servidor mandou apagar → se há vendas ou outros registros aguardando envio, mandam-se para a quarentena do
 * servidor (que guarda sem aplicar) → só depois da resposta definitiva (ou de 3 tentativas **espaçadas** sem resposta) →
 * apaga: primeiro o arquivo da chave (sem ele o banco é ilegível), depois o banco, o WAL, as fotos, o token e o
 * identificador do computador.
 *
 * O estado fica numa marca em disco (`apagando.json`): se o aplicativo fechar no meio, ao abrir **retoma de onde
 * estava**, sem zerar a contagem de tentativas, e termina de apagar. A marca é o último arquivo a sair.
 */

/** Esperas antes da 1ª, da 2ª e da 3ª tentativa de quarentena: uma queda de rede passageira não pode levar a apagar. */
export const ESPERAS_DA_QUARENTENA_MS: readonly number[] = Object.freeze([30_000, 120_000, 600_000]);
export const TENTATIVAS_DA_QUARENTENA = ESPERAS_DA_QUARENTENA_MS.length;

export type FaseDoApagar = 'QUARENTENA' | 'APAGANDO';

export interface MarcaDoApagar {
  versao: 1;
  fase: FaseDoApagar;
  /** Tentativas de quarentena já feitas sem resposta definitiva. */
  tentativas: number;
  /** Quando a próxima tentativa pode acontecer (hora do computador, ISO). */
  proximaTentativaEm: string | null;
  iniciadoEm: string;
}

/** O que a tela precisa saber (sem contar nada do banco). */
export type EstadoDoApagar = 'NENHUM' | 'ENVIANDO' | 'APAGADO';

export type RespostaDaQuarentena = 'DEFINITIVA' | 'SEM_RESPOSTA';

/** Onde estão os arquivos do computador. Tudo isto some no fim. */
export interface LugaresParaApagar {
  /** O arquivo da chave do banco (o primeiro a sair). */
  chave: string;
  /** O banco; o WAL e o `-shm` ficam ao lado (`<banco>-wal`, `<banco>-shm`). */
  banco: string;
  /** A pasta de dados inteira (cópias antes de migrar, bancos `.ilegivel`...). */
  pastaDeDados: string;
  /** Pastas de fotos. */
  pastasDeFotos: string[];
  /** O token de renovação, o identificador e o último contato. */
  token: string;
  identificador: string;
  ultimoContato: string;
  /** A marca de que o apagar está em andamento (o último arquivo a sair). */
  marca: string;
  /** Pastas que ficam vazias (do token e do identificador): saem junto, no fim. */
  pastasDeApoio: string[];
}

export interface OpcoesDoApagador {
  lugares: LugaresParaApagar;
  /** O banco aberto agora, ou `null`. */
  banco: () => BancoAberto | null;
  /** Abre o banco só para ler o que aguarda envio (pelo cofre do Windows; o computador já foi desligado). */
  abrirBancoParaApagar: () => Promise<BancoAberto | null>;
  fecharBanco: () => void;
  /** Envia o corpo à quarentena do servidor. `corpo` já leva o token e o identificador. */
  enviarParaQuarentena: (corpo: string) => Promise<Resultado<RespostaDoServidor>>;
  /** O token de renovação (já revogado) e o identificador deste computador. */
  credenciais: () => Promise<{ token: string; dispositivoId: string } | null>;
  relogio: Relogio;
  esperas?: readonly number[];
  remover?: (caminho: string) => Promise<void>;
  aoMudar?: (estado: EstadoDoApagar) => void;
  registrar?: (mensagem: string) => void;
}

export class ApagadorDeDados {
  private estado: EstadoDoApagar = 'NENHUM';
  private rodando: Promise<void> | null = null;
  private readonly esperas: readonly number[];

  constructor(private readonly o: OpcoesDoApagador) {
    this.esperas = o.esperas ?? ESPERAS_DA_QUARENTENA_MS;
  }

  get situacao(): EstadoDoApagar {
    return this.estado;
  }

  /** O servidor mandou apagar. Repetir enquanto está em andamento não faz nada de novo. */
  receberOrdem(): Promise<void> {
    if (this.estado === 'APAGADO') return Promise.resolve();
    return (this.rodando ??= this.rodar(true).finally(() => {
      this.rodando = null;
    }));
  }

  /** Ao abrir o aplicativo: se havia um apagar pela metade, continua. */
  async retomar(): Promise<void> {
    if (this.rodando) return this.rodando;
    if (!(await this.lerMarca())) return;
    return (this.rodando ??= this.rodar(false).finally(() => {
      this.rodando = null;
    }));
  }

  async emAndamento(): Promise<boolean> {
    return (await this.lerMarca()) !== null;
  }

  // --- o fluxo ---

  private async rodar(nova: boolean): Promise<void> {
    let marca = await this.lerMarca();
    if (!marca) {
      if (!nova) return;
      marca = {
        versao: 1,
        fase: 'QUARENTENA',
        tentativas: 0,
        proximaTentativaEm: new Date(this.o.relogio.agora() + (this.esperas[0] ?? 0)).toISOString(),
        iniciadoEm: new Date(this.o.relogio.agora()).toISOString(),
      };
      await this.gravarMarca(marca);
    }
    this.mudar('ENVIANDO');
    if (marca.fase === 'QUARENTENA') {
      marca = await this.quarentena(marca);
    }
    await this.apagar();
    this.mudar('APAGADO');
  }

  private async quarentena(inicial: MarcaDoApagar): Promise<MarcaDoApagar> {
    let marca = inicial;
    const db = this.o.banco() ?? (await this.o.abrirBancoParaApagar().catch(() => null));
    const fila: LinhaDaOutbox[] = db ? lerAguardando(db, Number.MAX_SAFE_INTEGER) : [];
    if (fila.length === 0) {
      // Nada aguardando (ou o banco nem abre): não há o que guardar.
      marca = { ...marca, fase: 'APAGANDO', proximaTentativaEm: null };
      await this.gravarMarca(marca);
      return marca;
    }
    while (marca.tentativas < TENTATIVAS_DA_QUARENTENA) {
      await this.esperarAte(marca);
      this.o.registrar?.(`Quarentena: tentativa ${marca.tentativas + 1} de ${TENTATIVAS_DA_QUARENTENA}.`);
      const resposta = await this.enviar(fila);
      if (resposta === 'DEFINITIVA') break;
      const feitas = marca.tentativas + 1;
      marca = {
        ...marca,
        tentativas: feitas,
        proximaTentativaEm:
          feitas < TENTATIVAS_DA_QUARENTENA
            ? new Date(this.o.relogio.agora() + (this.esperas[feitas] ?? 0)).toISOString()
            : null,
      };
      await this.gravarMarca(marca);
    }
    marca = { ...marca, fase: 'APAGANDO', proximaTentativaEm: null };
    await this.gravarMarca(marca);
    return marca;
  }

  /** Espera até a hora da próxima tentativa, nunca mais que a espera prevista (relógio mexido não trava o apagar). */
  private async esperarAte(marca: MarcaDoApagar): Promise<void> {
    if (!marca.proximaTentativaEm) return;
    const previsto = this.esperas[Math.min(marca.tentativas, this.esperas.length - 1)] ?? 0;
    const falta = Date.parse(marca.proximaTentativaEm) - this.o.relogio.agora();
    const espera = Math.min(Math.max(0, Number.isNaN(falta) ? 0 : falta), previsto);
    if (espera <= 0) return;
    await new Promise<void>((r) => {
      this.o.relogio.agendar(espera, r);
    });
  }

  /** Manda tudo, em lotes. `DEFINITIVA` = o servidor respondeu (aceitou ou recusou); o resto, sem resposta. */
  private async enviar(fila: LinhaDaOutbox[]): Promise<RespostaDaQuarentena> {
    const credenciais = await this.o.credenciais();
    // Sem token nem identificador não há como falar com o servidor: não há quem responder.
    if (!credenciais) return 'DEFINITIVA';
    let restantes = fila;
    let limite = MAXIMO_DE_OPERACOES;
    while (restantes.length > 0) {
      const lote = montarLote(restantes.slice(0, limite), new Date(this.o.relogio.agora()), restantes.length);
      if (!lote) break;
      const cabecalho = `"tokenDeRenovacao":${JSON.stringify(credenciais.token)},"dispositivoId":${JSON.stringify(credenciais.dispositivoId)},`;
      const r = await this.o.enviarParaQuarentena(`{${cabecalho}${lote.corpo.slice(1)}`);
      if (!r.ok) return 'SEM_RESPOSTA';
      const { status } = r.dados;
      if (status === 200) {
        restantes = restantes.slice(lote.operacoes.length);
        continue;
      }
      if (status === 413) {
        if (lote.operacoes.length > 1) {
          limite = Math.max(1, Math.floor(lote.operacoes.length / 2));
          continue;
        }
        return 'DEFINITIVA'; // uma operação sozinha grande demais: o servidor nunca vai aceitar
      }
      // 429 e erros do servidor são passageiros; as outras recusas (sessão, token, prazo de 30 dias) são a resposta.
      if (status === 429 || status >= 500) return 'SEM_RESPOSTA';
      return 'DEFINITIVA';
    }
    return 'DEFINITIVA';
  }

  // --- apagar ---

  /** Chave primeiro; depois o banco, o WAL, o resto da pasta, as fotos, o token e o identificador; a marca por último. */
  private async apagar(): Promise<void> {
    const l = this.o.lugares;
    const tirar = this.o.remover ?? ((c: string) => rm(c, { recursive: true, force: true }));
    await tirar(l.chave);
    this.o.fecharBanco();
    for (const arquivo of [l.banco, `${l.banco}-wal`, `${l.banco}-shm`, `${l.banco}-journal`]) {
      await tirar(arquivo);
    }
    await tirar(l.pastaDeDados);
    for (const pasta of l.pastasDeFotos) await tirar(pasta);
    await tirar(l.token);
    await tirar(l.identificador);
    await tirar(l.ultimoContato);
    await tirar(l.marca);
    for (const pasta of l.pastasDeApoio) await tirar(pasta);
  }

  // --- a marca ---

  private async lerMarca(): Promise<MarcaDoApagar | null> {
    try {
      const m = JSON.parse(await readFile(this.o.lugares.marca, 'utf8')) as Partial<MarcaDoApagar>;
      if (
        m.versao === 1 &&
        (m.fase === 'QUARENTENA' || m.fase === 'APAGANDO') &&
        typeof m.tentativas === 'number' &&
        Number.isInteger(m.tentativas) &&
        m.tentativas >= 0
      ) {
        return {
          versao: 1,
          fase: m.fase,
          tentativas: m.tentativas,
          proximaTentativaEm: typeof m.proximaTentativaEm === 'string' ? m.proximaTentativaEm : null,
          iniciadoEm: typeof m.iniciadoEm === 'string' ? m.iniciadoEm : new Date(0).toISOString(),
        };
      }
    } catch (erro) {
      if ((erro as NodeJS.ErrnoException).code !== 'ENOENT') {
        // Marca estragada: não dá para saber onde parou; apagar é o lado seguro (os dados já foram desligados).
        return { versao: 1, fase: 'APAGANDO', tentativas: TENTATIVAS_DA_QUARENTENA, proximaTentativaEm: null, iniciadoEm: new Date(0).toISOString() };
      }
    }
    return null;
  }

  private async gravarMarca(marca: MarcaDoApagar): Promise<void> {
    const caminho = this.o.lugares.marca;
    await mkdir(dirname(caminho), { recursive: true });
    const temporario = `${caminho}.novo`;
    await writeFile(temporario, JSON.stringify(marca), { mode: 0o600 });
    await rename(temporario, caminho);
  }

  private mudar(e: EstadoDoApagar): void {
    if (this.estado === e) return;
    this.estado = e;
    this.o.aoMudar?.(e);
  }
}

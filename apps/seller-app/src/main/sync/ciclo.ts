import { createHash } from 'node:crypto';
import { type BancoAberto, gravar } from '../banco/banco-local';
import { TABELAS_SINCRONIZAVEIS } from '../banco/migracoes';
import type { Resultado } from '../tipos';
import { aplicarLote, LoteRecusado } from './aplicador-do-pull';
import { ehTabelaSincronizavel, type TabelaSincronizavel } from './colunas';
import { CHAVES, gravarEstado, lerEstado, registrarContato } from './estado-sync';
import {
  desfazerOperacao,
  type LinhaDaOutbox,
  lerAguardando,
  limparOutboxAntiga,
  limparProvisoriasEnviadas,
} from './outbox';
import type { RespostaDoServidor, TransporteDoSync } from './transporte-do-sync';

/** Limites do push (ADR-0013, item 2; o servidor recusa acima disso com 413). */
export const MAXIMO_DE_OPERACOES = 50;
export const MAXIMO_DE_BYTES = 512 * 1024;
/** Depois de 5 `NAO_PROCESSADO` seguidos na mesma operação, a tela avisa (sem nunca pular a operação). */
export const TENTATIVAS_PARA_AVISAR = 5;
/** Linhas por página do pull (padrão do servidor). */
export const LIMITE_DO_PULL = 500;
/** Conferência por resumo: uma vez por dia (ADR-0013, item 6). */
export const INTERVALO_DA_CONFERENCIA_MS = 24 * 60 * 60 * 1000;
/** Defesa contra laço sem fim (servidor com defeito): páginas por recebimento e esperas de 429 por ciclo. */
const MAXIMO_DE_PAGINAS = 10_000;
const MAXIMO_DE_ESPERAS = 3;

export type DesfechoDoCiclo =
  /** Enviou o que havia e recebeu tudo. */
  | 'ok'
  /** A rota de saúde não respondeu: nenhuma outra rota foi chamada. */
  | 'sem-internet'
  /** Algo não deu certo (rede, servidor, sessão, uma operação parada): tenta de novo com espera crescente. */
  | 'falha'
  /** O servidor mandou parar (apagar os dados, app desatualizado, sessão encerrada): o motor desliga. */
  | 'parado';

/** Ordens que param o motor. Quem trata é a tarefa 5.8 (apagar e bloqueio) e a sessão (entrar de novo). */
export type OrdemDoServidor = 'apagar-dados' | 'app-desatualizado' | 'sessao-terminou';

export type ComToken = <T>(
  chamada: (token: string) => Promise<Resultado<T>>,
) => Promise<Resultado<T>>;

export interface OpcoesDoCiclo {
  banco: () => BancoAberto | null;
  transporte: TransporteDoSync;
  /** A guarda de tokens: renova uma vez por vez (fila única) e repete uma vez no 401. */
  comToken: ComToken;
  agora?: () => Date;
  esperar?: (ms: number) => Promise<void>;
  /** Mudou algo que a tela mostra (com internet, enviando, contadores). */
  aoMudar?: () => void;
  /** Ponto de extensão: o servidor mandou parar (tarefa 5.8). */
  aoReceberOrdem?: (ordem: OrdemDoServidor) => void;
  /** Para os testes: linhas por página do pull. */
  limiteDoPull?: number;
  /** Para os testes: registro técnico (nunca vai para a tela). */
  registrar?: (mensagem: string) => void;
}

type Etapa = 'ok' | 'falha' | 'parado';

/**
 * Um ciclo do motor (ADR-0013, itens 2, 3, 6 e 19): primeiro envia, depois recebe tudo, depois confere quando é a
 * hora. Um ciclo por vez: quem pede um ciclo enquanto outro roda recebe o mesmo resultado.
 */
export class CicloDeSincronizacao {
  private rodando: Promise<DesfechoDoCiclo> | null = null;
  private _comInternet: boolean | null = null;
  private _enviando = false;
  private _desatualizado = false;

  constructor(private readonly o: OpcoesDoCiclo) {}

  /**
   * O último envio voltou com `appDesatualizado` (tarefa 5.13): este app está abaixo da versão mínima. O envio continua;
   * o recebimento e a conferência não são pedidos (dariam 426 e parariam o motor, prendendo as vendas novas).
   */
  get desatualizado(): boolean {
    return this._desatualizado;
  }

  /** O ciclo em andamento (para o preparo da atualização esperar), ou `null`. */
  get emCurso(): Promise<DesfechoDoCiclo> | null {
    return this.rodando;
  }

  /**
   * Só o envio, sem receber nem conferir (tarefa 5.13): o "mais um envio" antes de instalar a atualização. Se um ciclo
   * já roda, devolve o mesmo resultado (um por vez).
   */
  enviarSo(): Promise<DesfechoDoCiclo> {
    this.rodando ??= this.rodarSoEnvio().finally(() => {
      this.rodando = null;
    });
    return this.rodando;
  }

  private async rodarSoEnvio(): Promise<DesfechoDoCiclo> {
    const db = this.o.banco();
    if (!db) return 'falha';
    const comInternet = await this.o.transporte.saude();
    this.mudar(() => {
      this._comInternet = comInternet;
    });
    if (!comInternet) return 'sem-internet';
    try {
      this.mudar(() => {
        this._enviando = true;
      });
      return await this.enviarTudo(db);
    } catch (erro) {
      this.registrar(`envio interrompido: ${erro instanceof Error ? erro.name : 'erro'}`);
      return 'falha';
    } finally {
      this.mudar(() => {
        this._enviando = false;
      });
    }
  }

  get comInternet(): boolean | null {
    return this._comInternet;
  }

  get enviando(): boolean {
    return this._enviando;
  }

  get emAndamento(): boolean {
    return this.rodando !== null;
  }

  executar(): Promise<DesfechoDoCiclo> {
    this.rodando ??= this.rodar().finally(() => {
      this.rodando = null;
    });
    return this.rodando;
  }

  // --- o ciclo ---

  private async rodar(): Promise<DesfechoDoCiclo> {
    const db = this.o.banco();
    if (!db) return 'falha';
    const comInternet = await this.o.transporte.saude();
    this.mudar(() => {
      this._comInternet = comInternet;
    });
    if (!comInternet) return 'sem-internet';
    try {
      this.mudar(() => {
        this._enviando = true;
      });
      const envio = await this.enviarTudo(db);
      if (envio === 'parado') return 'parado';
      // Abaixo da versão mínima: só envia (o pull daria 426). Os dados novos chegam depois de atualizar.
      if (this._desatualizado) return envio;
      const recebimento = await this.receberTudo(db);
      // O recebimento respondeu 426 (app abaixo da versão mínima, nada a enviar): segue só enviando.
      if (this._desatualizado) return envio;
      if (recebimento !== 'ok') return recebimento;
      const dirigida = await this.recargaDirigida(db);
      if (dirigida !== 'ok') return dirigida;
      const conferencia = await this.conferirSeForAHora(db);
      if (conferencia !== 'ok') return conferencia;
      return envio;
    } catch (erro) {
      this.registrar(`ciclo interrompido: ${erro instanceof Error ? erro.name : 'erro'}`);
      return 'falha';
    } finally {
      this.mudar(() => {
        this._enviando = false;
      });
    }
  }

  // --- envio (push) ---

  private async enviarTudo(db: BancoAberto): Promise<Etapa> {
    let limite = MAXIMO_DE_OPERACOES;
    let esperas = 0;
    for (;;) {
      const lote = montarLote(lerAguardando(db, limite), this.agora(), contarAguardando(db));
      if (!lote) return 'ok';
      const r = await this.o.comToken((t) => this.o.transporte.enviar(t, lote.corpo));
      if (!r.ok) return this.falhaDaChamada(r.falha);
      const { status, corpo } = r.dados;
      if (status === 200) {
        const parou = this.aplicarResultados(db, lote.operacoes, corpo);
        this._desatualizado = ehObjeto(corpo) && corpo['appDesatualizado'] === true;
        this.o.aoMudar?.();
        if (ehObjeto(corpo) && corpo['apagarDados'] === true) {
          // Computador trocado (ADR-0013, item 13; issue #127): o servidor só sabe o `aguardandoDepoisDeste` de quando o
          // lote foi montado. O vendedor pode ter gravado mais durante o envio: só obedece com a fila zerada AGORA.
          // A conferência e a ordem (que põe o computador em "desligando" e para as gravações) rodam no mesmo trecho
          // síncrono, sem `await` no meio: nenhuma gravação da tela entra entre as duas.
          const aguardando = contarAguardando(db);
          if (aguardando === 0) return this.ordem('apagar-dados');
          this.registrar(`ordem de apagar adiada: ${aguardando} aguardando`);
        }
        if (parou) return 'falha';
        continue;
      }
      if (status === 413) {
        if (lote.operacoes.length > 1) {
          limite = Math.max(1, Math.floor(lote.operacoes.length / 2));
          continue;
        }
        // Uma operação sozinha grande demais: conta a tentativa e avisa depois de 5; nunca pula.
        gravar(db, () => contarTentativa(db, lote.operacoes[0], 'LOTE_GRANDE_DEMAIS'));
        this.o.aoMudar?.();
        return 'falha';
      }
      if (status === 429 && esperas < MAXIMO_DE_ESPERAS) {
        esperas += 1;
        await this.esperar((r.dados.esperarSegundos ?? 5) * 1000);
        continue;
      }
      return this.falhaDoStatus(r.dados);
    }
  }

  /**
   * Aplica os resultados do lote numa transação só: a situação na outbox e, no RECUSADO e no CONFLITO, o desfazer do
   * efeito local (a provisória sai e o saldo volta) entram juntos. Só muda operação que ainda está AGUARDANDO: a mesma
   * resposta chegando de novo não faz nada. Operação sem resposta fica como está. Devolve se o envio parou.
   */
  private aplicarResultados(
    db: BancoAberto,
    enviadas: readonly LinhaDaOutbox[],
    corpo: unknown,
  ): boolean {
    const doLote = new Set(enviadas.map((e) => e.operacao_id));
    const resultados =
      ehObjeto(corpo) && Array.isArray(corpo['resultados']) ? corpo['resultados'] : [];
    const agora = this.agora().toISOString();
    return gravar(db, () => {
      const respondidas = new Set<string>();
      let parou = false;
      for (const bruto of resultados) {
        if (!ehObjeto(bruto)) continue;
        const id = bruto['operacaoId'];
        const resultado = bruto['resultado'];
        if (typeof id !== 'string' || !doLote.has(id) || respondidas.has(id)) continue;
        respondidas.add(id);
        const codigo = typeof bruto['codigo'] === 'string' ? bruto['codigo'] : null;
        const mensagem = typeof bruto['mensagem'] === 'string' ? bruto['mensagem'] : null;
        const atual = db.prepare('SELECT situacao FROM outbox WHERE operacao_id = ?').get(id) as
          { situacao: string } | undefined;
        if (atual?.situacao !== 'AGUARDANDO') continue;
        switch (resultado) {
          case 'ACEITO':
          case 'JA_RECEBIDO':
            marcar(db, id, 'ENVIADO', resultado, null, agora);
            break;
          case 'CONFLITO':
          case 'RECUSADO':
            desfazerOperacao(db, id);
            marcar(db, id, resultado, codigo ?? resultado, mensagem, agora);
            break;
          case 'NAO_PROCESSADO':
            if (!parou) {
              contarTentativa(
                db,
                enviadas.find((e) => e.operacao_id === id),
                codigo ?? 'NAO_PROCESSADO',
              );
            }
            parou = true;
            break;
          default:
            parou = true;
        }
      }
      if (respondidas.size < doLote.size) parou = true;
      const hora = ehObjeto(corpo) ? corpo['horaServidor'] : null;
      if (typeof hora === 'string') registrarContato(db, hora, this.agora());
      guardarParametros(db, corpo);
      return parou;
    });
  }

  // --- recebimento (pull) ---

  private async receberTudo(db: BancoAberto): Promise<Etapa> {
    let recarregou = false;
    let dadosAte: string | null = null;
    for (let pagina = 0; pagina < MAXIMO_DE_PAGINAS; pagina++) {
      const cursor = lerEstado(db, CHAVES.cursor);
      if (cursor === null) gravar(db, () => gravarEstado(db, CHAVES.conferirDepoisDaCarga, '1'));
      const r = await this.o.comToken((t) =>
        this.o.transporte.receber(t, { cursor, limite: this.limiteDoPull() }),
      );
      if (!r.ok) return this.falhaDaChamada(r.falha, 'recebimento');
      const { status, corpo } = r.dados;
      if (status === 409 && codigoDe(corpo) === 'CURSOR_INVALIDO' && !recarregou) {
        recarregou = true;
        recargaPorEpoca(db);
        continue;
      }
      if (status !== 200) return this.falhaDoStatus(r.dados);
      const pagina200 = lerPagina(corpo);
      if (!pagina200) return 'falha';
      dadosAte ??= pagina200.horaServidor;
      try {
        // Cursor nulo do servidor: o anterior fica (`cursor: null` não mexe), nunca texto vazio.
        aplicarLote(db, pagina200.linhas, { cursor: pagina200.cursor || null });
      } catch (erro) {
        if (erro instanceof LoteRecusado) {
          this.registrar(erro.message);
          return 'falha';
        }
        throw erro;
      }
      gravar(db, () => {
        registrarContato(db, pagina200.horaServidor, this.agora());
        guardarParametros(db, corpo);
      });
      if (!pagina200.temMais) {
        const ate = dadosAte;
        gravar(db, () => {
          gravarEstado(db, CHAVES.dadosAte, ate);
          limparProvisoriasEnviadas(db);
          limparOutboxAntiga(db, this.agora());
        });
        this.o.aoMudar?.();
        return 'ok';
      }
    }
    return 'falha';
  }

  /** Recarga de UMA tabela (`?tabela=`, cursor próprio): as cópias do servidor dela saem e vêm de novo. */
  private async recarregarTabela(db: BancoAberto, tabela: TabelaSincronizavel): Promise<Etapa> {
    // O aviso de conferir de novo entra na MESMA transação do apagar: se o app cair antes de a tabela voltar, a
    // próxima rodada confere e recarrega.
    gravar(db, () => {
      db.prepare(`DELETE FROM ${tabela} WHERE origem_local = 'SERVIDOR'`).run();
      gravarEstado(db, CHAVES.conferirDepoisDaCarga, '1');
    });
    let cursor: string | null = null;
    for (let pagina = 0; pagina < MAXIMO_DE_PAGINAS; pagina++) {
      const atual: string | null = cursor;
      const r = await this.o.comToken((t) =>
        this.o.transporte.receber(t, { cursor: atual, limite: this.limiteDoPull(), tabela }),
      );
      if (!r.ok) return this.falhaDaChamada(r.falha, 'recebimento');
      if (r.dados.status !== 200) return this.falhaDoStatus(r.dados);
      const p = lerPagina(r.dados.corpo);
      if (!p) return 'falha';
      try {
        aplicarLote(db, p.linhas, { cursor: null });
      } catch (erro) {
        if (erro instanceof LoteRecusado) return 'falha';
        throw erro;
      }
      if (!p.temMais) return 'ok';
      cursor = p.cursor;
    }
    return 'falha';
  }

  // --- recarga dirigida (migração local 3, tarefa 6.7) ---

  /**
   * Depois da migração local 3, só em banco que já tinha cursor: as tabelas da marca vêm de novo pelo `?tabela=`, uma por
   * vez, substituindo também a versão igual e SEM apagar nada antes (a maleta nunca mostra saldo errado no meio, nem sem
   * internet). A tabela sai da marca na transação da última página dela; uma queda no meio recomeça aquela tabela no
   * próximo ciclo, o que é inofensivo pela regra da versão.
   */
  private async recargaDirigida(db: BancoAberto): Promise<Etapa> {
    for (;;) {
      const pendentes = tabelasDaRecargaDirigida(db);
      const tabela = pendentes[0];
      if (tabela === undefined) return 'ok';
      let cursor: string | null = null;
      let terminou = false;
      for (let pagina = 0; pagina < MAXIMO_DE_PAGINAS && !terminou; pagina++) {
        const atual: string | null = cursor;
        const r = await this.o.comToken((t) =>
          this.o.transporte.receber(t, { cursor: atual, limite: this.limiteDoPull(), tabela }),
        );
        if (!r.ok) return this.falhaDaChamada(r.falha, 'recebimento');
        if (r.dados.status !== 200) return this.falhaDoStatus(r.dados);
        const p = lerPagina(r.dados.corpo);
        if (!p) return 'falha';
        try {
          aplicarLote(db, p.linhas, {
            cursor: null,
            substituirIgual: true,
            naMesmaTransacao: p.temMais
              ? undefined
              : () => gravarTabelasDaRecargaDirigida(db, tabelasDaRecargaDirigida(db).filter((t) => t !== tabela)),
          });
        } catch (erro) {
          if (erro instanceof LoteRecusado) {
            this.registrar(erro.message);
            return 'falha';
          }
          throw erro;
        }
        terminou = !p.temMais;
        cursor = p.cursor;
      }
      if (!terminou) return 'falha';
      this.o.aoMudar?.();
    }
  }

  // --- conferência por resumo ---

  private async conferirSeForAHora(db: BancoAberto): Promise<Etapa> {
    const depoisDaCarga = lerEstado(db, CHAVES.conferirDepoisDaCarga) === '1';
    const ultima = lerEstado(db, CHAVES.conferidoEm);
    const horaServidor = lerEstado(db, CHAVES.horaServidor);
    const referencia = horaServidor ? Date.parse(horaServidor) : this.agora().getTime();
    const vencida = !ultima || referencia - Date.parse(ultima) >= INTERVALO_DA_CONFERENCIA_MS;
    if (!depoisDaCarga && !vencida) return 'ok';

    const primeira = await this.diferencas(db);
    if (primeira === null) return 'falha';
    let persistentes: TabelaSincronizavel[] = [];
    if (primeira.length > 0) {
      // Pode ser só o que mudou entre receber e conferir: recebe o que faltou e confere de novo.
      const de_novo = await this.receberTudo(db);
      if (de_novo !== 'ok') return de_novo;
      const segunda = await this.diferencas(db);
      if (segunda === null) return 'falha';
      persistentes = primeira.filter((t) => segunda.includes(t));
    }
    if (persistentes.length > 0) {
      // Defeito do filtro (ADR-0013, item 6): o servidor registra; o computador recarrega só aquelas tabelas.
      await this.o.comToken((t) => this.o.transporte.avisarDiferencas(t, persistentes));
      for (const tabela of persistentes) {
        const r = await this.recarregarTabela(db, tabela);
        if (r !== 'ok') return r;
      }
    }
    const conferidoEm = lerEstado(db, CHAVES.horaServidor) ?? this.agora().toISOString();
    gravar(db, () => {
      gravarEstado(db, CHAVES.conferidoEm, conferidoEm);
      gravarEstado(db, CHAVES.conferirDepoisDaCarga, null);
    });
    return 'ok';
  }

  /** As tabelas em que o resumo local não bate com o do servidor; `null` se a conferência não respondeu. */
  private async diferencas(db: BancoAberto): Promise<TabelaSincronizavel[] | null> {
    const r = await this.o.comToken((t) => this.o.transporte.conferir(t));
    if (!r.ok || r.dados.status !== 200 || !ehObjeto(r.dados.corpo)) return null;
    const tabelas = r.dados.corpo['tabelas'];
    if (!Array.isArray(tabelas)) return null;
    const diferentes: TabelaSincronizavel[] = [];
    for (const t of tabelas) {
      if (!ehObjeto(t) || !ehTabelaSincronizavel(t['tabela'])) continue;
      const local = resumoLocal(db, t['tabela']);
      if (local.quantidade !== t['quantidade'] || local.resumo !== t['resumo'])
        diferentes.push(t['tabela']);
    }
    return diferentes;
  }

  // --- auxiliares ---

  private falhaDaChamada(
    f: { tipo: string; status?: number },
    etapa: 'envio' | 'recebimento' = 'envio',
  ): Etapa {
    if (f.tipo === 'app-desatualizado' && etapa === 'recebimento') {
      // Versão mínima não prende vendas (tarefa 5.13): o motor não para; os próximos ciclos só enviam.
      this._desatualizado = true;
      this.o.aoMudar?.();
      return 'falha';
    }
    if (f.tipo === 'app-desatualizado') return this.ordem('app-desatualizado');
    if (f.tipo === 'recusada' && f.status === 401) return this.ordem('sessao-terminou');
    if (f.tipo === 'sem-conexao') {
      this.mudar(() => {
        this._comInternet = false;
      });
    }
    return 'falha';
  }

  private falhaDoStatus(r: RespostaDoServidor): Etapa {
    // Computador bloqueado ou trocado e já vazio: o servidor manda apagar (ADR-0013, item 14).
    if (r.status === 403 && ehObjeto(r.corpo) && r.corpo['acao'] === 'APAGAR_DADOS')
      return this.ordem('apagar-dados');
    this.registrar(`resposta ${r.status} ${codigoDe(r.corpo) ?? ''}`.trim());
    return 'falha';
  }

  private ordem(o: OrdemDoServidor): Etapa {
    this.o.aoReceberOrdem?.(o);
    return 'parado';
  }

  private mudar(fn: () => void): void {
    const antes = `${String(this._comInternet)}|${String(this._enviando)}`;
    fn();
    if (`${String(this._comInternet)}|${String(this._enviando)}` !== antes) this.o.aoMudar?.();
  }

  private agora(): Date {
    return this.o.agora?.() ?? new Date();
  }

  private esperar(ms: number): Promise<void> {
    return this.o.esperar?.(ms) ?? new Promise((r) => setTimeout(r, ms));
  }

  private limiteDoPull(): number {
    return this.o.limiteDoPull ?? LIMITE_DO_PULL;
  }

  private registrar(mensagem: string): void {
    this.o.registrar?.(mensagem);
  }
}

// --- funções puras e de banco ---

interface Lote {
  corpo: string;
  operacoes: LinhaDaOutbox[];
}

/**
 * Monta o corpo do envio: até 50 operações e 512 KB, na ordem. O `dados` de cada operação entra como texto guardado,
 * sem passar por `JSON.parse`/`stringify` (o reenvio manda sempre os mesmos bytes).
 */
export function montarLote(
  fila: readonly LinhaDaOutbox[],
  agora: Date,
  totalAguardando: number,
): Lote | null {
  if (fila.length === 0) return null;
  const partes: string[] = [];
  const operacoes: LinhaDaOutbox[] = [];
  const corpoCom = (ps: string[], restantes: number): string =>
    `{"horaLocal":${JSON.stringify(agora.toISOString())},"aguardandoDepoisDeste":${restantes},"operacoes":[${ps.join(',')}]}`;
  for (const op of fila.slice(0, MAXIMO_DE_OPERACOES)) {
    const parte =
      `{"operacaoId":${JSON.stringify(op.operacao_id)},"tipo":${JSON.stringify(op.tipo)},` +
      `"formato":${op.formato},"criadoEmLocal":${JSON.stringify(op.criado_em_local)},` +
      `"ultimoContatoServidor":${JSON.stringify(op.ultimo_contato_servidor)},"dados":${op.dados}}`;
    const tentativa = corpoCom([...partes, parte], totalAguardando - operacoes.length - 1);
    if (operacoes.length > 0 && Buffer.byteLength(tentativa, 'utf8') > MAXIMO_DE_BYTES) break;
    partes.push(parte);
    operacoes.push(op);
  }
  return { corpo: corpoCom(partes, totalAguardando - operacoes.length), operacoes };
}

function contarAguardando(db: BancoAberto): number {
  return (
    db.prepare("SELECT count(*) AS n FROM outbox WHERE situacao = 'AGUARDANDO'").get() as {
      n: number;
    }
  ).n;
}

function marcar(
  db: BancoAberto,
  id: string,
  situacao: 'ENVIADO' | 'RECUSADO' | 'CONFLITO',
  codigo: string,
  mensagem: string | null,
  agora: string,
): void {
  db.prepare(
    `UPDATE outbox SET situacao = ?, codigo_do_resultado = ?, ultimo_erro = ?, respondido_em = ?
      WHERE operacao_id = ? AND situacao = 'AGUARDANDO'`,
  ).run(situacao, codigo, mensagem, agora, id);
}

function contarTentativa(db: BancoAberto, op: LinhaDaOutbox | undefined, codigo: string): void {
  if (!op) return;
  db.prepare(
    `UPDATE outbox SET tentativas = tentativas + 1, ultimo_erro = ?
      WHERE operacao_id = ? AND situacao = 'AGUARDANDO'`,
  ).run(codigo, op.operacao_id);
}

/** As tabelas que ainda faltam na recarga dirigida (vazio = nada a fazer); nome que o app não conhece é ignorado. */
export function tabelasDaRecargaDirigida(db: BancoAberto): TabelaSincronizavel[] {
  const valor = lerEstado(db, CHAVES.recargaDirigida);
  if (valor === null) return [];
  try {
    const lista: unknown = JSON.parse(valor);
    return Array.isArray(lista) ? lista.filter((t): t is TabelaSincronizavel => ehTabelaSincronizavel(t)) : [];
  } catch {
    return [];
  }
}

function gravarTabelasDaRecargaDirigida(db: BancoAberto, tabelas: readonly TabelaSincronizavel[]): void {
  gravarEstado(db, CHAVES.recargaDirigida, tabelas.length === 0 ? null : JSON.stringify(tabelas));
}

/**
 * Recarga por época (409 `CURSOR_INVALIDO`) e carga inicial (ADR-0013, item 7): só as cópias do SERVIDOR saem; as
 * provisórias e a outbox ficam. O cursor zera e o recebimento recomeça.
 */
export function recargaPorEpoca(db: BancoAberto): void {
  gravar(db, () => {
    for (const t of TABELAS_SINCRONIZAVEIS)
      db.prepare(`DELETE FROM ${t} WHERE origem_local = 'SERVIDOR'`).run();
    gravarEstado(db, CHAVES.cursor, null);
    gravarEstado(db, CHAVES.conferirDepoisDaCarga, '1');
  });
}

/** Resumo local de uma tabela, como o servidor calcula: quantas linhas e o SHA-256 de "id:version\n" em ordem de id. */
export function resumoLocal(
  db: BancoAberto,
  tabela: TabelaSincronizavel,
): { quantidade: number; resumo: string } {
  const linhas = db
    .prepare(`SELECT id, version FROM ${tabela} WHERE origem_local = 'SERVIDOR' ORDER BY id`)
    .all() as { id: string; version: number }[];
  const sha = createHash('sha256');
  for (const l of linhas) sha.update(`${l.id}:${l.version}\n`, 'utf8');
  return { quantidade: linhas.length, resumo: sha.digest('hex') };
}

interface Pagina {
  linhas: unknown[];
  cursor: string | null;
  temMais: boolean;
  horaServidor: string;
}

function lerPagina(corpo: unknown): Pagina | null {
  if (!ehObjeto(corpo)) return null;
  const { linhas, cursor, temMais, horaServidor } = corpo;
  if (!Array.isArray(linhas) || typeof temMais !== 'boolean' || typeof horaServidor !== 'string')
    return null;
  if (cursor !== null && cursor !== undefined && typeof cursor !== 'string') return null;
  return { linhas, cursor: cursor ?? null, temMais, horaServidor };
}

function guardarParametros(db: BancoAberto, corpo: unknown): void {
  if (!ehObjeto(corpo) || !ehObjeto(corpo['parametros'])) return;
  const p = corpo['parametros'];
  // Só a lista explícita dos parâmetros do vendedor (ADR-0013, item 4).
  const limpos = {
    limiteDescontoPercentual: inteiroOuNulo(p['limiteDescontoPercentual']),
    tempoMaximoOfflineDias: inteiroOuNulo(p['tempoMaximoOfflineDias']),
    // A garantia (ADR-0014, item 4): o app só mostra a prévia do prazo; quem decide é o servidor.
    prazoArrependimentoDias: inteiroOuNulo(p['prazoArrependimentoDias']),
    prazoDefeitoDias: inteiroOuNulo(p['prazoDefeitoDias']),
    situacaoDoAparelho:
      typeof p['situacaoDoAparelho'] === 'string' ? p['situacaoDoAparelho'] : null,
  };
  gravarEstado(db, CHAVES.parametros, JSON.stringify(limpos));
}

const inteiroOuNulo = (v: unknown): number | null =>
  typeof v === 'number' && Number.isInteger(v) ? v : null;

function codigoDe(corpo: unknown): string | null {
  return ehObjeto(corpo) && typeof corpo['codigo'] === 'string' ? corpo['codigo'] : null;
}

function ehObjeto(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

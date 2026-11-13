import { type BancoAberto, gravar } from '../banco/banco-local';
import { reaplicarPendentes } from './camada-de-pendentes';
import {
  colunasGravaveis,
  ehTabelaSincronizavel,
  MARCADORES_PROIBIDOS,
  type TabelaSincronizavel,
} from './colunas';
import { CHAVES, gravarEstado } from './estado-sync';

/** Uma linha do pull já conferida (o formato `LinhaRecebida` do servidor, tarefa 5.4). */
export interface LinhaRecebida {
  tabela: TabelaSincronizavel;
  id: string;
  version: number;
  deletedAt: string | null;
  campos: Record<string, string | number | null>;
}

/** O lote não serve: nada dele é gravado (nem o cursor) e ele volta no próximo ciclo. */
export class LoteRecusado extends Error {
  constructor(motivo: string) {
    super(`Lote do pull recusado: ${motivo}`);
    this.name = 'LoteRecusado';
  }
}

export interface ResultadoDaAplicacao {
  gravadas: number;
  ignoradas: number;
  /** Clientes cuja cópia mudou neste lote (a camada de pendentes já foi reaplicada neles). */
  clientesTocados: string[];
}

export interface OpcoesDaAplicacao {
  /**
   * Cursor novo, gravado na MESMA transação do lote. `null` = não mexe no cursor (recarga de uma tabela pelo
   * `?tabela=`, que tem cursor próprio).
   */
  cursor: string | null;
  /**
   * Recarga dirigida (tarefa 6.7, dúvida S6): a cópia do servidor com a MESMA `version` também é substituída (as cópias
   * antigas não têm as colunas novas). Só a recarga dirigida usa; o pull normal continua "igual é ignorada".
   */
  substituirIgual?: boolean;
  /** Roda dentro da MESMA transação do lote, depois das linhas (a recarga dirigida tira a tabela da marca aqui). */
  naMesmaTransacao?: () => void;
  /** Só para os testes: chamado depois de cada linha gravada (para injetar uma falha no meio do lote). */
  depoisDeCadaLinha?: (indice: number) => void;
}

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/**
 * Confere o lote inteiro antes de gravar qualquer coisa: tabela conhecida, id, `version`, lápide e só colunas da lista
 * explícita, com valores simples. Qualquer coisa fora disso recusa o lote todo ({@link LoteRecusado}).
 *
 * - Booleano vira 0/1 (SQLite não tem booleano).
 * - Número só inteiro: dinheiro chega como texto ("12.30") e continua texto; número quebrado é recusado.
 */
export function conferirLinhas(linhas: unknown): LinhaRecebida[] {
  if (!Array.isArray(linhas)) throw new LoteRecusado('linhas ausentes');
  return linhas.map((bruta, i): LinhaRecebida => {
    if (typeof bruta !== 'object' || bruta === null) throw new LoteRecusado(`linha ${i}`);
    const l = bruta as Record<string, unknown>;
    if (!ehTabelaSincronizavel(l['tabela']))
      throw new LoteRecusado(`tabela desconhecida (linha ${i})`);
    const tabela = l['tabela'];
    const id = l['id'];
    if (typeof id !== 'string' || !ID.test(id.toLowerCase()))
      throw new LoteRecusado(`id (linha ${i})`);
    const version = l['version'];
    if (typeof version !== 'number' || !Number.isInteger(version) || version < 1)
      throw new LoteRecusado(`version (linha ${i})`);
    const deletedAt = l['deletedAt'] ?? null;
    if (
      deletedAt !== null &&
      (typeof deletedAt !== 'string' || Number.isNaN(Date.parse(deletedAt)))
    )
      throw new LoteRecusado(`lápide (linha ${i})`);
    const brutos = l['campos'] ?? {};
    if (typeof brutos !== 'object' || brutos === null || Array.isArray(brutos))
      throw new LoteRecusado(`campos (linha ${i})`);
    const permitidas = new Set(colunasGravaveis(tabela));
    const campos: Record<string, string | number | null> = {};
    for (const [coluna, valor] of Object.entries(brutos as Record<string, unknown>)) {
      const minuscula = coluna.toLowerCase();
      if (!permitidas.has(coluna) || MARCADORES_PROIBIDOS.some((m) => minuscula.includes(m)))
        throw new LoteRecusado(`coluna fora da lista em ${tabela} (linha ${i})`);
      campos[coluna] = valorSimples(valor, i);
    }
    return { tabela, id: id.toLowerCase(), version, deletedAt, campos };
  });
}

function valorSimples(valor: unknown, i: number): string | number | null {
  if (valor === null || valor === undefined) return null;
  if (typeof valor === 'string') return valor;
  if (typeof valor === 'boolean') return valor ? 1 : 0;
  if (typeof valor === 'number' && Number.isSafeInteger(valor)) return valor;
  throw new LoteRecusado(`valor que não é texto nem inteiro (linha ${i})`);
}

/**
 * Aplica um lote do pull numa ÚNICA transação do SQLite, junto com o cursor novo (ADR-0013, item 3). Se o app cair no
 * meio, nada do lote fica gravado e ele volta no próximo ciclo.
 *
 * Regras por linha:
 * - `version` recebida maior que a local: troca a cópia inteira (colunas que não vieram ficam vazias). Igual ou menor:
 *   ignorada. Por isso a mesma linha duas vezes, ou fora de ordem, é inofensiva.
 * - Lápide marca `deleted_at`, nunca apaga.
 * - Cópia PROVISÓRIA com o MESMO id: o servidor vence (vira `SERVIDOR`, qualquer `version`), e a linha deixa de contar
 *   como provisória. Uma linha de OUTRO id nunca toca numa provisória: os itens e movimentos provisórios só saem quando a
 *   operação deles foi enviada (ver `limparProvisoriasEnviadas`) ou recusada (`desfazerOperacao`).
 * - Clientes tocados recebem de novo as alterações ainda aguardando envio (camada de pendentes).
 */
export function aplicarLote(
  db: BancoAberto,
  linhasBrutas: unknown,
  opcoes: OpcoesDaAplicacao,
): ResultadoDaAplicacao {
  const linhas = conferirLinhas(linhasBrutas);
  return gravar(db, () => {
    const r = aplicarLinhas(db, linhas, opcoes.substituirIgual === true, opcoes.depoisDeCadaLinha);
    reaplicarPendentes(db, r.clientesTocados);
    if (opcoes.cursor !== null) gravarEstado(db, CHAVES.cursor, opcoes.cursor);
    opcoes.naMesmaTransacao?.();
    return r;
  });
}

function aplicarLinhas(
  db: BancoAberto,
  linhas: readonly LinhaRecebida[],
  substituirIgual: boolean,
  depoisDeCadaLinha?: (indice: number) => void,
): ResultadoDaAplicacao {
  let gravadas = 0;
  let ignoradas = 0;
  const clientes = new Set<string>();
  linhas.forEach((l, i) => {
    const local = db
      .prepare(`SELECT version, origem_local FROM ${l.tabela} WHERE id = ?`)
      .get(l.id) as { version: number; origem_local: string } | undefined;
    const provisoria = local?.origem_local === 'PROVISORIO';
    const antiga = substituirIgual ? l.version < (local?.version ?? 0) : l.version <= (local?.version ?? 0);
    if (local && !provisoria && antiga) {
      ignoradas += 1;
      return;
    }
    gravarLinha(db, l);
    if (provisoria) {
      db.prepare('DELETE FROM provisorias WHERE tabela = ? AND linha_id = ?').run(l.tabela, l.id);
    }
    if (l.tabela === 'clientes') clientes.add(l.id);
    gravadas += 1;
    depoisDeCadaLinha?.(i);
  });
  return { gravadas, ignoradas, clientesTocados: [...clientes] };
}

function gravarLinha(db: BancoAberto, l: LinhaRecebida): void {
  const colunas = colunasGravaveis(l.tabela);
  const todas = ['id', 'version', 'deleted_at', 'origem_local', ...colunas];
  const valores = [
    l.id,
    l.version,
    l.deletedAt,
    'SERVIDOR',
    ...colunas.map((c) => (c in l.campos ? l.campos[c] : null)),
  ];
  const atualizar = todas
    .filter((c) => c !== 'id')
    .map((c) => `${c} = excluded.${c}`)
    .join(', ');
  db.prepare(
    `INSERT INTO ${l.tabela} (${todas.join(', ')}) VALUES (${todas.map(() => '?').join(', ')})
     ON CONFLICT(id) DO UPDATE SET ${atualizar}`,
  ).run(...valores);
}

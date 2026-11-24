import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import { diaEmBrasilia } from '../../shared/garantia';
import type { BancoAberto } from '../banco/banco-local';

/**
 * Leituras que as gravações e as telas do consignado ao cliente dividem (tarefa 9.2; ADR-0016). Tudo sai do espelho local
 * (cópias do servidor e linhas PROVISÓRIAS das operações ainda não enviadas): a nota, o acerto e a pendência aparecem na hora,
 * sem internet. Dinheiro em centavos inteiros (`bigint`); a situação da nota, a pendência e a semana atual são sempre
 * CALCULADAS dos fatos (nunca se muda uma cópia do servidor no lugar, então recusar uma operação só tira as linhas dela).
 */

/** O número que a tela mostra: semana com 7 dígitos, hífen e sequência com ao menos 2 (`0000064-21`). */
export function numeroDaNota(semana: number, sequencia: number): string {
  return `${String(semana).padStart(7, '0')}-${String(sequencia).padStart(2, '0')}`;
}

/** O Tam./Ton. da peça (texto curto do cadastro, tarefa 9.8); `null` quando a peça não tem ou o computador ainda não o recebeu. */
export function tamTonDoProduto(db: BancoAberto, produtoId: string): string | null {
  const r = db.prepare('SELECT tam_ton FROM produtos WHERE id = ?').get(produtoId) as
    { tam_ton: string | null } | undefined;
  const t = r?.tam_ton?.trim();
  return t ? t : null;
}

/** A cotação vigente num instante: as taxas de venda, de onde veio e, se a dona a digitou, "1 real = Gs. N". */
export interface CotacaoVigente {
  id: string;
  data: string;
  dolar: string;
  guarani: string;
  origem: 'BANCO_CENTRAL' | 'DONA';
  vigenteDesde: string;
  guaranisPorReal: string | null;
}

/**
 * A cotação que vale num instante (ADR-0006, itens 7 e 15): a de maior `vigente_desde` menor ou igual a ele, empate pelo maior
 * id (a mesma regra do servidor). A do Banco Central vale desde 00:00 do dia em Brasília; a da dona, desde a hora em que foi
 * gravada. Compara por `julianday`, então "…:00Z" e "…:00.123Z" se comparam certo (texto puro erraria). Linha de antes da migração
 * 13, ainda sem `vigente_desde`, vale desde 00:00 do dia dela em Brasília. Sem nenhuma, `null` ("Sem cotação"). É o ÚNICO lugar
 * do app que escolhe cotação pelo momento (um teste de arquitetura confere): só tela, o valor gravado é sempre Real.
 */
export function cotacaoVigenteEm(db: BancoAberto, instante: string | Date): CotacaoVigente | null {
  const iso = typeof instante === 'string' ? instante : instante.toISOString();
  const r = db
    .prepare(
      `SELECT id, data_cotacao, usd_venda, pyg_venda, origem, vigente_desde, guaranis_por_real FROM cotacoes
        WHERE deleted_at IS NULL AND usd_venda IS NOT NULL AND pyg_venda IS NOT NULL
          AND julianday(coalesce(vigente_desde, data_cotacao || 'T03:00:00.000Z')) <= julianday(?)
        ORDER BY julianday(coalesce(vigente_desde, data_cotacao || 'T03:00:00.000Z')) DESC, id DESC LIMIT 1`,
    )
    .get(iso) as
    | {
        id: string;
        data_cotacao: string;
        usd_venda: string;
        pyg_venda: string;
        origem: string | null;
        vigente_desde: string | null;
        guaranis_por_real: string | null;
      }
    | undefined;
  if (!r) return null;
  return {
    id: r.id,
    data: r.data_cotacao,
    dolar: r.usd_venda,
    guarani: r.pyg_venda,
    origem: r.origem === 'DONA' ? 'DONA' : 'BANCO_CENTRAL',
    vigenteDesde: r.vigente_desde ?? `${r.data_cotacao}T03:00:00.000Z`,
    guaranisPorReal: r.guaranis_por_real,
  };
}

/**
 * A cotação da tela do consignado: a vigente ao FIM do dia pedido em Brasília (um dia passado), ou a vigente agora se nenhum
 * dia foi pedido. Mesma forma de antes (`data`, `dolar`, `guarani`).
 */
export function cotacaoDaNota(
  db: BancoAberto,
  dia: string | undefined,
  agora: Date,
): { data: string; dolar: string; guarani: string } | null {
  const instante = dia ? new Date(`${dia}T23:59:59.999-03:00`) : agora;
  const c = cotacaoVigenteEm(db, instante);
  return c ? { data: c.data, dolar: c.dolar, guarani: c.guarani } : null;
}

/** O número do recibo que a tela mostra: o mesmo formato da nota com o prefixo `R ` (`R 0000064-01`); nunca é gravado assim. */
export function numeroDoRecibo(semana: number, sequencia: number): string {
  return `R ${numeroDaNota(semana, sequencia)}`;
}

/**
 * O código da maleta que a tela mostra e a pesquisa aceita: o do servidor (`0001`, `0002`...). Só enquanto a maleta ainda não
 * trouxe o código (banco que ainda não recebeu a carga nova) vale o provisório `M-` com os 6 últimos dígitos do identificador.
 */
export function codigoDaMaleta(db: BancoAberto, remessaId: string): string {
  const r = db.prepare('SELECT codigo FROM remessas WHERE id = ?').get(remessaId) as
    { codigo: string | null } | undefined;
  const codigo = r?.codigo?.trim();
  return codigo ? codigo : `M-${remessaId.replace(/-/g, '').slice(-6).toUpperCase()}`;
}

/** O nome da praça do representante: o da maleta mais recente que o computador recebeu (ou `null`). */
export function pracaDoRepresentante(db: BancoAberto): string | null {
  const r = db
    .prepare(
      `SELECT praca_nome FROM remessas WHERE deleted_at IS NULL AND praca_nome IS NOT NULL AND trim(praca_nome) <> ''
        ORDER BY enviada_em DESC, id DESC LIMIT 1`,
    )
    .get() as { praca_nome: string } | undefined;
  return r?.praca_nome.trim() ?? null;
}

export interface SemanaLocal {
  id: string;
  numero: number;
  aberta_em: string | null;
}

/** A semana do representante: a de maior número é a que está em andamento (as outras já estão fechadas). */
export function semanaAtual(db: BancoAberto): SemanaLocal | null {
  return (
    (db
      .prepare(
        `SELECT id, numero, aberta_em FROM semanas_vendedor WHERE deleted_at IS NULL ORDER BY numero DESC LIMIT 1`,
      )
      .get() as SemanaLocal | undefined) ?? null
  );
}

/**
 * O acerto está valendo? Sem estorno na cópia e sem estorno ainda esperando o servidor (a operação AGUARDANDO ou já aceita:
 * se o servidor recusar, a linha da outbox deixa de contar e o acerto volta a valer).
 */
export const SQL_ACERTO_ATIVO = (alias: string): string =>
  `(${alias}.deleted_at IS NULL AND ${alias}.estornado_em IS NULL AND NOT EXISTS (
      SELECT 1 FROM outbox o WHERE o.tipo = 'ESTORNO_ACERTO_NOTA' AND o.situacao IN ('AGUARDANDO', 'ENVIADO')
         AND json_extract(o.dados, '$.acertoId') = ${alias}.id))`;

/** O recibo está valendo? Sem estorno próprio, sem estorno pedido e, se é de um acerto, com o acerto valendo. */
export const SQL_RECIBO_ATIVO = (alias: string): string =>
  `(${alias}.deleted_at IS NULL AND ${alias}.estornado_em IS NULL AND NOT EXISTS (
      SELECT 1 FROM outbox o WHERE o.tipo = 'ESTORNO_RECIBO' AND o.situacao IN ('AGUARDANDO', 'ENVIADO')
         AND json_extract(o.dados, '$.reciboId') = ${alias}.id)
    AND (${alias}.acerto_id IS NULL OR EXISTS (
      SELECT 1 FROM acertos_nota ac WHERE ac.id = ${alias}.acerto_id AND ${SQL_ACERTO_ATIVO('ac')})))`;

export interface NotaLocal {
  id: string;
  semana_id: string;
  sequencia: number;
  cliente_id: string;
  saida_em: string | null;
  data_cobranca: string | null;
  nota_origem_id: string | null;
  semana_numero: number;
}

export function acharNota(db: BancoAberto, id: string): NotaLocal | null {
  return (
    (db
      .prepare(
        `SELECT n.id, n.semana_id, n.sequencia, n.cliente_id, n.saida_em, n.data_cobranca, n.nota_origem_id,
                coalesce(s.numero, 0) AS semana_numero
           FROM notas_consignado n LEFT JOIN semanas_vendedor s ON s.id = n.semana_id
          WHERE n.id = ? AND n.deleted_at IS NULL`,
      )
      .get(id) as NotaLocal | undefined) ?? null
  );
}

/** A Data do retorno que vale: a da última remarcação, ou a da nota. */
export function dataDeCobranca(db: BancoAberto, nota: NotaLocal): string | null {
  const r = db
    .prepare(
      `SELECT data_cobranca_nova AS nova FROM remarcacoes_nota
        WHERE nota_id = ? AND deleted_at IS NULL ORDER BY remarcada_em DESC, id DESC LIMIT 1`,
    )
    .get(nota.id) as { nova: string | null } | undefined;
  return r?.nova ?? nota.data_cobranca;
}

export function foiRemarcada(db: BancoAberto, notaId: string): boolean {
  return (
    db
      .prepare('SELECT 1 FROM remarcacoes_nota WHERE nota_id = ? AND deleted_at IS NULL LIMIT 1')
      .get(notaId) !== undefined
  );
}

export interface LinhaDaNotaLocal {
  produtoId: string;
  remessaId: string;
  /** DX: soma das quantidades dos itens (a troca tira e põe). */
  dx: number;
  /** O preço da maleta (centavos). */
  preco: bigint;
}

/** As linhas (produto + maleta) da nota, com DX e preço. Linha que zerou na troca fica de fora. */
export function linhasDaNota(db: BancoAberto, notaId: string): LinhaDaNotaLocal[] {
  const itens = db
    .prepare(
      `SELECT produto_id, remessa_id, quantidade, preco_unitario FROM itens_nota_consignado
        WHERE nota_id = ? AND deleted_at IS NULL ORDER BY created_at, id`,
    )
    .all(notaId) as {
    produto_id: string;
    remessa_id: string;
    quantidade: number;
    preco_unitario: string | null;
  }[];
  const mapa = new Map<string, LinhaDaNotaLocal>();
  for (const i of itens) {
    const chave = `${i.produto_id}|${i.remessa_id}`;
    const atual = mapa.get(chave);
    const preco = exigirCentavos(i.preco_unitario ?? '0');
    if (atual) atual.dx += i.quantidade;
    else
      mapa.set(chave, {
        produtoId: i.produto_id,
        remessaId: i.remessa_id,
        dx: i.quantidade,
        preco,
      });
  }
  return [...mapa.values()].filter((l) => l.dx > 0);
}

export interface AcertoLocal {
  id: string;
  nota_id: string;
  semana_id: string | null;
  acertado_em: string | null;
  registrado_em: string | null;
  comissao_percentual: string | null;
  itens_dx: number | null;
  itens_dv: number | null;
  itens_vd: number | null;
  valor_dx: string | null;
  valor_dv: string | null;
  valor_vd: string | null;
  comissao: string | null;
  bruto: string | null;
  pendencias_anteriores: string | null;
  total_a_receber: string | null;
  total_pago: string | null;
  ficou_devendo: string | null;
  recibo_id: string | null;
}

export function acertoAtivoDaNota(db: BancoAberto, notaId: string): AcertoLocal | null {
  return (
    (db
      .prepare(
        `SELECT a.* FROM acertos_nota a WHERE a.nota_id = ? AND ${SQL_ACERTO_ATIVO('a')}
          ORDER BY coalesce(a.registrado_em, a.acertado_em) DESC LIMIT 1`,
      )
      .get(notaId) as AcertoLocal | undefined) ?? null
  );
}

export interface DebitoLocal {
  acertoId: string;
  notaId: string;
  numeroDaNota: string;
  acertadoEm: string;
  bruto: bigint;
  /** O que ainda deve deste acerto. */
  saldo: bigint;
}

/**
 * A sobra "a aplicar" do cliente: o que os recibos valendo dele receberam a mais do que já foi aplicado em notas
 * (pagamento ou estorno que deixou dinheiro sem nota para abater). Entra sozinha no próximo acerto (ADR-0016, item 10).
 */
export function sobraAAplicarDoCliente(db: BancoAberto, clienteId: string): bigint {
  const recibos = db
    .prepare(
      `SELECT r.id, r.total FROM recibos_consignado r
        WHERE r.cliente_id = ? AND ${SQL_RECIBO_ATIVO('r')}`,
    )
    .all(clienteId) as { id: string; total: string | null }[];
  let sobra = 0n;
  for (const r of recibos) {
    const aplicado = db
      .prepare('SELECT valor FROM aplicacoes_recibo WHERE recibo_id = ? AND deleted_at IS NULL')
      .all(r.id) as { valor: string | null }[];
    const parte =
      exigirCentavos(r.total ?? '0') -
      aplicado.reduce((s, a) => s + exigirCentavos(a.valor ?? '0'), 0n);
    if (parte > 0n) sobra += parte;
  }
  return sobra;
}

/**
 * Os débitos do cliente (um por acerto valendo), do MAIS ANTIGO para o mais novo, com o saldo de cada um: o bruto do
 * acerto menos o que as aplicações de recibos já quitaram (ADR-0016, item 10). Devolve também os de saldo zero.
 */
export function debitosDoCliente(db: BancoAberto, clienteId: string): DebitoLocal[] {
  const acertos = db
    .prepare(
      `SELECT a.id, a.nota_id, a.acertado_em, a.bruto, n.sequencia, coalesce(s.numero, 0) AS semana_numero
         FROM acertos_nota a
         JOIN notas_consignado n ON n.id = a.nota_id AND n.deleted_at IS NULL
         LEFT JOIN semanas_vendedor s ON s.id = n.semana_id
        WHERE n.cliente_id = ? AND ${SQL_ACERTO_ATIVO('a')}
        ORDER BY coalesce(a.registrado_em, a.acertado_em), semana_numero, n.sequencia, a.id`,
    )
    .all(clienteId) as {
    id: string;
    nota_id: string;
    acertado_em: string | null;
    bruto: string | null;
    sequencia: number;
    semana_numero: number;
  }[];
  if (acertos.length === 0) return [];
  const aplicado = new Map<string, bigint>();
  const linhas = db
    .prepare(
      `SELECT acerto_id, valor FROM aplicacoes_recibo
        WHERE deleted_at IS NULL AND acerto_id IN (${acertos.map(() => '?').join(', ')})`,
    )
    .all(...acertos.map((a) => a.id)) as { acerto_id: string; valor: string | null }[];
  for (const l of linhas)
    aplicado.set(l.acerto_id, (aplicado.get(l.acerto_id) ?? 0n) + exigirCentavos(l.valor ?? '0'));
  return acertos.map((a) => {
    const bruto = exigirCentavos(a.bruto ?? '0');
    return {
      acertoId: a.id,
      notaId: a.nota_id,
      numeroDaNota: numeroDaNota(a.semana_numero, a.sequencia),
      acertadoEm: a.acertado_em ?? '',
      bruto,
      saldo: bruto - (aplicado.get(a.id) ?? 0n),
    };
  });
}

/** O que o cliente deve agora (soma dos saldos que ainda devem). */
export function pendenciaDoCliente(db: BancoAberto, clienteId: string): bigint {
  return debitosDoCliente(db, clienteId).reduce((s, d) => (d.saldo > 0n ? s + d.saldo : s), 0n);
}

/** O saldo do débito de um acerto valendo, ou `null` se o acerto não vale. */
export function saldoDoAcerto(
  db: BancoAberto,
  acerto: AcertoLocal,
  clienteId: string,
): bigint | null {
  const d = debitosDoCliente(db, clienteId).find((x) => x.acertoId === acerto.id);
  return d ? d.saldo : null;
}

/** A hora de agora como dia civil em Brasília mais `dias` (Data do retorno sugerida: saída + 45 dias). */
export function diaMaisDias(agora: Date, dias: number): string {
  return diaEmBrasilia(new Date(agora.getTime() + dias * 86_400_000).toISOString());
}

export { textoDeCentavos };

import { diaEmBrasilia } from '../../shared/garantia';
import type {
  ComissaoDoFechamento,
  ContaDaLoja,
  FechamentoDoAcerto,
  FichaDoAcerto,
  ItemDoFechamento,
  ParcelaAReceber,
  PendenciaDoFechamento,
  RecebiveisDoVendedor,
  RecebimentoDoCliente,
  RecebimentoDoFechamento,
  ResumoDoAcerto,
  SituacaoDaParcela,
  SituacaoDoAcerto,
  Venda,
  VendaAPrazo,
} from '../../shared/dados';
import type { BancoAberto } from '../banco/banco-local';
import { ehDoVendedor } from '../sync/operacoes-da-fase-6';
import { pecaDoProduto } from './peca-do-produto';

/**
 * Acerto fechado, parcelas e recebíveis do vendedor, lidos do banco do computador (tarefa 7.11; ADR-0012). Tudo vem das
 * cópias que o servidor mandou (parcelas, contas do nível 2, acerto e fechamento) e do que foi feito aqui e ainda não
 * chegou à loja. **Nada é calculado**: nem parcela, nem comissão, nem total do acerto; o que vale é o servidor. Só o que é do
 * vendedor deste computador; nunca custo, caixa nem contas a pagar.
 */
export interface OpcoesDasConsultasDoAcerto {
  banco: () => BancoAberto;
  agora: () => Date;
  /** A venda como as outras telas a veem (com o "a receber" e os pagamentos já descontados do devolvido). */
  venda: (id: string) => Venda | null;
}

const SITUACOES_DA_PARCELA: readonly string[] = ['ABERTA', 'PARCIAL', 'PAGA', 'CANCELADA'];
const situacaoDaParcela = (s: string | null): SituacaoDaParcela =>
  SITUACOES_DA_PARCELA.includes(s ?? '') ? (s as SituacaoDaParcela) : 'ABERTA';
const SITUACOES_DO_ACERTO: readonly string[] = ['EM_ACERTO', 'ACERTADO', 'DESISTIDO'];
const situacaoDoAcerto = (s: string | null): SituacaoDoAcerto =>
  SITUACOES_DO_ACERTO.includes(s ?? '') ? (s as SituacaoDoAcerto) : 'EM_ACERTO';

const texto = (v: string | null | undefined): string => v ?? '0.00';

interface LinhaDeConta {
  id: string;
  parcela: number | null;
  total_parcelas: number | null;
  vencimento: string | null;
  valor: string;
  valor_pago: string | null;
  valor_cancelado: string | null;
  status: string | null;
  forma_prevista?: string | null;
  origem_id?: string | null;
}

export class ConsultasDoAcerto {
  constructor(private readonly o: OpcoesDasConsultasDoAcerto) {}

  private get db(): BancoAberto {
    return this.o.banco();
  }

  private hoje(): string {
    return diaEmBrasilia(this.o.agora());
  }

  private vencida(vencimento: string | null, situacao: SituacaoDaParcela): boolean {
    return (
      vencimento !== null &&
      (situacao === 'ABERTA' || situacao === 'PARCIAL') &&
      vencimento < this.hoje()
    );
  }

  // --- recebíveis ---

  recebiveis(): RecebiveisDoVendedor {
    return { dosClientes: this.dosClientes(), daLoja: this.daLoja() };
  }

  private dosClientes(): VendaAPrazo[] {
    const db = this.db;
    // Candidatas: as que ainda podem ter valor em aberto (filtro largo; o "a receber" exato vem da venda) e as que
    // receberam dinheiro hoje (para poder estornar).
    const candidatas = new Set(
      (
        db
          .prepare(
            `SELECT v.id FROM vendas v
              WHERE v.deleted_at IS NULL AND coalesce(v.status, '') <> 'ESTORNADA'
                AND CAST(coalesce(v.total, '0') AS REAL)
                    - coalesce((SELECT sum(CAST(p.valor AS REAL)) FROM pagamentos_cliente p
                                 WHERE p.venda_id = v.id AND p.deleted_at IS NULL), 0) > 0.001`,
          )
          .all() as { id: string }[]
      ).map((r) => r.id),
    );
    const dosRecebimentos = db
      .prepare(
        `SELECT venda_id, pago_em FROM pagamentos_cliente
          WHERE posterior = 1 AND tipo = 'PAGAMENTO' AND deleted_at IS NULL`,
      )
      .all() as { venda_id: string; pago_em: string | null }[];
    for (const r of dosRecebimentos)
      if (r.pago_em && diaEmBrasilia(r.pago_em) === this.hoje()) candidatas.add(r.venda_id);

    const saida: VendaAPrazo[] = [];
    for (const id of candidatas) {
      const v = this.o.venda(id);
      if (!v || v.status !== 'CONCLUIDA') continue;
      const recebimentos = this.recebimentosDaVenda(id);
      const temRecebimentoDeHoje = recebimentos.some((r) => r.podeEstornar);
      if (v.aReceber === '0.00' && !temRecebimentoDeHoje) continue;
      if (v.aReceber.startsWith('-') && !temRecebimentoDeHoje) continue;
      const aguardando = db
        .prepare(
          `SELECT 1 FROM outbox WHERE operacao_id = ? AND tipo IN ('VENDA', 'TROCA') AND situacao = 'AGUARDANDO'`,
        )
        .get(id);
      saida.push({
        vendaId: id,
        vendidaEm: v.vendidaEm,
        clienteId: v.clienteId,
        clienteNome: v.clienteNome,
        total: v.total,
        pago: v.pago,
        aReceber: v.aReceber.startsWith('-') ? '0.00' : v.aReceber,
        parcelas: this.parcelasDaVenda(id),
        recebimentos,
        aguardandoEnvio: aguardando !== undefined,
      });
    }
    return saida.sort(
      (a, b) => a.vendidaEm.localeCompare(b.vendidaEm) || a.vendaId.localeCompare(b.vendaId),
    );
  }

  private parcelasDaVenda(vendaId: string): ParcelaAReceber[] {
    return (
      this.db
        .prepare(
          `SELECT id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_cancelado, status
             FROM contas_receber
            WHERE venda_id = ? AND devedor_tipo = 'CLIENTE' AND deleted_at IS NULL
            ORDER BY vencimento, parcela, id`,
        )
        .all(vendaId) as LinhaDeConta[]
    ).map((c) => {
      const situacao = situacaoDaParcela(c.status);
      return {
        id: c.id,
        numero: c.parcela ?? 1,
        total: c.total_parcelas ?? 1,
        vencimento: c.vencimento ?? '',
        valor: c.valor,
        valorPago: texto(c.valor_pago),
        valorCancelado: texto(c.valor_cancelado),
        situacao,
        vencida: this.vencida(c.vencimento, situacao),
      };
    });
  }

  private recebimentosDaVenda(vendaId: string): RecebimentoDoCliente[] {
    const db = this.db;
    const linhas = db
      .prepare(
        `SELECT p.id, p.valor, p.forma, p.pago_em,
                EXISTS (SELECT 1 FROM pagamentos_cliente e
                         WHERE e.estorna_id = p.id AND e.deleted_at IS NULL) AS estornado,
                EXISTS (SELECT 1 FROM outbox o
                         WHERE o.operacao_id = p.id AND o.situacao = 'AGUARDANDO') AS aguardando
           FROM pagamentos_cliente p
          WHERE p.venda_id = ? AND p.posterior = 1 AND p.tipo = 'PAGAMENTO' AND p.deleted_at IS NULL
          ORDER BY p.pago_em, p.id`,
      )
      .all(vendaId) as {
      id: string;
      valor: string;
      forma: string | null;
      pago_em: string | null;
      estornado: number;
      aguardando: number;
    }[];
    return linhas.map((l) => ({
      id: l.id,
      valor: l.valor,
      forma: l.forma ?? '',
      em: l.pago_em ?? '',
      estornado: l.estornado === 1,
      // Só no dia do recebimento e se ainda não foi estornado (depois, só a dona).
      podeEstornar:
        l.estornado !== 1 && l.pago_em !== null && diaEmBrasilia(l.pago_em) === this.hoje(),
      aguardandoEnvio: l.aguardando === 1,
    }));
  }

  private contaDaLoja(c: LinhaDeConta): ContaDaLoja {
    const situacao = situacaoDaParcela(c.status);
    return {
      id: c.id,
      numero: c.parcela ?? 1,
      total: c.total_parcelas ?? 1,
      vencimento: c.vencimento,
      valor: c.valor,
      valorPago: texto(c.valor_pago),
      valorCancelado: texto(c.valor_cancelado),
      situacao,
      vencida: this.vencida(c.vencimento, situacao),
      formaPrevista: c.forma_prevista ?? null,
      acertoId: c.origem_id ?? null,
    };
  }

  private contasDoVendedor(filtro: string, ...args: unknown[]): ContaDaLoja[] {
    const db = this.db;
    return (
      db
        .prepare(
          `SELECT id, parcela, total_parcelas, vencimento, valor, valor_pago, valor_cancelado, status,
                  forma_prevista, origem_id, vendedor_id
             FROM contas_receber
            WHERE devedor_tipo = 'VENDEDOR' AND deleted_at IS NULL ${filtro}
            ORDER BY vencimento, parcela, id`,
        )
        .all(...args) as (LinhaDeConta & { vendedor_id: string | null })[]
    )
      .filter((c) => ehDoVendedor(db, c.vendedor_id))
      .map((c) => this.contaDaLoja(c));
  }

  private daLoja(): ContaDaLoja[] {
    return this.contasDoVendedor("AND status IN ('ABERTA', 'PARCIAL')");
  }

  // --- acertos ---

  listarAcertos(): ResumoDoAcerto[] {
    const db = this.db;
    return (
      db
        .prepare(
          `SELECT a.id, a.vendedor_id, a.status, a.pedido_pelo_vendedor, a.iniciado_em, a.fechado_em, s.numero
             FROM acertos a LEFT JOIN semanas s ON s.id = a.semana_id
            WHERE a.deleted_at IS NULL
            ORDER BY a.iniciado_em DESC, a.id DESC`,
        )
        .all() as {
        id: string;
        vendedor_id: string | null;
        status: string | null;
        pedido_pelo_vendedor: number | null;
        iniciado_em: string | null;
        fechado_em: string | null;
        numero: number | null;
      }[]
    )
      .filter((a) => ehDoVendedor(db, a.vendedor_id))
      .map((a) => ({
        id: a.id,
        semanaNumero: a.numero ?? 0,
        situacao: situacaoDoAcerto(a.status),
        pedidoPeloVendedor: a.pedido_pelo_vendedor === 1,
        iniciadoEm: a.iniciado_em,
        fechadoEm: a.fechado_em,
      }));
  }

  verAcerto(id: string): FichaDoAcerto | null {
    const resumo = this.listarAcertos().find((a) => a.id === id);
    if (!resumo) return null;
    return {
      ...resumo,
      fechamento: resumo.situacao === 'ACERTADO' ? this.fechamento(id) : null,
    };
  }

  private fechamento(id: string): FechamentoDoAcerto {
    const db = this.db;
    const a = db.prepare('SELECT * FROM acertos WHERE id = ?').get(id) as Record<string, unknown>;
    const s = (campo: string): string => (typeof a[campo] === 'string' ? a[campo] : '0.00');
    const sOuNulo = (campo: string): string | null =>
      typeof a[campo] === 'string' ? a[campo] : null;
    const n = (campo: string): number | null => (typeof a[campo] === 'number' ? a[campo] : null);
    const cotacao = db
      .prepare('SELECT data_cotacao, usd_venda, pyg_venda FROM cotacoes WHERE id = ?')
      .get(a['cotacao_id']) as
      { data_cotacao: string; usd_venda: string | null; pyg_venda: string | null } | undefined;
    return {
      fechadoEm: sOuNulo('fechado_em'),
      cotacao:
        cotacao?.usd_venda && cotacao.pyg_venda
          ? { data: cotacao.data_cotacao, dolar: cotacao.usd_venda, guarani: cotacao.pyg_venda }
          : null,
      metaGuarani: n('meta_guarani'),
      percentualComissao: sOuNulo('percentual_comissao'),
      vendidoParaMetaGuarani: n('vendido_para_meta_guarani'),
      vendidoBruto: s('vendido_bruto'),
      descontos: s('descontos'),
      devolvido: s('devolvido'),
      vendidoLiquido: s('vendido_liquido'),
      comissao: s('comissao'),
      faltasCobradas: s('faltas_cobradas'),
      liquido: s('liquido'),
      recebidoNoAcerto: s('recebido_no_acerto'),
      ficouDevendo: s('ficou_devendo'),
      pagoAoVendedor: sOuNulo('pago_ao_vendedor'),
      creditoUsado: sOuNulo('credito_usado'),
      itens: this.itensDoFechamento(id),
      comissoes: this.comissoesDoFechamento(id),
      pendencias: this.pendenciasDoFechamento(id),
      recebimentos: this.recebimentosDoFechamento(id),
      contas: this.contasDoVendedor("AND origem_tipo = 'ACERTO' AND origem_id = ?", id),
    };
  }

  private itensDoFechamento(acertoId: string): ItemDoFechamento[] {
    const db = this.db;
    return (
      db
        .prepare(
          `SELECT i.produto_id, i.esperado, i.contado_boa, i.contado_defeito, i.falta, i.defeito_aprovado
             FROM itens_acerto i LEFT JOIN produtos p ON p.id = i.produto_id
            WHERE i.acerto_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.id`,
        )
        .all(acertoId) as {
        produto_id: string;
        esperado: number | null;
        contado_boa: number | null;
        contado_defeito: number | null;
        falta: number | null;
        defeito_aprovado: number | null;
      }[]
    ).map((i) => ({
      produto: pecaDoProduto(db, i.produto_id),
      esperado: i.esperado ?? 0,
      contadoBoa: i.contado_boa ?? 0,
      contadoDefeito: i.contado_defeito ?? 0,
      falta: i.falta ?? 0,
      defeitoAprovado: i.defeito_aprovado ?? 0,
    }));
  }

  private comissoesDoFechamento(acertoId: string): ComissaoDoFechamento[] {
    return (
      this.db
        .prepare(
          `SELECT c.venda_id, c.tipo, c.base, c.percentual, c.valor, cl.nome AS cliente
             FROM comissoes c
             LEFT JOIN vendas v ON v.id = c.venda_id
             LEFT JOIN clientes cl ON cl.id = v.cliente_id
            WHERE c.acerto_id = ? AND c.deleted_at IS NULL ORDER BY c.created_at, c.id`,
        )
        .all(acertoId) as {
        venda_id: string | null;
        tipo: string | null;
        base: string | null;
        percentual: string | null;
        valor: string | null;
        cliente: string | null;
      }[]
    ).map((c) => ({
      vendaId: c.venda_id,
      clienteNome: c.cliente ?? '',
      tipo: c.tipo === 'ESTORNO' ? 'ESTORNO' : 'COMISSAO',
      base: texto(c.base),
      percentual: texto(c.percentual),
      valor: texto(c.valor),
    }));
  }

  private pendenciasDoFechamento(acertoId: string): PendenciaDoFechamento[] {
    const db = this.db;
    return (
      db
        .prepare(
          `SELECT id, tipo, produto_id, quantidade, valor, status FROM pendencias_acerto
            WHERE acerto_id = ? AND deleted_at IS NULL ORDER BY created_at, id`,
        )
        .all(acertoId) as {
        id: string;
        tipo: string | null;
        produto_id: string | null;
        quantidade: number | null;
        valor: string | null;
        status: string | null;
      }[]
    ).map((p) => ({
      id: p.id,
      tipo: p.tipo ?? '',
      produto: p.produto_id ? pecaDoProduto(db, p.produto_id) : null,
      quantidade: p.quantidade,
      valor: texto(p.valor),
      status: p.status ?? '',
    }));
  }

  private recebimentosDoFechamento(acertoId: string): RecebimentoDoFechamento[] {
    return (
      this.db
        .prepare(
          `SELECT r.id, r.forma, r.valor, r.aplicado_em, r.recebido_em,
                  EXISTS (SELECT 1 FROM recebimentos_vendedor e
                           WHERE e.estorna_id = r.id AND e.deleted_at IS NULL) AS estornado
             FROM recebimentos_vendedor r
            WHERE r.acerto_id = ? AND r.estorna_id IS NULL AND r.deleted_at IS NULL
            ORDER BY r.recebido_em, r.id`,
        )
        .all(acertoId) as {
        id: string;
        forma: string | null;
        valor: string;
        aplicado_em: string | null;
        recebido_em: string | null;
        estornado: number;
      }[]
    ).map((r) => ({
      id: r.id,
      forma: r.forma ?? '',
      valor: r.valor,
      aplicadoEm: r.aplicado_em ?? '',
      em: r.recebido_em,
      estornado: r.estornado === 1,
    }));
  }
}

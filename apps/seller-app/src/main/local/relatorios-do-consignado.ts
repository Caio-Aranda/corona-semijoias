import { dolarEmCentavos, guaraniInteiro, taxaDeTexto } from '@corona/shared/moedas';
import type {
  ClienteCompleto,
  DetalheDaNota,
  MaletaDoConsignado,
  PainelDaSemana,
} from '../../shared/dados-do-consignado';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import { diaEmBrasilia } from '../../shared/garantia';
import {
  FORMAS_DO_CONSIGNADO,
  type PedidoDoConsignado,
} from '../../shared/operacoes-do-consignado';
import type {
  CabecalhoDoRelatorio,
  ClienteDoRelatorio,
  FormaNoRelatorio,
  LinhaDePecaVendida,
  LinhaVendida,
  NotaDeSaida,
  NotaPendenteDaSituacao,
  PecaComOCliente,
  PecaDaSituacaoDaMaleta,
  PecasVendidasPorCliente,
  PedidoDeRelatorio,
  RelatorioDoAcerto,
  RelatorioDoConsignado,
  SituacaoDaMaleta,
  SituacaoDoCliente,
  TotaisDaSemanaNoRelatorio,
  ValorNasTresMoedas,
} from '../../shared/relatorios-do-consignado';
import type { BancoAberto } from '../banco/banco-local';
import {
  acertoAtivoDaNota,
  acharNota,
  codigoDaMaleta,
  debitosDoCliente,
  linhasDaNota,
  numeroDaNota,
  pracaDoRepresentante,
  SQL_RECIBO_ATIVO,
} from '../sync/consignado-comum';
import { ErroDaOperacao } from '../sync/operacoes-locais';
import type { Resultado } from '../tipos';

/**
 * Monta os relatórios do consignado ao cliente (tarefa 9.3; §23.11) do banco do computador, sem internet. Nenhuma conta nova:
 * os valores de cada nota, acerto e débito vêm da mesma leitura que as telas usam (`consultar`), que usa as fórmulas de
 * `@corona/shared/consignado`; aqui só se somam esses valores e se convertem em Dólar e Guarani, cada lançamento pela
 * cotação do seu dia (a gravada nele; sem ela, a do dia do lançamento ou a última antes; sem nenhuma, só o Real).
 */

export interface OpcoesDosRelatorios {
  banco: () => BancoAberto;
  agora: () => Date;
  /** A leitura das telas (`consignado.nota`, `.cliente`, `.painel` e `.maletas`). */
  consultar: (pedido: PedidoDoConsignado) => Resultado<unknown>;
}

interface Taxas {
  usd: bigint;
  pyg: bigint;
}

const centavos = (t: string | null | undefined): bigint => exigirCentavos(t ?? '0');
const dinheiro = textoDeCentavos;

/** A soma de valores em Real e, se todos os lançamentos tinham cotação, em Dólar e Guarani. */
class Soma {
  private real = 0n;
  private dolar = 0n;
  private guarani = 0n;
  private completa = true;

  somar(valor: bigint, taxas: Taxas | null): this {
    this.real += valor;
    if (taxas) {
      this.dolar += dolarEmCentavos(valor, taxas.usd);
      this.guarani += guaraniInteiro(valor, taxas.pyg);
    } else if (valor !== 0n) {
      this.completa = false;
    }
    return this;
  }

  valor(): ValorNasTresMoedas {
    return {
      real: dinheiro(this.real),
      dolar: this.completa ? dinheiro(this.dolar) : null,
      guarani: this.completa ? this.guarani.toString() : null,
    };
  }

  get emReal(): bigint {
    return this.real;
  }
}

const um = (valor: bigint, taxas: Taxas | null): ValorNasTresMoedas =>
  new Soma().somar(valor, taxas).valor();

export class RelatoriosDoConsignado {
  constructor(private readonly o: OpcoesDosRelatorios) {}

  private db(): BancoAberto {
    return this.o.banco();
  }

  montar(pedido: PedidoDeRelatorio): RelatorioDoConsignado {
    switch (pedido.tipo) {
      case 'NOTA_DE_SAIDA':
        return this.notaDeSaida(pedido.notaId);
      case 'ACERTO':
        return this.acerto(pedido.notaId);
      case 'SITUACAO_DO_CLIENTE':
        return this.situacaoDoCliente(pedido.clienteId);
      case 'PECAS_VENDIDAS':
        return this.pecasVendidas(pedido.clienteId, pedido.de, pedido.ate);
      case 'TOTAIS_DA_SEMANA':
        return this.totaisDaSemana(pedido.semanaId);
      case 'SITUACAO_DA_MALETA':
        return this.situacaoDaMaleta(pedido.remessaId);
    }
  }

  // --- leituras de apoio ---

  private ler<T>(pedido: PedidoDoConsignado, codigo: 'NOTA_NAO_ENCONTRADA'): T;
  private ler<T>(pedido: PedidoDoConsignado, codigo: 'CLIENTE_NAO_ENCONTRADO'): T;
  private ler<T>(pedido: PedidoDoConsignado, codigo: 'MALETA_NAO_ENCONTRADA'): T;
  private ler<T>(
    pedido: PedidoDoConsignado,
    codigo: 'NOTA_NAO_ENCONTRADA' | 'CLIENTE_NAO_ENCONTRADO' | 'MALETA_NAO_ENCONTRADA',
  ): T {
    const r = this.o.consultar(pedido);
    if (!r.ok) throw new ErroDaOperacao(codigo);
    return r.dados as T;
  }

  private cabecalho(): CabecalhoDoRelatorio {
    const db = this.db();
    const nome = db.prepare("SELECT valor FROM estado_sync WHERE chave = 'vendedor_nome'").get() as
      { valor: string | null } | undefined;
    return {
      representante: nome?.valor ?? null,
      praca: pracaDoRepresentante(db),
      geradoEm: this.o.agora().toISOString(),
    };
  }

  private taxasDe(data: string, dolar: string | null, guarani: string | null): Taxas | null {
    const usd = dolar ? taxaDeTexto(dolar) : null;
    const pyg = guarani ? taxaDeTexto(guarani) : null;
    return usd && pyg ? { usd, pyg } : null;
  }

  /** A cotação mais nova com data até o dia (a do próprio dia, ou a última antes dele). */
  private cotacaoAte(dia: string): Taxas | null {
    const r = this.db()
      .prepare(
        `SELECT data_cotacao, usd_venda, pyg_venda FROM cotacoes
          WHERE deleted_at IS NULL AND data_cotacao <= ? AND usd_venda IS NOT NULL AND pyg_venda IS NOT NULL
          ORDER BY data_cotacao DESC LIMIT 1`,
      )
      .get(dia) as { data_cotacao: string; usd_venda: string; pyg_venda: string } | undefined;
    return r ? this.taxasDe(r.data_cotacao, r.usd_venda, r.pyg_venda) : null;
  }

  /** A cotação do lançamento: a gravada nele; senão (feito sem internet) a do dia dele ou a última antes. */
  private cotacaoDoLancamento(cotacaoId: string | null, instante: string | null): Taxas | null {
    if (cotacaoId) {
      const r = this.db()
        .prepare('SELECT data_cotacao, usd_venda, pyg_venda FROM cotacoes WHERE id = ?')
        .get(cotacaoId) as
        { data_cotacao: string; usd_venda: string | null; pyg_venda: string | null } | undefined;
      const t = r ? this.taxasDe(r.data_cotacao, r.usd_venda, r.pyg_venda) : null;
      if (t) return t;
    }
    return instante ? this.cotacaoAte(diaEmBrasilia(instante)) : null;
  }

  private taxasDaNota(notaId: string): Taxas | null {
    const r = this.db()
      .prepare('SELECT cotacao_id, saida_em FROM notas_consignado WHERE id = ?')
      .get(notaId) as { cotacao_id: string | null; saida_em: string | null } | undefined;
    return r ? this.cotacaoDoLancamento(r.cotacao_id, r.saida_em) : null;
  }

  private taxasDoAcerto(acertoId: string): Taxas | null {
    const r = this.db()
      .prepare('SELECT cotacao_id, acertado_em FROM acertos_nota WHERE id = ?')
      .get(acertoId) as { cotacao_id: string | null; acertado_em: string | null } | undefined;
    return r ? this.cotacaoDoLancamento(r.cotacao_id, r.acertado_em) : null;
  }

  private taxasDoRecibo(reciboId: string): Taxas | null {
    const r = this.db()
      .prepare('SELECT cotacao_id, pago_em FROM recibos_consignado WHERE id = ?')
      .get(reciboId) as { cotacao_id: string | null; pago_em: string | null } | undefined;
    return r ? this.cotacaoDoLancamento(r.cotacao_id, r.pago_em) : null;
  }

  private clienteDoRelatorio(clienteId: string): ClienteDoRelatorio {
    const c = this.db()
      .prepare(
        `SELECT nome, documento, cpf, telefone, whatsapp, endereco_logradouro, endereco_numero, endereco_complemento,
                endereco_bairro, endereco_cidade, endereco_uf FROM clientes WHERE id = ?`,
      )
      .get(clienteId) as Record<string, string | null> | undefined;
    if (!c) throw new ErroDaOperacao('CLIENTE_NAO_ENCONTRADO');
    const parte = (k: string): string => (c[k] ?? '').trim();
    const ruaENumero = [parte('endereco_logradouro'), parte('endereco_numero')]
      .filter(Boolean)
      .join(', ');
    const endereco = [
      ruaENumero,
      parte('endereco_complemento'),
      parte('endereco_bairro'),
      [parte('endereco_cidade'), parte('endereco_uf')].filter(Boolean).join(' - '),
    ]
      .filter(Boolean)
      .join(' · ');
    return {
      nome: parte('nome'),
      documento: parte('documento') || parte('cpf') || null,
      telefone: parte('whatsapp') || parte('telefone') || null,
      endereco: endereco || null,
    };
  }

  // --- Nota de saída ---

  private notaDeSaida(notaId: string): NotaDeSaida {
    const n = this.ler<DetalheDaNota>(
      { operacao: 'consignado.nota', id: notaId },
      'NOTA_NAO_ENCONTRADA',
    );
    const taxas = this.taxasDaNota(notaId);
    const pendencias = n.pendenciasAnteriores.filter((d) => centavos(d.saldo) > 0n);
    const pendente = new Soma();
    for (const d of pendencias) pendente.somar(centavos(d.saldo), this.taxasDoAcerto(d.acertoId));
    return {
      tipo: 'NOTA_DE_SAIDA',
      cabecalho: this.cabecalho(),
      numero: n.numero,
      semana: n.semanaNumero,
      saidaEm: n.saidaEm,
      dataCobranca: n.dataCobranca,
      cliente: this.clienteDoRelatorio(n.clienteId),
      maletas: [...new Set(n.linhas.map((l) => l.maleta))],
      linhas: n.linhas.map((l) => ({
        codigo: l.codigo ?? '',
        produto: l.nome,
        tamTon: l.tamTon,
        maleta: l.maleta,
        quantidade: l.dx,
        preco: l.precoUnitario,
        subTotal: um(centavos(l.subTotal), taxas),
      })),
      itensDx: n.itensDx,
      totalDx: um(centavos(n.valorDx), taxas),
      pendenciaAnterior: pendente.valor(),
      pendencias: pendencias.map((d) => ({
        numeroDaNota: d.numeroDaNota,
        data: d.acertadoEm,
        saldo: um(centavos(d.saldo), this.taxasDoAcerto(d.acertoId)),
      })),
    };
  }

  // --- Relatório do acerto ---

  private acerto(notaId: string): RelatorioDoAcerto {
    const n = this.ler<DetalheDaNota>(
      { operacao: 'consignado.nota', id: notaId },
      'NOTA_NAO_ENCONTRADA',
    );
    const a = n.acerto;
    if (!a) throw new ErroDaOperacao('NOTA_SEM_ACERTO');
    const t = this.taxasDoAcerto(a.id);
    const vendidas: LinhaVendida[] = n.linhas
      .filter((l) => l.vd > 0)
      .map((l) => ({
        codigo: l.codigo ?? '',
        produto: l.nome,
        tamTon: l.tamTon,
        maleta: l.maleta,
        quantidade: l.vd,
        preco: l.precoUnitario,
        valor: um(centavos(l.precoUnitario) * BigInt(l.vd), t),
      }));
    const pago: FormaNoRelatorio[] = (a.recibo?.formas ?? []).map((f) => ({
      forma: f.forma,
      valor: um(centavos(f.valor), t),
    }));
    return {
      tipo: 'ACERTO',
      cabecalho: this.cabecalho(),
      numero: n.numero,
      cliente: this.clienteDoRelatorio(n.clienteId),
      saidaEm: n.saidaEm,
      acertadoEm: a.acertadoEm,
      vendidas,
      itensDx: a.itensDx,
      itensDv: a.itensDv,
      itensVd: a.itensVd,
      valorDx: um(centavos(a.valorDx), t),
      valorDv: um(centavos(a.valorDv), t),
      valorVd: um(centavos(a.valorVd), t),
      vendaPercentual: a.vendaPercentual,
      comissaoPercentual: a.comissaoPercentual,
      comissao: um(centavos(a.comissao), t),
      valorLiquido: um(centavos(a.bruto), t),
      totalPendenciasAnteriores: um(centavos(a.pendenciasAnteriores), t),
      totalAReceber: um(centavos(a.totalAReceber), t),
      recibo: a.recibo?.numero ?? null,
      pago,
      totalPago: um(centavos(a.totalPago), t),
      ficouDevendo: um(centavos(a.ficouDevendo), t),
    };
  }

  // --- Situação do cliente ---

  /**
   * Quanto já foi pago desta nota: tudo que os recibos valendo aplicaram ao acerto dela (o do próprio acerto e os de Lançar
   * pagamento depois). É o bruto menos o saldo; o que o recibo do acerto pagou de pendências de outras notas conta na nota
   * delas, nunca duas vezes.
   */
  private aplicadoAoAcerto(acertoId: string): bigint {
    const linhas = this.db()
      .prepare('SELECT valor FROM aplicacoes_recibo WHERE acerto_id = ? AND deleted_at IS NULL')
      .all(acertoId) as { valor: string | null }[];
    return linhas.reduce((s, l) => s + exigirCentavos(l.valor ?? '0'), 0n);
  }

  private situacaoDoCliente(clienteId: string): SituacaoDoCliente {
    const c = this.ler<ClienteCompleto>(
      { operacao: 'consignado.cliente', id: clienteId },
      'CLIENTE_NAO_ENCONTRADO',
    );
    const dx = new Soma();
    const dv = new Soma();
    const vd = new Soma();
    const comissao = new Soma();
    const liquido = new Soma();
    const pago = new Soma();
    const comOCliente = new Soma();
    const deve = new Soma();
    let itensDx = 0;
    let itensDv = 0;
    let itensVd = 0;
    let itensComOCliente = 0;
    const jaVendeu: SituacaoDoCliente['jaVendeu'] = [];
    const comEle: PecaComOCliente[] = [];
    const pendentes: NotaPendenteDaSituacao[] = [];
    for (const p of c.pedidos) {
      const n = this.ler<DetalheDaNota>(
        { operacao: 'consignado.nota', id: p.notaId },
        'NOTA_NAO_ENCONTRADA',
      );
      if (n.acerto && n.situacao !== 'FECHADO') {
        // Nota acertada que ainda tem saldo: não é "já vendeu" (só as Fechadas, §23.11); entra em "O que falta", com quanto deve.
        const a = n.acerto;
        const saldo = c.debitos.find((d) => d.acertoId === a.id)?.saldo ?? '0';
        pendentes.push({
          numero: n.numero,
          situacao: n.situacao,
          valorDx: um(centavos(a.valorDx), this.taxasDoAcerto(a.id)),
          valorVd: um(centavos(a.valorVd), this.taxasDoAcerto(a.id)),
          ficouDevendo: um(centavos(saldo), this.taxasDoAcerto(a.id)),
        });
      } else if (n.acerto) {
        const a = n.acerto;
        const t = this.taxasDoAcerto(a.id);
        itensDx += a.itensDx;
        itensDv += a.itensDv;
        itensVd += a.itensVd;
        dx.somar(centavos(a.valorDx), t);
        dv.somar(centavos(a.valorDv), t);
        vd.somar(centavos(a.valorVd), t);
        comissao.somar(centavos(a.comissao), t);
        liquido.somar(centavos(a.bruto), t);
        // Pago = tudo que foi aplicado a esta nota, no acerto ou por Lançar pagamento depois (defeito 5 do relatório 9.10).
        const pagoDaNota = this.aplicadoAoAcerto(a.id);
        pago.somar(pagoDaNota, t);
        jaVendeu.push({
          numero: n.numero,
          situacao: n.situacao,
          itensDx: a.itensDx,
          itensDv: a.itensDv,
          itensVd: a.itensVd,
          valorDx: um(centavos(a.valorDx), t),
          valorDv: um(centavos(a.valorDv), t),
          valorVd: um(centavos(a.valorVd), t),
          comissao: um(centavos(a.comissao), t),
          valorLiquido: um(centavos(a.bruto), t),
          pago: um(pagoDaNota, t),
        });
      } else {
        const t = this.taxasDaNota(n.id);
        for (const l of n.linhas) {
          if (l.dx <= 0) continue;
          itensComOCliente += l.dx;
          comOCliente.somar(centavos(l.precoUnitario) * BigInt(l.dx), t);
          comEle.push({
            nota: n.numero,
            maleta: l.maleta,
            codigo: l.codigo ?? '',
            produto: l.nome,
            quantidade: l.dx,
            dataCobranca: n.dataCobranca,
          });
        }
      }
    }
    for (const d of c.debitos) deve.somar(centavos(d.saldo), this.taxasDoAcerto(d.acertoId));
    return {
      tipo: 'SITUACAO_DO_CLIENTE',
      cabecalho: this.cabecalho(),
      cliente: this.clienteDoRelatorio(clienteId),
      itensDx,
      itensDv,
      itensVd,
      valorDx: dx.valor(),
      valorDv: dv.valor(),
      valorVd: vd.valor(),
      jaVendeu,
      pendentes,
      comissao: comissao.valor(),
      valorLiquido: liquido.valor(),
      pago: pago.valor(),
      comOCliente: comEle,
      itensComOCliente,
      valorComOCliente: comOCliente.valor(),
      deve: deve.valor(),
    };
  }

  // --- Peças vendidas por cliente ---

  private pecasVendidas(clienteId: string, de: string, ate: string): PecasVendidasPorCliente {
    const c = this.ler<ClienteCompleto>(
      { operacao: 'consignado.cliente', id: clienteId },
      'CLIENTE_NAO_ENCONTRADO',
    );
    const total = new Soma();
    let itens = 0;
    const linhas: LinhaDePecaVendida[] = [];
    for (const p of c.pedidos) {
      const n = this.ler<DetalheDaNota>(
        { operacao: 'consignado.nota', id: p.notaId },
        'NOTA_NAO_ENCONTRADA',
      );
      const a = n.acerto;
      if (!a?.acertadoEm) continue;
      const dia = diaEmBrasilia(a.acertadoEm);
      if (dia < de || dia > ate) continue;
      const t = this.taxasDoAcerto(a.id);
      for (const l of n.linhas) {
        if (l.vd <= 0) continue;
        const valor = centavos(l.precoUnitario) * BigInt(l.vd);
        itens += l.vd;
        total.somar(valor, t);
        linhas.push({
          data: dia,
          nota: n.numero,
          codigo: l.codigo ?? '',
          produto: l.nome,
          maleta: l.maleta,
          quantidade: l.vd,
          valor: um(valor, t),
        });
      }
    }
    linhas.sort((x, y) => x.data.localeCompare(y.data) || x.nota.localeCompare(y.nota));
    return {
      tipo: 'PECAS_VENDIDAS',
      cabecalho: this.cabecalho(),
      cliente: this.clienteDoRelatorio(clienteId),
      de,
      ate,
      linhas,
      itensVd: itens,
      valorVd: total.valor(),
    };
  }

  // --- Totais da semana ---

  private totaisDaSemana(semanaId: string | undefined): TotaisDaSemanaNoRelatorio {
    const db = this.db();
    const painel = this.ler<PainelDaSemana>(
      { operacao: 'consignado.painel', ...(semanaId ? { semanaId } : {}) },
      'NOTA_NAO_ENCONTRADA',
    );
    if (!painel.semana) throw new ErroDaOperacao('SEM_SEMANA_ABERTA');
    const dx = new Soma();
    const dv = new Soma();
    const vd = new Soma();
    const comissao = new Soma();
    const liquido = new Soma();
    const pendente = new Soma();
    const debitos = new Map<string, ReturnType<typeof debitosDoCliente>>();
    for (const p of painel.pedidos) {
      if (p.acertoId) {
        const t = this.taxasDoAcerto(p.acertoId);
        dx.somar(centavos(p.valorDx), t);
        dv.somar(centavos(p.valorDv), t);
        vd.somar(centavos(p.valorVd), t);
        comissao.somar(centavos(p.comissao), t);
        liquido.somar(centavos(p.valorLiquido), t);
        let d = debitos.get(p.clienteId);
        if (!d) {
          d = debitosDoCliente(db, p.clienteId);
          debitos.set(p.clienteId, d);
        }
        const saldo = d.find((x) => x.acertoId === p.acertoId)?.saldo ?? 0n;
        if (saldo > 0n) pendente.somar(saldo, t);
      } else {
        dx.somar(centavos(p.valorDx), this.taxasDaNota(p.notaId));
      }
    }
    const porForma = new Map(FORMAS_DO_CONSIGNADO.map((f) => [f, new Soma()] as const));
    const recebido = new Soma();
    const recibos = db
      .prepare(
        `SELECT r.id AS recibo_id, f.forma, f.valor FROM formas_recibo_consignado f
           JOIN recibos_consignado r ON r.id = f.recibo_id
          WHERE r.semana_id = ? AND f.deleted_at IS NULL AND ${SQL_RECIBO_ATIVO('r')}`,
      )
      .all(painel.semana.id) as { recibo_id: string; forma: string; valor: string | null }[];
    for (const f of recibos) {
      const soma = porForma.get(f.forma as (typeof FORMAS_DO_CONSIGNADO)[number]);
      if (!soma) continue;
      const t = this.taxasDoRecibo(f.recibo_id);
      soma.somar(centavos(f.valor), t);
      recebido.somar(centavos(f.valor), t);
    }
    return {
      tipo: 'TOTAIS_DA_SEMANA',
      cabecalho: this.cabecalho(),
      semana: painel.semana.numero,
      situacaoDaSemana: painel.semana.situacao,
      pedidos: painel.pedidos.length,
      itensDx: painel.totais.itensDx,
      itensDv: painel.totais.itensDv,
      itensVd: painel.totais.itensVd,
      valorDx: dx.valor(),
      valorDv: dv.valor(),
      valorVd: vd.valor(),
      comissao: comissao.valor(),
      valorLiquido: liquido.valor(),
      recebido: recebido.valor(),
      recebidoPorForma: [...porForma.entries()].map(([forma, soma]) => ({
        forma,
        valor: soma.valor(),
      })),
      pendente: pendente.valor(),
    };
  }

  // --- Situação da maleta ---

  private situacaoDaMaleta(remessaId: string): SituacaoDaMaleta {
    const db = this.db();
    const maletas = this.ler<MaletaDoConsignado[]>(
      { operacao: 'consignado.maletas' },
      'MALETA_NAO_ENCONTRADA',
    );
    const m = maletas.find((x) => x.id === remessaId);
    if (!m) throw new ErroDaOperacao('MALETA_NAO_ENCONTRADA');
    const dx = new Soma();
    const dv = new Soma();
    const vd = new Soma();
    const comClientes = new Soma();
    const naMaleta = new Soma();
    let itensDx = 0;
    let itensDv = 0;
    let itensVd = 0;
    const quem = new Map<string, string[]>();
    const hoje = this.cotacaoAte(diaEmBrasilia(this.o.agora()));
    const notas = db
      .prepare(
        `SELECT DISTINCT i.nota_id FROM itens_nota_consignado i
           JOIN notas_consignado n ON n.id = i.nota_id AND n.deleted_at IS NULL
          WHERE i.remessa_id = ? AND i.deleted_at IS NULL`,
      )
      .all(remessaId) as { nota_id: string }[];
    for (const { nota_id: notaId } of notas) {
      const nota = acharNota(db, notaId);
      if (!nota) continue;
      const doNota = linhasDaNota(db, notaId).filter((l) => l.remessaId === remessaId);
      const acerto = acertoAtivoDaNota(db, notaId);
      const tNota = this.taxasDaNota(notaId);
      const nome =
        (
          db.prepare('SELECT nome FROM clientes WHERE id = ?').get(nota.cliente_id) as
            { nome: string } | undefined
        )?.nome ?? '—';
      for (const l of doNota) {
        itensDx += l.dx;
        const valorDx = l.preco * BigInt(l.dx);
        if (!acerto) {
          dx.somar(valorDx, tNota);
          comClientes.somar(valorDx, tNota);
          const lista = quem.get(l.produtoId) ?? [];
          lista.push(
            `${nome} (${numeroDaNota(nota.semana_numero, nota.sequencia)}): ${String(l.dx)}`,
          );
          quem.set(l.produtoId, lista);
        }
      }
      if (acerto) {
        const t = this.taxasDoAcerto(acerto.id);
        const devolvidos = db
          .prepare(
            `SELECT produto_id, qtd_dv FROM itens_acerto_nota
              WHERE acerto_id = ? AND remessa_id = ? AND deleted_at IS NULL`,
          )
          .all(acerto.id, remessaId) as { produto_id: string; qtd_dv: number | null }[];
        for (const l of doNota) {
          const devolvida = devolvidos
            .filter((x) => x.produto_id === l.produtoId)
            .reduce((s, x) => s + (x.qtd_dv ?? 0), 0);
          const vendida = l.dx - devolvida;
          itensDv += devolvida;
          itensVd += vendida;
          dx.somar(l.preco * BigInt(l.dx), t);
          dv.somar(l.preco * BigInt(devolvida), t);
          vd.somar(l.preco * BigInt(vendida), t);
        }
      }
    }
    for (const p of m.pecas) naMaleta.somar(centavos(p.precoUnitario) * BigInt(p.disponivel), hoje);
    const pecas: PecaDaSituacaoDaMaleta[] = m.pecas.map((p) => ({
      codigo: p.codigo ?? '',
      produto: p.nome,
      enviada: p.enviada,
      naMaleta: p.disponivel,
      comClientes: p.comClientes,
      vendida: p.vendida,
      devolvidaALoja: p.devolvidaALoja,
      comQuem: quem.get(p.produtoId) ?? [],
    }));
    return {
      tipo: 'SITUACAO_DA_MALETA',
      cabecalho: this.cabecalho(),
      maleta: codigoDaMaleta(db, remessaId),
      status: m.status,
      enviadaEm: m.enviadaEm,
      pecas,
      totalEnviada: m.totalEnviada,
      totalNaMaleta: m.totalDisponivel,
      totalComClientes: m.totalComClientes,
      totalVendida: m.totalVendida,
      itensDx,
      itensDv,
      itensVd,
      valorDx: dx.valor(),
      valorDv: dv.valor(),
      valorVd: vd.valor(),
      valorNaMaleta: naMaleta.valor(),
      valorComClientes: comClientes.valor(),
    };
  }
}

import type { BancoAberto } from '../banco/banco-local';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import { diferencaDaTroca } from '../../shared/diferenca-da-troca';
import type {
  AutorizacoesDoVendedor,
  AvisoDeAutorizacao,
  CreditosDoCliente,
  DevolucaoOuTroca,
  PedidoDeAutorizacao,
  PreviaDaDevolucao,
  PreviaDaTroca,
  SituacaoDoPedido,
  SituacaoNaLista,
  VendaComAPeca,
} from '../../shared/dados';
import {
  dentroDoPrazo,
  diaEmBrasilia,
  diasEntre,
  garantiaAte,
  lerPrazos,
  prazoDoMotivo,
} from '../../shared/garantia';
import type {
  LinhaDaVendaParaEnviar,
  MotivoDeDevolucao,
  PecaQueVolta,
} from '../../shared/operacoes';
import { cotacaoVigenteEm } from '../sync/consignado-comum';
import { CHAVES, lerEstado } from '../sync/estado-sync';
import {
  autorizacaoUsadaAqui,
  creditosDoCliente,
  devolucaoAtiva,
  ehDoVendedor,
  pecasDaVenda,
  type PreviaInterna,
  preverDevolucao,
  trocaCanceladaAqui,
  vendaEstornada,
} from '../sync/operacoes-da-fase-6';
import { ErroDaOperacao, MENSAGENS_DAS_OPERACOES } from '../sync/operacoes-locais';
import { pecaDoProduto } from './peca-do-produto';

/** Texto do glossário (parte 6, "Devolução, troca e crédito no Corona Vendas"). */
export const TEXTO_PEDIDO_ESPERANDO =
  'Você já pediu autorização à dona para esta venda. Espere a resposta.';

/** Por quantos dias o aviso do pedido negado fica na tela inicial (dúvida V5). */
const DIAS_DO_AVISO_NEGADO = 7;

const texto = (c: bigint): string => textoDeCentavos(c);
const dinheiro = (v: string | null | undefined): bigint => exigirCentavos(v ?? '0');

export interface OpcoesDaFase6 {
  banco: () => BancoAberto | null;
  agora?: () => Date;
}

/**
 * As consultas das telas de devolução, troca e crédito (tarefa 6.8), lidas do banco do computador, sem rede. Tudo é
 * prévia: o que vale chega do servidor pelo pull. Nada aqui mostra custo, estoque da loja ou dados de outro vendedor
 * (defesa: o pull já só traz o que é dele, e cada lista confere o vendedor de novo).
 */
export class ConsultasDaFase6 {
  constructor(private readonly o: OpcoesDaFase6) {}

  private db(): BancoAberto {
    const db = this.o.banco();
    if (!db) throw new Error('Banco local fechado.');
    return db;
  }

  private agora(): Date {
    return this.o.agora?.() ?? new Date();
  }

  private prazos() {
    return lerPrazos(lerEstado(this.db(), CHAVES.parametros));
  }

  /** "Vendas com esta peça": as vendas deste vendedor com a peça lida, da mais nova para a mais antiga. */
  vendasComAPeca(codigo: string): VendaComAPeca[] {
    const db = this.db();
    const lido = codigo.trim().toLowerCase();
    if (lido === '' || lido.length > 100) return [];
    const produtos = (
      db
        .prepare(
          `SELECT DISTINCT produto_id FROM produto_codigos
            WHERE lower(trim(codigo)) = ? AND deleted_at IS NULL AND ativo IS NOT 0`,
        )
        .all(lido) as { produto_id: string }[]
    ).map((r) => r.produto_id);
    const prazos = this.prazos();
    const saida: VendaComAPeca[] = [];
    for (const produto of produtos) {
      const vendas = db
        .prepare(
          `SELECT DISTINCT v.id, v.status, v.vendida_em, v.cliente_id, c.nome AS cliente, v.vendedor_id, v.total,
                  r.status AS maleta
             FROM itens_venda i JOIN vendas v ON v.id = i.venda_id
             LEFT JOIN clientes c ON c.id = v.cliente_id
             LEFT JOIN remessas r ON r.id = v.remessa_id
            WHERE i.produto_id = ? AND i.deleted_at IS NULL AND v.deleted_at IS NULL`,
        )
        .all(produto) as {
        id: string;
        status: string | null;
        vendida_em: string;
        cliente_id: string | null;
        cliente: string | null;
        vendedor_id: string | null;
        total: string | null;
        maleta: string | null;
      }[];
      for (const v of vendas) {
        if (!ehDoVendedor(db, v.vendedor_id) || vendaEstornada(db, v)) continue;
        const linha = pecasDaVenda(db, v.id).get(produto);
        if (!linha) continue;
        // A venda a prazo também se devolve (tarefa 6.6): a devolução abate o que o cliente ainda deve.
        const bloqueio = v.maleta === 'EM_ACERTO' ? MENSAGENS_DAS_OPERACOES.MALETA_EM_ACERTO : null;
        saida.push({
          vendaId: v.id,
          vendidaEm: v.vendida_em,
          clienteId: v.cliente_id ?? '',
          clienteNome: v.cliente ?? '',
          peca: pecaDoProduto(db, produto),
          vendidas: linha.vendidas,
          jaDevolvidas: linha.jaDevolvidas,
          podemVoltar: linha.podemVoltar,
          desistenciaAte: garantiaAte(v.vendida_em, prazoDoMotivo('ARREPENDIMENTO', prazos)),
          defeitoAte: garantiaAte(v.vendida_em, prazoDoMotivo('DEFEITO', prazos)),
          bloqueio,
        });
      }
    }
    return saida.sort(
      (a, b) => b.vendidaEm.localeCompare(a.vendidaEm) || b.vendaId.localeCompare(a.vendaId),
    );
  }

  /** A venda escolhida para a devolução: as peças, a prévia do valor, o prazo e a autorização. Só leitura. */
  previaDaDevolucao(p: {
    vendaId: string;
    motivo?: MotivoDeDevolucao;
    observacao?: string;
    itens: readonly PecaQueVolta[];
  }): PreviaDaDevolucao {
    const db = this.db();
    const agora = this.agora();
    const venda = db
      .prepare(
        `SELECT v.id, v.cliente_id, c.nome AS cliente, v.vendida_em, v.remessa_id, v.vendedor_id
           FROM vendas v LEFT JOIN clientes c ON c.id = v.cliente_id
          WHERE v.id = ? AND v.deleted_at IS NULL`,
      )
      .get(p.vendaId) as
      | {
          id: string;
          cliente_id: string | null;
          cliente: string | null;
          vendida_em: string;
          remessa_id: string | null;
          vendedor_id: string | null;
        }
      | undefined;
    if (!venda || !ehDoVendedor(db, venda.vendedor_id))
      throw new ErroDaOperacao('VENDA_NAO_ENCONTRADA');
    const motivo = p.motivo ?? 'ARREPENDIMENTO';
    const itens = p.itens.filter((i) => i.quantidade > 0);
    let previa: PreviaInterna | null = null;
    let problema: string | null = null;
    try {
      previa = preverDevolucao(
        db,
        agora,
        { vendaId: venda.id, motivo, observacao: p.observacao ?? null, itens },
        false,
        undefined,
        true,
      );
    } catch (e) {
      if (!(e instanceof ErroDaOperacao)) throw e;
      problema = e.message;
    }
    const prazos = this.prazos();
    const hoje = diaEmBrasilia(agora);
    const pedidoEsperando = this.existe(
      "SELECT 1 FROM autorizacoes_excecao WHERE venda_id = ? AND situacao = 'PEDIDA' AND deleted_at IS NULL",
      venda.id,
    );
    const jaPedido =
      pedidoEsperando ||
      this.existe(
        `SELECT 1 FROM autorizacoes_excecao WHERE venda_id = ? AND situacao = 'APROVADA'
            AND substr(valida_ate, 1, 10) >= ? AND deleted_at IS NULL`,
        venda.id,
        hoje,
      );
    const dentro = previa?.dentroDoPrazo ?? dentroDoPrazo(venda.vendida_em, agora, motivo, prazos);
    if (previa && !previa.dentroDoPrazo && !previa.autorizacao)
      problema = pedidoEsperando
        ? TEXTO_PEDIDO_ESPERANDO
        : MENSAGENS_DAS_OPERACOES[previa.problemaDaAutorizacao ?? 'FORA_DO_PRAZO'];
    return {
      vendaId: venda.id,
      clienteId: venda.cliente_id ?? '',
      clienteNome: venda.cliente ?? '',
      vendidaEm: venda.vendida_em,
      remessaId: venda.remessa_id ?? '',
      pecas: [...pecasDaVenda(db, venda.id).values()].map((l) => ({
        produto: pecaDoProduto(db, l.produtoId),
        vendidas: l.vendidas,
        jaDevolvidas: l.jaDevolvidas,
        podemVoltar: l.podemVoltar,
      })),
      valor: texto(previa?.valor ?? 0n),
      abatido: texto(previa?.abatido ?? 0n),
      emDinheiro: texto(previa?.emDinheiro ?? 0n),
      emCredito: texto(previa?.emCredito ?? 0n),
      destinos: (previa?.calculadas ?? []).map((c) => ({
        produtoId: c.produtoId,
        estado: c.estado,
        destino: c.estado === 'BOA' && previa?.maletaComOVendedor ? 'MALETA' : 'LOJA',
      })),
      dentroDoPrazo: dentro,
      desistenciaAte: garantiaAte(venda.vendida_em, prazoDoMotivo('ARREPENDIMENTO', prazos)),
      defeitoAte: garantiaAte(venda.vendida_em, prazoDoMotivo('DEFEITO', prazos)),
      autorizacao: previa?.autorizacao ?? null,
      pedidoEsperando,
      problema,
      podePedirAutorizacao:
        previa !== null && !previa.dentroDoPrazo && !previa.autorizacao && !jaPedido,
    };
  }

  /** A troca antes de registrar: o valor do que volta, o das peças novas e a diferença. Só leitura. */
  previaDaTroca(p: {
    devolucao: {
      vendaId: string;
      motivo?: MotivoDeDevolucao;
      observacao?: string;
      itens: readonly PecaQueVolta[];
    };
    remessaId?: string;
    itens: readonly LinhaDaVendaParaEnviar[];
  }): PreviaDaTroca {
    const db = this.db();
    const agora = this.agora();
    let problema: string | null = null;
    let valorA = 0n;
    let creditoDeA = 0n;
    let abatidoDeA = 0n;
    try {
      const a = preverDevolucao(
        db,
        agora,
        {
          vendaId: p.devolucao.vendaId,
          motivo: p.devolucao.motivo ?? 'ARREPENDIMENTO',
          observacao: p.devolucao.observacao ?? null,
          itens: p.devolucao.itens.filter((i) => i.quantidade > 0),
        },
        true,
        undefined,
        true,
      );
      valorA = a.valor;
      creditoDeA = a.emCredito;
      abatidoDeA = a.abatido;
      if (!a.dentroDoPrazo && !a.autorizacao)
        problema = MENSAGENS_DAS_OPERACOES[a.problemaDaAutorizacao ?? 'FORA_DO_PRAZO'];
    } catch (e) {
      if (!(e instanceof ErroDaOperacao)) throw e;
      problema = e.message;
    }
    const remessaId = p.remessaId ?? this.maletaConferida();
    let valorB = 0n;
    for (const i of p.itens) {
      const peca = remessaId
        ? (db
            .prepare(
              `SELECT preco_remessa FROM itens_remessa
                WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL LIMIT 1`,
            )
            .get(remessaId, i.produtoId) as { preco_remessa: string } | undefined)
        : undefined;
      if (!peca) {
        problema ??= MENSAGENS_DAS_OPERACOES.PECA_FORA_DA_MALETA;
        continue;
      }
      valorB += dinheiro(peca.preco_remessa) * BigInt(i.quantidade) - dinheiro(i.desconto);
    }
    return {
      remessaId,
      valorQueVolta: texto(valorA),
      valorNovo: texto(valorB),
      abatido: texto(abatidoDeA),
      diferenca: texto(diferencaDaTroca(valorB, creditoDeA)),
      problema,
    };
  }

  /** A maleta conferida deste vendedor (de onde saem as peças da troca), ou nenhuma. */
  maletaConferida(): string | null {
    const db = this.db();
    const r = db
      .prepare(
        `SELECT id, vendedor_id FROM remessas WHERE status = 'CONFERIDA' AND deleted_at IS NULL
          ORDER BY enviada_em DESC, id DESC`,
      )
      .all() as { id: string; vendedor_id: string | null }[];
    return r.find((m) => ehDoVendedor(db, m.vendedor_id))?.id ?? null;
  }

  /** "Minhas devoluções e trocas", da mais nova para a mais antiga. */
  listar(f: { de?: string; ate?: string }): DevolucaoOuTroca[] {
    const db = this.db();
    const agora = this.agora();
    const saida: DevolucaoOuTroca[] = [];
    const devolucoes = db
      .prepare(
        `SELECT d.id, d.venda_id, d.vendedor_id, d.situacao, d.origem_local, d.devolvida_em, d.valor_devolvido,
                d.valor_reembolsado, d.valor_em_credito, d.valor_abatido, coalesce(c.nome, cv.nome) AS cliente
           FROM devolucoes d
           LEFT JOIN clientes c ON c.id = d.cliente_id
           LEFT JOIN vendas v ON v.id = d.venda_id
           LEFT JOIN clientes cv ON cv.id = v.cliente_id
          WHERE d.deleted_at IS NULL AND coalesce(d.destino, 'REEMBOLSO') <> 'TROCA'`,
      )
      .all() as {
      id: string;
      venda_id: string;
      vendedor_id: string | null;
      situacao: string | null;
      origem_local: string;
      devolvida_em: string | null;
      valor_devolvido: string | null;
      valor_reembolsado: string | null;
      valor_em_credito: string | null;
      valor_abatido: string | null;
      cliente: string | null;
    }[];
    for (const d of devolucoes) {
      if (!ehDoVendedor(db, d.vendedor_id)) continue;
      const situacao: SituacaoNaLista = !devolucaoAtiva(db, d.id)
        ? 'CANCELADA'
        : d.origem_local === 'PROVISORIO'
          ? 'AGUARDANDO_ENVIO'
          : situacaoDaDevolucao(d.situacao);
      saida.push({
        id: d.id,
        tipo: 'DEVOLUCAO',
        em: d.devolvida_em ?? '',
        vendaId: d.venda_id,
        clienteNome: d.cliente ?? '',
        pecas: this.pecasDaDevolucao(d.id),
        valor: texto(dinheiro(d.valor_devolvido)),
        abatido: texto(dinheiro(d.valor_abatido)),
        emDinheiro: texto(dinheiro(d.valor_reembolsado)),
        emCredito: texto(dinheiro(d.valor_em_credito)),
        diferenca: null,
        situacao,
        podeCancelar:
          ['AGUARDANDO_ENVIO', 'ACEITA', 'EM_REVISAO'].includes(situacao) &&
          mesmoDia(d.devolvida_em, agora),
      });
    }
    const trocas = db
      .prepare(
        `SELECT t.id, t.venda_origem_id, t.devolucao_id, t.vendedor_id, t.situacao, t.origem_local, t.trocada_em,
                t.diferenca, d.situacao AS situacao_da_devolucao, d.valor_devolvido, d.valor_em_credito, d.valor_abatido,
                coalesce(c.nome, cv.nome) AS cliente
           FROM trocas t
           LEFT JOIN devolucoes d ON d.id = t.devolucao_id
           LEFT JOIN clientes c ON c.id = t.cliente_id
           LEFT JOIN vendas v ON v.id = t.venda_origem_id
           LEFT JOIN clientes cv ON cv.id = v.cliente_id
          WHERE t.deleted_at IS NULL`,
      )
      .all() as {
      id: string;
      venda_origem_id: string;
      devolucao_id: string | null;
      vendedor_id: string | null;
      situacao: string | null;
      origem_local: string;
      trocada_em: string | null;
      diferenca: string | null;
      situacao_da_devolucao: string | null;
      valor_devolvido: string | null;
      valor_em_credito: string | null;
      valor_abatido: string | null;
      cliente: string | null;
    }[];
    for (const t of trocas) {
      if (!ehDoVendedor(db, t.vendedor_id)) continue;
      const cancelada = t.situacao === 'CANCELADA' || trocaCanceladaAqui(db, t.id);
      const situacao: SituacaoNaLista = cancelada
        ? 'CANCELADA'
        : t.origem_local === 'PROVISORIO'
          ? 'AGUARDANDO_ENVIO'
          : t.situacao_da_devolucao === 'EM_REVISAO'
            ? 'EM_REVISAO'
            : 'FEITA';
      const devolucaoQueDeixa =
        t.origem_local === 'PROVISORIO' ||
        t.situacao_da_devolucao === 'ACEITA' ||
        t.situacao_da_devolucao === 'EM_REVISAO';
      saida.push({
        id: t.id,
        tipo: 'TROCA',
        em: t.trocada_em ?? '',
        vendaId: t.venda_origem_id,
        clienteNome: t.cliente ?? '',
        pecas: t.devolucao_id ? this.pecasDaDevolucao(t.devolucao_id) : 0,
        valor: texto(dinheiro(t.valor_devolvido)),
        abatido: texto(dinheiro(t.valor_abatido)),
        emDinheiro: '0.00',
        emCredito: texto(dinheiro(t.valor_em_credito)),
        diferenca: texto(dinheiro(t.diferenca)),
        situacao,
        podeCancelar: !cancelada && devolucaoQueDeixa && mesmoDia(t.trocada_em, agora),
      });
    }
    for (const l of saida) l.cotacao = l.em ? this.cotacaoAte(l.em) : null;
    return saida
      .filter((l) => {
        const dia = l.em ? diaEmBrasilia(l.em) : '';
        return (!f.de || dia >= f.de) && (!f.ate || dia <= f.ate);
      })
      .sort((a, b) => b.em.localeCompare(a.em) || b.id.localeCompare(a.id));
  }

  /** Os pedidos de autorização e os avisos das respostas da dona (dúvida V5). */
  autorizacoes(): AutorizacoesDoVendedor {
    const db = this.db();
    const hoje = diaEmBrasilia(this.agora());
    const linhas = db
      .prepare(
        `SELECT a.id, a.venda_id, a.vendedor_id, a.situacao, a.origem_local, a.valida_ate, a.pedida_em,
                a.decidida_em, coalesce(c.nome, cv.nome) AS cliente
           FROM autorizacoes_excecao a
           LEFT JOIN clientes c ON c.id = a.cliente_id
           LEFT JOIN vendas v ON v.id = a.venda_id
           LEFT JOIN clientes cv ON cv.id = v.cliente_id
          WHERE a.deleted_at IS NULL ORDER BY a.pedida_em DESC, a.id DESC`,
      )
      .all() as {
      id: string;
      venda_id: string;
      vendedor_id: string | null;
      situacao: string | null;
      origem_local: string;
      valida_ate: string | null;
      pedida_em: string | null;
      decidida_em: string | null;
      cliente: string | null;
    }[];
    const pedidos: PedidoDeAutorizacao[] = [];
    const avisos: AvisoDeAutorizacao[] = [];
    for (const a of linhas) {
      if (!ehDoVendedor(db, a.vendedor_id)) continue;
      const validaAte = a.valida_ate ? a.valida_ate.slice(0, 10) : null;
      let situacao: SituacaoDoPedido;
      if (a.origem_local === 'PROVISORIO') situacao = 'AGUARDANDO_ENVIO';
      else if (a.situacao === 'APROVADA')
        situacao = autorizacaoUsadaAqui(db, a.id)
          ? 'USADA'
          : !validaAte || validaAte < hoje
            ? 'VENCIDA'
            : 'APROVADA';
      else if (
        a.situacao === 'PEDIDA' ||
        a.situacao === 'NEGADA' ||
        a.situacao === 'USADA' ||
        a.situacao === 'VENCIDA'
      )
        situacao = a.situacao;
      else situacao = 'PEDIDA';
      pedidos.push({
        id: a.id,
        vendaId: a.venda_id,
        clienteNome: a.cliente ?? '',
        pedidaEm: a.pedida_em ?? '',
        pecas: this.somaDasPecas('itens_autorizacao', 'autorizacao_id', a.id),
        situacao,
        validaAte,
      });
      if (situacao === 'APROVADA')
        avisos.push({
          id: a.id,
          tipo: 'APROVADA',
          vendaId: a.venda_id,
          clienteNome: a.cliente ?? '',
          validaAte,
        });
      else if (
        situacao === 'NEGADA' &&
        a.decidida_em &&
        diasEntre(diaEmBrasilia(a.decidida_em), hoje) <= DIAS_DO_AVISO_NEGADO
      )
        avisos.push({
          id: a.id,
          tipo: 'NEGADA',
          vendaId: a.venda_id,
          clienteNome: a.cliente ?? '',
          validaAte: null,
        });
    }
    return { pedidos, avisos };
  }

  /** Os créditos em aberto do cliente com este vendedor, com o saldo do computador. */
  creditosDoCliente(clienteId: string): CreditosDoCliente {
    const creditos = creditosDoCliente(this.db(), clienteId);
    return {
      total: texto(creditos.reduce((t, c) => t + c.saldo, 0n)),
      creditos: creditos.map((c) => ({ id: c.id, saldo: texto(c.saldo) })),
    };
  }

  // --- apoio ---

  /** A cotação mais nova até o dia (a do dia do lançamento, ou a última antes dele). */
  private cotacaoAte(instante: string): { data: string; dolar: string; guarani: string } | null {
    const c = cotacaoVigenteEm(this.db(), instante);
    return c ? { data: c.data, dolar: c.dolar, guarani: c.guarani } : null;
  }

  private existe(sql: string, ...p: unknown[]): boolean {
    return (
      (
        this.db()
          .prepare(`SELECT EXISTS (${sql}) AS e`)
          .get(...p) as { e: number }
      ).e === 1
    );
  }

  /** Peças de uma devolução: as linhas do servidor quando já chegaram; senão, as provisórias. */
  private pecasDaDevolucao(devolucaoId: string): number {
    return this.somaDasPecas('itens_devolucao', 'devolucao_id', devolucaoId);
  }

  private somaDasPecas(tabela: string, coluna: string, id: string): number {
    const linhas = this.db()
      .prepare(
        `SELECT quantidade, origem_local FROM ${tabela} WHERE ${coluna} = ? AND deleted_at IS NULL`,
      )
      .all(id) as { quantidade: number; origem_local: string }[];
    const doServidor = linhas.filter((l) => l.origem_local === 'SERVIDOR');
    return (doServidor.length > 0 ? doServidor : linhas).reduce((t, l) => t + l.quantidade, 0);
  }
}

function situacaoDaDevolucao(s: string | null): SituacaoNaLista {
  switch (s) {
    case 'EM_REVISAO':
    case 'MANTIDA':
    case 'NAO_ACEITA':
    case 'CANCELADA':
      return s;
    default:
      return 'ACEITA';
  }
}

function mesmoDia(iso: string | null, agora: Date): boolean {
  return iso !== null && iso !== '' && diaEmBrasilia(iso) === diaEmBrasilia(agora);
}

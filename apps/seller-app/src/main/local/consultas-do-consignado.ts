import { percentualDe, situacaoDaNota, textoDePorcentagem } from '@corona/shared/consignado';
import { diaEmBrasilia } from '../../shared/garantia';
import type {
  AcertoDaTela,
  ClienteCompleto,
  ClienteDaLista,
  DebitoDoCliente,
  DetalheDaNota,
  ItemDoPedido,
  LinhaDaNota,
  LinhaDeNotaNaBusca,
  LinhaDoPedido,
  MaletaDoConsignado,
  PagamentoDoHistorico,
  PainelDaSemana,
  PedidoDoHistorico,
  PedidoEmMontagem,
  PecaDaMaletaDoConsignado,
  PrevisaoDoAcerto,
  ReciboDaTela,
  ResultadoDaPeca,
  ResultadoDaPesquisa,
  SituacaoDoPedido,
  TotaisDaSemana,
} from '../../shared/dados-do-consignado';
import { exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import {
  FORMAS_DO_CONSIGNADO,
  type FormaDoConsignado,
  type PedidoDoConsignado,
} from '../../shared/operacoes-do-consignado';
import type { BancoAberto } from '../banco/banco-local';
import {
  type AcertoLocal,
  acertoAtivoDaNota,
  acharNota,
  codigoDaMaleta,
  cotacaoAteODia,
  tamTonDoProduto,
  dataDeCobranca,
  type DebitoLocal,
  debitosDoCliente,
  foiRemarcada,
  linhasDaNota,
  type NotaLocal,
  numeroDaNota,
  numeroDoRecibo,
  pendenciaDoCliente,
  pracaDoRepresentante,
  semanaAtual,
  SQL_ACERTO_ATIVO,
  SQL_RECIBO_ATIVO,
  sobraAAplicarDoCliente,
} from '../sync/consignado-comum';
import {
  ErroDaOperacao,
  MENSAGENS_DAS_OPERACOES,
  type OperacoesLocais,
} from '../sync/operacoes-locais';
import {
  maletasEmUso,
  previaDoAcerto,
  produtosDoCodigo,
  type RascunhoDoPedido,
} from '../sync/operacoes-do-consignado';
import type { Falha, Resultado } from '../tipos';

/** Texto em minúsculas e sem acento, para a busca achar "Jose", "José" e "JOSÉ" do mesmo jeito. */
export function semAcento(texto: string): string {
  return texto.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}
import { pecaDoProduto } from './peca-do-produto';
import { RelatoriosDoConsignado } from './relatorios-do-consignado';

/** O que as consultas precisam do armazém de fotos do cliente (criptografado, com fila de envio). */
export interface FotosDoClienteLocais {
  /** Guarda a foto (JPEG em endereço de dados) e devolve o SHA-256; `null` se não for uma imagem aceita. */
  guardar(dataUrl: string): string | null;
  /** A foto como endereço de dados, ou `null` se não está neste computador. */
  ver(hash: string): string | null;
}

export interface OpcoesDasConsultasDoConsignado {
  banco: () => BancoAberto | null;
  operacoes: OperacoesLocais;
  fotos?: FotosDoClienteLocais;
  agora?: () => Date;
}

const ok = (dados: unknown): Resultado<unknown> => ({ ok: true, dados });
const falhou = (mensagem: string, status = 404, codigo?: string): Resultado<unknown> => ({
  ok: false,
  falha: { tipo: 'recusada', status, mensagem, ...(codigo ? { codigo } : {}) } as Falha,
});

const dinheiro = textoDeCentavos;
const centavos = (t: string | null | undefined): bigint => exigirCentavos(t ?? '0');
const ZERO = '0.00';

export function ehPedidoDoConsignado(pedido: { operacao: string }): pedido is PedidoDoConsignado {
  return pedido.operacao.startsWith('consignado.');
}

/**
 * As telas do consignado ao cliente leem DAQUI, do banco local (tarefa 9.2; ADR-0016): a nota, o acerto, a pendência e o
 * saldo da maleta aparecem na hora e sem internet. A situação de cada nota e a pendência são sempre calculadas dos fatos
 * (acerto valendo, remarcação e saldo do débito), com as mesmas fórmulas do servidor (`@corona/shared/consignado`).
 */
export class ConsultasDoConsignado {
  constructor(private readonly o: OpcoesDasConsultasDoConsignado) {}

  private db(): BancoAberto {
    const db = this.o.banco();
    if (!db) throw new Error('Banco local fechado.');
    return db;
  }

  private agora(): Date {
    return this.o.agora?.() ?? new Date();
  }

  chamar(pedido: PedidoDoConsignado): Resultado<unknown> {
    try {
      return this.atender(pedido);
    } catch (erro) {
      if (erro instanceof ErroDaOperacao) {
        return {
          ok: false,
          falha: {
            tipo: 'recusada',
            status: 409,
            mensagem: erro.message,
            codigo: erro.codigo,
          },
        };
      }
      const mensagem = erro instanceof Error ? erro.message : '';
      return {
        ok: false,
        falha: { tipo: 'recusada', status: 500, ...(mensagem ? { mensagem } : {}) },
      };
    }
  }

  private atender(pedido: PedidoDoConsignado): Resultado<unknown> {
    const op = this.o.operacoes;
    switch (pedido.operacao) {
      case 'consignado.painel':
        return ok(this.painel(pedido.semanaId));
      case 'consignado.semanaNova': {
        const r = op.abrirSemana({
          id: pedido.id,
          ...(pedido.numero ? { numero: pedido.numero } : {}),
        });
        return ok({ ...r, painel: this.painel() });
      }
      case 'consignado.clientes':
        return ok(this.clientes(pedido.busca ?? ''));
      case 'consignado.cliente': {
        const c = this.cliente(pedido.id);
        return c ? ok(c) : falhou(MENSAGENS_DAS_OPERACOES.CLIENTE_NAO_ENCONTRADO);
      }
      case 'consignado.clienteCadastrar': {
        op.cadastrarClienteDoConsignado(pedido.cliente);
        return ok(this.cliente(pedido.cliente.id));
      }
      case 'consignado.clienteAlterar': {
        if (!this.cliente(pedido.id)) return falhou(MENSAGENS_DAS_OPERACOES.CLIENTE_NAO_ENCONTRADO);
        op.alterarClienteDoConsignado(pedido.id, pedido.campos);
        return ok(this.cliente(pedido.id));
      }
      case 'consignado.fotoGuardar': {
        const hash = this.o.fotos?.guardar(pedido.dataUrl) ?? null;
        if (!hash) throw new ErroDaOperacao('FOTO_INVALIDA');
        return ok({ hash });
      }
      case 'consignado.clienteTrocarFoto': {
        if (!this.cliente(pedido.id)) return falhou(MENSAGENS_DAS_OPERACOES.CLIENTE_NAO_ENCONTRADO);
        op.trocarFotoDoCliente(pedido.id, pedido.tipo, pedido.hash);
        return ok(this.cliente(pedido.id));
      }
      case 'consignado.fotoVer':
        return ok({ dataUrl: this.o.fotos?.ver(pedido.hash) ?? null });
      case 'consignado.pedidoVer':
        return ok(this.pedido());
      case 'consignado.pedidoDefinir':
        op.definirPedido({
          ...(pedido.clienteId !== undefined ? { clienteId: pedido.clienteId } : {}),
          ...(pedido.remessaId !== undefined ? { remessaId: pedido.remessaId } : {}),
          ...(pedido.dataCobranca !== undefined ? { dataCobranca: pedido.dataCobranca } : {}),
        });
        return ok(this.pedido());
      case 'consignado.pedidoBipar': {
        const r = op.biparPedido(pedido.codigo);
        if (r.resultado === 'ADICIONADA')
          return ok({ resultado: 'ADICIONADA', pedido: this.pedido() });
        if (r.resultado === 'EM_OUTRA_MALETA')
          return ok({
            resultado: 'EM_OUTRA_MALETA',
            maleta: codigoDaMaleta(this.db(), r.remessaId),
          });
        if (r.resultado === 'SEM_SALDO')
          return ok({ resultado: 'SEM_SALDO', disponivel: r.disponivel });
        return ok({ resultado: 'FORA_DA_MALETA' });
      }
      case 'consignado.pedidoQuantidade':
        op.ajustarItemDoPedido(pedido.itemId, pedido.quantidade);
        return ok(this.pedido());
      case 'consignado.pedidoFinalizar': {
        const r = op.finalizarPedido({ id: pedido.id });
        return ok({ ...r, nota: this.nota(r.notaId) });
      }
      case 'consignado.pedidoDescartar':
        op.descartarPedido();
        return ok(this.pedido());
      case 'consignado.maletas':
        return ok(this.maletas());
      case 'consignado.peca':
        return ok(this.peca(pedido.remessaId, pedido.codigo));
      case 'consignado.notas':
        return ok(this.notas(pedido.texto ?? '', pedido.soEmAberto === true));
      case 'consignado.nota': {
        const n = this.nota(pedido.id);
        return n ? ok(n) : falhou(MENSAGENS_DAS_OPERACOES.NOTA_NAO_ENCONTRADA);
      }
      case 'consignado.acertoPrevia':
        return ok(this.previa(pedido.acerto));
      case 'consignado.acertoSalvar': {
        const r = op.registrarAcerto(pedido.acerto);
        return ok({ ...r, nota: this.nota(pedido.acerto.notaId) });
      }
      case 'consignado.reposicao': {
        const r = op.registrarReposicao(pedido.reposicao);
        return ok({ ...r, nota: this.nota(r.notaId) });
      }
      case 'consignado.trocaNaNota': {
        const r = op.registrarTrocaNaNota(pedido.troca);
        return ok({ ...r, nota: this.nota(pedido.troca.notaId) });
      }
      case 'consignado.remarcar': {
        const r = op.remarcarNota({
          id: pedido.id,
          notaId: pedido.notaId,
          dataCobranca: pedido.dataCobranca,
        });
        return ok({ ...r, nota: this.nota(pedido.notaId) });
      }
      case 'consignado.pagamento': {
        const r = op.registrarPagamento(pedido.pagamento);
        return ok({ ...r, cliente: this.cliente(pedido.pagamento.clienteId) });
      }
      case 'consignado.estornarAcerto': {
        const r = op.estornarAcerto({
          id: pedido.id,
          acertoId: pedido.acertoId,
          motivo: pedido.motivo,
        });
        const nota = this.db()
          .prepare('SELECT nota_id FROM acertos_nota WHERE id = ?')
          .get(pedido.acertoId) as { nota_id: string } | undefined;
        return ok({ ...r, nota: nota ? this.nota(nota.nota_id) : null });
      }
      case 'consignado.estornarRecibo': {
        const r = op.estornarRecibo({
          id: pedido.id,
          reciboId: pedido.reciboId,
          motivo: pedido.motivo,
        });
        const recibo = this.db()
          .prepare('SELECT cliente_id FROM recibos_consignado WHERE id = ?')
          .get(pedido.reciboId) as { cliente_id: string } | undefined;
        return ok({ ...r, cliente: recibo ? this.cliente(recibo.cliente_id) : null });
      }
      case 'consignado.relatorio':
        return ok(
          new RelatoriosDoConsignado({
            banco: () => this.db(),
            agora: () => this.agora(),
            consultar: (p) => this.chamar(p),
          }).montar(pedido.relatorio),
        );
      case 'consignado.pesquisar':
        return ok(this.pesquisar(pedido.texto));
    }
  }

  // --- peças ---

  private codigosDoProduto(produtoId: string): string[] {
    return (
      this.db()
        .prepare(
          `SELECT codigo FROM produto_codigos WHERE produto_id = ? AND deleted_at IS NULL AND ativo IS NOT 0`,
        )
        .all(produtoId) as { codigo: string }[]
    ).map((c) => c.codigo.trim().toLowerCase());
  }

  private pecaDaTela(produtoId: string): {
    nome: string;
    codigo: string | null;
    fotoHash: string | null;
    codigos: string[];
    tamTon: string | null;
  } {
    const p = pecaDoProduto(this.db(), produtoId);
    return {
      tamTon: tamTonDoProduto(this.db(), produtoId),
      nome: p.nome,
      codigo: p.codigoFornecedor ?? p.codigo,
      fotoHash: p.fotoHash,
      codigos: this.codigosDoProduto(produtoId),
    };
  }

  private precoNaMaleta(remessaId: string, produtoId: string): string | null {
    const r = this.db()
      .prepare(
        `SELECT preco_remessa FROM itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL LIMIT 1`,
      )
      .get(remessaId, produtoId) as { preco_remessa: string } | undefined;
    return r?.preco_remessa ?? null;
  }

  private disponivel(remessaId: string, produtoId: string): number {
    const r = this.db()
      .prepare(
        `SELECT coalesce((SELECT sum(qtd_enviada) FROM itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL), 0)
              + coalesce((SELECT sum(quantidade) FROM movimentos_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL), 0) AS saldo`,
      )
      .get(remessaId, produtoId, remessaId, produtoId) as { saldo: number };
    return r.saldo;
  }

  private peca(remessaId: string, codigo: string): ResultadoDaPeca {
    const db = this.db();
    const produtos = produtosDoCodigo(db, codigo);
    const aqui = produtos.find((p) => this.precoNaMaleta(remessaId, p) !== null);
    if (aqui) {
      const p = this.pecaDaTela(aqui);
      return {
        tipo: 'ACHADA',
        produtoId: aqui,
        remessaId,
        maleta: codigoDaMaleta(this.db(), remessaId),
        nome: p.nome,
        codigo: p.codigo,
        fotoHash: p.fotoHash,
        precoUnitario: this.precoNaMaleta(remessaId, aqui) ?? ZERO,
        disponivel: this.disponivel(remessaId, aqui),
      };
    }
    for (const outra of maletasEmUso(db)) {
      if (outra !== remessaId && produtos.some((p) => this.precoNaMaleta(outra, p) !== null))
        return { tipo: 'EM_OUTRA_MALETA', maleta: codigoDaMaleta(this.db(), outra) };
    }
    return { tipo: 'FORA_DA_MALETA' };
  }

  // --- notas ---

  private linhasDaTela(nota: NotaLocal, acerto: AcertoLocal | null): LinhaDaNota[] {
    const db = this.db();
    const dv = new Map<string, number>();
    if (acerto) {
      const it = db
        .prepare(
          `SELECT produto_id, remessa_id, qtd_dv FROM itens_acerto_nota WHERE acerto_id = ? AND deleted_at IS NULL`,
        )
        .all(acerto.id) as { produto_id: string; remessa_id: string; qtd_dv: number | null }[];
      for (const i of it)
        dv.set(
          `${i.produto_id}|${i.remessa_id}`,
          (dv.get(`${i.produto_id}|${i.remessa_id}`) ?? 0) + (i.qtd_dv ?? 0),
        );
    }
    return linhasDaNota(db, nota.id).map((l) => {
      const p = this.pecaDaTela(l.produtoId);
      const devolvido = dv.get(`${l.produtoId}|${l.remessaId}`) ?? 0;
      return {
        produtoId: l.produtoId,
        remessaId: l.remessaId,
        maleta: codigoDaMaleta(this.db(), l.remessaId),
        codigo: p.codigo,
        codigos: p.codigos,
        nome: p.nome,
        tamTon: p.tamTon,
        fotoHash: p.fotoHash,
        dx: l.dx,
        dv: devolvido,
        vd: l.dx - devolvido,
        precoUnitario: dinheiro(l.preco),
        subTotal: dinheiro(l.preco * BigInt(l.dx)),
      };
    });
  }

  private valorDx(notaId: string): { itens: number; valor: bigint } {
    const linhas = linhasDaNota(this.db(), notaId);
    return {
      itens: linhas.reduce((s, l) => s + l.dx, 0),
      valor: linhas.reduce((s, l) => s + l.preco * BigInt(l.dx), 0n),
    };
  }

  private situacao(
    nota: NotaLocal,
    acerto: AcertoLocal | null,
    debitos: readonly DebitoLocal[],
  ): SituacaoDoPedido {
    const saldo = acerto ? (debitos.find((d) => d.acertoId === acerto.id)?.saldo ?? 0n) : null;
    return situacaoDaNota(
      acerto !== null,
      foiRemarcada(this.db(), nota.id),
      saldo === null ? null : dinheiro(saldo),
    );
  }

  private debitosEmCache(cache: Map<string, DebitoLocal[]>, clienteId: string): DebitoLocal[] {
    let d = cache.get(clienteId);
    if (!d) {
      d = debitosDoCliente(this.db(), clienteId);
      cache.set(clienteId, d);
    }
    return d;
  }

  private linhaDoPedido(
    nota: NotaLocal & { cliente_nome: string | null },
    cache: Map<string, DebitoLocal[]>,
  ): LinhaDoPedido {
    const db = this.db();
    const acerto = acertoAtivoDaNota(db, nota.id);
    const dx = this.valorDx(nota.id);
    const debitos = this.debitosEmCache(cache, nota.cliente_id);
    return {
      notaId: nota.id,
      numero: numeroDaNota(nota.semana_numero, nota.sequencia),
      clienteId: nota.cliente_id,
      cliente: nota.cliente_nome ?? '—',
      dataCobranca: dataDeCobranca(db, nota),
      itensDx: acerto?.itens_dx ?? dx.itens,
      itensDv: acerto?.itens_dv ?? 0,
      itensVd: acerto?.itens_vd ?? 0,
      valorDx: acerto?.valor_dx ?? dinheiro(dx.valor),
      valorDv: acerto?.valor_dv ?? ZERO,
      valorVd: acerto?.valor_vd ?? ZERO,
      // Em aberto e remarcada ainda não têm acerto: mostra a comissão do cliente (§23.4 só zera o valor em R$).
      comissaoPercentual: acerto?.comissao_percentual ?? this.comissaoDoCliente(nota.cliente_id),
      comissao: acerto?.comissao ?? ZERO,
      valorLiquido: acerto?.bruto ?? ZERO,
      situacao: this.situacao(nota, acerto, debitos),
      acertoId: acerto?.id ?? null,
    };
  }

  private comissaoDoCliente(clienteId: string): string {
    const c = this.db()
      .prepare('SELECT comissao_percentual FROM clientes WHERE id = ?')
      .get(clienteId) as { comissao_percentual: string | null } | undefined;
    return c?.comissao_percentual ?? '30.00';
  }

  private painel(semanaId?: string): PainelDaSemana {
    const db = this.db();
    const semanas = db
      .prepare(
        `SELECT id, numero FROM semanas_vendedor WHERE deleted_at IS NULL ORDER BY numero DESC`,
      )
      .all() as { id: string; numero: number }[];
    const nome = db.prepare("SELECT valor FROM estado_sync WHERE chave = 'vendedor_nome'").get() as
      { valor: string | null } | undefined;
    const vazio: TotaisDaSemana = {
      itensDx: 0,
      itensDv: 0,
      itensVd: 0,
      valorDx: ZERO,
      valorDv: ZERO,
      valorVd: ZERO,
      comissao: ZERO,
      liquido: ZERO,
      recebidoPorForma: { DINHEIRO: ZERO, PIX: ZERO, DEBITO: ZERO, CREDITO: ZERO },
      recebido: ZERO,
      pendente: ZERO,
    };
    const atual = semanas[0];
    const escolhida = (semanaId ? semanas.find((s) => s.id === semanaId) : undefined) ?? atual;
    const base: PainelDaSemana = {
      representante: nome?.valor ?? null,
      praca: pracaDoRepresentante(db),
      semSemana: semanas.length === 0,
      semanas: semanas.map((s) => ({ id: s.id, numero: s.numero, atual: s.id === atual?.id })),
      semana: null,
      pedidos: [],
      totais: vazio,
      podeNovoPedido: false,
    };
    if (!escolhida) return base;
    const eAtual = escolhida.id === atual?.id;
    const notas = db
      .prepare(
        `SELECT n.id, n.semana_id, n.sequencia, n.cliente_id, n.saida_em, n.data_cobranca, n.nota_origem_id,
                ${String(escolhida.numero)} AS semana_numero, c.nome AS cliente_nome
           FROM notas_consignado n LEFT JOIN clientes c ON c.id = n.cliente_id
          WHERE n.semana_id = ? AND n.deleted_at IS NULL ORDER BY n.sequencia`,
      )
      .all(escolhida.id) as (NotaLocal & { cliente_nome: string | null })[];
    const cache = new Map<string, DebitoLocal[]>();
    const pedidos = notas.map((n) => this.linhaDoPedido(n, cache));
    return {
      ...base,
      semana: {
        id: escolhida.id,
        numero: escolhida.numero,
        atual: eAtual,
        situacao: eAtual ? 'EM_ANDAMENTO' : 'FECHADA',
      },
      pedidos,
      totais: this.totais(escolhida.id, pedidos, cache),
      podeNovoPedido: eAtual,
    };
  }

  private totais(
    semanaId: string,
    pedidos: readonly LinhaDoPedido[],
    cache: Map<string, DebitoLocal[]>,
  ): TotaisDaSemana {
    const db = this.db();
    let itensDx = 0;
    let itensDv = 0;
    let itensVd = 0;
    let dx = 0n;
    let dv = 0n;
    let vd = 0n;
    let comissao = 0n;
    let liquido = 0n;
    let pendente = 0n;
    for (const p of pedidos) {
      itensDx += p.itensDx;
      itensDv += p.itensDv;
      itensVd += p.itensVd;
      dx += centavos(p.valorDx);
      dv += centavos(p.valorDv);
      vd += centavos(p.valorVd);
      comissao += centavos(p.comissao);
      liquido += centavos(p.valorLiquido);
      if (p.acertoId) {
        const d = cache.get(p.clienteId)?.find((x) => x.acertoId === p.acertoId);
        if (d && d.saldo > 0n) pendente += d.saldo;
      }
    }
    const recebidoPorForma: Record<FormaDoConsignado, bigint> = {
      DINHEIRO: 0n,
      PIX: 0n,
      DEBITO: 0n,
      CREDITO: 0n,
    };
    const formas = db
      .prepare(
        `SELECT f.forma, f.valor FROM formas_recibo_consignado f
           JOIN recibos_consignado r ON r.id = f.recibo_id
          WHERE r.semana_id = ? AND f.deleted_at IS NULL AND ${SQL_RECIBO_ATIVO('r')}`,
      )
      .all(semanaId) as { forma: string; valor: string | null }[];
    for (const f of formas)
      if ((FORMAS_DO_CONSIGNADO as readonly string[]).includes(f.forma))
        recebidoPorForma[f.forma as FormaDoConsignado] += centavos(f.valor);
    const recebido = Object.values(recebidoPorForma).reduce((s, v) => s + v, 0n);
    return {
      itensDx,
      itensDv,
      itensVd,
      valorDx: dinheiro(dx),
      valorDv: dinheiro(dv),
      valorVd: dinheiro(vd),
      comissao: dinheiro(comissao),
      liquido: dinheiro(liquido),
      recebidoPorForma: {
        DINHEIRO: dinheiro(recebidoPorForma.DINHEIRO),
        PIX: dinheiro(recebidoPorForma.PIX),
        DEBITO: dinheiro(recebidoPorForma.DEBITO),
        CREDITO: dinheiro(recebidoPorForma.CREDITO),
      },
      recebido: dinheiro(recebido),
      pendente: dinheiro(pendente),
    };
  }

  private recibo(id: string): ReciboDaTela | null {
    const db = this.db();
    const r = db
      .prepare(
        `SELECT r.id, r.sequencia, r.pago_em, r.total, coalesce(s.numero, 0) AS semana
           FROM recibos_consignado r LEFT JOIN semanas_vendedor s ON s.id = r.semana_id
          WHERE r.id = ? AND r.deleted_at IS NULL`,
      )
      .get(id) as
      | {
          id: string;
          sequencia: number;
          pago_em: string | null;
          total: string | null;
          semana: number;
        }
      | undefined;
    if (!r) return null;
    const formas = db
      .prepare(
        `SELECT forma, valor FROM formas_recibo_consignado WHERE recibo_id = ? AND deleted_at IS NULL ORDER BY forma`,
      )
      .all(id) as { forma: FormaDoConsignado; valor: string }[];
    return {
      id: r.id,
      numero: numeroDoRecibo(r.semana, r.sequencia),
      pagoEm: r.pago_em,
      total: r.total ?? ZERO,
      formas,
    };
  }

  private acertoDaTela(a: AcertoLocal): AcertoDaTela {
    const vd = centavos(a.valor_vd);
    const dx = centavos(a.valor_dx);
    return {
      id: a.id,
      acertadoEm: a.acertado_em,
      comissaoPercentual: a.comissao_percentual ?? ZERO,
      itensDx: a.itens_dx ?? 0,
      itensDv: a.itens_dv ?? 0,
      itensVd: a.itens_vd ?? 0,
      valorDx: a.valor_dx ?? ZERO,
      valorDv: a.valor_dv ?? ZERO,
      valorVd: a.valor_vd ?? ZERO,
      vendaPercentual: textoDePorcentagem(percentualDe(vd, dx)),
      comissao: a.comissao ?? ZERO,
      bruto: a.bruto ?? ZERO,
      pendenciasAnteriores: a.pendencias_anteriores ?? ZERO,
      totalAReceber: a.total_a_receber ?? ZERO,
      totalPago: a.total_pago ?? ZERO,
      ficouDevendo: a.ficou_devendo ?? ZERO,
      recibo: a.recibo_id ? this.recibo(a.recibo_id) : null,
    };
  }

  private debitoDaTela(d: DebitoLocal): DebitoDoCliente {
    return {
      acertoId: d.acertoId,
      notaId: d.notaId,
      numeroDaNota: d.numeroDaNota,
      acertadoEm: d.acertadoEm,
      valor: dinheiro(d.bruto),
      saldo: dinheiro(d.saldo),
    };
  }

  private nota(id: string): DetalheDaNota | null {
    const db = this.db();
    const nota = acharNota(db, id);
    if (!nota) return null;
    const cliente = db
      .prepare('SELECT nome, comissao_percentual FROM clientes WHERE id = ?')
      .get(nota.cliente_id) as { nome: string; comissao_percentual: string | null } | undefined;
    const acerto = acertoAtivoDaNota(db, id);
    const atual = semanaAtual(db);
    const debitos = debitosDoCliente(db, nota.cliente_id);
    const dx = this.valorDx(id);
    return {
      id,
      numero: numeroDaNota(nota.semana_numero, nota.sequencia),
      clienteId: nota.cliente_id,
      cliente: cliente?.nome ?? '—',
      semanaNumero: nota.semana_numero,
      daSemanaAtual: atual?.id === nota.semana_id,
      saidaEm: nota.saida_em,
      dataCobranca: dataDeCobranca(db, nota),
      situacao: this.situacao(nota, acerto, debitos),
      aceitaMudanca: acerto === null,
      linhas: this.linhasDaTela(nota, acerto),
      itensDx: dx.itens,
      valorDx: dinheiro(dx.valor),
      comissaoDoCliente: cliente?.comissao_percentual ?? '30.00',
      pendenciasAnteriores: acerto
        ? []
        : debitos.filter((d) => d.saldo > 0n).map((d) => this.debitoDaTela(d)),
      acerto: acerto ? this.acertoDaTela(acerto) : null,
      saldoDaNota: dinheiro(
        acerto ? (debitos.find((d) => d.acertoId === acerto.id)?.saldo ?? 0n) : 0n,
      ),
    };
  }

  private previa(p: Parameters<typeof previaDoAcerto>[1]): PrevisaoDoAcerto {
    const r = previaDoAcerto(this.db(), p);
    const c = r.calculo;
    return {
      linhas: r.linhas,
      itensDx: c.itensDx,
      itensDv: c.itensDv,
      itensVd: c.itensVd,
      valorDx: c.valorDx,
      valorDv: c.valorDv,
      valorVd: c.valorVd,
      vendaPercentual: c.vendaPercentual,
      comissaoPercentual: r.comissaoPercentual,
      comissao: c.comissao,
      bruto: c.bruto,
      pendenciasAnteriores: c.pendenciasAnteriores,
      totalAReceber: c.totalAReceber,
      totalPago: c.totalPago,
      ficouDevendo: c.ficouDevendo,
      debitos: r.debitos,
      cotacao: cotacaoAteODia(this.db(), p.data ?? diaEmBrasilia(this.agora())),
    };
  }

  /** Notas por nome do cliente, número da nota ou código da maleta (Cobrança e Pesquisar). */
  private notas(texto: string, soEmAberto: boolean): LinhaDeNotaNaBusca[] {
    const db = this.db();
    const t = semAcento(texto.trim());
    const todas = db
      .prepare(
        `SELECT n.id, n.semana_id, n.sequencia, n.cliente_id, n.saida_em, n.data_cobranca, n.nota_origem_id,
                coalesce(s.numero, 0) AS semana_numero, c.nome AS cliente_nome
           FROM notas_consignado n
           LEFT JOIN clientes c ON c.id = n.cliente_id
           LEFT JOIN semanas_vendedor s ON s.id = n.semana_id
          WHERE n.deleted_at IS NULL ORDER BY s.numero DESC, n.sequencia DESC`,
      )
      .all() as (NotaLocal & { cliente_nome: string | null })[];
    const cache = new Map<string, DebitoLocal[]>();
    const saida: LinhaDeNotaNaBusca[] = [];
    for (const n of todas) {
      const numero = numeroDaNota(n.semana_numero, n.sequencia);
      const maletas = [
        ...new Set(linhasDaNota(db, n.id).map((l) => codigoDaMaleta(this.db(), l.remessaId))),
      ];
      const acerto = acertoAtivoDaNota(db, n.id);
      if (soEmAberto && acerto) continue;
      if (
        t !== '' &&
        !semAcento(n.cliente_nome ?? '').includes(t) &&
        !numero.includes(t) &&
        !maletas.some((m) => semAcento(m).includes(t))
      )
        continue;
      const dx = this.valorDx(n.id);
      saida.push({
        notaId: n.id,
        numero,
        cliente: n.cliente_nome ?? '—',
        clienteId: n.cliente_id,
        dataCobranca: dataDeCobranca(db, n),
        valorDx: acerto?.valor_dx ?? dinheiro(dx.valor),
        situacao: this.situacao(n, acerto, this.debitosEmCache(cache, n.cliente_id)),
        maletas,
      });
      if (saida.length >= 100) break;
    }
    return saida;
  }

  private pesquisar(texto: string): ResultadoDaPesquisa {
    const t = semAcento(texto.trim());
    return {
      notas: this.notas(texto, false),
      clientes: t === '' ? [] : this.clientes(texto).slice(0, 20),
      maletas:
        t === ''
          ? []
          : maletasEmUso(this.db())
              .map((id) => ({ id, codigo: codigoDaMaleta(this.db(), id) }))
              .filter((m) => semAcento(m.codigo).includes(t)),
    };
  }

  // --- clientes ---

  private fotosEsperando(clienteId: string): number {
    return (
      this.db()
        .prepare(
          'SELECT count(*) AS n FROM fila_fotos_cliente WHERE cliente_id = ? AND enviada_em IS NULL',
        )
        .get(clienteId) as { n: number }
    ).n;
  }

  private clientes(busca: string): ClienteDaLista[] {
    const db = this.db();
    // Sem diferenciar acento nem maiúscula ("jose" acha "José"): o filtro é feito aqui, não no SQL (o LIKE do SQLite não
    // conhece acento).
    const t = semAcento(busca.trim());
    const digitos = t.replace(/\D/g, '');
    const todas = db
      .prepare(
        `SELECT id, nome, documento, telefone, whatsapp, endereco_cidade, foto_hash FROM clientes
          WHERE deleted_at IS NULL AND ativo IS NOT 0
          ORDER BY lower(nome), id`,
      )
      .all() as {
      id: string;
      nome: string;
      documento: string | null;
      telefone: string | null;
      whatsapp: string | null;
      endereco_cidade: string | null;
      foto_hash: string | null;
    }[];
    const linhas = todas
      .filter(
        (c) =>
          t === '' ||
          semAcento(c.nome).includes(t) ||
          semAcento(c.documento ?? '').includes(t) ||
          (digitos !== '' &&
            `${c.telefone ?? ''}${c.whatsapp ?? ''}`.replace(/[ \-(]/g, '').includes(digitos)),
      )
      .slice(0, 200);
    return linhas.map((c) => ({
      id: c.id,
      nome: c.nome,
      telefone: c.telefone ?? c.whatsapp,
      cidade: c.endereco_cidade,
      pendencia: dinheiro(pendenciaDoCliente(db, c.id)),
      fotoHash: c.foto_hash,
    }));
  }

  private cliente(id: string): ClienteCompleto | null {
    const db = this.db();
    const c = db.prepare('SELECT * FROM clientes WHERE id = ? AND deleted_at IS NULL').get(id) as
      Record<string, string | number | null> | undefined;
    if (!c) return null;
    const texto = (k: string): string | null => (c[k] as string | null | undefined) ?? null;
    const debitos = debitosDoCliente(db, id);
    const notas = db
      .prepare(
        `SELECT n.id, n.semana_id, n.sequencia, n.cliente_id, n.saida_em, n.data_cobranca, n.nota_origem_id,
                coalesce(s.numero, 0) AS semana_numero
           FROM notas_consignado n LEFT JOIN semanas_vendedor s ON s.id = n.semana_id
          WHERE n.cliente_id = ? AND n.deleted_at IS NULL ORDER BY s.numero DESC, n.sequencia DESC`,
      )
      .all(id) as NotaLocal[];
    const pedidos: PedidoDoHistorico[] = [];
    const comCliente = new Map<string, number>();
    for (const n of notas) {
      const acerto = acertoAtivoDaNota(db, n.id);
      const dx = this.valorDx(n.id);
      pedidos.push({
        notaId: n.id,
        numero: numeroDaNota(n.semana_numero, n.sequencia),
        semana: n.semana_numero,
        dataCobranca: dataDeCobranca(db, n),
        valorDx: acerto?.valor_dx ?? dinheiro(dx.valor),
        valorVd: acerto?.valor_vd ?? ZERO,
        valorLiquido: acerto?.bruto ?? ZERO,
        situacao: this.situacao(n, acerto, debitos),
      });
      if (!acerto)
        for (const l of linhasDaNota(db, n.id))
          comCliente.set(l.remessaId, (comCliente.get(l.remessaId) ?? 0) + l.dx);
    }
    const recibos = db
      .prepare(
        `SELECT r.id, r.sequencia, r.pago_em, r.total, r.acerto_id, coalesce(s.numero, 0) AS semana,
                ${SQL_RECIBO_ATIVO('r')} AS ativo
           FROM recibos_consignado r LEFT JOIN semanas_vendedor s ON s.id = r.semana_id
          WHERE r.cliente_id = ? AND r.deleted_at IS NULL ORDER BY r.pago_em DESC, r.sequencia DESC`,
      )
      .all(id) as {
      id: string;
      sequencia: number;
      pago_em: string | null;
      total: string | null;
      acerto_id: string | null;
      semana: number;
      ativo: number;
    }[];
    const pagamentos: PagamentoDoHistorico[] = recibos.map((r) => ({
      reciboId: r.id,
      numero: numeroDoRecibo(r.semana, r.sequencia),
      pagoEm: r.pago_em,
      total: r.total ?? ZERO,
      formas: db
        .prepare(
          `SELECT forma, valor FROM formas_recibo_consignado WHERE recibo_id = ? AND deleted_at IS NULL ORDER BY forma`,
        )
        .all(r.id) as { forma: FormaDoConsignado; valor: string }[],
      podeEstornar: r.ativo === 1 && r.acerto_id === null,
      estornado: r.ativo !== 1,
      doAcerto: r.acerto_id !== null,
    }));
    return {
      id,
      nome: texto('nome') ?? '',
      telefone: texto('telefone'),
      whatsapp: texto('whatsapp'),
      documento: texto('documento') ?? texto('cpf'),
      documentoTipo: texto('documento_tipo'),
      dataNascimento: texto('data_nascimento'),
      enderecoLogradouro: texto('endereco_logradouro'),
      enderecoNumero: texto('endereco_numero'),
      enderecoComplemento: texto('endereco_complemento'),
      enderecoBairro: texto('endereco_bairro'),
      enderecoCidade: texto('endereco_cidade'),
      enderecoUf: texto('endereco_uf'),
      enderecoCep: texto('endereco_cep'),
      indicadoPorClienteId: texto('indicado_por_cliente_id'),
      indicadoPorNome: texto('indicado_por_nome'),
      comoConheceu: texto('como_conheceu'),
      comissaoPercentual: texto('comissao_percentual') ?? '30.00',
      observacoes: texto('observacoes'),
      fotoHash: texto('foto_hash'),
      fotoDocumentoFrenteHash: texto('foto_documento_frente_hash'),
      fotoDocumentoVersoHash: texto('foto_documento_verso_hash'),
      fotosEsperandoEnvio: this.fotosEsperando(id),
      pendencia: dinheiro(debitos.reduce((s, d) => (d.saldo > 0n ? s + d.saldo : s), 0n)),
      debitos: debitos.filter((d) => d.saldo > 0n).map((d) => this.debitoDaTela(d)),
      sobraAAplicar: dinheiro(sobraAAplicarDoCliente(this.db(), id)),
      maletasAtuais: [...comCliente.entries()]
        .filter(([, n]) => n > 0)
        .map(([remessaId, pecas]) => ({
          remessaId,
          maleta: codigoDaMaleta(this.db(), remessaId),
          pecas,
        })),
      pedidos,
      pagamentos,
    };
  }

  // --- maletas ---

  private maletas(): MaletaDoConsignado[] {
    const db = this.db();
    const remessas = db
      .prepare(
        `SELECT id, status, enviada_em FROM remessas
          WHERE deleted_at IS NULL AND status IN ('ENVIADA', 'CONFERIDA', 'FECHADA', 'EM_ACERTO') ORDER BY enviada_em DESC, id`,
      )
      .all() as { id: string; status: string; enviada_em: string | null }[];
    return remessas.map((r) => {
      const itens = db
        .prepare(
          `SELECT produto_id, sum(qtd_enviada) AS enviada, max(preco_remessa) AS preco FROM itens_remessa
            WHERE remessa_id = ? AND deleted_at IS NULL GROUP BY produto_id ORDER BY produto_id`,
        )
        .all(r.id) as { produto_id: string; enviada: number; preco: string }[];
      const mov = db
        .prepare(
          `SELECT produto_id, tipo, sum(quantidade) AS q FROM movimentos_remessa
            WHERE remessa_id = ? AND deleted_at IS NULL GROUP BY produto_id, tipo`,
        )
        .all(r.id) as { produto_id: string; tipo: string; q: number }[];
      const vendidas = db
        .prepare(
          `SELECT i.produto_id, sum(i.qtd_dx - i.qtd_dv) AS vd FROM itens_acerto_nota i
             JOIN acertos_nota a ON a.id = i.acerto_id
            WHERE i.remessa_id = ? AND i.deleted_at IS NULL AND ${SQL_ACERTO_ATIVO('a')} GROUP BY i.produto_id`,
        )
        .all(r.id) as { produto_id: string; vd: number }[];
      const pecas: PecaDaMaletaDoConsignado[] = itens.map((i) => {
        const doProduto = mov.filter((m) => m.produto_id === i.produto_id);
        const soma = (tipos: string[]): number =>
          doProduto.filter((m) => tipos.includes(m.tipo)).reduce((s, m) => s + m.q, 0);
        const vendida = vendidas.find((v) => v.produto_id === i.produto_id)?.vd ?? 0;
        const todos = doProduto.reduce((s, m) => s + m.q, 0);
        const p = this.pecaDaTela(i.produto_id);
        return {
          produtoId: i.produto_id,
          nome: p.nome,
          codigo: p.codigo,
          fotoHash: p.fotoHash,
          precoUnitario: i.preco,
          enviada: i.enviada,
          comClientes: -soma(['CONSIGNADO_SAIDA', 'CONSIGNADO_RETORNO']) - vendida,
          vendida,
          devolvidaALoja: -soma(['DEVOLUCAO_DONA']),
          disponivel: i.enviada + todos,
        };
      });
      const total = (campo: 'enviada' | 'comClientes' | 'vendida' | 'disponivel'): number =>
        pecas.reduce((s, p) => s + p[campo], 0);
      return {
        id: r.id,
        codigo: codigoDaMaleta(this.db(), r.id),
        enviadaEm: r.enviada_em,
        status: r.status,
        pecas,
        totalEnviada: total('enviada'),
        totalComClientes: total('comClientes'),
        totalVendida: total('vendida'),
        totalDisponivel: total('disponivel'),
      };
    });
  }

  // --- Novo Pedido ---

  private itensDoPedido(r: RascunhoDoPedido): ItemDoPedido[] {
    return r.itens.map((i) => {
      const p = this.pecaDaTela(i.produtoId);
      return {
        id: i.id,
        produtoId: i.produtoId,
        remessaId: i.remessaId,
        maleta: codigoDaMaleta(this.db(), i.remessaId),
        codigo: p.codigo,
        nome: p.nome,
        tamTon: p.tamTon,
        fotoHash: p.fotoHash,
        quantidade: i.quantidade,
        precoUnitario: i.precoUnitario,
        subTotal: dinheiro(centavos(i.precoUnitario) * BigInt(i.quantidade)),
      };
    });
  }

  private pedido(): PedidoEmMontagem {
    const db = this.db();
    // Ler o rascunho pode criá-lo; só quem grava (a operação) roda na transação do computador, então aqui só lê.
    const r = this.o.operacoes.pedidoEmMontagem();
    const cliente = r.clienteId
      ? ((db.prepare('SELECT nome FROM clientes WHERE id = ?').get(r.clienteId) as
          { nome: string } | undefined) ?? null)
      : null;
    const itens = this.itensDoPedido(r);
    return {
      id: r.id,
      clienteId: r.clienteId,
      cliente: cliente?.nome ?? null,
      pendenciaDoCliente: r.clienteId ? dinheiro(pendenciaDoCliente(db, r.clienteId)) : ZERO,
      remessaId: r.remessaId,
      maletaEscolhida: r.remessaId ? codigoDaMaleta(this.db(), r.remessaId) : null,
      dataCobranca: r.dataCobranca,
      itens,
      totalDeItens: itens.reduce((s, i) => s + i.quantidade, 0),
      totalDx: dinheiro(itens.reduce((s, i) => s + centavos(i.subTotal), 0n)),
      maletas: maletasEmUso(db).map((id) => ({ id, codigo: codigoDaMaleta(this.db(), id) })),
    };
  }
}

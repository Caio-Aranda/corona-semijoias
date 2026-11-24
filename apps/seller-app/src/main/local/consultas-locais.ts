import { cotacaoVigenteEm } from '../sync/consignado-comum';
import type { BancoAberto } from '../banco/banco-local';
import { centavosDe, exigirCentavos, textoDeCentavos } from '../../shared/dinheiro';
import type {
  Cliente,
  Conferencia,
  CotacaoDoDia,
  EnvioNaoAceito,
  LinhaDaConferencia,
  LinhaDaMaleta,
  Maleta,
  PagamentoDaVenda,
  PecaDaMaleta,
  RelatorioAReceber,
  RelatorioDeCreditos,
  RelatorioDeDevolucoes,
  RelatorioDeVendas,
  Venda,
} from '../../shared/dados';
import type { PedidoDeChamada } from '../../shared/operacoes';
import { diaEmBrasilia } from '../../shared/garantia';
import {
  creditoValido,
  ehDoVendedor,
  saldoDoCredito,
  SQL_DEVOLUCAO_ATIVA,
  SQL_VENDA_ESTORNADA_AQUI,
} from '../sync/operacoes-da-fase-6';
import { marcarEntendido } from '../sync/outbox';
import {
  ErroDaOperacao,
  type OperacoesLocais,
  MENSAGENS_DAS_OPERACOES,
} from '../sync/operacoes-locais';
import type { Falha, Resultado } from '../tipos';
import { ConsultasDaFase6 } from './consultas-da-fase-6';
import { ConsultasDoAcerto } from './consultas-do-acerto';
import {
  ConsultasDoConsignado,
  ehPedidoDoConsignado,
  type FotosDoClienteLocais,
} from './consultas-do-consignado';
import { pecaDoProduto } from './peca-do-produto';

/**
 * As telas do Corona Vendas leem DAQUI, do banco local (tarefa 5.7; ADR-0013): a mesma resposta que a API dava, montada
 * das cópias guardadas no computador, então tudo funciona sem internet. As gravações passam pelas `OperacoesLocais`
 * (cópia provisória e operação na mesma transação). Nada aqui chega à rede, ao token nem ao custo (que o app nunca
 * recebe). Dólar e Guarani são só para mostrar, pela cotação do dia do lançamento (ADR-0006); tudo é guardado em Real.
 */
export interface OpcoesDasConsultas {
  banco: () => BancoAberto | null;
  operacoes: OperacoesLocais;
  agora?: () => Date;
  /** Avisado quando algo que a tela mostra no indicador mudou sem gravar uma operação (o "Entendi"). */
  aoMudarOEstado?: () => void;
  /** As fotos dos clientes (criptografadas, com fila de envio; tarefa 9.2). */
  fotosDosClientes?: FotosDoClienteLocais;
}

const MENSAGENS_ACERTO_NAO_ENCONTRADO =
  'Não encontramos este acerto neste computador. Atualize a tela e tente de novo.';

/** O código do texto, para a tela mostrar a frase no idioma escolhido (os textos das operações são únicos). */
function codigoDoTexto(mensagem: string): string | undefined {
  if (mensagem === MENSAGENS_ACERTO_NAO_ENCONTRADO) return 'ACERTO_NAO_ENCONTRADO_NO_COMPUTADOR';
  return Object.entries(MENSAGENS_DAS_OPERACOES).find(([, texto]) => texto === mensagem)?.[0];
}

const naoEncontrado = (mensagem: string): Falha => {
  const codigo = codigoDoTexto(mensagem);
  return { tipo: 'recusada', status: 404, mensagem, ...(codigo ? { codigo } : {}) };
};

/** Dia civil (AAAA-MM-DD) em Brasília de um instante ISO (o mesmo da garantia, tarefa 6.8). */
export { diaEmBrasilia };

/** Divide `n` por `d` (> 0) arredondando para longe do zero no meio exato (como o servidor, HALF_UP). */
function dividir(n: bigint, d: bigint): bigint {
  return n >= 0n ? (2n * n + d) / (2n * d) : -((-2n * n + d) / (2n * d));
}

const CASAS_DA_TAXA = 8;

/** "5.5013" → 550130000n (unidades de 10⁻⁸); formato errado ou zero → `null`. */
function taxaDe(texto: string | null): bigint | null {
  if (!texto) return null;
  const m = /^(\d{1,10})(?:\.(\d{1,8}))?$/.exec(texto.trim());
  if (!m) return null;
  const v =
    BigInt(m[1] ?? '0') * 10n ** BigInt(CASAS_DA_TAXA) +
    BigInt((m[2] ?? '').padEnd(CASAS_DA_TAXA, '0') || '0');
  return v > 0n ? v : null;
}

/** US$ em centavos. */
const paraDolar = (centavos: bigint, taxa: bigint): bigint =>
  dividir(centavos * 10n ** BigInt(CASAS_DA_TAXA), taxa);
/** Gs. inteiros. */
const paraGuarani = (centavos: bigint, taxa: bigint): bigint =>
  dividir(centavos * 10n ** BigInt(CASAS_DA_TAXA - 2), taxa);

interface Taxas {
  origem: 'BANCO_CENTRAL' | 'DONA';
  vigenteDesde: string;
  data: string;
  dolar: string;
  guarani: string;
  usd: bigint;
  pyg: bigint;
}

/** Soma de lançamentos em Real e, cada um pela sua cotação, em Dólar e Guarani. Sem cotação: só Real. */
class Soma {
  reais = 0n;
  dolar = 0n;
  guarani = 0n;
  semCotacao = false;

  somar(centavos: bigint, taxas: Taxas | null): void {
    this.reais += centavos;
    if (!taxas) {
      this.semCotacao = true;
      return;
    }
    this.dolar += paraDolar(centavos, taxas.usd);
    this.guarani += paraGuarani(centavos, taxas.pyg);
  }

  mais(o: Soma): Soma {
    const s = new Soma();
    s.reais = this.reais + o.reais;
    s.dolar = this.dolar + o.dolar;
    s.guarani = this.guarani + o.guarani;
    s.semCotacao = this.semCotacao || o.semCotacao;
    return s;
  }

  menos(o: Soma): Soma {
    const s = new Soma();
    s.reais = this.reais - o.reais;
    s.dolar = this.dolar - o.dolar;
    s.guarani = this.guarani - o.guarani;
    s.semCotacao = this.semCotacao || o.semCotacao;
    return s;
  }

  get textoReais(): string {
    return textoDeCentavos(this.reais);
  }
  get textoDolar(): string | null {
    return this.semCotacao ? null : textoDeCentavos(this.dolar);
  }
  get textoGuarani(): string | null {
    return this.semCotacao ? null : this.guarani.toString();
  }
}

interface LinhaDaVenda {
  id: string;
  cliente_id: string | null;
  cliente_nome: string | null;
  remessa_id: string | null;
  status: string | null;
  subtotal: string | null;
  desconto: string | null;
  total: string | null;
  cotacao_id: string | null;
  vendida_em: string;
  estornada_em: string | null;
  motivo_estorno: string | null;
  estornada_aqui: number;
  troca_id: string | null;
}

/** Pedidos que as telas fazem ao banco local. `fotos.ver` e `eu` continuam na rede (fotos sem internet: tarefa 5.9). */
export function ehConsultaLocal(pedido: PedidoDeChamada): boolean {
  // A prévia da comissão é calculada pelo servidor (precisa de internet): nunca é local.
  return (
    pedido.operacao !== 'eu' &&
    pedido.operacao !== 'fotos.ver' &&
    pedido.operacao !== 'comissao.previa' &&
    // Pedir o acerto e contar as sobras (tarefa 8.10): só com internet, direto na API.
    pedido.operacao !== 'acertos.pedir' &&
    pedido.operacao !== 'acertos.agora' &&
    pedido.operacao !== 'acertos.ler'
  );
}

export class ConsultasLocais {
  /** As consultas da devolução, troca e crédito (tarefa 6.8). */
  readonly fase6: ConsultasDaFase6;

  /** Acerto fechado, parcelas e recebíveis (tarefa 7.11). */
  readonly acerto: ConsultasDoAcerto;

  /** O consignado ao cliente: semana, pedidos, acerto, clientes e maletas (tarefa 9.2). */
  readonly consignado: ConsultasDoConsignado;

  constructor(private readonly o: OpcoesDasConsultas) {
    this.fase6 = new ConsultasDaFase6({ banco: o.banco, ...(o.agora ? { agora: o.agora } : {}) });
    this.consignado = new ConsultasDoConsignado({
      banco: o.banco,
      operacoes: o.operacoes,
      ...(o.fotosDosClientes ? { fotos: o.fotosDosClientes } : {}),
      ...(o.agora ? { agora: o.agora } : {}),
    });
    this.acerto = new ConsultasDoAcerto({
      banco: () => this.db(),
      agora: () => this.agora(),
      venda: (id) => this.venda(id),
    });
  }

  /** O banco local está aberto (a pessoa entrou com internet ao menos uma vez neste computador). */
  get disponivel(): boolean {
    return this.o.banco() !== null;
  }

  private agora(): Date {
    return this.o.agora?.() ?? new Date();
  }

  private db(): BancoAberto {
    const db = this.o.banco();
    if (!db) throw new Error('Banco local fechado.');
    return db;
  }

  /** Atende o pedido pelo banco local. Nunca fala com a rede. */
  chamar(pedido: PedidoDeChamada): Resultado<unknown> {
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
            ...(erro.clienteExistente ? { clienteExistente: erro.clienteExistente } : {}),
          },
        };
      }
      const mensagem = erro instanceof Error ? erro.message : '';
      // Disco cheio e afins já chegam em português do banco local.
      return {
        ok: false,
        falha: { tipo: 'recusada', status: 500, ...(mensagem ? { mensagem } : {}) },
      };
    }
  }

  private atender(pedido: PedidoDeChamada): Resultado<unknown> {
    if (ehPedidoDoConsignado(pedido)) return this.consignado.chamar(pedido);
    switch (pedido.operacao) {
      case 'maletas.listar':
        return ok(this.maletas(['ENVIADA', 'CONFERIDA']));
      case 'maletas.ver': {
        const m = this.maletas(['ENVIADA', 'CONFERIDA', 'EM_ACERTO'], pedido.id)[0];
        return m ? ok(m) : nao(MENSAGENS_DAS_OPERACOES.MALETA_NAO_ENCONTRADA);
      }
      case 'maletas.peca': {
        const m = this.maletas(['ENVIADA', 'CONFERIDA', 'EM_ACERTO'], pedido.id)[0];
        if (!m) return nao(MENSAGENS_DAS_OPERACOES.MALETA_NAO_ENCONTRADA);
        const lido = pedido.codigo.trim().toLowerCase();
        const achada =
          lido === ''
            ? undefined
            : m.itens.find((l) => this.codigosDoProduto(l.produto.id).some((c) => c === lido));
        return achada ? ok(achada) : nao(MENSAGENS_DAS_OPERACOES.PECA_FORA_DA_MALETA);
      }
      case 'maletas.buscarPeca': {
        const m = this.maletas(['ENVIADA', 'CONFERIDA', 'EM_ACERTO'], pedido.id)[0];
        if (!m) return nao(MENSAGENS_DAS_OPERACOES.MALETA_NAO_ENCONTRADA);
        const texto = pedido.texto.trim().toLowerCase();
        if (texto === '' || texto.length > 100) return ok([]);
        const referencias = this.referencias();
        const achadas = m.itens.filter(
          (l) =>
            l.produto.nome.toLowerCase().includes(texto) ||
            (referencias.get(l.produto.id) ?? '').toLowerCase().includes(texto) ||
            this.codigosDoProduto(l.produto.id).some((c) => c.includes(texto)),
        );
        return ok(achadas.slice(0, 20));
      }
      case 'maletas.conferir': {
        this.o.operacoes.conferir({
          id: pedido.conferenciaId,
          remessaId: pedido.id,
          itens: pedido.itens,
        });
        return ok(this.conferencia(pedido.conferenciaId));
      }
      case 'maletas.verConferencia': {
        const c = this.db()
          .prepare(
            'SELECT id FROM conferencias WHERE remessa_id = ? AND deleted_at IS NULL ORDER BY conferida_em DESC LIMIT 1',
          )
          .get(pedido.id) as { id: string } | undefined;
        return c
          ? ok(this.conferencia(c.id))
          : nao('Esta maleta ainda não foi conferida.', 'MALETA_SEM_CONFERENCIA');
      }
      case 'vendas.vender': {
        this.o.operacoes.vender(pedido.venda);
        return ok(this.venda(pedido.venda.id));
      }
      case 'vendas.listar':
        return ok(this.vendas(pedido));
      case 'vendas.ver': {
        const v = this.venda(pedido.id);
        return v ? ok(v) : nao(MENSAGENS_DAS_OPERACOES.VENDA_NAO_ENCONTRADA);
      }
      case 'vendas.estornar': {
        this.o.operacoes.estornar({
          id: pedido.estornoId,
          vendaId: pedido.id,
          motivo: pedido.motivo,
        });
        return ok(this.venda(pedido.id));
      }
      case 'clientes.listar':
        return ok(this.clientes(pedido.busca ?? ''));
      case 'clientes.ver': {
        const c = this.cliente(pedido.id);
        return c ? ok(c) : nao(MENSAGENS_DAS_OPERACOES.CLIENTE_NAO_ENCONTRADO);
      }
      case 'clientes.cadastrar': {
        this.o.operacoes.cadastrarCliente(pedido.cliente);
        return ok(this.cliente(pedido.cliente.id));
      }
      case 'clientes.alterar': {
        const atual = this.cliente(pedido.id);
        if (!atual) return nao(MENSAGENS_DAS_OPERACOES.CLIENTE_NAO_ENCONTRADO);
        if (!atual.meu) {
          return {
            ok: false,
            falha: {
              tipo: 'recusada',
              status: 403,
              mensagem: 'Só o vendedor que atende o cliente pode mudar o cadastro dele.',
              codigo: 'CLIENTE_DE_OUTRO_VENDEDOR',
            },
          };
        }
        const campos: Record<string, string | null> = {};
        for (const [campo, valor] of Object.entries(pedido.campos)) {
          const limpo = valor.trim();
          campos[campo] = limpo === '' ? null : limpo;
        }
        this.o.operacoes.alterarCliente(pedido.id, campos);
        return ok(this.cliente(pedido.id));
      }
      case 'cotacao.hoje':
        return ok(this.cotacaoDeHoje());
      case 'relatorios.vendas':
        return ok(this.relatorioDeVendas(pedido.de, pedido.ate, pedido.agrupar ?? 'cliente'));
      case 'relatorios.recebimentos':
        return ok(this.relatorioDeRecebimentos(pedido.de, pedido.ate));
      case 'relatorios.aReceber':
        return ok(this.relatorioAReceber());
      case 'relatorios.devolucoes':
        return ok(this.relatorioDeDevolucoes(pedido.de, pedido.ate));
      case 'relatorios.creditos':
        return ok(this.relatorioDeCreditos());
      case 'envios.naoAceitos':
        return ok(this.enviosNaoAceitos());
      case 'envios.entendi': {
        marcarEntendido(this.db(), pedido.id, this.agora());
        this.o.aoMudarOEstado?.();
        return ok(null);
      }
      // --- devolução, troca e crédito (tarefa 6.8) ---
      case 'devolucoes.vendasComAPeca':
        return ok(this.fase6.vendasComAPeca(pedido.codigo));
      case 'devolucoes.previa':
        return ok(
          this.fase6.previaDaDevolucao({
            vendaId: pedido.vendaId,
            ...(pedido.motivo ? { motivo: pedido.motivo } : {}),
            ...(pedido.observacao !== undefined ? { observacao: pedido.observacao } : {}),
            itens: pedido.itens,
          }),
        );
      case 'devolucoes.registrar':
        return ok(this.o.operacoes.devolver(pedido.devolucao));
      case 'devolucoes.cancelar':
        return ok(
          this.o.operacoes.cancelarDevolucao({
            id: pedido.cancelamentoId,
            devolucaoId: pedido.id,
            motivo: pedido.motivo,
          }),
        );
      case 'devolucoes.listar':
        return ok(this.fase6.listar(pedido));
      case 'autorizacoes.pedir':
        return ok(this.o.operacoes.pedirAutorizacao(pedido.pedido));
      case 'autorizacoes.listar':
        return ok(this.fase6.autorizacoes());
      case 'trocas.previa':
        return ok(
          this.fase6.previaDaTroca({
            devolucao: pedido.devolucao,
            ...(pedido.remessaId ? { remessaId: pedido.remessaId } : {}),
            itens: pedido.itens,
          }),
        );
      case 'trocas.registrar':
        return ok(this.o.operacoes.trocar(pedido.troca));
      case 'trocas.cancelar':
        return ok(
          this.o.operacoes.cancelarTroca({
            id: pedido.cancelamentoId,
            trocaId: pedido.id,
            motivo: pedido.motivo,
          }),
        );
      case 'creditos.doCliente':
        return ok(this.fase6.creditosDoCliente(pedido.clienteId));
      // --- acerto, parcelas e recebíveis (tarefa 7.11) ---
      case 'recebimentos.receber': {
        this.o.operacoes.receberDoCliente(pedido.recebimento);
        return ok(this.venda(pedido.recebimento.vendaId));
      }
      case 'recebimentos.estornar': {
        this.o.operacoes.estornarRecebimento({
          id: pedido.estornoId,
          recebimentoId: pedido.id,
          motivo: pedido.motivo,
        });
        const dono = this.db()
          .prepare('SELECT venda_id FROM pagamentos_cliente WHERE id = ?')
          .get(pedido.id) as { venda_id: string } | undefined;
        return ok(dono ? this.venda(dono.venda_id) : null);
      }
      case 'recebiveis.ver':
        return ok(this.acerto.recebiveis());
      case 'acertos.listar':
        return ok(this.acerto.listarAcertos());
      case 'acertos.ver': {
        const ficha = this.acerto.verAcerto(pedido.id);
        return ficha ? ok(ficha) : nao(MENSAGENS_ACERTO_NAO_ENCONTRADO);
      }
      case 'eu':
      case 'fotos.ver':
      case 'comissao.previa':
      case 'acertos.pedir':
      case 'acertos.agora':
      case 'acertos.ler':
        return nao('Esta consulta não é feita no computador.', 'CONSULTA_SO_COM_INTERNET');
    }
  }

  // --- peças e maletas ---

  private codigosDoProduto(produtoId: string): string[] {
    return (
      this.db()
        .prepare(
          `SELECT codigo FROM produto_codigos
            WHERE produto_id = ? AND deleted_at IS NULL AND ativo IS NOT 0`,
        )
        .all(produtoId) as { codigo: string }[]
    ).map((c) => c.codigo.trim().toLowerCase());
  }

  private referencias(): Map<string, string> {
    const m = new Map<string, string>();
    for (const r of this.db()
      .prepare('SELECT id, referencia FROM produtos WHERE referencia IS NOT NULL')
      .all() as { id: string; referencia: string }[])
      m.set(r.id, r.referencia);
    return m;
  }

  private peca(produtoId: string): PecaDaMaleta {
    return pecaDoProduto(this.db(), produtoId);
  }

  private maletas(situacoes: string[], id?: string): Maleta[] {
    const db = this.db();
    const marcas = situacoes.map(() => '?').join(', ');
    const cabecas = db
      .prepare(
        `SELECT r.id, r.tipo, r.status, r.enviada_em, s.numero AS semana
           FROM remessas r LEFT JOIN semanas s ON s.id = r.semana_id
          WHERE r.deleted_at IS NULL AND r.status IN (${marcas}) ${id ? 'AND r.id = ?' : ''}
          ORDER BY r.enviada_em DESC, r.id DESC`,
      )
      .all(...situacoes, ...(id ? [id] : [])) as {
      id: string;
      tipo: string | null;
      status: string;
      enviada_em: string | null;
      semana: number | null;
    }[];
    return cabecas.map((c) => {
      const itens = this.linhasDaMaleta(c.id);
      const pecas = itens.reduce((t, l) => t + l.saldo, 0);
      const valor = itens.reduce((t, l) => t + exigirCentavos(l.valorEmAberto), 0n);
      const ordem = db
        .prepare(
          `SELECT count(*) AS n FROM remessas x
            WHERE x.deleted_at IS NULL AND x.enviada_em IS NOT NULL
              AND coalesce((SELECT numero FROM semanas WHERE id = x.semana_id), -1) = ?
              AND (x.enviada_em < ? OR (x.enviada_em = ? AND x.id <= ?))`,
        )
        .get(c.semana ?? -1, c.enviada_em ?? '', c.enviada_em ?? '', c.id) as { n: number };
      return {
        id: c.id,
        numero: c.enviada_em ? `S${c.semana ?? 0}-${ordem.n}` : null,
        semanaNumero: c.semana ?? 0,
        tipo: c.tipo === 'REPOSICAO' ? 'REPOSICAO' : 'NOVA',
        status: c.status,
        enviadaEm: c.enviada_em ?? '',
        pecasEmAberto: pecas,
        valorEmAberto: textoDeCentavos(valor),
        itens,
      } satisfies Maleta;
    });
  }

  private linhasDaMaleta(remessaId: string): LinhaDaMaleta[] {
    const db = this.db();
    const itens = db
      .prepare(
        `SELECT i.produto_id, i.qtd_enviada, i.preco_remessa, p.nome
           FROM itens_remessa i LEFT JOIN produtos p ON p.id = i.produto_id
          WHERE i.remessa_id = ? AND i.deleted_at IS NULL
          ORDER BY p.nome, i.produto_id`,
      )
      .all(remessaId) as {
      produto_id: string;
      qtd_enviada: number;
      preco_remessa: string;
      nome: string | null;
    }[];
    const soma = db.prepare(
      // "Vendida" como o servidor (tarefa 6.3): a troca sai e a peça que o cliente devolveu à maleta volta.
      `SELECT coalesce(sum(CASE WHEN tipo IN ('VENDA', 'ESTORNO_VENDA', 'TROCA_SAIDA', 'DEVOLUCAO_CLIENTE',
                                             'TROCA_ENTRADA') THEN -quantidade END), 0) AS vendida,
              coalesce(sum(CASE WHEN tipo = 'DEVOLUCAO_DONA' THEN -quantidade END), 0) AS devolvida,
              coalesce(sum(quantidade), 0) AS movido
         FROM movimentos_remessa
        WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL`,
    );
    return itens.map((i) => {
      const m = soma.get(remessaId, i.produto_id) as {
        vendida: number;
        devolvida: number;
        movido: number;
      };
      const saldo = i.qtd_enviada + m.movido;
      return {
        produto: this.peca(i.produto_id),
        enviada: i.qtd_enviada,
        vendida: m.vendida,
        devolvida: m.devolvida,
        saldo,
        precoUnitario: i.preco_remessa,
        valorEmAberto: textoDeCentavos(exigirCentavos(i.preco_remessa) * BigInt(saldo)),
      };
    });
  }

  private conferencia(id: string): Conferencia {
    const db = this.db();
    const c = db
      .prepare('SELECT remessa_id, divergencias, conferida_em FROM conferencias WHERE id = ?')
      .get(id) as { remessa_id: string; divergencias: number | null; conferida_em: string | null };
    const itens = db
      .prepare(
        `SELECT i.produto_id, p.nome, i.esperado, i.contado
           FROM itens_conferencia i LEFT JOIN produtos p ON p.id = i.produto_id
          WHERE i.conferencia_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.produto_id`,
      )
      .all(id) as { produto_id: string; nome: string | null; esperado: number; contado: number }[];
    const linhas: LinhaDaConferencia[] = itens.map((i) => ({
      produtoId: i.produto_id,
      produtoNome: i.nome ?? 'Peça',
      esperado: i.esperado,
      contado: i.contado,
      diferenca: i.contado - i.esperado,
    }));
    return {
      id,
      remessaId: c.remessa_id,
      conferidaEm: c.conferida_em ?? '',
      divergencias: c.divergencias ?? 0,
      itens: linhas,
    };
  }

  // --- cotação ---

  /** A cotação vigente no instante (a de maior `vigente_desde` até ele; ADR-0006, item 15). */
  private cotacaoEm(instante: string | Date): Taxas | null {
    const r = cotacaoVigenteEm(this.db(), instante);
    return r ? this.taxas(r.data, r.dolar, r.guarani, r.origem, r.vigenteDesde) : null;
  }

  private taxas(
    data: string,
    dolar: string | null,
    guarani: string | null,
    origem: 'BANCO_CENTRAL' | 'DONA' = 'BANCO_CENTRAL',
    vigenteDesde = `${data}T03:00:00.000Z`,
  ): Taxas | null {
    const usd = taxaDe(dolar);
    const pyg = taxaDe(guarani);
    return usd && pyg && dolar && guarani
      ? { data, dolar, guarani, usd, pyg, origem, vigenteDesde }
      : null;
  }

  /** A cotação da venda: a gravada nela, ou (venda feita sem internet) a vigente na hora dela. */
  private cotacaoDaVenda(v: { cotacao_id: string | null; vendida_em: string }): Taxas | null {
    if (v.cotacao_id) {
      const r = this.db()
        .prepare('SELECT data_cotacao, usd_venda, pyg_venda FROM cotacoes WHERE id = ?')
        .get(v.cotacao_id) as
        { data_cotacao: string; usd_venda: string | null; pyg_venda: string | null } | undefined;
      const t = r ? this.taxas(r.data_cotacao, r.usd_venda, r.pyg_venda) : null;
      if (t) return t;
    }
    return this.cotacaoEm(v.vendida_em);
  }

  private cotacaoDeHoje(): CotacaoDoDia {
    const hoje = diaEmBrasilia(this.agora());
    const t = this.cotacaoEm(this.agora());
    if (!t) {
      return {
        disponivel: false,
        dataDeHoje: hoje,
        dataDaCotacao: null,
        dolar: null,
        guarani: null,
        deDiaAnterior: false,
      };
    }
    return {
      disponivel: true,
      dataDeHoje: hoje,
      dataDaCotacao: t.data,
      dolar: t.dolar,
      guarani: t.guarani,
      deDiaAnterior: t.data < hoje,
      origem: t.origem,
      vigenteDesde: t.vigenteDesde,
    };
  }

  // --- vendas ---

  private static readonly SELECT_VENDA = `
    SELECT v.id, v.cliente_id, c.nome AS cliente_nome, v.remessa_id, v.status, v.subtotal, v.desconto, v.total,
           v.cotacao_id, v.vendida_em, v.estornada_em, v.motivo_estorno, v.troca_id,
           (EXISTS (SELECT 1 FROM movimentos_remessa m
                     WHERE m.venda_id = v.id AND m.tipo = 'ESTORNO_VENDA' AND m.deleted_at IS NULL)
            OR ${SQL_VENDA_ESTORNADA_AQUI('v')}) AS estornada_aqui
      FROM vendas v LEFT JOIN clientes c ON c.id = v.cliente_id`;

  private venda(id: string): Venda | null {
    const linha = this.db()
      .prepare(`${ConsultasLocais.SELECT_VENDA} WHERE v.id = ? AND v.deleted_at IS NULL`)
      .get(id) as LinhaDaVenda | undefined;
    return linha ? this.montarVenda(linha) : null;
  }

  private vendas(f: {
    de?: string;
    ate?: string;
    clienteId?: string;
    status?: 'CONCLUIDA' | 'ESTORNADA';
  }): Venda[] {
    const linhas = this.db()
      .prepare(
        `${ConsultasLocais.SELECT_VENDA} WHERE v.deleted_at IS NULL
          ${f.clienteId ? 'AND v.cliente_id = ?' : ''}
          ORDER BY v.vendida_em DESC, v.id DESC`,
      )
      .all(...(f.clienteId ? [f.clienteId] : [])) as LinhaDaVenda[];
    return linhas
      .filter((l) => {
        const dia = diaEmBrasilia(l.vendida_em);
        if (f.de && dia < f.de) return false;
        if (f.ate && dia > f.ate) return false;
        return !f.status || this.situacao(l) === f.status;
      })
      .map((l) => this.montarVenda(l));
  }

  private situacao(l: LinhaDaVenda): 'CONCLUIDA' | 'ESTORNADA' {
    return l.status === 'ESTORNADA' || l.estornada_aqui === 1 ? 'ESTORNADA' : 'CONCLUIDA';
  }

  private montarVenda(l: LinhaDaVenda): Venda {
    const db = this.db();
    const status = this.situacao(l);
    const taxas = this.cotacaoDaVenda(l);
    const total = centavosDe(l.total ?? '0') ?? 0n;
    const itens = (
      db
        .prepare(
          `SELECT i.produto_id, i.quantidade, i.preco_unitario, i.desconto, p.nome
             FROM itens_venda i LEFT JOIN produtos p ON p.id = i.produto_id
            WHERE i.venda_id = ? AND i.deleted_at IS NULL ORDER BY p.nome, i.produto_id`,
        )
        .all(l.id) as {
        produto_id: string;
        quantidade: number;
        preco_unitario: string | null;
        desconto: string | null;
      }[]
    ).map((i) => {
      const preco = centavosDe(i.preco_unitario ?? '0') ?? 0n;
      const desconto = centavosDe(i.desconto ?? '0') ?? 0n;
      return {
        produto: this.peca(i.produto_id),
        quantidade: i.quantidade,
        precoUnitario: textoDeCentavos(preco),
        desconto: textoDeCentavos(desconto),
        total: textoDeCentavos(preco * BigInt(i.quantidade) - desconto),
      };
    });
    const pagamentos = (
      db
        .prepare(
          `SELECT id, tipo, forma, valor, pago_em, devolucao_id, posterior FROM pagamentos_cliente
            WHERE venda_id = ? AND deleted_at IS NULL ORDER BY pago_em, rowid`,
        )
        .all(l.id) as {
        id: string;
        tipo: string | null;
        forma: string | null;
        valor: string;
        pago_em: string | null;
        devolucao_id: string | null;
        posterior: number | null;
      }[]
    ).map((p) => ({
      id: p.id,
      tipo: tipoDoPagamento(p.tipo),
      forma: p.forma ?? '',
      valor: p.valor,
      em: p.pago_em ?? '',
      daDevolucao: p.devolucao_id !== null,
      posterior: p.posterior === 1,
    }));
    const pago = pagamentos.reduce((t, p) => t + (centavosDe(p.valor) ?? 0n), 0n);
    const devolvido = this.devolvidoDaVenda(l.id);
    const creditoSemSaldo =
      (
        db
          .prepare(
            `SELECT EXISTS (SELECT 1 FROM creditos_sem_saldo
                              WHERE venda_id = ? AND situacao = 'EM_REVISAO' AND deleted_at IS NULL) AS e`,
          )
          .get(l.id) as { e: number }
      ).e === 1;
    const estorno = this.estornoDaVenda(l);
    return {
      id: l.id,
      clienteId: l.cliente_id ?? '',
      clienteNome: l.cliente_nome ?? '',
      remessaId: l.remessa_id ?? '',
      status,
      subtotal: l.subtotal ?? '0.00',
      desconto: l.desconto ?? '0.00',
      total: textoDeCentavos(total),
      totalDolar: taxas ? textoDeCentavos(paraDolar(total, taxas.usd)) : null,
      totalGuarani: taxas ? paraGuarani(total, taxas.pyg).toString() : null,
      cotacao: taxas ? { data: taxas.data, dolar: taxas.dolar, guarani: taxas.guarani } : null,
      pago: textoDeCentavos(pago),
      devolvido: textoDeCentavos(devolvido),
      // O devolvido sai do que o cliente deve (tarefa 6.8, como o servidor): total − devolvido − pago.
      aReceber: status === 'ESTORNADA' ? '0.00' : textoDeCentavos(total - devolvido - pago),
      vendidaEm: l.vendida_em,
      trocaId: l.troca_id,
      creditoSemSaldo,
      estornadaEm: status === 'ESTORNADA' ? estorno.em : null,
      motivoDoEstorno: status === 'ESTORNADA' ? estorno.motivo : null,
      itens,
      pagamentos,
    };
  }

  /** Soma das devoluções não canceladas da venda (nem no servidor nem aqui). */
  private devolvidoDaVenda(vendaId: string): bigint {
    return (
      this.db()
        .prepare(
          `SELECT valor_devolvido FROM devolucoes d WHERE d.venda_id = ? AND ${SQL_DEVOLUCAO_ATIVA('d')}`,
        )
        .all(vendaId) as { valor_devolvido: string | null }[]
    ).reduce((t, d) => t + (centavosDe(d.valor_devolvido ?? '0') ?? 0n), 0n);
  }

  /** Quando e por quê: da venda vinda do servidor, ou do estorno feito aqui e ainda aguardando envio. */
  private estornoDaVenda(l: LinhaDaVenda): { em: string | null; motivo: string | null } {
    if (l.estornada_em) return { em: l.estornada_em, motivo: l.motivo_estorno };
    const o = this.db()
      .prepare(
        `SELECT json_extract(dados, '$.estornadoEm') AS em, json_extract(dados, '$.motivo') AS motivo
           FROM outbox WHERE tipo = 'ESTORNO_VENDA' AND json_extract(dados, '$.vendaId') = ?
            ORDER BY ordem DESC LIMIT 1`,
      )
      .get(l.id) as { em: string | null; motivo: string | null } | undefined;
    return { em: o?.em ?? null, motivo: o?.motivo ?? l.motivo_estorno };
  }

  // --- clientes ---

  private static readonly SELECT_CLIENTE = `
    SELECT id, nome, cpf, telefone, endereco_cidade, data_nascimento, whatsapp, observacoes, ativo,
           (vendedor_responsavel_id IS NOT NULL OR origem_local = 'PROVISORIO') AS meu
      FROM clientes`;

  private static comoCliente(r: Record<string, unknown>): Cliente {
    const meu = r['meu'] === 1;
    const texto = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null);
    return {
      id: r['id'] as string,
      nome: r['nome'] as string,
      cpf: texto(r['cpf']),
      telefone: texto(r['telefone']),
      cidade: texto(r['endereco_cidade']),
      meu,
      dataNascimento: meu ? texto(r['data_nascimento']) : null,
      whatsapp: meu ? texto(r['whatsapp']) : null,
      observacoes: meu ? texto(r['observacoes']) : null,
      ativo: r['ativo'] !== 0,
    };
  }

  private cliente(id: string): Cliente | null {
    const r = this.db()
      .prepare(`${ConsultasLocais.SELECT_CLIENTE} WHERE id = ? AND deleted_at IS NULL`)
      .get(id) as Record<string, unknown> | undefined;
    return r ? ConsultasLocais.comoCliente(r) : null;
  }

  private clientes(busca: string): Cliente[] {
    const texto = busca.trim().toLowerCase();
    const digitos = texto.replace(/\D/g, '');
    const todos = (
      this.db()
        .prepare(
          `${ConsultasLocais.SELECT_CLIENTE}
            WHERE deleted_at IS NULL AND ativo IS NOT 0 ORDER BY lower(nome), id`,
        )
        .all() as Record<string, unknown>[]
    ).map((r) => ({ r, c: ConsultasLocais.comoCliente(r) }));
    const so = (v: string | null): string => (v ?? '').replace(/\D/g, '');
    return todos
      .filter(({ c }) => {
        if (texto === '') return true;
        if (c.nome.toLowerCase().includes(texto)) return true;
        if (digitos === '') return false;
        return (
          so(c.telefone).includes(digitos) ||
          (c.meu && so(c.whatsapp).includes(digitos)) ||
          so(c.cpf).startsWith(digitos)
        );
      })
      .slice(0, 100)
      .map(({ c }) => c);
  }

  // --- relatórios ---

  private lancamentosDeVendas(
    de: string | undefined,
    ate: string | undefined,
  ): (LinhaDaVenda & { taxas: Taxas | null })[] {
    const linhas = this.db()
      .prepare(
        `${ConsultasLocais.SELECT_VENDA} WHERE v.deleted_at IS NULL ORDER BY v.vendida_em, v.id`,
      )
      .all() as LinhaDaVenda[];
    return linhas
      .filter((l) => {
        const dia = diaEmBrasilia(l.vendida_em);
        return (!de || dia >= de) && (!ate || dia <= ate) && this.situacao(l) === 'CONCLUIDA';
      })
      .map((l) => ({ ...l, taxas: this.cotacaoDaVenda(l) }));
  }

  private relatorioDeVendas(
    de: string | undefined,
    ate: string | undefined,
    agrupar: 'cliente' | 'peca' | 'dia',
  ): RelatorioDeVendas {
    const chaves = new Map<
      string,
      { nome: string; quantidade: number; valor: Soma; desconto: Soma }
    >();
    const acumular = (
      chave: string,
      nome: string,
      quantidade: number,
      valor: bigint,
      desconto: bigint,
      taxas: Taxas | null,
    ) => {
      let l = chaves.get(chave);
      if (!l) {
        l = { nome, quantidade: 0, valor: new Soma(), desconto: new Soma() };
        chaves.set(chave, l);
      }
      l.quantidade += quantidade;
      l.valor.somar(valor, taxas);
      l.desconto.somar(desconto, taxas);
    };
    const vendas = this.lancamentosDeVendas(de, ate);
    if (agrupar === 'peca') {
      const itensDe = this.db().prepare(
        `SELECT i.produto_id, i.quantidade, i.preco_unitario, i.desconto, p.nome
           FROM itens_venda i LEFT JOIN produtos p ON p.id = i.produto_id
          WHERE i.venda_id = ? AND i.deleted_at IS NULL ORDER BY i.id`,
      );
      for (const v of vendas) {
        for (const i of itensDe.all(v.id) as {
          produto_id: string;
          quantidade: number;
          preco_unitario: string | null;
          desconto: string | null;
          nome: string | null;
        }[]) {
          const desconto = centavosDe(i.desconto ?? '0') ?? 0n;
          const valor =
            (centavosDe(i.preco_unitario ?? '0') ?? 0n) * BigInt(i.quantidade) - desconto;
          acumular(i.produto_id, i.nome ?? 'Peça', i.quantidade, valor, desconto, v.taxas);
        }
      }
    } else {
      for (const v of vendas) {
        const total = centavosDe(v.total ?? '0') ?? 0n;
        const desconto = centavosDe(v.desconto ?? '0') ?? 0n;
        if (agrupar === 'cliente')
          acumular(v.cliente_id ?? '', v.cliente_nome ?? '', 1, total, desconto, v.taxas);
        else {
          const dia = diaEmBrasilia(v.vendida_em);
          const [a, m, d] = dia.split('-');
          acumular(dia, `${d}/${m}/${a}`, 1, total, desconto, v.taxas);
        }
      }
    }
    return montarRelatorio(chaves, agrupar === 'dia');
  }

  private relatorioDeRecebimentos(de?: string, ate?: string): RelatorioDeVendas {
    const linhas = this.db()
      .prepare(
        `SELECT p.forma, p.tipo, p.valor, p.pago_em FROM pagamentos_cliente p
           JOIN vendas v ON v.id = p.venda_id
          WHERE p.deleted_at IS NULL AND v.deleted_at IS NULL AND coalesce(p.forma, '') <> 'CREDITO'
          ORDER BY p.forma, p.pago_em, p.id`,
      )
      .all() as {
      forma: string | null;
      tipo: string | null;
      valor: string;
      pago_em: string | null;
    }[];
    const chaves = new Map<
      string,
      { nome: string; quantidade: number; valor: Soma; desconto: Soma }
    >();
    for (const p of linhas) {
      const dia = diaEmBrasilia(p.pago_em ?? this.agora());
      if ((de && dia < de) || (ate && dia > ate)) continue;
      const forma = p.forma ?? '';
      let l = chaves.get(forma);
      if (!l) {
        l = { nome: forma, quantidade: 0, valor: new Soma(), desconto: new Soma() };
        chaves.set(forma, l);
      }
      const taxas = this.cotacaoEm(p.pago_em ?? this.agora());
      l.quantidade += p.tipo === 'PAGAMENTO' ? 1 : 0;
      l.valor.somar(centavosDe(p.valor) ?? 0n, taxas);
      l.desconto.somar(0n, taxas);
    }
    return montarRelatorio(chaves, false);
  }

  /**
   * Minhas devoluções e trocas (tarefa 6.10): as não canceladas (nem aqui) deste vendedor, por motivo, no período (dias
   * de Brasília). Cada uma pela cotação do seu dia (a gravada nela ou a do dia). Mesma regra do servidor.
   */
  private relatorioDeDevolucoes(de?: string, ate?: string): RelatorioDeDevolucoes {
    const db = this.db();
    const linhas = db
      .prepare(
        `SELECT d.motivo, d.destino, d.vendedor_id, d.valor_devolvido, d.valor_reembolsado, d.valor_em_credito,
                d.cotacao_id, d.devolvida_em
           FROM devolucoes d WHERE ${SQL_DEVOLUCAO_ATIVA('d')} ORDER BY d.devolvida_em, d.id`,
      )
      .all() as {
      motivo: string | null;
      destino: string | null;
      vendedor_id: string | null;
      valor_devolvido: string | null;
      valor_reembolsado: string | null;
      valor_em_credito: string | null;
      cotacao_id: string | null;
      devolvida_em: string;
    }[];
    const porMotivo = new Map<string, { quantidade: number; valor: Soma }>();
    const devolvido = new Soma();
    const dinheiro = new Soma();
    const credito = new Soma();
    let devolucoes = 0;
    let trocas = 0;
    for (const d of linhas) {
      if (!ehDoVendedor(db, d.vendedor_id)) continue;
      const dia = diaEmBrasilia(d.devolvida_em);
      if ((de && dia < de) || (ate && dia > ate)) continue;
      const taxas = this.cotacaoDaVenda({ cotacao_id: d.cotacao_id, vendida_em: d.devolvida_em });
      const valor = centavosDe(d.valor_devolvido ?? '0') ?? 0n;
      const motivo = d.motivo ?? 'OUTRO';
      let l = porMotivo.get(motivo);
      if (!l) porMotivo.set(motivo, (l = { quantidade: 0, valor: new Soma() }));
      l.quantidade += 1;
      l.valor.somar(valor, taxas);
      devolvido.somar(valor, taxas);
      dinheiro.somar(centavosDe(d.valor_reembolsado ?? '0') ?? 0n, taxas);
      credito.somar(centavosDe(d.valor_em_credito ?? '0') ?? 0n, taxas);
      if (d.destino === 'TROCA') trocas += 1;
      else devolucoes += 1;
    }
    return {
      linhas: [...porMotivo.entries()].map(([motivo, l]) => ({
        motivo,
        devolucoes: l.quantidade,
        valor: l.valor.textoReais,
        valorEmDolar: l.valor.textoDolar,
        valorEmGuarani: l.valor.textoGuarani,
      })),
      totais: {
        devolucoes,
        trocas,
        valorDevolvido: devolvido.textoReais,
        valorDevolvidoEmDolar: devolvido.textoDolar,
        valorDevolvidoEmGuarani: devolvido.textoGuarani,
        emDinheiro: dinheiro.textoReais,
        emDinheiroEmDolar: dinheiro.textoDolar,
        emDinheiroEmGuarani: dinheiro.textoGuarani,
        emCredito: credito.textoReais,
        emCreditoEmDolar: credito.textoDolar,
        emCreditoEmGuarani: credito.textoGuarani,
      },
    };
  }

  /**
   * Crédito dos meus clientes (tarefa 6.10): os créditos deste vendedor que ainda valem (sem os cancelados, nem aqui),
   * por cliente: gerado, usado e em aberto, cada um pela cotação do dia em que nasceu. O usado conta também o uso feito
   * aqui e ainda não enviado.
   */
  private relatorioDeCreditos(): RelatorioDeCreditos {
    const db = this.db();
    const creditos = db
      .prepare(
        `SELECT cr.id, cr.cliente_id, cr.vendedor_id, cr.troca_id, cr.devolucao_id, cr.valor, cr.situacao,
                cr.cotacao_id, cr.gerado_em, c.nome AS cliente_nome
           FROM creditos_cliente cr LEFT JOIN clientes c ON c.id = cr.cliente_id
          WHERE cr.deleted_at IS NULL ORDER BY lower(c.nome), cr.cliente_id, cr.gerado_em, cr.id`,
      )
      .all() as {
      id: string;
      cliente_id: string;
      vendedor_id: string | null;
      troca_id: string | null;
      devolucao_id: string | null;
      valor: string;
      situacao: string | null;
      cotacao_id: string | null;
      gerado_em: string | null;
      cliente_nome: string | null;
    }[];
    const nomes = new Map<string, string>();
    const somas = new Map<string, { gerado: Soma; usado: Soma; emAberto: Soma }>();
    const total = { gerado: new Soma(), usado: new Soma(), emAberto: new Soma() };
    for (const c of creditos) {
      if (!ehDoVendedor(db, c.vendedor_id) || !creditoValido(db, c)) continue;
      const geradoEm = c.gerado_em ?? this.agora().toISOString();
      const taxas = this.cotacaoDaVenda({ cotacao_id: c.cotacao_id, vendida_em: geradoEm });
      const valor = centavosDe(c.valor) ?? 0n;
      const saldo = saldoDoCredito(db, c.id);
      nomes.set(c.cliente_id, nomes.get(c.cliente_id) ?? c.cliente_nome ?? '');
      let linha = somas.get(c.cliente_id);
      if (!linha)
        somas.set(
          c.cliente_id,
          (linha = { gerado: new Soma(), usado: new Soma(), emAberto: new Soma() }),
        );
      for (const alvo of [linha, total]) {
        alvo.gerado.somar(valor, taxas);
        alvo.usado.somar(valor - saldo, taxas);
        alvo.emAberto.somar(saldo, taxas);
      }
    }
    const clientes = [...somas.entries()].map(([id, s]) => ({
      clienteId: id,
      clienteNome: nomes.get(id) ?? '',
      gerado: s.gerado.textoReais,
      geradoEmDolar: s.gerado.textoDolar,
      geradoEmGuarani: s.gerado.textoGuarani,
      usado: s.usado.textoReais,
      usadoEmDolar: s.usado.textoDolar,
      usadoEmGuarani: s.usado.textoGuarani,
      emAberto: s.emAberto.textoReais,
      emAbertoEmDolar: s.emAberto.textoDolar,
      emAbertoEmGuarani: s.emAberto.textoGuarani,
    }));
    return {
      clientes,
      totais: {
        gerado: total.gerado.textoReais,
        geradoEmDolar: total.gerado.textoDolar,
        geradoEmGuarani: total.gerado.textoGuarani,
        usado: total.usado.textoReais,
        usadoEmDolar: total.usado.textoDolar,
        usadoEmGuarani: total.usado.textoGuarani,
        emAberto: total.emAberto.textoReais,
        emAbertoEmDolar: total.emAberto.textoDolar,
        emAbertoEmGuarani: total.emAberto.textoGuarani,
      },
    };
  }

  private relatorioAReceber(): RelatorioAReceber {
    const nomes = new Map<string, string>();
    const vendido = new Map<string, Soma>();
    const pago = new Map<string, Soma>();
    const concluidas = new Set<string>();
    for (const v of this.lancamentosDeVendas(undefined, undefined)) {
      concluidas.add(v.id);
      const c = v.cliente_id ?? '';
      nomes.set(c, nomes.get(c) ?? v.cliente_nome ?? '');
      let s = vendido.get(c);
      if (!s) vendido.set(c, (s = new Soma()));
      // O vendido já sem o devolvido (tarefa 6.8, como o servidor).
      s.somar((centavosDe(v.total ?? '0') ?? 0n) - this.devolvidoDaVenda(v.id), v.taxas);
    }
    const pagamentos = this.db()
      .prepare(
        `SELECT v.cliente_id, p.valor, p.pago_em, v.id AS venda_id
           FROM pagamentos_cliente p JOIN vendas v ON v.id = p.venda_id
          WHERE p.deleted_at IS NULL AND v.deleted_at IS NULL ORDER BY p.pago_em, p.id`,
      )
      .all() as {
      cliente_id: string | null;
      valor: string;
      pago_em: string | null;
      venda_id: string;
    }[];
    for (const p of pagamentos) {
      if (!concluidas.has(p.venda_id)) continue;
      const c = p.cliente_id ?? '';
      let s = pago.get(c);
      if (!s) pago.set(c, (s = new Soma()));
      s.somar(centavosDe(p.valor) ?? 0n, this.cotacaoEm(p.pago_em ?? this.agora()));
    }
    const clientes = [];
    let total = new Soma();
    const ordenados = [...nomes.entries()].sort(
      (a, b) =>
        a[1].toLowerCase().localeCompare(b[1].toLowerCase(), 'pt-BR') || a[0].localeCompare(b[0]),
    );
    for (const [id, nome] of ordenados) {
      const v = vendido.get(id) ?? new Soma();
      const p = pago.get(id) ?? new Soma();
      const devendo = v.menos(p);
      if (devendo.reais <= 0n) continue;
      clientes.push({
        clienteId: id,
        clienteNome: nome,
        vendido: v.textoReais,
        pago: p.textoReais,
        aReceber: devendo.textoReais,
        vendidoEmDolar: v.textoDolar,
        vendidoEmGuarani: v.textoGuarani,
        pagoEmDolar: p.textoDolar,
        pagoEmGuarani: p.textoGuarani,
        aReceberEmDolar: devendo.textoDolar,
        aReceberEmGuarani: devendo.textoGuarani,
      });
      total = total.mais(devendo);
    }
    return { clientes, totais: totaisDe(total) };
  }

  // --- envios não aceitos ---

  private enviosNaoAceitos(): EnvioNaoAceito[] {
    const db = this.db();
    const linhas = db
      .prepare(
        `SELECT operacao_id, tipo, dados, criado_em_local, ultimo_erro FROM outbox
          WHERE situacao IN ('RECUSADO', 'CONFLITO') AND entendido_em IS NULL ORDER BY ordem`,
      )
      .all() as {
      operacao_id: string;
      tipo: string;
      dados: string;
      criado_em_local: string;
      ultimo_erro: string | null;
    }[];
    const nomeDoCliente = (id: unknown): string => {
      if (typeof id !== 'string') return '';
      const r = db.prepare('SELECT nome FROM clientes WHERE id = ?').get(id) as
        { nome: string } | undefined;
      return r?.nome ?? '';
    };
    return linhas.map((l) => {
      let d: Record<string, unknown> = {};
      try {
        d = JSON.parse(l.dados) as Record<string, unknown>;
      } catch {
        /* a descrição fica genérica */
      }
      const clienteDaVenda = (vendaId: unknown): string => {
        if (typeof vendaId !== 'string') return '';
        const r = db.prepare('SELECT cliente_id FROM vendas WHERE id = ?').get(vendaId) as
          { cliente_id: string | null } | undefined;
        return nomeDoCliente(r?.cliente_id);
      };
      const descricao = ((): string => {
        switch (l.tipo) {
          case 'VENDA': {
            const nome = nomeDoCliente(d['clienteId']);
            return nome ? `Venda para ${nome}` : 'Venda';
          }
          // Tarefa 6.8 (glossário, parte 6).
          case 'DEVOLUCAO': {
            const nome = clienteDaVenda(d['vendaId']);
            return nome ? `Devolução de ${nome}` : 'Devolução';
          }
          case 'TROCA': {
            const a = d['devolucao'] as Record<string, unknown> | undefined;
            const nome = clienteDaVenda(a?.['vendaId']);
            return nome ? `Troca de ${nome}` : 'Troca';
          }
          case 'PEDIDO_AUTORIZACAO': {
            const nome = clienteDaVenda(d['vendaId']);
            return nome ? `Pedido de autorização: venda de ${nome}` : 'Pedido de autorização';
          }
          case 'RECEBIMENTO_CLIENTE': {
            const nome = clienteDaVenda(d['vendaId']);
            return nome ? `Recebimento de ${nome}` : 'Recebimento de cliente';
          }
          case 'ESTORNO_RECEBIMENTO_CLIENTE':
            return 'Estorno de recebimento';
          case 'CANCELAR_DEVOLUCAO':
            return 'Cancelamento de devolução';
          case 'CANCELAR_TROCA':
            return 'Cancelamento de troca';
          case 'ESTORNO_VENDA':
            return 'Estorno de venda';
          case 'CLIENTE_NOVO':
            return typeof d['nome'] === 'string' ? `Cliente novo: ${d['nome']}` : 'Cliente novo';
          case 'CLIENTE_ALTERADO': {
            const nome = nomeDoCliente(d['clienteId']);
            return nome ? `Mudança no cadastro de ${nome}` : 'Mudança no cadastro de cliente';
          }
          default:
            return 'Conferência da maleta';
        }
      })();
      return {
        id: l.operacao_id,
        ehVenda: l.tipo === 'VENDA',
        descricao,
        feitoEm: l.criado_em_local,
        motivo: l.ultimo_erro?.trim() || 'Sem motivo informado. Fale com a dona.',
      };
    });
  }
}

function totaisDe(s: Soma) {
  return {
    total: s.textoReais,
    totalEmDolar: s.textoDolar,
    totalEmGuarani: s.textoGuarani,
    dataDaCotacao: null,
  };
}

function montarRelatorio(
  chaves: Map<string, { nome: string; quantidade: number; valor: Soma; desconto: Soma }>,
  ordemPelaChave: boolean,
): RelatorioDeVendas {
  const entradas = [...chaves.entries()];
  entradas.sort(([ca, a], [cb, b]) =>
    ordemPelaChave
      ? ca.localeCompare(cb)
      : a.nome.toLowerCase().localeCompare(b.nome.toLowerCase(), 'pt-BR') || ca.localeCompare(cb),
  );
  let total = new Soma();
  const linhas = entradas.map(([id, l]) => {
    total = total.mais(l.valor);
    return {
      id,
      nome: l.nome,
      quantidade: l.quantidade,
      total: l.valor.textoReais,
      desconto: l.desconto.textoReais,
      totalEmDolar: l.valor.textoDolar,
      totalEmGuarani: l.valor.textoGuarani,
      descontoEmDolar: l.desconto.textoDolar,
      descontoEmGuarani: l.desconto.textoGuarani,
    };
  });
  return { linhas, totais: totaisDe(total) };
}

const ok = (dados: unknown): Resultado<unknown> => ({ ok: true, dados });
const nao = (mensagem: string, codigo?: string): Resultado<unknown> => ({
  ok: false,
  falha: { ...naoEncontrado(mensagem), ...(codigo ? { codigo } : {}) },
});

/** O tipo do lançamento na venda: o desfazer do reembolso (devolução cancelada) é um tipo próprio (tarefa 6.8). */
function tipoDoPagamento(tipo: string | null): PagamentoDaVenda['tipo'] {
  return tipo === 'REEMBOLSO' || tipo === 'ESTORNO_REEMBOLSO' || tipo === 'ESTORNO_PAGAMENTO'
    ? tipo
    : 'PAGAMENTO';
}

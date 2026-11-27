import { createHash, randomUUID } from 'node:crypto';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';

/**
 * Servidor falso do sync, em `node:http`, que fala o formato real das rotas da 5.4 e da 5.5 (campos copiados do
 * `SyncDoVendedorController`). Tem um modelo simples do servidor (registro de linhas com cursor por posição, operações
 * por id com o conteúdo, resumo por tabela) e é scriptável: respostas, atrasos e queda DEPOIS de gravar e antes de
 * responder.
 */

export interface LinhaDoServidor {
  tabela: string;
  id: string;
  version: number;
  deletedAt: string | null;
  campos: Record<string, unknown>;
}

export interface PedidoRecebido {
  metodo: string;
  caminho: string;
  query: URLSearchParams;
  corpo: string;
  autorizacao: string | undefined;
  versao: string | undefined;
  tipo: string | undefined;
}

export type ResultadoDaOperacao =
  'ACEITO' | 'JA_RECEBIDO' | 'CONFLITO' | 'RECUSADO' | 'NAO_PROCESSADO';

export interface Roteiro {
  status: number;
  corpo?: unknown;
  cabecalhos?: Record<string, string>;
}

interface OperacaoRecebida {
  operacaoId: string;
  tipo: string;
  formato: number;
  dados: Record<string, unknown>;
}

const HORA = '2026-11-12T13:00:00.000Z';

export class ServidorFalso {
  readonly pedidos: PedidoRecebido[] = [];
  /** O registro do servidor: cada mudança de linha entra no fim. O cursor é `época:posição`. */
  readonly registro: LinhaDoServidor[] = [];
  epoca = 1;
  /** Operações gravadas: id → conteúdo canônico (JSON.stringify do que foi lido). */
  readonly operacoes = new Map<string, string>();
  /** Quantas vezes cada operação foi gravada de verdade (para provar que nada duplica). */
  readonly gravacoes = new Map<string, number>();
  saudavel = true;
  /** Token aceito; outro token responde 401. */
  tokenValido: string | null = null;
  horaServidor = HORA;
  parametros = {
    limiteDescontoPercentual: 20,
    tempoMaximoOfflineDias: 10,
    situacaoDoAparelho: 'ATIVO',
    prazoArrependimentoDias: 7,
    prazoDefeitoDias: 30,
    consignadoMetaGuarani: 700000,
    consignadoComissaoAbaixoDaMeta: 30,
    consignadoComissaoNaMeta: 40,
    consignadoDiasDoRetorno: 45,
  };

  /**
   * Versão mínima do Corona Vendas (tarefa 5.13): app abaixo dela ainda envia (o push responde com
   * `appDesatualizado`), mas o pull e a conferência recebem 426, como no servidor real.
   */
  versaoMinima: string | null = null;
  /** Roteiros que passam na frente do modelo (o primeiro da fila é usado e sai). */
  readonly roteirosDoPush: (Roteiro | 'cair-depois-de-gravar')[] = [];
  readonly roteirosDoPull: Roteiro[] = [];
  readonly roteirosDaConferencia: Roteiro[] = [];
  /** Resultado escolhido por operação (o resto segue o modelo). */
  resultadoPara: (op: OperacaoRecebida) => ResultadoDaOperacao | undefined = () => undefined;
  /** Linhas que o servidor cria ao aceitar uma operação (padrão: a cópia da venda, do cliente ou da conferência). */
  linhasDaOperacao: (op: OperacaoRecebida) => LinhaDoServidor[] = linhasPadrao;
  /** Resumo que o servidor manda na conferência: padrão é o do registro; troque para simular defeito do filtro. */
  resumoDaTabela: (
    tabela: string,
    linhas: LinhaDoServidor[],
  ) => { quantidade: number; resumo: string } = resumoDe;
  /** Atraso em ms antes de responder (todas as rotas de dados). */
  atraso = 0;
  /** Quantos pulls de UMA tabela (`?tabela=`) caem sem resposta (queda depois de o app apagar a tabela). */
  cairNoPullDaTabela = 0;
  /** O pull desta tabela (`?tabela=`) cai sem resposta, as vezes pedidas (as outras tabelas respondem). */
  cairNaTabela: { tabela: string; vezes: number } | null = null;
  /**
   * Computador trocado pela dona (ADR-0013, item 13): como o `ServicoDeEnvio`, o push responde `apagarDados` quando o
   * lote foi todo resolvido e o app avisou `aguardandoDepoisDeste = 0`.
   */
  computadorTrocado = false;
  /**
   * Chamado com o número do push (1, 2, ...) enquanto ele está "na rede" (o corpo já foi montado pelo app e a resposta
   * ainda não voltou): o vendedor continua trabalhando no computador nesse meio-tempo (issue #127).
   */
  durantePush: ((numero: number) => void) | null = null;
  private pushes = 0;

  private servidor: Server | null = null;

  async iniciar(): Promise<string> {
    this.servidor = createServer((req, res) => {
      void this.atender(req, res);
    });
    await new Promise<void>((r) => this.servidor?.listen(0, '127.0.0.1', r));
    const { port } = this.servidor.address() as AddressInfo;
    return `http://127.0.0.1:${port}`;
  }

  async parar(): Promise<void> {
    const s = this.servidor;
    if (!s) return;
    s.closeAllConnections();
    await new Promise<void>((r) => s.close(() => r()));
  }

  /** A dona fez algo no servidor: a linha entra no registro (version maior que a última do mesmo id). */
  mudar(linha: Omit<LinhaDoServidor, 'version'> & { version?: number }): LinhaDoServidor {
    const ultima = this.estado().get(`${linha.tabela}:${linha.id}`);
    const nova: LinhaDoServidor = {
      ...linha,
      version: linha.version ?? (ultima ? ultima.version + 1 : 1),
    };
    this.registro.push(nova);
    return nova;
  }

  /** O estado atual: a última versão de cada linha. */
  estado(): Map<string, LinhaDoServidor> {
    const m = new Map<string, LinhaDoServidor>();
    for (const l of this.registro) m.set(`${l.tabela}:${l.id}`, l);
    return m;
  }

  pedidosPara(caminho: string): PedidoRecebido[] {
    return this.pedidos.filter((p) => p.caminho === caminho);
  }

  // --- atendimento ---

  private async atender(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'http://x');
    const corpo = await new Promise<string>((r) => {
      const partes: Buffer[] = [];
      req.on('data', (p: Buffer) => partes.push(p));
      req.on('end', () => r(Buffer.concat(partes).toString('utf8')));
    });
    const cabecalho = (n: string) => {
      const v = req.headers[n];
      return Array.isArray(v) ? v[0] : v;
    };
    this.pedidos.push({
      metodo: req.method ?? 'GET',
      caminho: url.pathname,
      query: url.searchParams,
      corpo,
      autorizacao: cabecalho('authorization'),
      versao: cabecalho('x-app-versao'),
      tipo: cabecalho('x-app-tipo'),
    });

    if (url.pathname === '/actuator/health') {
      if (!this.saudavel) {
        req.socket.destroy();
        return;
      }
      return responder(res, { status: 200, corpo: { status: 'UP' } });
    }
    if (this.atraso > 0) await new Promise((r) => setTimeout(r, this.atraso));
    if (this.tokenValido && cabecalho('authorization') !== `Bearer ${this.tokenValido}`) {
      return responder(res, { status: 401, corpo: { codigo: 'NAO_AUTENTICADO' } });
    }
    switch (url.pathname) {
      case '/api/vendedor/sync/pull':
        if (this.abaixoDaMinima(req)) return responder(res, DESATUALIZADO);
        if (
          this.cairNaTabela &&
          this.cairNaTabela.vezes > 0 &&
          url.searchParams.get('tabela') === this.cairNaTabela.tabela
        ) {
          this.cairNaTabela.vezes -= 1;
          req.socket.destroy();
          return;
        }
        if (url.searchParams.get('tabela') && this.cairNoPullDaTabela > 0) {
          this.cairNoPullDaTabela -= 1;
          req.socket.destroy();
          return;
        }
        return responder(res, this.roteirosDoPull.shift() ?? this.pull(url.searchParams));
      case '/api/vendedor/sync/push': {
        this.pushes += 1;
        this.durantePush?.(this.pushes);
        const roteiro = this.roteirosDoPush.shift();
        if (roteiro === 'cair-depois-de-gravar') {
          this.push(corpo);
          req.socket.destroy();
          return;
        }
        const r = roteiro ?? this.push(corpo);
        if (this.abaixoDaMinima(req) && r.status === 200 && r.corpo && typeof r.corpo === 'object')
          r.corpo = { ...r.corpo, appDesatualizado: true, versaoMinima: this.versaoMinima };
        return responder(res, r);
      }
      case '/api/vendedor/sync/conferencia':
        if (this.abaixoDaMinima(req)) return responder(res, DESATUALIZADO);
        return responder(res, this.roteirosDaConferencia.shift() ?? this.conferencia());
      case '/api/vendedor/sync/conferencia/diferencas':
        return responder(res, { status: 204 });
      default:
        return responder(res, { status: 404 });
    }
  }

  private abaixoDaMinima(req: IncomingMessage): boolean {
    if (!this.versaoMinima) return false;
    const versao = String(req.headers['x-app-versao'] ?? '0.0.0');
    const n = (v: string) => v.split('.').map(Number);
    const [a, b] = [n(versao), n(this.versaoMinima)];
    for (let i = 0; i < 3; i += 1)
      if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) < (b[i] ?? 0);
    return false;
  }

  private pull(q: URLSearchParams): Roteiro {
    const limite = Number(q.get('limite') ?? '500');
    const tabela = q.get('tabela');
    if (tabela) {
      const linhas = [...this.estado().values()].filter((l) => l.tabela === tabela);
      return this.pagina(linhas, `${this.epoca}:${this.registro.length}`, false);
    }
    const cursor = q.get('cursor');
    let inicio = 0;
    if (cursor) {
      const [epoca, posicao] = cursor.split(':').map(Number);
      if (epoca !== this.epoca) return { status: 409, corpo: { codigo: 'CURSOR_INVALIDO' } };
      inicio = posicao ?? 0;
    }
    // Sem cursor (carga inicial) o registro é lido do começo: versões antigas chegam antes das novas, o que a regra
    // da version torna inofensivo.
    const fatia = this.registro.slice(inicio, inicio + limite);
    const fim = inicio + fatia.length;
    return this.pagina(fatia, `${this.epoca}:${fim}`, fim < this.registro.length);
  }

  private pagina(linhas: LinhaDoServidor[], cursor: string, temMais: boolean): Roteiro {
    return {
      status: 200,
      corpo: {
        linhas,
        cursor,
        temMais,
        horaServidor: this.horaServidor,
        parametros: this.parametros,
      },
    };
  }

  private push(texto: string): Roteiro {
    if (Buffer.byteLength(texto, 'utf8') > 512 * 1024)
      return { status: 413, corpo: { codigo: 'LOTE_GRANDE_DEMAIS' } };
    const pedido = JSON.parse(texto) as {
      aguardandoDepoisDeste?: number | null;
      operacoes: OperacaoRecebida[];
    };
    if (pedido.operacoes.length > 50)
      return { status: 413, corpo: { codigo: 'LOTE_GRANDE_DEMAIS' } };
    let parou = false;
    const resultados = pedido.operacoes.map((op) => {
      if (parou) return { operacaoId: op.operacaoId, resultado: 'NAO_PROCESSADO' };
      const canonico = JSON.stringify(op.dados);
      const escolhido = this.resultadoPara(op);
      if (escolhido === 'NAO_PROCESSADO') {
        parou = true;
        return {
          operacaoId: op.operacaoId,
          resultado: 'NAO_PROCESSADO',
          codigo: 'ERRO_PASSAGEIRO',
        };
      }
      if (escolhido === 'RECUSADO')
        return {
          operacaoId: op.operacaoId,
          resultado: 'RECUSADO',
          codigo: 'VENDA_SEM_SALDO',
          mensagem: 'Não há mais peças desta na sua maleta.',
        };
      if (escolhido) return { operacaoId: op.operacaoId, resultado: escolhido };
      const ja = this.operacoes.get(op.operacaoId);
      if (ja !== undefined)
        return {
          operacaoId: op.operacaoId,
          resultado: ja === canonico ? 'JA_RECEBIDO' : 'CONFLITO',
        };
      this.operacoes.set(op.operacaoId, canonico);
      this.gravacoes.set(op.operacaoId, (this.gravacoes.get(op.operacaoId) ?? 0) + 1);
      for (const l of this.linhasDaOperacao(op)) this.mudar(l);
      return { operacaoId: op.operacaoId, resultado: 'ACEITO' };
    });
    // Igual ao `ServicoDeEnvio.enviarNaVez`: o servidor só sabe o `aguardandoDepoisDeste` que o app mandou.
    const aguardando = pedido.aguardandoDepoisDeste;
    const completo =
      resultados.every((r) => r.resultado !== 'NAO_PROCESSADO') &&
      (aguardando === null || aguardando === undefined || aguardando === 0);
    return {
      status: 200,
      corpo: {
        resultados,
        horaServidor: this.horaServidor,
        parametros: this.parametros,
        apagarDados: this.computadorTrocado && completo,
      },
    };
  }

  private conferencia(): Roteiro {
    const porTabela = new Map<string, LinhaDoServidor[]>();
    for (const l of this.estado().values()) {
      const lista = porTabela.get(l.tabela) ?? [];
      lista.push(l);
      porTabela.set(l.tabela, lista);
    }
    const tabelas = TABELAS.map((t) => ({
      tabela: t,
      ...this.resumoDaTabela(t, porTabela.get(t) ?? []),
    }));
    return { status: 200, corpo: { tabelas, horaServidor: this.horaServidor } };
  }
}

const DESATUALIZADO: Roteiro = {
  status: 426,
  corpo: {
    codigo: 'APP_DESATUALIZADO',
    title: 'Seu aplicativo está desatualizado.',
    detail: 'Seu aplicativo está desatualizado. Atualize para continuar usando.',
  },
};

const TABELAS = [
  'cotacoes',
  'categorias',
  'semanas',
  'clientes',
  'produtos',
  'produto_codigos',
  'produtos_fotos',
  'remessas',
  'itens_remessa',
  'movimentos_remessa',
  'vendas',
  'itens_venda',
  'pagamentos_cliente',
  'conferencias',
  'itens_conferencia',
  // Fase 6 (tarefa 6.7): as 23 da conferência do servidor.
  'devolucoes',
  'itens_devolucao',
  'autorizacoes_excecao',
  'itens_autorizacao',
  'trocas',
  'creditos_cliente',
  'usos_credito',
  'creditos_sem_saldo',
];

export function resumoDe(
  _tabela: string,
  linhas: LinhaDoServidor[],
): { quantidade: number; resumo: string } {
  const sha = createHash('sha256');
  for (const l of [...linhas].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)))
    sha.update(`${l.id}:${l.version}\n`, 'utf8');
  return { quantidade: linhas.length, resumo: sha.digest('hex') };
}

function responder(res: ServerResponse, r: Roteiro): void {
  const corpo = r.corpo === undefined ? '' : JSON.stringify(r.corpo);
  res.writeHead(r.status, { 'Content-Type': 'application/json', ...(r.cabecalhos ?? {}) });
  res.end(corpo);
}

/** O que o servidor real grava ao aceitar cada tipo (o essencial para o app ver a cópia chegar pelo pull). */
function linhasPadrao(op: OperacaoRecebida): LinhaDoServidor[] {
  const d = op.dados;
  const novo = () => randomUUID();
  switch (op.tipo) {
    case 'VENDA': {
      const itens = d['itens'] as { produtoId: string; quantidade: number; desconto: string }[];
      const pagamentos = d['pagamentos'] as { id: string; valor: string; forma: string }[];
      return [
        {
          tabela: 'vendas',
          id: String(d['id']),
          version: 1,
          deletedAt: null,
          campos: {
            remessa_id: d['remessaId'],
            cliente_id: d['clienteId'],
            status: 'CONCLUIDA',
            total: d['totalConferido'],
            vendida_em: d['vendidaEm'],
          },
        },
        ...itens.flatMap((i): LinhaDoServidor[] => [
          {
            tabela: 'itens_venda',
            id: novo(),
            version: 1,
            deletedAt: null,
            campos: {
              venda_id: d['id'],
              produto_id: i.produtoId,
              quantidade: i.quantidade,
              desconto: i.desconto,
            },
          },
          {
            tabela: 'movimentos_remessa',
            id: novo(),
            version: 1,
            deletedAt: null,
            campos: {
              remessa_id: d['remessaId'],
              produto_id: i.produtoId,
              tipo: 'VENDA',
              quantidade: -i.quantidade,
              venda_id: d['id'],
            },
          },
        ]),
        ...pagamentos.map((p): LinhaDoServidor => ({
          tabela: 'pagamentos_cliente',
          id: p.id,
          version: 1,
          deletedAt: null,
          campos: { venda_id: d['id'], tipo: 'PAGAMENTO', forma: p.forma, valor: p.valor },
        })),
      ];
    }
    case 'CLIENTE_NOVO':
      return [
        {
          tabela: 'clientes',
          id: String(d['id']),
          version: 1,
          deletedAt: null,
          campos: { nome: d['nome'], cpf: d['cpf'], telefone: d['telefone'] },
        },
      ];
    default:
      return [];
  }
}

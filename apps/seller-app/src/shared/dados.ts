/**
 * Formato das respostas da API que a tela usa (o contrato do vendedor: sem custo, sem estoque da loja, só o que é
 * dele). Valores em reais chegam como texto com ponto ("50.00"). A tela confere o formato antes de usar.
 */

export interface PecaDaMaleta {
  id: string;
  nome: string;
  codigo: string | null;
  fotoHash: string | null;
  /** Nome da categoria da peça. */
  categoria: string | null;
  /** COD Fábrica: o código do fornecedor, quando houver. Nunca custo nem estoque da loja. */
  codigoFornecedor: string | null;
}

export interface LinhaDaMaleta {
  produto: PecaDaMaleta;
  enviada: number;
  vendida: number;
  devolvida: number;
  saldo: number;
  precoUnitario: string;
  valorEmAberto: string;
}

export interface Maleta {
  id: string;
  numero: string | null;
  semanaNumero: number;
  tipo: 'NOVA' | 'REPOSICAO';
  status: string;
  enviadaEm: string;
  pecasEmAberto: number;
  valorEmAberto: string;
  itens: LinhaDaMaleta[];
}

export interface LinhaDaConferencia {
  produtoId: string;
  produtoNome: string;
  esperado: number;
  contado: number;
  diferenca: number;
}

export interface Conferencia {
  id: string;
  remessaId: string;
  conferidaEm: string;
  divergencias: number;
  itens: LinhaDaConferencia[];
}

/**
 * Cliente da LOJA, como o servidor mostra ao vendedor (11/11/2026): nome, CPF, telefone e cidade para todos;
 * nascimento, WhatsApp e observações só dos clientes que ele cadastrou (`meu`).
 */
export interface Cliente {
  id: string;
  nome: string;
  cpf: string | null;
  telefone: string | null;
  cidade: string | null;
  meu: boolean;
  dataNascimento: string | null;
  whatsapp: string | null;
  observacoes: string | null;
  ativo: boolean;
}

export interface ItemDaVenda {
  produto: PecaDaMaleta;
  quantidade: number;
  precoUnitario: string;
  desconto: string;
  total: string;
}

export interface PagamentoDaVenda {
  id: string;
  /** `ESTORNO_REEMBOLSO`: o que voltou para a venda quando uma devolução foi cancelada (tarefa 6.8). */
  tipo: 'PAGAMENTO' | 'REEMBOLSO' | 'ESTORNO_REEMBOLSO';
  /** `CREDITO` é o crédito do cliente (na tela, "Crédito do cliente"). */
  forma: string;
  valor: string;
  em: string;
  /** O reembolso (ou o desfazer dele) veio de uma devolução (na tela, "Devolução"). */
  daDevolucao?: boolean;
}

export interface CotacaoDaVenda {
  data: string;
  dolar: string;
  guarani: string;
}

export interface Venda {
  id: string;
  clienteId: string;
  clienteNome: string;
  remessaId: string;
  status: 'CONCLUIDA' | 'ESTORNADA';
  subtotal: string;
  desconto: string;
  total: string;
  totalDolar: string | null;
  totalGuarani: string | null;
  cotacao: CotacaoDaVenda | null;
  pago: string;
  /** Soma das devoluções não canceladas (tarefa 6.8): sai do que o cliente deve. */
  devolvido?: string;
  /** total − devolvido − pago (zero na venda estornada). */
  aReceber: string;
  vendidaEm: string;
  /** A venda nova de uma troca (só se desfaz pelo "Cancelar troca"). */
  trocaId?: string | null;
  /** Paga com crédito que já tinha acabado quando chegou à loja (a dona vai olhar; tarefa 6.7). */
  creditoSemSaldo?: boolean;
  estornadaEm: string | null;
  motivoDoEstorno: string | null;
  itens: ItemDaVenda[];
  pagamentos: PagamentoDaVenda[];
}

export interface CotacaoDoDia {
  disponivel: boolean;
  dataDeHoje: string;
  dataDaCotacao: string | null;
  dolar: string | null;
  guarani: string | null;
  deDiaAnterior: boolean;
}

export interface FotoPronta {
  dataUrl: string;
}

export interface LinhaDeRelatorio {
  id: string;
  nome: string;
  quantidade: number;
  total: string;
  desconto: string;
  /** Soma dos lançamentos da linha, cada um pela cotação do seu dia; vazio se faltou cotação. */
  totalEmDolar?: string | null;
  totalEmGuarani?: string | null;
  descontoEmDolar?: string | null;
  descontoEmGuarani?: string | null;
}

export interface TotaisDeRelatorio {
  total: string;
  totalEmDolar: string | null;
  totalEmGuarani: string | null;
  dataDaCotacao: string | null;
}

export interface RelatorioDeVendas {
  linhas: LinhaDeRelatorio[];
  totais: TotaisDeRelatorio;
}

export interface Devedor {
  clienteId: string;
  clienteNome: string;
  vendido: string;
  pago: string;
  aReceber: string;
  vendidoEmDolar?: string | null;
  vendidoEmGuarani?: string | null;
  pagoEmDolar?: string | null;
  pagoEmGuarani?: string | null;
  aReceberEmDolar?: string | null;
  aReceberEmGuarani?: string | null;
}

export interface RelatorioAReceber {
  clientes: Devedor[];
  totais: TotaisDeRelatorio;
}

/**
 * Um envio que o servidor não aceitou (uma venda que a peça já não estava na maleta, por exemplo). O motivo é o texto em
 * palavras simples que o servidor devolveu; a descrição diz o que foi, sem código nem termo técnico.
 */
export interface EnvioNaoAceito {
  id: string;
  /** Se foi uma venda (para o aviso dizer "venda" ou "item"). */
  ehVenda: boolean;
  /** "Venda para Ana", "Estorno de venda", "Cliente novo: Ana"... */
  descricao: string;
  /** Hora em que a pessoa fez isto neste computador (ISO, UTC). */
  feitoEm: string;
  motivo: string;
}

// --- devolução, troca e crédito no Corona Vendas (tarefa 6.8). Valores são PRÉVIA: o que vale vem do servidor. ---

/** Uma venda deste vendedor com a peça lida, para escolher o que devolver ou trocar. */
export interface VendaComAPeca {
  vendaId: string;
  vendidaEm: string;
  clienteId: string;
  clienteNome: string;
  peca: PecaDaMaleta;
  vendidas: number;
  jaDevolvidas: number;
  podemVoltar: number;
  /** Últimos dias (AAAA-MM-DD, Brasília) da garantia por desistência e por defeito. */
  desistenciaAte: string;
  defeitoAte: string;
  /** Por que não dá para devolver nem trocar agora (venda com parcelas, maleta em acerto). Nulo = dá. */
  bloqueio: string | null;
}

export interface PecaParaDevolver {
  produto: PecaDaMaleta;
  vendidas: number;
  jaDevolvidas: number;
  podemVoltar: number;
}

export interface PreviaDaDevolucao {
  vendaId: string;
  clienteId: string;
  clienteNome: string;
  vendidaEm: string;
  remessaId: string;
  pecas: PecaParaDevolver[];
  valor: string;
  emDinheiro: string;
  emCredito: string;
  destinos: { produtoId: string; estado: 'BOA' | 'DEFEITO'; destino: 'MALETA' | 'LOJA' }[];
  dentroDoPrazo: boolean;
  desistenciaAte: string;
  defeitoAte: string;
  /** A autorização aprovada que esta devolução vai usar (sozinha). */
  autorizacao: { id: string; validaAte: string } | null;
  /** Já há um pedido esperando a dona. */
  pedidoEsperando: boolean;
  /** Por que não dá para registrar como está (o texto da loja). Nulo = dá. */
  problema: string | null;
  /** Fora do prazo e sem pedido: mostrar "Pedir autorização à dona". */
  podePedirAutorizacao: boolean;
}

export interface PreviaDaTroca {
  /** A maleta conferida de onde saem as peças novas (nula = nenhuma conferida). */
  remessaId: string | null;
  valorQueVolta: string;
  valorNovo: string;
  /** Peças novas − peças que voltam: positivo = a pagar, negativo = fica de crédito. */
  diferenca: string;
  problema: string | null;
}

export type SituacaoNaLista =
  'AGUARDANDO_ENVIO' | 'ACEITA' | 'EM_REVISAO' | 'MANTIDA' | 'NAO_ACEITA' | 'FEITA' | 'CANCELADA';

/** Uma linha de "Minhas devoluções e trocas". */
export interface DevolucaoOuTroca {
  id: string;
  tipo: 'DEVOLUCAO' | 'TROCA';
  em: string;
  vendaId: string;
  clienteNome: string;
  pecas: number;
  valor: string;
  emDinheiro: string;
  emCredito: string;
  /** Só na troca: peças novas − peças que voltaram. */
  diferenca: string | null;
  situacao: SituacaoNaLista;
  /** No mesmo dia e ainda não cancelada (depois, só a dona). */
  podeCancelar: boolean;
}

export type SituacaoDoPedido =
  'AGUARDANDO_ENVIO' | 'PEDIDA' | 'APROVADA' | 'NEGADA' | 'USADA' | 'VENCIDA';

export interface PedidoDeAutorizacao {
  id: string;
  vendaId: string;
  clienteNome: string;
  pedidaEm: string;
  pecas: number;
  situacao: SituacaoDoPedido;
  validaAte: string | null;
}

export interface AvisoDeAutorizacao {
  id: string;
  tipo: 'APROVADA' | 'NEGADA';
  vendaId: string;
  clienteNome: string;
  validaAte: string | null;
}

export interface AutorizacoesDoVendedor {
  pedidos: PedidoDeAutorizacao[];
  avisos: AvisoDeAutorizacao[];
}

export interface CreditosDoCliente {
  total: string;
  creditos: { id: string; saldo: string }[];
}

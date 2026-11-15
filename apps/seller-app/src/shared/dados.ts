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
  tipo: 'PAGAMENTO' | 'REEMBOLSO' | 'ESTORNO_REEMBOLSO' | 'ESTORNO_PAGAMENTO';
  /** `CREDITO` é o crédito do cliente (na tela, "Crédito do cliente"). */
  forma: string;
  valor: string;
  em: string;
  /** O reembolso (ou o desfazer dele) veio de uma devolução (na tela, "Devolução"). */
  daDevolucao?: boolean;
  /** Recebido depois da venda (tarefa 7.11): só estes se estornam, e só no mesmo dia. */
  posterior?: boolean;
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

/** Minhas devoluções e trocas (tarefa 6.10): por motivo, devoluções e trocas juntas; cada uma pela cotação do seu dia. */
export interface LinhaDeDevolucaoDoRelatorio {
  motivo: string;
  devolucoes: number;
  valor: string;
  valorEmDolar: string | null;
  valorEmGuarani: string | null;
}

export interface TotaisDeDevolucaoDoRelatorio {
  devolucoes: number;
  trocas: number;
  valorDevolvido: string;
  valorDevolvidoEmDolar: string | null;
  valorDevolvidoEmGuarani: string | null;
  emDinheiro: string;
  emDinheiroEmDolar: string | null;
  emDinheiroEmGuarani: string | null;
  emCredito: string;
  emCreditoEmDolar: string | null;
  emCreditoEmGuarani: string | null;
}

export interface RelatorioDeDevolucoes {
  linhas: LinhaDeDevolucaoDoRelatorio[];
  totais: TotaisDeDevolucaoDoRelatorio;
}

/** Crédito dos meus clientes (tarefa 6.10): gerado, usado e em aberto por cliente (só o que o vendedor pode usar). */
export interface ClienteComCredito {
  clienteId: string;
  clienteNome: string;
  gerado: string;
  geradoEmDolar: string | null;
  geradoEmGuarani: string | null;
  usado: string;
  usadoEmDolar: string | null;
  usadoEmGuarani: string | null;
  emAberto: string;
  emAbertoEmDolar: string | null;
  emAbertoEmGuarani: string | null;
}

export interface TotaisDeCreditoDoRelatorio {
  gerado: string;
  geradoEmDolar: string | null;
  geradoEmGuarani: string | null;
  usado: string;
  usadoEmDolar: string | null;
  usadoEmGuarani: string | null;
  emAberto: string;
  emAbertoEmDolar: string | null;
  emAbertoEmGuarani: string | null;
}

export interface RelatorioDeCreditos {
  clientes: ClienteComCredito[];
  totais: TotaisDeCreditoDoRelatorio;
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
  /** O que abate do que o cliente ainda deve nas parcelas (venda a prazo; "0.00" na venda à vista). */
  abatido: string;
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
  /** O que A abate do que o cliente ainda deve (A a prazo); "0.00" quando A estava paga. */
  abatido: string;
  /**
   * Peças novas − o crédito que A gera (o valor de A menos o que abateu das parcelas): positivo = a pagar, negativo =
   * fica de crédito. Com A paga, é peças novas − peças que voltam.
   */
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
  /** O que abateu do que o cliente devia nas parcelas (venda a prazo; "0.00" nas outras). */
  abatido: string;
  emDinheiro: string;
  emCredito: string;
  /** Só na troca: peças novas − peças que voltaram. */
  diferenca: string | null;
  situacao: SituacaoNaLista;
  /** No mesmo dia e ainda não cancelada (depois, só a dona). */
  podeCancelar: boolean;
  /** A cotação do dia do lançamento (Dólar e Guarani só para mostrar, ADR-0006). */
  cotacao?: CotacaoDaVenda | null;
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

// --- acerto, parcelas e recebíveis no Corona Vendas (tarefa 7.11). Tudo em Real; o que vale é o que o servidor devolve. ---

export type SituacaoDaParcela = 'ABERTA' | 'PARCIAL' | 'PAGA' | 'CANCELADA';

/** Uma parcela que o servidor gerou (nunca calculada no computador). */
export interface ParcelaAReceber {
  id: string;
  numero: number;
  total: number;
  /** AAAA-MM-DD. */
  vencimento: string;
  valor: string;
  valorPago: string;
  valorCancelado: string;
  situacao: SituacaoDaParcela;
  /** Aberta ou em parte, com o vencimento antes de hoje (Brasília). */
  vencida: boolean;
}

/** Um recebimento do cliente depois da venda, e se ainda dá para estornar (mesmo dia). */
export interface RecebimentoDoCliente {
  id: string;
  valor: string;
  forma: string;
  em: string;
  estornado: boolean;
  podeEstornar: boolean;
  /** Ainda não chegou à loja (aguardando envio). */
  aguardandoEnvio: boolean;
}

/** Uma venda a prazo (ou com recebimento de hoje) do vendedor, com as parcelas que o servidor gerou. */
export interface VendaAPrazo {
  vendaId: string;
  vendidaEm: string;
  clienteId: string;
  clienteNome: string;
  total: string;
  pago: string;
  /** Quanto falta o cliente pagar (prévia: o que vale é o servidor). */
  aReceber: string;
  parcelas: ParcelaAReceber[];
  recebimentos: RecebimentoDoCliente[];
  /** A venda ainda não chegou à loja: as parcelas só aparecem depois. */
  aguardandoEnvio: boolean;
}

/** Uma conta que o vendedor deve à loja (nível 2, gerada no fechamento do acerto). */
export interface ContaDaLoja {
  id: string;
  numero: number;
  total: number;
  vencimento: string | null;
  valor: string;
  valorPago: string;
  valorCancelado: string;
  situacao: SituacaoDaParcela;
  vencida: boolean;
  /** `DINHEIRO`, `PIX` ou `BOLETO`: só o boleto aparece como conta no fechamento, lido do servidor. */
  formaPrevista: string | null;
  acertoId: string | null;
}

export interface RecebiveisDoVendedor {
  /** O que os clientes devem ao vendedor, da venda mais antiga para a mais nova. */
  dosClientes: VendaAPrazo[];
  /** O que o vendedor deve à loja, da que vence primeiro para a que vence depois. */
  daLoja: ContaDaLoja[];
}

export type SituacaoDoAcerto = 'EM_ACERTO' | 'ACERTADO' | 'DESISTIDO';

export interface ResumoDoAcerto {
  id: string;
  semanaNumero: number;
  situacao: SituacaoDoAcerto;
  pedidoPeloVendedor: boolean;
  iniciadoEm: string | null;
  fechadoEm: string | null;
}

export interface ItemDoFechamento {
  produto: PecaDaMaleta;
  esperado: number;
  contadoBoa: number;
  contadoDefeito: number;
  falta: number;
  defeitoAprovado: number;
}

export interface ComissaoDoFechamento {
  vendaId: string | null;
  clienteNome: string;
  tipo: 'COMISSAO' | 'ESTORNO';
  base: string;
  percentual: string;
  valor: string;
}

export interface PendenciaDoFechamento {
  id: string;
  /** `FALTA`, `DEFEITO`, `DEVOLUCAO_NAO_ACEITA` ou `CREDITO_SEM_SALDO_NAO_ACEITO`. */
  tipo: string;
  produto: PecaDaMaleta | null;
  quantidade: number | null;
  valor: string;
  status: string;
}

export interface RecebimentoDoFechamento {
  id: string;
  forma: string;
  valor: string;
  /** `ACERTO` (pagou o acerto) ou `CONTAS` (pagou conta antiga). */
  aplicadoEm: string;
  em: string | null;
  estornado: boolean;
}

/** O que o fechamento gravou (acerto acertado). A cotação é a gravada no fechamento: Dólar e Guarani só para mostrar. */
export interface FechamentoDoAcerto {
  fechadoEm: string | null;
  cotacao: CotacaoDaVenda | null;
  metaGuarani: number | null;
  percentualComissao: string | null;
  vendidoParaMetaGuarani: number | null;
  vendidoBruto: string;
  descontos: string;
  devolvido: string;
  vendidoLiquido: string;
  comissao: string;
  faltasCobradas: string;
  liquido: string;
  recebidoNoAcerto: string;
  ficouDevendo: string;
  pagoAoVendedor: string | null;
  creditoUsado: string | null;
  itens: ItemDoFechamento[];
  comissoes: ComissaoDoFechamento[];
  pendencias: PendenciaDoFechamento[];
  recebimentos: RecebimentoDoFechamento[];
  contas: ContaDaLoja[];
}

export interface FichaDoAcerto extends ResumoDoAcerto {
  fechamento: FechamentoDoAcerto | null;
}

/** O acerto como o servidor o vê agora (tarefa 8.10; só com internet): as maletas e a contagem por peça e estado. */
export interface FichaDoAcertoAgora {
  id: string;
  semanaNumero: number;
  status: SituacaoDoAcerto;
  pedidoPeloVendedor: boolean;
  maletas: { id: string; numero: string; tipo: string; status: string }[];
  contagem: {
    produto: { id: string; nome: string; codigo: string | null; fotoHash: string | null };
    estadoPeca: 'BOA' | 'DEFEITO';
    quantidade: number;
  }[];
}

/** A prévia da comissão da semana: o que o servidor calcula (precisa de internet). Nada é gravado. */
export interface PreviaDaComissao {
  vendidoParaMetaGuarani: number;
  metaGuarani: number;
  percentualQueValeriaHoje: string;
  faltaParaMetaGuarani: number;
  faltaParaMetaReais: string;
  comissaoPrevista: string;
  cotacaoData: string | null;
}

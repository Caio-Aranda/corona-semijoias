/**
 * Formato das respostas da API que a tela usa (o contrato do vendedor: sem custo, sem estoque da loja, só o que é
 * dele). Valores em reais chegam como texto com ponto ("50.00"). A tela confere o formato antes de usar.
 */

export interface PecaDaMaleta {
  id: string;
  nome: string;
  codigo: string | null;
  fotoHash: string | null;
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
  tipo: 'PAGAMENTO' | 'REEMBOLSO';
  forma: string;
  valor: string;
  em: string;
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
  aReceber: string;
  vendidaEm: string;
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

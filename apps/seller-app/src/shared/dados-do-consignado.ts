/**
 * O que o computador devolve à tela sobre o consignado ao cliente (tarefa 9.2; §23.4 a §23.8). Dinheiro sempre texto com
 * duas casas, em Real ("1617.00"); Dólar e Guarani a tela mostra pela cotação do dia. Nada de custo, nada de outro
 * representante, nunca o token.
 */
import type { FormaDoConsignado } from './operacoes-do-consignado';

export type SituacaoDoPedido = 'EM_ABERTO' | 'PENDENTE' | 'FECHADO' | 'REMARCADO';

export interface LinhaDoPedido {
  notaId: string;
  /** `0000064-21`. */
  numero: string;
  clienteId: string;
  cliente: string;
  dataCobranca: string | null;
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  /** Só depois do acerto. */
  comissaoPercentual: string | null;
  comissao: string;
  valorLiquido: string;
  situacao: SituacaoDoPedido;
  acertoId: string | null;
}

export interface TotaisDaSemana {
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  comissao: string;
  liquido: string;
  recebidoPorForma: Record<FormaDoConsignado, string>;
  recebido: string;
  pendente: string;
}

export interface SemanaDaLista {
  id: string;
  numero: number;
  atual: boolean;
}

export interface PainelDaSemana {
  representante: string | null;
  /** A praça do representante (o nome que veio com as maletas), se já chegou. */
  praca: string | null;
  /** Ainda não existe semana neste computador: a tela pede o número para abrir a primeira. */
  semSemana: boolean;
  semanas: SemanaDaLista[];
  semana: {
    id: string;
    numero: number;
    atual: boolean;
    situacao: 'EM_ANDAMENTO' | 'FECHADA';
  } | null;
  pedidos: LinhaDoPedido[];
  totais: TotaisDaSemana;
  /** Novo Pedido só na semana atual. */
  podeNovoPedido: boolean;
}

export interface ClienteDaLista {
  id: string;
  nome: string;
  telefone: string | null;
  cidade: string | null;
  /** O que ele deve agora. */
  pendencia: string;
  fotoHash: string | null;
}

export interface DebitoDoCliente {
  acertoId: string;
  notaId: string;
  numeroDaNota: string;
  acertadoEm: string;
  /** O valor líquido da nota (o que ele devia). */
  valor: string;
  /** O que ainda deve desta nota. */
  saldo: string;
}

export interface PedidoDoHistorico {
  notaId: string;
  numero: string;
  semana: number;
  dataCobranca: string | null;
  valorDx: string;
  valorVd: string;
  valorLiquido: string;
  situacao: SituacaoDoPedido;
}

export interface PagamentoDoHistorico {
  reciboId: string;
  numero: string;
  pagoEm: string | null;
  total: string;
  formas: { forma: FormaDoConsignado; valor: string }[];
  /** Só o pagamento lançado no cadastro se estorna sozinho; o do acerto, com o acerto. */
  podeEstornar: boolean;
  estornado: boolean;
  doAcerto: boolean;
}

export interface ClienteCompleto {
  id: string;
  nome: string;
  telefone: string | null;
  whatsapp: string | null;
  documento: string | null;
  documentoTipo: string | null;
  dataNascimento: string | null;
  enderecoLogradouro: string | null;
  enderecoNumero: string | null;
  enderecoComplemento: string | null;
  enderecoBairro: string | null;
  enderecoCidade: string | null;
  enderecoUf: string | null;
  enderecoCep: string | null;
  indicadoPorClienteId: string | null;
  indicadoPorNome: string | null;
  comoConheceu: string | null;
  comissaoPercentual: string;
  observacoes: string | null;
  fotoHash: string | null;
  fotoDocumentoFrenteHash: string | null;
  fotoDocumentoVersoHash: string | null;
  /** Há foto deste cliente esperando para subir (sem internet): a falta de envio nunca trava nada. */
  fotosEsperandoEnvio: number;
  pendencia: string;
  debitos: DebitoDoCliente[];
  maletasAtuais: { remessaId: string; maleta: string; pecas: number }[];
  pedidos: PedidoDoHistorico[];
  pagamentos: PagamentoDoHistorico[];
}

export interface LinhaDaNota {
  produtoId: string;
  remessaId: string;
  maleta: string;
  /** O código que aparece na tela (o do fornecedor; senão, o principal). */
  codigo: string | null;
  /** Todos os códigos da peça, em minúsculas, para o código lido bater. */
  codigos: string[];
  nome: string;
  fotoHash: string | null;
  dx: number;
  dv: number;
  vd: number;
  precoUnitario: string;
  subTotal: string;
}

export interface ReciboDaTela {
  id: string;
  numero: string;
  pagoEm: string | null;
  total: string;
  formas: { forma: FormaDoConsignado; valor: string }[];
}

export interface AcertoDaTela {
  id: string;
  acertadoEm: string | null;
  comissaoPercentual: string;
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  vendaPercentual: string;
  comissao: string;
  bruto: string;
  pendenciasAnteriores: string;
  totalAReceber: string;
  totalPago: string;
  ficouDevendo: string;
  recibo: ReciboDaTela | null;
}

export interface DetalheDaNota {
  id: string;
  numero: string;
  clienteId: string;
  cliente: string;
  semanaNumero: number;
  /** A nota é da semana em andamento (Reposição entra nela; senão nasce nota nova). */
  daSemanaAtual: boolean;
  saidaEm: string | null;
  dataCobranca: string | null;
  situacao: SituacaoDoPedido;
  /** Sem acerto valendo: ainda dá para acertar, repor, trocar e remarcar. */
  aceitaMudanca: boolean;
  linhas: LinhaDaNota[];
  itensDx: number;
  valorDx: string;
  /** A Comissão % que vem do cadastro do cliente. */
  comissaoDoCliente: string;
  /** Os débitos de antes (entram sozinhos no acerto e não se removem). */
  pendenciasAnteriores: DebitoDoCliente[];
  acerto: AcertoDaTela | null;
  /** O que ainda deve desta nota (se acertada). */
  saldoDaNota: string;
}

export interface PrevisaoDoAcerto {
  linhas: {
    produtoId: string;
    remessaId: string;
    dx: number;
    dv: number;
    vd: number;
    precoUnitario: string;
  }[];
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  vendaPercentual: string;
  comissaoPercentual: string;
  comissao: string;
  bruto: string;
  pendenciasAnteriores: string;
  totalAReceber: string;
  totalPago: string;
  ficouDevendo: string;
  debitos: { acertoId: string; numeroDaNota: string; acertadoEm: string; saldo: string }[];
  /** A cotação do dia do acerto (a Dt. Acerto escolhida; hoje se vazia); `null` = "Sem cotação" (só o Real). */
  cotacao?: { data: string; dolar: string; guarani: string } | null;
}

export interface PecaDaMaletaDoConsignado {
  produtoId: string;
  nome: string;
  codigo: string | null;
  fotoHash: string | null;
  precoUnitario: string;
  enviada: number;
  comClientes: number;
  vendida: number;
  devolvidaALoja: number;
  disponivel: number;
}

export interface MaletaDoConsignado {
  id: string;
  /** O código curto que a tela mostra e a pesquisa aceita. */
  codigo: string;
  enviadaEm: string | null;
  status: string;
  pecas: PecaDaMaletaDoConsignado[];
  totalEnviada: number;
  totalComClientes: number;
  totalVendida: number;
  totalDisponivel: number;
}

export type ResultadoDaPeca =
  | {
      tipo: 'ACHADA';
      produtoId: string;
      remessaId: string;
      maleta: string;
      nome: string;
      codigo: string | null;
      fotoHash: string | null;
      precoUnitario: string;
      disponivel: number;
    }
  | { tipo: 'EM_OUTRA_MALETA'; maleta: string }
  | { tipo: 'FORA_DA_MALETA' };

export interface ItemDoPedido {
  id: string;
  produtoId: string;
  remessaId: string;
  maleta: string;
  codigo: string | null;
  nome: string;
  fotoHash: string | null;
  quantidade: number;
  precoUnitario: string;
  subTotal: string;
}

export interface PedidoEmMontagem {
  id: string;
  clienteId: string | null;
  cliente: string | null;
  /** Se o cliente deve, a tela avisa: "Este cliente deve R$ X. O valor será somado ao próximo acerto." */
  pendenciaDoCliente: string;
  remessaId: string | null;
  maletaEscolhida: string | null;
  dataCobranca: string;
  itens: ItemDoPedido[];
  totalDeItens: number;
  totalDx: string;
  /** As maletas para escolher. */
  maletas: { id: string; codigo: string }[];
}

export type ResultadoDoBipeNaTela =
  | { resultado: 'ADICIONADA'; pedido: PedidoEmMontagem }
  | { resultado: 'FORA_DA_MALETA' }
  | { resultado: 'EM_OUTRA_MALETA'; maleta: string }
  | { resultado: 'SEM_SALDO'; disponivel: number };

export interface LinhaDeNotaNaBusca {
  notaId: string;
  numero: string;
  cliente: string;
  clienteId: string;
  dataCobranca: string | null;
  valorDx: string;
  situacao: SituacaoDoPedido;
  /** As maletas em que a nota tem peças. */
  maletas: string[];
}

export interface ResultadoDaPesquisa {
  notas: LinhaDeNotaNaBusca[];
  clientes: ClienteDaLista[];
  maletas: { id: string; codigo: string }[];
}

export interface FotoDoClientePronta {
  dataUrl: string | null;
}

/**
 * Os relatórios do consignado ao cliente no Corona Vendas (tarefa 9.3; ESPECIFICACAO §23.11). O computador monta cada
 * relatório do banco local, sem internet, com as mesmas contas de `@corona/shared/consignado`; a tela só desenha, imprime,
 * salva em PDF e prepara o envio pelo WhatsApp. Dinheiro sempre texto com ponto: Real com duas casas ("1617.00"), Dólar com
 * duas casas e Guarani inteiro, cada lançamento pela cotação do seu dia (ADR-0006); sem cotação, só o Real.
 */
import type { FormaDoConsignado } from './operacoes-do-consignado';

export const TIPOS_DE_RELATORIO = [
  'NOTA_DE_SAIDA',
  'ACERTO',
  'SITUACAO_DO_CLIENTE',
  'PECAS_VENDIDAS',
  'TOTAIS_DA_SEMANA',
  'SITUACAO_DA_MALETA',
] as const;
export type TipoDeRelatorio = (typeof TIPOS_DE_RELATORIO)[number];

/** O que a tela pede: só ids e datas que a pessoa escolheu. */
export type PedidoDeRelatorio =
  | { tipo: 'NOTA_DE_SAIDA'; notaId: string }
  | { tipo: 'ACERTO'; notaId: string }
  | { tipo: 'SITUACAO_DO_CLIENTE'; clienteId: string }
  | { tipo: 'PECAS_VENDIDAS'; clienteId: string; de: string; ate: string }
  | { tipo: 'TOTAIS_DA_SEMANA'; semanaId?: string }
  | { tipo: 'SITUACAO_DA_MALETA'; remessaId: string };

/**
 * Um valor em Real e, quando todos os lançamentos que o formam tinham cotação, em Dólar (centavos, "297.90") e Guarani
 * (inteiro, "8123456"). `dolar` e `guarani` vazios = "Sem cotação".
 */
export interface ValorNasTresMoedas {
  real: string;
  dolar: string | null;
  guarani: string | null;
}

export interface CabecalhoDoRelatorio {
  representante: string | null;
  praca: string | null;
  /** O instante em que o relatório foi montado (ISO, UTC). */
  geradoEm: string;
}

export interface ClienteDoRelatorio {
  nome: string;
  documento: string | null;
  telefone: string | null;
  endereco: string | null;
}

/** Quantidades e valores no padrão DX, DV e VD (§23.2). */
export interface ParteDxDvVd {
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: ValorNasTresMoedas;
  valorDv: ValorNasTresMoedas;
  valorVd: ValorNasTresMoedas;
}

export interface LinhaDaNotaDeSaida {
  codigo: string;
  produto: string;
  maleta: string;
  quantidade: number;
  preco: string;
  subTotal: string;
}

export interface PendenciaNoRelatorio {
  numeroDaNota: string;
  data: string;
  saldo: string;
}

export interface NotaDeSaida {
  tipo: 'NOTA_DE_SAIDA';
  cabecalho: CabecalhoDoRelatorio;
  numero: string;
  semana: number;
  saidaEm: string | null;
  dataCobranca: string | null;
  cliente: ClienteDoRelatorio;
  maletas: string[];
  linhas: LinhaDaNotaDeSaida[];
  itensDx: number;
  totalDx: ValorNasTresMoedas;
  pendenciaAnterior: ValorNasTresMoedas;
  pendencias: PendenciaNoRelatorio[];
}

export interface LinhaVendida {
  codigo: string;
  produto: string;
  maleta: string;
  quantidade: number;
  preco: string;
  valor: string;
}

export interface FormaNoRelatorio {
  forma: FormaDoConsignado;
  valor: ValorNasTresMoedas;
}

export interface RelatorioDoAcerto extends ParteDxDvVd {
  tipo: 'ACERTO';
  cabecalho: CabecalhoDoRelatorio;
  numero: string;
  cliente: ClienteDoRelatorio;
  saidaEm: string | null;
  acertadoEm: string | null;
  vendidas: LinhaVendida[];
  vendaPercentual: string;
  comissaoPercentual: string;
  comissao: ValorNasTresMoedas;
  valorLiquido: ValorNasTresMoedas;
  totalPendenciasAnteriores: ValorNasTresMoedas;
  totalAReceber: ValorNasTresMoedas;
  recibo: string | null;
  pago: FormaNoRelatorio[];
  totalPago: ValorNasTresMoedas;
  ficouDevendo: ValorNasTresMoedas;
}

export interface NotaDaSituacaoDoCliente {
  numero: string;
  situacao: string;
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  comissao: string;
  valorLiquido: string;
  pago: string;
}

export interface PecaComOCliente {
  nota: string;
  maleta: string;
  codigo: string;
  produto: string;
  quantidade: number;
  dataCobranca: string | null;
}

/** Nota já acertada que o cliente ainda não quitou: aparece em "O que falta", com quanto ainda deve. */
export interface NotaPendenteDaSituacao {
  numero: string;
  situacao: string;
  valorDx: string;
  valorVd: string;
  ficouDevendo: ValorNasTresMoedas;
}

export interface SituacaoDoCliente extends ParteDxDvVd {
  tipo: 'SITUACAO_DO_CLIENTE';
  cabecalho: CabecalhoDoRelatorio;
  cliente: ClienteDoRelatorio;
  /** Só as notas Fechadas (§23.11). */
  jaVendeu: NotaDaSituacaoDoCliente[];
  pendentes: NotaPendenteDaSituacao[];
  comissao: ValorNasTresMoedas;
  valorLiquido: ValorNasTresMoedas;
  pago: ValorNasTresMoedas;
  comOCliente: PecaComOCliente[];
  itensComOCliente: number;
  valorComOCliente: ValorNasTresMoedas;
  deve: ValorNasTresMoedas;
}

export interface LinhaDePecaVendida {
  data: string;
  nota: string;
  codigo: string;
  produto: string;
  maleta: string;
  quantidade: number;
  valor: string;
}

export interface PecasVendidasPorCliente {
  tipo: 'PECAS_VENDIDAS';
  cabecalho: CabecalhoDoRelatorio;
  cliente: ClienteDoRelatorio;
  de: string;
  ate: string;
  linhas: LinhaDePecaVendida[];
  itensVd: number;
  valorVd: ValorNasTresMoedas;
}

export interface TotaisDaSemanaNoRelatorio extends ParteDxDvVd {
  tipo: 'TOTAIS_DA_SEMANA';
  cabecalho: CabecalhoDoRelatorio;
  semana: number;
  situacaoDaSemana: 'EM_ANDAMENTO' | 'FECHADA';
  comissao: ValorNasTresMoedas;
  valorLiquido: ValorNasTresMoedas;
  recebido: ValorNasTresMoedas;
  recebidoPorForma: FormaNoRelatorio[];
  pendente: ValorNasTresMoedas;
  pedidos: number;
}

export interface PecaDaSituacaoDaMaleta {
  codigo: string;
  produto: string;
  enviada: number;
  naMaleta: number;
  comClientes: number;
  vendida: number;
  devolvidaALoja: number;
  /** Quem está com a peça: "Maria (nota 0000064-21): 2". */
  comQuem: string[];
}

export interface SituacaoDaMaleta extends ParteDxDvVd {
  tipo: 'SITUACAO_DA_MALETA';
  cabecalho: CabecalhoDoRelatorio;
  maleta: string;
  status: string;
  enviadaEm: string | null;
  pecas: PecaDaSituacaoDaMaleta[];
  totalEnviada: number;
  totalNaMaleta: number;
  totalComClientes: number;
  totalVendida: number;
  valorNaMaleta: ValorNasTresMoedas;
  valorComClientes: ValorNasTresMoedas;
}

export type RelatorioDoConsignado =
  | NotaDeSaida
  | RelatorioDoAcerto
  | SituacaoDoCliente
  | PecasVendidasPorCliente
  | TotaisDaSemanaNoRelatorio
  | SituacaoDaMaleta;

/** O que a ponte devolve ao imprimir, salvar em PDF ou preparar o WhatsApp. */
export type ResultadoDeSaidaDoRelatorio =
  | { ok: true; arquivo?: string; pasta?: string }
  | {
      ok: false;
      motivo: 'cancelado' | 'sem-telefone' | 'sem-internet' | 'erro';
      /** Com `sem-internet` o PDF já ficou guardado aqui. */
      arquivo?: string;
      pasta?: string;
    };

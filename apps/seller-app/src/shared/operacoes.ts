/**
 * O que a tela pode pedir ao processo principal (a API fica atrás da ponte; o token nunca chega à tela).
 * Cada pedido tem o nome da operação e só os dados que a pessoa escolheu: nunca preço, total nem vendedor
 * (o servidor calcula e sabe quem é pelo login). Corona Vendas, ADR-0011.
 */

export interface ContagemDaPeca {
  produtoId: string;
  contada: number;
}

export interface LinhaDaVendaParaEnviar {
  produtoId: string;
  quantidade: number;
  /** Desconto da linha em reais, texto com ponto ("5.00"). */
  desconto?: string;
}

export const FORMAS_DE_PAGAMENTO = ['DINHEIRO', 'PIX', 'CARTAO', 'CHEQUE'] as const;
export type FormaDePagamento = (typeof FORMAS_DE_PAGAMENTO)[number];

export interface PagamentoParaEnviar {
  id: string;
  /** Valor em reais, texto com ponto ("50.00"). */
  valor: string;
  forma: FormaDePagamento;
}

export interface VendaParaEnviar {
  id: string;
  remessaId: string;
  clienteId: string;
  itens: LinhaDaVendaParaEnviar[];
  pagamentos: PagamentoParaEnviar[];
  /** O total que a tela mostrou, só para o servidor avisar se houver diferença. */
  totalConferido?: string;
}

export interface ClienteParaCadastrar {
  id: string;
  nome: string;
  cpf?: string;
  telefone?: string;
  whatsapp?: string;
  dataNascimento?: string;
  observacoes?: string;
}

/** Os campos do cadastro que o vendedor muda nos clientes que ele atende (os mesmos do envio `CLIENTE_ALTERADO`). */
export const CAMPOS_DO_CLIENTE_EDITAVEIS = [
  'nome',
  'cpf',
  'telefone',
  'whatsapp',
  'observacoes',
  'dataNascimento',
] as const;
export type CampoDoClienteEditavel = (typeof CAMPOS_DO_CLIENTE_EDITAVEIS)[number];

export const AGRUPAMENTOS_DE_VENDAS = ['cliente', 'peca', 'dia'] as const;
export type AgrupamentoDeVendas = (typeof AGRUPAMENTOS_DE_VENDAS)[number];

export const TAMANHOS_DE_FOTO = ['miniatura', 'media', 'grande'] as const;
export type TamanhoDeFoto = (typeof TAMANHOS_DE_FOTO)[number];

export const SITUACOES_DA_VENDA = ['CONCLUIDA', 'ESTORNADA'] as const;

export type PedidoDeChamada =
  | { operacao: 'eu' }
  | { operacao: 'maletas.listar' }
  | { operacao: 'maletas.ver'; id: string }
  | { operacao: 'maletas.peca'; id: string; codigo: string }
  /** "A etiqueta não lê? Procure pelo nome": só entre as peças da própria maleta. */
  | { operacao: 'maletas.buscarPeca'; id: string; texto: string }
  | { operacao: 'maletas.conferir'; id: string; conferenciaId: string; itens: ContagemDaPeca[] }
  | { operacao: 'maletas.verConferencia'; id: string }
  | { operacao: 'vendas.vender'; venda: VendaParaEnviar }
  | {
      operacao: 'vendas.listar';
      de?: string;
      ate?: string;
      clienteId?: string;
      status?: 'CONCLUIDA' | 'ESTORNADA';
    }
  | { operacao: 'vendas.ver'; id: string }
  | { operacao: 'vendas.estornar'; id: string; estornoId: string; motivo: string }
  | { operacao: 'clientes.listar'; busca?: string }
  | { operacao: 'clientes.ver'; id: string }
  | { operacao: 'clientes.cadastrar'; cliente: ClienteParaCadastrar }
  /** Muda o cadastro de um cliente que o vendedor atende. Texto vazio apaga o campo. */
  | {
      operacao: 'clientes.alterar';
      id: string;
      campos: Partial<Record<CampoDoClienteEditavel, string>>;
    }
  | { operacao: 'envios.naoAceitos' }
  /** "Entendi": a pessoa leu o motivo de um envio que não foi aceito. */
  | { operacao: 'envios.entendi'; id: string }
  | { operacao: 'cotacao.hoje' }
  | { operacao: 'fotos.ver'; hash: string; tamanho: TamanhoDeFoto }
  | { operacao: 'relatorios.vendas'; de?: string; ate?: string; agrupar?: AgrupamentoDeVendas }
  | { operacao: 'relatorios.recebimentos'; de?: string; ate?: string }
  | { operacao: 'relatorios.aReceber' };

export type OperacaoDaApi = PedidoDeChamada['operacao'];

export const OPERACOES_DA_API: readonly OperacaoDaApi[] = [
  'eu',
  'maletas.listar',
  'maletas.ver',
  'maletas.peca',
  'maletas.buscarPeca',
  'maletas.conferir',
  'maletas.verConferencia',
  'vendas.vender',
  'vendas.listar',
  'vendas.ver',
  'vendas.estornar',
  'clientes.listar',
  'clientes.ver',
  'clientes.cadastrar',
  'clientes.alterar',
  'envios.naoAceitos',
  'envios.entendi',
  'cotacao.hoje',
  'fotos.ver',
  'relatorios.vendas',
  'relatorios.recebimentos',
  'relatorios.aReceber',
];

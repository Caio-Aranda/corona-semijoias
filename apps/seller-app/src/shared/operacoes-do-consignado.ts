/**
 * O que a tela pode pedir sobre o consignado ao cliente (tarefa 9.2; ESPECIFICACAO §23; ADR-0016). Só o que a pessoa escolheu:
 * ids, quantidades, formas e valores que ela digitou. O computador faz a prévia das contas (as mesmas fórmulas do servidor) e o
 * servidor recalcula tudo; a tela nunca manda total, preço nem representante.
 */
import type { PedidoDeRelatorio } from './relatorios-do-consignado';

/** As quatro formas da §23.8 (sem cheque, sem boleto, sem crédito parcelado). */
export const FORMAS_DO_CONSIGNADO = ['DINHEIRO', 'PIX', 'DEBITO', 'CREDITO'] as const;
export type FormaDoConsignado = (typeof FORMAS_DO_CONSIGNADO)[number];

export interface FormaPaga {
  forma: FormaDoConsignado;
  /** Em reais, texto com ponto ("50.00"). */
  valor: string;
}

export interface PecaDevolvida {
  produtoId: string;
  remessaId: string;
  quantidade: number;
}

export interface PreviaDoAcertoPedida {
  notaId: string;
  devolvidos: PecaDevolvida[];
  /** De 0 a 40, texto com ponto ("30" ou "7.5"). */
  comissaoPercentual: string;
  formas: FormaPaga[];
  /** Dt. Acerto (AAAA-MM-DD, em Brasília): hoje ou um dia que já passou, nunca antes da saída da nota; sem ela vale hoje. */
  data?: string;
}

export interface AcertoParaEnviar extends PreviaDoAcertoPedida {
  /** O id do acerto (do computador; é a chave de idempotência). */
  id: string;
  /** O id do recibo (só usado se houve pagamento). */
  reciboId: string;
}

export interface LinhaParaReposicao {
  produtoId: string;
  remessaId: string;
  quantidade: number;
}

export interface ReposicaoParaEnviar {
  id: string;
  notaId: string;
  itens: LinhaParaReposicao[];
}

export interface TrocaNaNotaParaEnviar {
  id: string;
  notaId: string;
  volta: { produtoId: string; remessaId: string };
  entra: { produtoId: string; remessaId: string };
}

export interface PagamentoDoConsignadoParaEnviar {
  id: string;
  clienteId: string;
  formas: FormaPaga[];
  /** Dia do pagamento (AAAA-MM-DD, em Brasília), nunca depois de hoje; sem ele vale hoje. */
  data?: string;
}

export const TIPOS_DE_DOCUMENTO = ['CPF', 'RG', 'CI', 'OUTRO'] as const;
export type TipoDeDocumento = (typeof TIPOS_DE_DOCUMENTO)[number];

export interface ClienteDoConsignadoParaCadastrar {
  id: string;
  nome: string;
  enderecoLogradouro: string;
  enderecoNumero: string;
  enderecoComplemento?: string;
  enderecoBairro: string;
  enderecoCidade: string;
  enderecoUf: string;
  enderecoCep?: string;
  telefone?: string;
  whatsapp?: string;
  documento?: string;
  documentoTipo?: TipoDeDocumento;
  dataNascimento?: string;
  /** Um cliente já cadastrado OU um nome digitado (no máximo um dos dois). */
  indicadoPorClienteId?: string;
  indicadoPorNome?: string;
  comoConheceu?: string;
  /** De 0 a 40; sem ele vale 30. */
  comissaoPercentual?: string;
  observacoes?: string;
  /** O SHA-256 das três fotos já guardadas pelo computador (`consignado.fotoGuardar`). */
  fotoHash: string;
  fotoDocumentoFrenteHash: string;
  fotoDocumentoVersoHash: string;
}

/** Os campos do cadastro que o representante muda depois (formato 2 do `CLIENTE_ALTERADO`). */
export const CAMPOS_DO_CLIENTE_DO_CONSIGNADO = [
  'nome',
  'documento',
  'documentoTipo',
  'dataNascimento',
  'telefone',
  'whatsapp',
  'enderecoLogradouro',
  'enderecoNumero',
  'enderecoComplemento',
  'enderecoBairro',
  'enderecoCidade',
  'enderecoUf',
  'enderecoCep',
  'indicadoPorNome',
  'comoConheceu',
  'comissaoPercentual',
  'observacoes',
] as const;
export type CampoDoClienteDoConsignado = (typeof CAMPOS_DO_CLIENTE_DO_CONSIGNADO)[number];

export const TIPOS_DE_FOTO_DO_CLIENTE = ['cliente', 'documento-frente', 'documento-verso'] as const;
export type TipoDeFotoDoCliente = (typeof TIPOS_DE_FOTO_DO_CLIENTE)[number];

export type PedidoDoConsignado =
  /** A tela principal: semanas, a semana escolhida (a de agora se não vier), Pedidos e Totais. */
  | { operacao: 'consignado.painel'; semanaId?: string }
  | { operacao: 'consignado.semanaNova'; id: string; numero?: number }
  | { operacao: 'consignado.clientes'; busca?: string }
  | { operacao: 'consignado.cliente'; id: string }
  | { operacao: 'consignado.clienteCadastrar'; cliente: ClienteDoConsignadoParaCadastrar }
  | {
      operacao: 'consignado.clienteAlterar';
      id: string;
      campos: Partial<Record<CampoDoClienteDoConsignado, string>>;
    }
  /** A foto (JPEG em endereço de dados) é guardada criptografada; devolve o SHA-256. */
  | { operacao: 'consignado.fotoGuardar'; dataUrl: string }
  /** Troca a foto de um cliente que já existe. */
  | {
      operacao: 'consignado.clienteTrocarFoto';
      id: string;
      tipo: TipoDeFotoDoCliente;
      hash: string;
    }
  | { operacao: 'consignado.fotoVer'; hash: string }
  | { operacao: 'consignado.pedidoVer' }
  | {
      operacao: 'consignado.pedidoDefinir';
      clienteId?: string | null;
      remessaId?: string | null;
      dataCobranca?: string;
    }
  | { operacao: 'consignado.pedidoBipar'; codigo: string }
  | { operacao: 'consignado.pedidoQuantidade'; itemId: string; quantidade: number }
  | { operacao: 'consignado.pedidoFinalizar'; id: string }
  | { operacao: 'consignado.pedidoDescartar' }
  | { operacao: 'consignado.maletas' }
  /** Procura uma peça numa maleta (Reposição e Troca). */
  | { operacao: 'consignado.peca'; remessaId: string; codigo: string }
  /** Acha notas por nome do cliente, número da nota ou código da maleta. */
  | { operacao: 'consignado.notas'; texto?: string; soEmAberto?: boolean }
  | { operacao: 'consignado.nota'; id: string }
  | { operacao: 'consignado.acertoPrevia'; acerto: PreviaDoAcertoPedida }
  | { operacao: 'consignado.acertoSalvar'; acerto: AcertoParaEnviar }
  | { operacao: 'consignado.reposicao'; reposicao: ReposicaoParaEnviar }
  | { operacao: 'consignado.trocaNaNota'; troca: TrocaNaNotaParaEnviar }
  | { operacao: 'consignado.remarcar'; id: string; notaId: string; dataCobranca: string }
  | { operacao: 'consignado.pagamento'; pagamento: PagamentoDoConsignadoParaEnviar }
  | { operacao: 'consignado.estornarAcerto'; id: string; acertoId: string; motivo: string }
  | { operacao: 'consignado.estornarRecibo'; id: string; reciboId: string; motivo: string }
  | { operacao: 'consignado.pesquisar'; texto: string }
  /** Monta um relatório do banco local (tarefa 9.3; §23.11). */
  | { operacao: 'consignado.relatorio'; relatorio: PedidoDeRelatorio };

export const OPERACOES_DO_CONSIGNADO = [
  'consignado.painel',
  'consignado.semanaNova',
  'consignado.clientes',
  'consignado.cliente',
  'consignado.clienteCadastrar',
  'consignado.clienteAlterar',
  'consignado.fotoGuardar',
  'consignado.clienteTrocarFoto',
  'consignado.fotoVer',
  'consignado.pedidoVer',
  'consignado.pedidoDefinir',
  'consignado.pedidoBipar',
  'consignado.pedidoQuantidade',
  'consignado.pedidoFinalizar',
  'consignado.pedidoDescartar',
  'consignado.maletas',
  'consignado.peca',
  'consignado.notas',
  'consignado.nota',
  'consignado.acertoPrevia',
  'consignado.acertoSalvar',
  'consignado.reposicao',
  'consignado.trocaNaNota',
  'consignado.remarcar',
  'consignado.pagamento',
  'consignado.estornarAcerto',
  'consignado.estornarRecibo',
  'consignado.pesquisar',
  'consignado.relatorio',
] as const satisfies readonly PedidoDoConsignado['operacao'][];

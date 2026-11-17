import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from './idioma/es/relatorios-devolucao';
/**
 * Textos dos relatórios de devolução e troca (tarefa 6.10). Vêm do glossário da interface ("Relatórios de devolução e
 * troca"). A dona é tratada por "você". Na tela nunca aparecem os nomes técnicos das situações.
 */
export const PT_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO = {
  grupoDoMenu: 'Relatórios de devoluções',
  cotacao: 'Dólar e Guarani pela cotação do Banco Central do dia de cada devolução.',
  cotacaoDoCredito:
    'Dólar e Guarani pela cotação do Banco Central do dia em que cada crédito nasceu.',
  cotacaoDoDinheiro:
    'Dólar e Guarani pela cotação do Banco Central do dia em que o dinheiro voltou ao cliente.',
  cotacaoDoPedido: 'Dólar e Guarani pela cotação do Banco Central do dia de cada pedido.',
  cancelada: 'Cancelada',
  feita: 'Feita',
  acimaDoLimite: 'Acima do limite',
  devolucao: 'Devolução',
  devolucaoCancelada: 'Devolução cancelada',
  motivos: {
    ARREPENDIMENTO: 'Desistência',
    DEFEITO: 'Defeito',
    TAMANHO: 'Não serviu',
    OUTRO: 'Outro',
  } as Record<string, string>,
  tipos: { DEVOLUCAO: 'Devolução', TROCA: 'Troca' } as Record<string, string>,
  situacoes: {
    ACEITA: 'Feita',
    EM_REVISAO: 'Para revisão',
    MANTIDA: 'Mantida',
    NAO_ACEITA: 'Não aceita',
    CANCELADA: 'Cancelada',
  } as Record<string, string>,
  origens: {
    TROCA: 'Troca',
    DEVOLUCAO: 'Devolução',
    TRANSFERENCIA: 'Passado pela dona',
  } as Record<string, string>,
  situacoesDoCredito: {
    ABERTO: 'Em aberto',
    UTILIZADO: 'Usado',
    CANCELADO: 'Cancelado',
  } as Record<string, string>,
  formas: {
    DINHEIRO: 'Dinheiro',
    PIX: 'Transferência',
    CARTAO: 'Cartão',
    CHEQUE: 'Cheque',
  } as Record<string, string>,
  porQue: {
    FORA_DO_PRAZO: 'Passou do prazo da garantia',
    ACIMA_DO_VALOR: 'Passou do valor',
  } as Record<string, string>,
  situacoesDoPedido: {
    PEDIDA: 'Esperando você',
    APROVADA: 'Aprovado',
    USADA: 'Usado',
    NEGADA: 'Negado',
    VENCIDA: 'Vencido',
  } as Record<string, string>,
  conferencia: {
    menu: 'Conferência das devoluções e trocas',
    titulo: 'Conferência das devoluções e trocas',
    ajuda:
      'O sistema confere sozinho se as devoluções, as trocas e os créditos batem com a maleta e com o dinheiro. O normal é a lista de cada conferência ficar vazia.',
    conferir: 'Conferir agora',
    conferindo: 'Conferindo...',
    tudoCerto:
      'Tudo certo: as devoluções, as trocas e os créditos batem com a maleta e com o dinheiro.',
    temDiferenca: 'Há diferenças. Avise o suporte e não mexa nas vendas até resolver.',
    conferidos: (n: number) => `Conferidos: ${n}`,
    semDiferenca: 'Nada fora do lugar.',
    geradaEm: (quando: string) => `Conferido em ${quando}.`,
    erro: 'Não foi possível conferir agora. Tente de novo.',
    titulos: {
      maleta: 'Maleta com devoluções e trocas',
      'devolvido-por-peca': 'Devolvido por peça da venda',
      creditos: 'Créditos e usos',
      'dinheiro-devolvido': 'Dinheiro devolvido',
      'contas-da-venda': 'Contas da venda',
    } as Record<string, string>,
    explicacoes: {
      maleta:
        'Cada peça que voltou à maleta ou saiu na troca está registrada uma vez, e o estoque da loja mais o das maletas fecha.',
      'devolvido-por-peca': 'Em cada peça vendida, o que voltou nunca passa do que foi vendido.',
      creditos: 'Créditos gerados menos usos é igual ao que está em aberto.',
      'dinheiro-devolvido': 'O dinheiro que voltou ao cliente nunca passa do que ele pagou.',
      'contas-da-venda':
        'O que o cliente pagou, já descontado o que voltou, nunca passa do total da venda menos o devolvido.',
    } as Record<string, string>,
  },
} as const;

export const TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO = textosVivos('rel-devolucao', PT_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO, ES_TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO);

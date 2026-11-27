import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_CONCILIACOES } from './idioma/es/conciliacoes';
/**
 * Textos da tela "Conferência do dinheiro" (tarefa 7.8). O sistema confere sozinho se vendas, acertos, comissões, caixa, contas e
 * estoque batem. Linguagem da loja, sem termo técnico; a dona é tratada por "você". O que aparece aqui é erro do sistema, não da
 * loja, e nada se corrige à mão por esta tela.
 */
export const PT_TEXTOS_DE_CONCILIACOES = {
  grupoDoMenu: 'Conferência',
  menu: 'Conferência do dinheiro',
  titulo: 'Conferência do dinheiro',
  ajuda:
    'O sistema confere sozinho se as vendas, os acertos, as comissões, o caixa, as contas e o estoque batem entre si. O normal é cada conferência dizer "Tudo certo".',
  aviso:
    'O que aparece aqui é erro do sistema, não da loja. Não corrija nada à mão: avise o suporte.',
  conferir: 'Conferir agora',
  conferindo: 'Conferindo...',
  tudoCerto: 'Tudo certo: o dinheiro, as contas e o estoque batem.',
  temDiferenca:
    'Há diferenças. Avise o suporte e não mexa nas vendas, nos acertos nem no caixa até resolver.',
  conferidos: (n: number) => `Conferidos: ${n}`,
  semDiferenca: 'Tudo certo.',
  geradaEm: (quando: string) => `Conferido em ${quando}.`,
  diferenca: (devia: string, esta: string) => `Devia ser ${devia}; está ${esta}.`,
  titulos: {
    C1: 'Vendas e o que falta receber',
    C2: 'Acerto do representante',
    C3: 'Comissão dos representantes',
    C4: 'Caixa',
    C5: 'Contas a receber e a pagar',
    C7: 'Estoque da loja e maletas',
    C8: 'Maletas acertadas e faltas',
    C9: 'Acertos: cada venda, estorno e devolução entra uma vez',
    C10: 'Compras e parcelas a pagar',
    C11: 'Estoque completo, peça por peça',
    C12: 'Cada maleta enviada, peça por peça',
  } as Record<string, string>,
  explicacoes: {
    C1: 'Para cada venda: o valor, menos o que foi devolvido, é igual ao que o cliente já pagou mais o que ainda falta nas parcelas. Venda desfeita não tem parcela em aberto.',
    C2: 'Para cada acerto fechado: vendido, descontos, devolvido, comissão e faltas dão o valor líquido, e o que foi recebido, ficou devendo ou foi pago ao representante confere com os recebimentos, as contas e o caixa.',
    C3: 'A soma das comissões de cada representante é a soma das comissões dos acertos dele, e cada comissão é a porcentagem decidida no acerto sobre o valor da venda.',
    C4: 'Cada recebimento do representante, cada pagamento a fornecedor e cada pagamento ao representante no acerto tem exatamente um lançamento no caixa, do mesmo valor. Entrada soma e saída diminui o saldo.',
    C5: 'Em cada conta (do cliente, do representante e de fornecedor): o valor pago e o cancelado são a soma das baixas, nunca passam do valor da conta, e a situação combina com eles.',
    C7: 'Para cada peça: o estoque guardado é a soma dos lançamentos, e o estoque da loja mais as peças nas maletas abertas é tudo o que entrou menos tudo o que saiu.',
    C8: 'Toda maleta acertada fica com saldo zero, e cada falta registrada no acerto tem a perda da mesma quantidade na maleta.',
    C9: 'Nenhuma venda, estorno de venda ou devolução entra em mais de um acerto, e nenhuma de maleta já acertada fica de fora de todos os acertos.',
    C10: 'Para cada compra com contas a pagar, a soma das parcelas (mesmo as canceladas) é o total da compra.',
    C11: 'Para cada peça: o que está na loja mais o que está nas maletas é o que foi comprado e lançado, menos o que foi vendido, o que faltou ou estragou no acerto, mais o que o cliente devolveu à maleta e o que chegou à loja pelo acerto.',
    C12: 'Em toda maleta enviada, para cada peça: o que há na maleta é o que foi enviado, menos o que foi vendido, devolvido à dona e perdido, mais o que o cliente devolveu. Nenhuma peça fica negativa e a maleta cancelada não guarda peça.',
  } as Record<string, string>,
  referencias: {
    VENDA: 'Venda',
    ESTORNO_VENDA: 'Venda desfeita',
    DEVOLUCAO: 'Devolução',
    ACERTO: 'Acerto',
    VENDEDOR: 'Representante',
    COMISSAO: 'Comissão',
    RECEBIMENTO: 'Recebimento do representante',
    PAGAMENTO_A_FORNECEDOR: 'Pagamento a fornecedor',
    LANCAMENTO: 'Lançamento do caixa',
    CONTA_A_RECEBER: 'Conta a receber',
    CONTA_A_PAGAR: 'Conta a pagar',
    PRODUTO: 'Peça',
    MALETA: 'Maleta',
    PENDENCIA: 'Falta',
    PENDENCIA_DA_MALETA: 'Falta no fechamento da maleta',
    COMPRA: 'Compra',
  } as Record<string, string>,
  outraReferencia: 'Registro',
  pecas: (n: string) => `${n} ${n === '1' ? 'peça' : 'peças'}`,
  registros: (n: string) => `${n} ${n === '1' ? 'registro' : 'registros'}`,
  /** A conferência de hoje (tarefa 8.4): o sistema confere sozinho de madrugada e a dona pode pedir na hora. */
  diaria: {
    menu: 'Conferência de hoje',
    titulo: 'Conferência de hoje',
    ajuda:
      'Todo dia, de madrugada, o sistema confere sozinho o dinheiro, as contas, o estoque e as devoluções. Aqui você vê o resultado e pode conferir de novo quando quiser.',
    aviso:
      'Nada é corrigido sozinho. Se aparecer diferença, avise o suporte e não mexa nas vendas, nos acertos nem no caixa até resolver.',
    conferirAgora: 'Conferir agora',
    conferindo: 'Conferindo...',
    ultima: 'Última conferência',
    anteriores: 'Conferências anteriores',
    nenhuma: 'Ainda não houve nenhuma conferência. Toque em Conferir agora.',
    quando: (q: string) => `Em ${q}`,
    automatica: 'Feita sozinha pelo sistema',
    pedidaPor: (nome: string) => (nome ? `Pedida por ${nome}` : 'Pedida por você'),
    tudoCerto: 'Tudo certo',
    achouDiferencas: (n: number) => `Achou ${n} ${n === 1 ? 'diferença' : 'diferenças'}`,
    naoTerminou: (partes: string) => `Não terminou: ${partes}`,
    naoTerminouAjuda:
      'Uma parte da conferência não terminou. Toque em Conferir agora; se continuar, avise o suporte.',
    ver: 'Ver',
    verDaRodada: (quando: string) => `Ver a conferência de ${quando}`,
    fechar: 'Fechar',
    semDiferenca: 'Tudo certo.',
    carregando: 'Carregando a conferência...',
    detalhe: (quando: string) => `Conferência de ${quando}`,
    naoRodouNestaConferencia: 'Esta parte não rodou nesta conferência.',
  },
  titulosDaConferencia: {
    ESTOQUE: 'Estoque: saldo de cada peça e peças nas maletas',
    DEVOLUCAO_E_TROCA: 'Devoluções, trocas e créditos',
  } as Record<string, string>,
} as const;

export const TEXTOS_DE_CONCILIACOES = textosVivos(
  'conciliacoes',
  PT_TEXTOS_DE_CONCILIACOES,
  ES_TEXTOS_DE_CONCILIACOES,
);

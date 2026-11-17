import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_RELATORIOS_FINANCEIROS } from './idioma/es/relatorios-financeiros';
/**
 * Textos dos relatórios financeiros da dona (tarefa 7.12), do glossário da interface ("Relatórios financeiros"). A dona é
 * tratada por "você". Na tela nunca aparecem os nomes técnicos das situações, das origens nem das formas.
 */
export const PT_TEXTOS_DE_RELATORIOS_FINANCEIROS = {
  grupoDoMenu: 'Relatórios financeiros',
  resultadoNota:
    'A devolução abate o mês em que a venda foi feita; por isso o resultado de um mês pode mudar depois. O custo das peças é o da compra mais recente até o dia da venda; onde não há compra, vale o custo do cadastro (aparece avisado), que muda se o cadastro mudar. Perdas, cobrado e caixa são da loja: não se dividem por peça nem por vendedor.',
  lucroNota:
    'Receita líquida (vendas menos devoluções e trocas) menos o custo das peças vendidas. "Custo do cadastro" quer dizer que não havia compra registrada até a venda: é uma estimativa.',
  sinaisDaLinha: { SOMA: '(+) ', SUBTRAI: '(−) ', TOTAL: '= ', AVISO: '' } as Record<
    string,
    string
  >,
  cotacao:
    'Dólar e Guarani pela cotação do Banco Central do dia de cada lançamento. Tudo é gravado em Real.',
  cotacaoDoAcerto:
    'Dólar e Guarani pela cotação do Banco Central do dia em que cada acerto foi fechado. Tudo é gravado em Real.',
  situacoesDaConta: {
    ABERTA: 'Em aberto',
    PARCIAL: 'Pago em parte',
    PAGA: 'Paga',
    CANCELADA: 'Cancelada',
    VENCIDA: 'Vencida',
  } as Record<string, string>,
  quemDeve: {
    CLIENTE: 'Cliente',
    VENDEDOR: 'Vendedor',
    FORNECEDOR: 'Fornecedor',
  } as Record<string, string>,
  tiposDaPendencia: {
    FALTA: 'Peça que faltou',
    DEFEITO: 'Peça com defeito',
    DEVOLUCAO_NAO_ACEITA: 'Devolução não aceita',
    CREDITO_SEM_SALDO_NAO_ACEITO: 'Crédito sem saldo',
  } as Record<string, string>,
  situacoesDaPendencia: {
    ABERTA: 'Em aberto',
    COBRADA: 'Cobrada do vendedor',
    ASSUMIDA_PELA_LOJA: 'A loja assumiu',
    ENCONTRADA: 'Peça encontrada',
    APROVADA: 'Defeito aprovado',
    RECUSADA: 'Defeito recusado',
  } as Record<string, string>,
  formas: { DINHEIRO: 'Dinheiro', PIX: 'Transferência', BOLETO: 'Boleto' } as Record<
    string,
    string
  >,
  tiposDoCaixa: { ENTRADA: 'Entrada', SAIDA: 'Saída' } as Record<string, string>,
  origensDoCaixa: {
    RECEBIMENTO_VENDEDOR: 'Recebido do vendedor',
    ACERTO_A_FAVOR_DO_VENDEDOR: 'Pago ao vendedor no acerto',
    PAGAMENTO_FORNECEDOR: 'Pago a fornecedor',
    MANUAL: 'Lançado por você',
  } as Record<string, string>,
  momentos: { ACERTO: 'No acerto', AVULSO: 'Depois do acerto' } as Record<string, string>,
  estorno: 'Estorno',
  comissaoPaga: 'Paga no acerto',
} as const;

export const TEXTOS_DE_RELATORIOS_FINANCEIROS = textosVivos(
  'rel-financeiros',
  PT_TEXTOS_DE_RELATORIOS_FINANCEIROS,
  ES_TEXTOS_DE_RELATORIOS_FINANCEIROS,
);

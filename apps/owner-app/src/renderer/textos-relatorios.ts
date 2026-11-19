import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_RELATORIOS } from './idioma/es/relatorios';
/** Textos dos relatórios de cadastro (glossário, "Corona Gestão: relatórios de cadastro", tarefa 1.12). */
export const PT_TEXTOS_RELATORIOS = {
  loja: 'Corona Semijoias',
  grupoDoMenu: 'Relatórios de cadastros',
  grupoDoEstoque: 'Relatórios de estoque',
  grupoDasCompras: 'Relatórios de compras',
  rotuloDosFiltros: 'Filtros do relatório',
  buscando: 'Buscando o relatório...',
  filtros: {
    busca: 'Buscar',
    situacao: 'Situação',
    ativos: 'Só ativos',
    inativos: 'Só inativos',
    todos: 'Todos',
    categoria: 'Categoria',
    fornecedor: 'Fornecedor',
    praca: 'Praça',
    vendedor: 'Representante',
    restricao: 'Restrição',
    de: 'De',
    ate: 'Até',
    mes: 'Mês',
    usuario: 'Quem lançou',
    qualquer: 'Todas',
    qualquerUm: 'Todos',
    limpar: 'Limpar filtros',
    periodoInvertido: 'A data final não pode ser antes da data inicial.',
    meses: [
      'Janeiro',
      'Fevereiro',
      'Março',
      'Abril',
      'Maio',
      'Junho',
      'Julho',
      'Agosto',
      'Setembro',
      'Outubro',
      'Novembro',
      'Dezembro',
    ],
  },
  totais: {
    titulo: 'Totais',
    verRegistros: 'Ver os registros',
    voltarAoRelatorio: 'Ver o relatório inteiro',
    mostrando: (rotulo: string) => `Mostrando só: ${rotulo}.`,
    pecas: (n: number) => (n === 1 ? '1 peça' : `${n} peças`),
    calculando: 'Calculando...',
  },
  exportar: {
    titulo: 'Exportar o relatório inteiro',
    csv: 'Baixar em CSV',
    excel: 'Baixar em Excel',
    pdf: 'Baixar em PDF',
    preparando: 'Preparando o arquivo...',
    pronto: (nome: string) => `O arquivo ${nome} foi preparado. Escolha onde guardar.`,
    erro: 'Não foi possível preparar o arquivo agora. Tente de novo.',
    semLinhas: 'Não há nada para exportar com estes filtros.',
    muitasLinhas:
      'Este relatório tem linhas demais para exportar de uma vez. Use os filtros para reduzir e tente de novo.',
  },
  cotacao: {
    deHoje: 'Neste relatório, o Dólar e o Guarani seguem a cotação de hoje.',
  },
  situacaoDoProduto: { ativo: 'Ativo', inativo: 'Inativo' },
  tiposDeCodigo: {
    LOJA: 'Código da loja',
    FORNECEDOR: 'Código do fornecedor',
    EAN13: 'Código de barras',
  } as Record<string, string>,
  tiposDeAniversariante: { VENDEDOR: 'Representante', CLIENTE: 'Cliente' } as Record<
    string,
    string
  >,
  alteracoesSemImportacao:
    'Mostra o que foi cadastrado e alterado, e quem fez. O resultado das importações em massa está no relatório "Resultado das importações".',
  importacoesNota:
    'Mostra as importações feitas a partir de agora. Na planilha, o resultado é o que o aplicativo informou ao terminar; se a internet cair no meio, as últimas linhas podem não aparecer aqui.',
  /** Relatórios de estoque e de compras (tarefa 2.8). */
  estoqueECompras: {
    origens: {
      MANUAL: 'Lançamento feito à mão',
      AJUSTE_MANUAL: 'Ajuste do estoque',
      COMPRA: 'Compra',
      INVENTARIO: 'Contagem do estoque',
      MALETA: 'Maleta',
      ESTORNO: 'Desfazer lançamento',
    } as Record<string, string>,
    desfeito: 'Desfeito',
    desfazOutro: 'Desfaz outro lançamento',
    compraRegistrada: 'Registrada',
    compraDesfeita: 'Desfeita',
    situacoesDaContagem: {
      EM_ANDAMENTO: 'Em andamento',
      FECHADO: 'Fechada',
      CANCELADO: 'Cancelada',
    } as Record<string, string>,
    escopoDaContagem: (escopo: string, nome: string) =>
      escopo === 'CATEGORIA'
        ? `Categoria ${nome}`
        : escopo === 'FORNECEDOR'
          ? `Fornecedor ${nome}`
          : 'Todas as peças',
    acoes: {
      verAsPecas: 'Ver as peças',
      soEstaContagem: 'Ver só esta contagem',
      historicoDeCusto: 'Ver o histórico de custo',
      tituloDaCompra: (data: string, fornecedor: string) =>
        `Compra de ${data}${fornecedor ? ` — ${fornecedor}` : ''}`,
      tituloDaContagem: (inicio: string) => `Contagem de ${inicio}`,
      tituloDaPeca: (nome: string) => `Histórico de custo: ${nome}`,
      voltar: 'Voltar ao relatório',
      filtroFixo: (texto: string) => `Mostrando só: ${texto}.`,
      coluna: 'Abrir',
    },
    dias: { padrao: (n: number) => `Sem saída há ${n} dias` },
    fotoNaoVaiNoArquivo:
      'A foto aparece na tela; os arquivos PDF, Excel e CSV levam o nome e o código da peça.',
  },
};

export const TEXTOS_RELATORIOS = textosVivos(
  'relatorios',
  PT_TEXTOS_RELATORIOS,
  ES_TEXTOS_RELATORIOS,
);

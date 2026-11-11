/**
 * Os relatórios de estoque e de compras da dona (tarefa 2.8, §12): nome na tela, filtros, colunas, totais
 * e o que abre cada linha. A tela e a exportação usam esta mesma definição. Os dez relatórios vêm da API
 * da tarefa 2.5; aqui só se mostra o que ela devolve (dinheiro nunca é calculado no cliente).
 *
 * Termos da tela: glossário, partes 2 e 12 (nada de "movimentação", "inventário", "estorno", "saldo"...).
 */
import type { NomeDoRelatorio, NomeDoRelatorioDeEstoqueOuCompras } from '../../shared/relatorios';
import { TEXTOS_RELATORIOS } from '../textos-relatorios';
import { rotuloDoTipo } from './estoque';
import { dataParaTela } from './moedas';
import type { DefinicaoDeRelatorio } from './relatorios';

const T = TEXTOS_RELATORIOS.estoqueECompras;

const SITUACAO_DA_PECA = [
  { valor: '', rotulo: TEXTOS_RELATORIOS.filtros.ativos },
  { valor: 'INATIVOS', rotulo: TEXTOS_RELATORIOS.filtros.inativos },
  { valor: 'TODOS', rotulo: TEXTOS_RELATORIOS.filtros.todos },
];
/** Onde o servidor mostra todos os produtos por padrão. */
const SITUACAO_DA_PECA_TODAS = [
  { valor: '', rotulo: TEXTOS_RELATORIOS.filtros.todos },
  { valor: 'ATIVOS', rotulo: TEXTOS_RELATORIOS.filtros.ativos },
  { valor: 'INATIVOS', rotulo: TEXTOS_RELATORIOS.filtros.inativos },
];

const FOTO = { chave: 'fotoPrincipalHash', titulo: 'Foto', tipo: 'foto' } as const;

const dados = (l: Record<string, unknown>, campo: string) =>
  typeof l[campo] === 'string' ? l[campo] : '';

const naoDesfeita = (l: Record<string, unknown>) => l.estornada !== true;

function dataDaCompra(l: Record<string, unknown>): string {
  const d = dados(l, 'dataCompra');
  return d ? dataParaTela(d) : '';
}

const ABRIR_O_HISTORICO = {
  rotulo: T.acoes.historicoDeCusto,
  abre: 'compras-custos',
  filtro: 'produtoId',
  campo: 'produtoId',
  titulo: (l: Record<string, unknown>) => T.acoes.tituloDaPeca(dados(l, 'nome')),
} as const;

export const RELATORIOS_DE_ESTOQUE: Record<
  NomeDoRelatorioDeEstoqueOuCompras,
  DefinicaoDeRelatorio
> = {
  'estoque-posicao': {
    nome: 'estoque-posicao',
    grupo: 'estoque',
    titulo: 'Estoque agora, com valor a custo',
    explicacao:
      'Quantas peças há de cada produto e quanto elas custaram. O custo aparece só aqui, para a dona.',
    filtros: ['busca', 'situacao', 'categoria', 'fornecedor'],
    situacoes: SITUACAO_DA_PECA,
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'saldo', titulo: 'Quantidade', tipo: 'inteiro' },
      { chave: 'estoqueMinimo', titulo: 'Estoque mínimo', tipo: 'inteiro' },
      { chave: 'custoUnitario', titulo: 'Custo de cada peça', tipo: 'dinheiro' },
      { chave: 'valorACusto', titulo: 'Valor a custo', tipo: 'dinheiro' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: { 'valor-a-custo': 'valorACusto' },
    rotulosDosTotais: {
      todos: 'Produtos',
      'valor-a-custo': 'Peças no estoque e valor a custo',
      'com-saldo': 'Com peças no estoque',
      zerados: 'Sem nenhuma peça',
      'sem-custo': 'Com peças e sem custo informado',
    },
    acoes: [ABRIR_O_HISTORICO],
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Nenhum produto com estes filtros.',
  },
  'estoque-movimentos': {
    nome: 'estoque-movimentos',
    grupo: 'estoque',
    titulo: 'Entradas e saídas por período',
    explicacao:
      'Tudo o que aumentou ou diminuiu o estoque: entradas, saídas, ajustes e perdas, com quem lançou.',
    filtros: ['periodo', 'busca', 'situacao', 'categoria', 'fornecedor', 'usuario'],
    situacoes: SITUACAO_DA_PECA_TODAS,
    extras: [
      {
        chave: 'tipo',
        rotulo: 'Tipo de lançamento',
        tipo: 'escolha',
        qualquer: 'Todos',
        opcoes: [
          { valor: 'ENTRADA', rotulo: rotuloDoTipo('ENTRADA') },
          { valor: 'SAIDA', rotulo: rotuloDoTipo('SAIDA') },
          { valor: 'AJUSTE', rotulo: rotuloDoTipo('AJUSTE') },
          { valor: 'AVARIA', rotulo: rotuloDoTipo('AVARIA') },
          { valor: 'REMESSA', rotulo: rotuloDoTipo('REMESSA') },
          { valor: 'DEVOLUCAO', rotulo: rotuloDoTipo('DEVOLUCAO') },
        ],
      },
      {
        chave: 'origem',
        rotulo: 'De onde veio',
        tipo: 'escolha',
        qualquer: 'Qualquer lugar',
        opcoes: Object.entries(T.origens).map(([valor, rotulo]) => ({ valor, rotulo })),
      },
    ],
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      { chave: 'ocorridaEm', titulo: 'Quando', tipo: 'dataHora' },
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'Tipo', tipo: 'texto' },
      { chave: 'quantidade', titulo: 'Quantidade', tipo: 'sinal' },
      { chave: 'saldoDepois', titulo: 'Ficou com', tipo: 'inteiro' },
      { chave: 'origemNaTela', titulo: 'De onde veio', tipo: 'texto' },
      { chave: 'motivo', titulo: 'Motivo', tipo: 'texto' },
      { chave: 'usuario', titulo: 'Quem lançou', tipo: 'texto' },
      { chave: 'situacaoNaTela', titulo: 'Observação', tipo: 'texto' },
    ],
    totaisDeValor: {},
    rotulosDosTotais: {
      todos: 'Lançamentos',
      entradas: 'Peças que entraram',
      saidas: 'Peças que saíram',
      'tipo-entrada': 'Entradas',
      'tipo-saida': 'Saídas',
      'tipo-ajuste': 'Ajustes',
      'tipo-remessa': 'Peças para a maleta',
      'tipo-devolucao': 'Devoluções da maleta',
      'tipo-avaria': 'Perdas',
    },
    itens: { um: 'lançamento', varios: 'lançamentos' },
    vazio: 'Nenhum lançamento neste período.',
  },
  'estoque-minimo': {
    nome: 'estoque-minimo',
    grupo: 'estoque',
    titulo: 'Peças que estão acabando',
    explicacao:
      'As peças que chegaram ao estoque mínimo ou estão com menos, e quantas faltam para voltar ao mínimo.',
    filtros: ['busca', 'situacao', 'categoria', 'fornecedor'],
    situacoes: SITUACAO_DA_PECA,
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'saldo', titulo: 'Quantidade', tipo: 'inteiro' },
      { chave: 'minimo', titulo: 'Estoque mínimo', tipo: 'inteiro' },
      { chave: 'falta', titulo: 'Quanto falta', tipo: 'inteiro' },
    ],
    totaisDeValor: {},
    rotulosDosTotais: {
      todos: 'Peças que estão acabando',
      zerados: 'Sem nenhuma peça',
      falta: 'Peças que faltam para chegar ao mínimo',
    },
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Nenhuma peça está acabando.',
  },
  'estoque-parados': {
    nome: 'estoque-parados',
    grupo: 'estoque',
    titulo: 'Peças paradas',
    explicacao: 'As peças que estão no estoque e não saem há muitos dias.',
    filtros: ['busca', 'situacao', 'categoria', 'fornecedor'],
    situacoes: SITUACAO_DA_PECA,
    extras: [
      {
        chave: 'dias',
        rotulo: 'Sem sair há quantos dias',
        tipo: 'inteiro',
        padrao: 30,
      },
    ],
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'saldo', titulo: 'Quantidade', tipo: 'inteiro' },
      { chave: 'ultimaSaidaEm', titulo: 'Última saída', tipo: 'data' },
      { chave: 'diasSemSair', titulo: 'Dias sem sair', tipo: 'inteiro' },
      { chave: 'valorACusto', titulo: 'Valor a custo', tipo: 'dinheiro' },
    ],
    totaisDeValor: { 'valor-a-custo': 'valorACusto' },
    rotulosDosTotais: {
      todos: 'Peças paradas',
      'nunca-saiu': 'Nunca tiveram saída',
      'valor-a-custo': 'Peças paradas e valor a custo',
    },
    acoes: [ABRIR_O_HISTORICO],
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Nenhuma peça parada com estes filtros.',
  },
  'estoque-contagens': {
    nome: 'estoque-contagens',
    grupo: 'estoque',
    titulo: 'Contagens do estoque e diferenças',
    explicacao:
      'Cada contagem do estoque, peça por peça: o que o sistema esperava, o que foi contado e a diferença.',
    filtros: ['periodo', 'busca', 'situacao', 'categoria', 'fornecedor'],
    situacoes: [
      { valor: '', rotulo: 'Todas' },
      { valor: 'EM_ANDAMENTO', rotulo: 'Em andamento' },
      { valor: 'FECHADO', rotulo: 'Fechadas' },
      { valor: 'CANCELADO', rotulo: 'Canceladas' },
    ],
    extras: [
      {
        chave: 'divergencia',
        rotulo: 'Diferença',
        tipo: 'escolha',
        qualquer: 'Tanto faz',
        opcoes: [
          { valor: 'SIM', rotulo: 'Só com diferença' },
          { valor: 'NAO', rotulo: 'Só sem diferença' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      { chave: 'iniciadoEm', titulo: 'Começou em', tipo: 'dataHora' },
      { chave: 'contagemNaTela', titulo: 'Contagem', tipo: 'texto' },
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'esperado', titulo: 'Esperado', tipo: 'inteiro' },
      { chave: 'contado', titulo: 'Contado', tipo: 'inteiro' },
      { chave: 'diferenca', titulo: 'Diferença', tipo: 'sinal' },
      { chave: 'ajuste', titulo: 'Ajuste feito', tipo: 'sinal' },
    ],
    totaisDeValor: {},
    rotulosDosTotais: {
      todos: 'Peças contadas',
      'com-divergencia': 'Com diferença',
      'nao-lidos': 'Ainda não lidas',
      sobras: 'Peças a mais',
      faltas: 'Peças a menos',
    },
    acoes: [
      {
        rotulo: T.acoes.soEstaContagem,
        abre: 'estoque-contagens',
        filtro: 'inventarioId',
        campo: 'inventarioId',
        titulo: (l) => T.acoes.tituloDaContagem(dados(l, 'iniciadoEm')),
      },
    ],
    itens: { um: 'linha', varios: 'linhas' },
    vazio: 'Nenhuma contagem com estes filtros.',
  },
  'estoque-conferencia': {
    nome: 'estoque-conferencia',
    grupo: 'estoque',
    titulo: 'Conferência do estoque',
    explicacao:
      'Confere se a quantidade de cada peça bate com tudo o que entrou e saiu, e se as peças da loja mais as que estão nas maletas fecham a conta. Deve ficar vazio; se aparecer alguma peça, algo precisa ser conferido.',
    filtros: ['busca'],
    buscaRotulo: 'Buscar por nome da peça',
    colunas: [
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'saldoGuardado', titulo: 'Quantidade guardada', tipo: 'inteiro' },
      { chave: 'somaDosMovimentos', titulo: 'Entradas menos saídas', tipo: 'inteiro' },
      { chave: 'diferenca', titulo: 'Diferença', tipo: 'sinal' },
      { chave: 'nasMaletas', titulo: 'Com os vendedores (maletas)', tipo: 'inteiro' },
      { chave: 'diferencaComMaletas', titulo: 'Diferença contando as maletas', tipo: 'sinal' },
    ],
    totaisDeValor: {},
    rotulosDosTotais: { todos: 'Peças com diferença' },
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Tudo certo: a quantidade de todas as peças bate com o que entrou e saiu.',
  },
  'compras-por-compra': {
    nome: 'compras-por-compra',
    grupo: 'compras',
    titulo: 'Compras por período',
    explicacao:
      'Uma linha por compra recebida. As compras desfeitas aparecem marcadas e ficam fora do total.',
    filtros: ['periodo', 'busca', 'situacao', 'fornecedor', 'categoria', 'usuario'],
    situacoes: [
      { valor: '', rotulo: 'Registradas' },
      { valor: 'ESTORNADAS', rotulo: 'Só desfeitas' },
      { valor: 'TODAS', rotulo: 'Todas (desfeitas marcadas)' },
    ],
    buscaRotulo: 'Buscar por número, fornecedor ou peça',
    colunas: [
      { chave: 'dataCompra', titulo: 'Data da compra', tipo: 'data' },
      { chave: 'numero', titulo: 'Nota', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'itens', titulo: 'Produtos', tipo: 'inteiro' },
      { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
      { chave: 'total', titulo: 'Total', tipo: 'dinheiro' },
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
      { chave: 'motivoDoEstorno', titulo: 'Motivo de ter sido desfeita', tipo: 'texto' },
      { chave: 'usuario', titulo: 'Quem lançou', tipo: 'texto' },
    ],
    totaisDeValor: { registradas: 'total', estornadas: 'total' },
    linhasDoTotal: { registradas: naoDesfeita, estornadas: (l) => l.estornada === true },
    rotulosDosTotais: {
      todos: 'Compras na lista',
      registradas: 'Compras registradas (valem no total)',
      estornadas: 'Compras desfeitas (fora do total)',
    },
    acoes: [
      {
        rotulo: T.acoes.verAsPecas,
        abre: 'compras-pecas',
        filtro: 'compraId',
        campo: 'id',
        titulo: (l) => T.acoes.tituloDaCompra(dataDaCompra(l), dados(l, 'fornecedor')),
      },
    ],
    itens: { um: 'compra', varios: 'compras' },
    vazio: 'Nenhuma compra com estes filtros.',
  },
  'compras-por-fornecedor': {
    nome: 'compras-por-fornecedor',
    grupo: 'compras',
    titulo: 'Compras por fornecedor',
    explicacao:
      'Quanto foi comprado de cada fornecedor no período. As compras desfeitas ficam em colunas à parte, fora do total.',
    filtros: ['periodo', 'busca', 'situacao', 'fornecedor', 'categoria', 'usuario'],
    situacoes: [
      { valor: '', rotulo: 'Registradas' },
      { valor: 'ESTORNADAS', rotulo: 'Só desfeitas' },
      { valor: 'TODAS', rotulo: 'Todas (desfeitas à parte)' },
    ],
    buscaRotulo: 'Buscar por fornecedor ou peça',
    colunas: [
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'compras', titulo: 'Compras', tipo: 'inteiro' },
      { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
      { chave: 'total', titulo: 'Total comprado', tipo: 'dinheiro' },
      { chave: 'comprasEstornadas', titulo: 'Compras desfeitas', tipo: 'inteiro' },
      { chave: 'totalEstornado', titulo: 'Total das desfeitas (fora do total)', tipo: 'dinheiro' },
    ],
    totaisDeValor: { geral: 'total' },
    rotulosDosTotais: { todos: 'Fornecedores', geral: 'Total geral das compras registradas' },
    itens: { um: 'fornecedor', varios: 'fornecedores' },
    vazio: 'Nenhuma compra com estes filtros.',
  },
  'compras-custos': {
    nome: 'compras-custos',
    grupo: 'compras',
    titulo: 'Histórico de custo das peças',
    explicacao:
      'Cada custo que uma compra deu à peça, com o custo de antes e quanto mudou. Para ver uma peça só, escreva o nome ou o código dela em Buscar.',
    filtros: ['periodo', 'busca', 'situacao', 'fornecedor', 'categoria', 'usuario'],
    situacoes: SITUACAO_DA_PECA_TODAS,
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      { chave: 'dataCompra', titulo: 'Data da compra', tipo: 'data' },
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'numeroDaCompra', titulo: 'Nota', tipo: 'texto' },
      { chave: 'custo', titulo: 'Custo de cada peça', tipo: 'dinheiro' },
      { chave: 'custoAnterior', titulo: 'Custo de antes', tipo: 'dinheiro' },
      { chave: 'variacao', titulo: 'Mudança em reais', tipo: 'dinheiro' },
      { chave: 'variacaoPercentual', titulo: 'Mudança (%)', tipo: 'percentual' },
      { chave: 'situacaoNaTela', titulo: 'Observação', tipo: 'texto' },
    ],
    totaisDeValor: {},
    rotulosDosTotais: {
      todos: 'Custos registrados',
      aumentos: 'Custos que subiram',
      reducoes: 'Custos que caíram',
      primeiros: 'Primeiro custo da peça',
    },
    itens: { um: 'custo', varios: 'custos' },
    vazio: 'Nenhum custo registrado com estes filtros.',
  },
  'compras-pecas': {
    nome: 'compras-pecas',
    grupo: 'compras',
    soAbreDeUmaLinha: true,
    titulo: 'Peças da compra',
    explicacao: 'As peças desta compra, com a quantidade, o custo de cada uma e o total.',
    filtros: [],
    buscaRotulo: '',
    colunas: [
      FOTO,
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'quantidade', titulo: 'Quantidade', tipo: 'inteiro' },
      { chave: 'custoUnitario', titulo: 'Custo de cada peça', tipo: 'dinheiro' },
      { chave: 'total', titulo: 'Total', tipo: 'dinheiro' },
      { chave: 'situacaoNaTela', titulo: 'Situação da compra', tipo: 'texto' },
    ],
    totaisDeValor: { total: 'total' },
    rotulosDosTotais: { todos: 'Peças diferentes', total: 'Peças e total da compra' },
    acoes: [ABRIR_O_HISTORICO],
    itens: { um: 'peça', varios: 'peças' },
    vazio: 'Esta compra não tem peças.',
  },
};

export const ORDEM_DOS_RELATORIOS_DE_ESTOQUE: NomeDoRelatorio[] = [
  'estoque-posicao',
  'estoque-movimentos',
  'estoque-minimo',
  'estoque-parados',
  'estoque-contagens',
  'estoque-conferencia',
];
export const ORDEM_DOS_RELATORIOS_DE_COMPRAS: NomeDoRelatorio[] = [
  'compras-por-compra',
  'compras-por-fornecedor',
  'compras-custos',
];

/** Acrescenta às linhas o texto da tela (tipo, origem, situação em palavras da loja). */
export function prepararLinhaDeEstoque(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
): Record<string, unknown> {
  switch (nome) {
    case 'estoque-movimentos':
      return {
        ...linha,
        tipoNaTela: rotuloDoTipo(dados(linha, 'tipo')),
        origemNaTela: T.origens[dados(linha, 'origem')] ?? '',
        situacaoNaTela:
          linha.desfeito === true ? T.desfeito : linha.estorno === true ? T.desfazOutro : '',
      };
    case 'estoque-contagens':
      return {
        ...linha,
        situacaoNaTela: T.situacoesDaContagem[dados(linha, 'situacao')] ?? '',
        contagemNaTela: T.escopoDaContagem(dados(linha, 'escopo'), dados(linha, 'escopoNome')),
      };
    case 'compras-por-compra':
      return {
        ...linha,
        situacaoNaTela: linha.estornada === true ? T.compraDesfeita : T.compraRegistrada,
      };
    case 'compras-pecas':
      return {
        ...linha,
        situacaoNaTela:
          dados(linha, 'situacaoDaCompra') === 'ESTORNADA' ? T.compraDesfeita : T.compraRegistrada,
      };
    case 'compras-custos':
      return { ...linha, situacaoNaTela: linha.compraEstornada === true ? T.compraDesfeita : '' };
    default:
      return linha;
  }
}

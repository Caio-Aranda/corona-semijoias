/**
 * Os onze relatórios de vendas da dona e a curva ABC (tarefa 8.5; glossário "Relatórios de vendas"): nome na tela, filtros,
 * colunas e totais. A tela e a exportação usam esta mesma definição. Os dados vêm da API da tarefa 8.5; Dólar e Guarani chegam
 * prontos, pela cotação do dia de CADA venda, e o rodapé traz a soma já convertida. Tudo é gravado em Real. Nenhum custo aparece
 * aqui. Todos saem do mesmo vendido líquido (vendido menos devolução e troca); a venda cancelada fica fora dos totais.
 */
import type { NomeDoRelatorio, NomeDoRelatorioDeVendas } from '../../shared/relatorios';
import { TEXTOS_DE_RELATORIOS_DE_VENDAS } from '../textos-relatorios-vendas';
import type { ColunaDoRelatorio } from './exportacao/grade';
import type { DefinicaoDeRelatorio } from './relatorios';
import { idiomaAtual } from '../idioma/idioma';
import { traducirDefinicion } from '../idioma/es/relatorios-mapa';

const T = TEXTOS_DE_RELATORIOS_DE_VENDAS;

const COTACAO = { chave: 'dataDaCotacao', titulo: 'Cotação do dia', tipo: 'data' } as const;
const FOTO = { chave: 'fotoPrincipalHash', titulo: 'Foto', tipo: 'foto' } as const;
const POSICAO = { chave: 'posicao', titulo: 'Posição', tipo: 'inteiro' } as const;

/** Coluna de dinheiro cujos campos são `<chave>`, `<chave>EmDolar` e `<chave>EmGuarani`. */
const dinheiro = (
  chave: string,
  titulo: string,
  dataDaCotacao = 'dataDaCotacao',
): ColunaDoRelatorio => ({
  chave,
  titulo,
  tipo: 'dinheiro',
  convertido: { dolar: `${chave}EmDolar`, guarani: `${chave}EmGuarani`, data: dataDaCotacao },
});

const TOTAIS_AGRUPADOS = { liquido: 'liquido', vendido: 'vendido', devolvido: 'devolvido' };
const ROTULOS_AGRUPADOS = {
  liquido: 'Vendido líquido',
  vendido: 'Vendido',
  devolvido: 'Devolvido',
};

/** As colunas que todo relatório agrupado repete, depois do que identifica a linha. */
function colunasDoVendido(dataDaCotacao?: string): ColunaDoRelatorio[] {
  return [
    { chave: 'vendas', titulo: 'Vendas', tipo: 'inteiro' },
    { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
    dinheiro('vendido', 'Vendido', dataDaCotacao),
    dinheiro('devolvido', 'Devolvido', dataDaCotacao),
    dinheiro('liquido', 'Vendido líquido', dataDaCotacao),
    { chave: 'ticketMedio', titulo: 'Valor médio por venda', tipo: 'dinheiro', soReal: true },
  ];
}

const FILTROS_DE_VENDAS = ['busca', 'vendedor', 'praca', 'categoria', 'periodo'] as const;
const BUSCA = 'Buscar por representante, cliente ou produto';

function agrupado(
  nome: NomeDoRelatorioDeVendas,
  titulo: string,
  explicacao: string,
  identificacao: ColunaDoRelatorio[],
  itens: { um: string; varios: string },
  dataDaCotacao?: string,
): DefinicaoDeRelatorio {
  return {
    nome,
    grupo: 'vendas',
    titulo,
    explicacao,
    filtros: [...FILTROS_DE_VENDAS],
    buscaRotulo: BUSCA,
    colunas: [...identificacao, ...colunasDoVendido(dataDaCotacao)],
    totaisDeValor: TOTAIS_AGRUPADOS,
    rotulosDosTotais: ROTULOS_AGRUPADOS,
    notaDaCotacao: T.cotacao,
    nota: T.liquido,
    itens,
    vazio: 'Nenhuma venda com estes filtros.',
  };
}

const COLUNAS_DA_VENDA: ColunaDoRelatorio[] = [
  { chave: 'vendidaEm', titulo: 'Vendida em', tipo: 'dataHora' },
  { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
  { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
  { chave: 'cliente', titulo: 'Cliente', tipo: 'texto' },
  { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
  dinheiro('vendido', 'Vendido'),
  dinheiro('devolvido', 'Devolvido'),
  dinheiro('liquido', 'Vendido líquido'),
  COTACAO,
];

export const RELATORIOS_DE_VENDAS: Record<NomeDoRelatorioDeVendas, DefinicaoDeRelatorio> = {
  'vendas-lista': {
    nome: 'vendas-lista',
    grupo: 'vendas',
    titulo: 'Vendas feitas',
    explicacao:
      'Uma venda por linha: quando, quem vendeu, para quem, quanto saiu, quanto voltou e o vendido líquido. Clique num total para ver as vendas que o formam.',
    filtros: [...FILTROS_DE_VENDAS, 'situacao'],
    situacoes: [
      { valor: '', rotulo: 'Só as feitas' },
      { valor: 'CANCELADAS', rotulo: 'Só as canceladas' },
      { valor: 'TODAS', rotulo: 'Feitas e canceladas' },
    ],
    buscaRotulo: BUSCA,
    colunas: [...COLUNAS_DA_VENDA, { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' }],
    totaisDeValor: { ...TOTAIS_AGRUPADOS, canceladas: 'vendido' },
    rotulosDosTotais: { ...ROTULOS_AGRUPADOS, canceladas: 'Vendas canceladas (fora do total)' },
    linhasDoTotal: {
      liquido: (l) => l.cancelada !== true,
      vendido: (l) => l.cancelada !== true,
      devolvido: (l) => l.cancelada !== true,
    },
    notaDaCotacao: T.cotacao,
    nota: T.liquido,
    itens: { um: 'venda', varios: 'vendas' },
    vazio: 'Nenhuma venda com estes filtros.',
  },
  'vendas-canceladas': {
    nome: 'vendas-canceladas',
    grupo: 'vendas',
    titulo: 'Vendas canceladas',
    explicacao:
      'As vendas que foram canceladas, com quem cancelou, quando e por quê. Elas ficam fora de todos os totais dos outros relatórios.',
    filtros: [...FILTROS_DE_VENDAS],
    buscaRotulo: BUSCA,
    colunas: [
      ...COLUNAS_DA_VENDA.filter((c) => !['devolvido', 'liquido'].includes(c.chave)),
      { chave: 'estornadaEm', titulo: 'Cancelada em', tipo: 'dataHora' },
      { chave: 'estornadaPor', titulo: 'Cancelada por', tipo: 'texto' },
      { chave: 'motivoDoEstorno', titulo: 'Motivo', tipo: 'texto' },
    ],
    totaisDeValor: { canceladas: 'vendido' },
    rotulosDosTotais: { canceladas: 'Vendas canceladas (fora do total)' },
    notaDaCotacao: T.cotacao,
    itens: { um: 'venda cancelada', varios: 'vendas canceladas' },
    vazio: 'Nenhuma venda cancelada com estes filtros.',
  },
  'vendas-por-vendedor': agrupado(
    'vendas-por-vendedor',
    'Vendas por representante',
    'Quanto cada representante vendeu: vendas, peças, vendido, devolvido, vendido líquido e valor médio por venda.',
    [
      { chave: 'nome', titulo: 'Representante', tipo: 'texto' },
      { chave: 'detalhe', titulo: 'Praça', tipo: 'texto' },
    ],
    { um: 'representante', varios: 'representantes' },
  ),
  'vendas-por-praca': agrupado(
    'vendas-por-praca',
    'Vendas por praça',
    'Quanto vendeu cada praça (a praça do representante).',
    [{ chave: 'nome', titulo: 'Praça', tipo: 'texto' }],
    { um: 'praça', varios: 'praças' },
  ),
  'vendas-por-cliente': agrupado(
    'vendas-por-cliente',
    'Vendas por cliente',
    'Quanto cada cliente comprou, já descontado o que devolveu ou trocou.',
    [{ chave: 'nome', titulo: 'Cliente', tipo: 'texto' }],
    { um: 'cliente', varios: 'clientes' },
  ),
  'vendas-por-produto': agrupado(
    'vendas-por-produto',
    'Vendas por produto',
    'Quanto vendeu cada produto. É o mesmo vendido líquido da curva ABC.',
    [
      FOTO,
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'detalhe', titulo: 'Referência', tipo: 'texto' },
    ],
    { um: 'produto', varios: 'produtos' },
  ),
  'vendas-por-categoria': agrupado(
    'vendas-por-categoria',
    'Vendas por categoria',
    'Quanto vendeu cada categoria de produto. Uma venda com peças de duas categorias conta nas duas.',
    [{ chave: 'nome', titulo: 'Categoria', tipo: 'texto' }],
    { um: 'categoria', varios: 'categorias' },
  ),
  'vendas-por-periodo': agrupado(
    'vendas-por-periodo',
    'Vendas por dia',
    'Quanto se vendeu em cada dia (pela data da venda, no horário de Brasília).',
    [{ chave: 'nome', titulo: 'Dia', tipo: 'data' }],
    { um: 'dia', varios: 'dias' },
    'chave',
  ),
  'vendas-ranking-vendedores': agrupado(
    'vendas-ranking-vendedores',
    'Ranking de representantes',
    'Os representantes do que mais vendeu para o que menos vendeu, pelo vendido líquido.',
    [POSICAO, { chave: 'nome', titulo: 'Representante', tipo: 'texto' }],
    { um: 'representante', varios: 'representantes' },
  ),
  'vendas-ranking-pecas': agrupado(
    'vendas-ranking-pecas',
    'Ranking de produtos',
    'Os produtos do que mais vendeu para o que menos vendeu, pelo vendido líquido.',
    [
      POSICAO,
      FOTO,
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
    ],
    { um: 'produto', varios: 'produtos' },
  ),
  'vendas-ticket-medio': agrupado(
    'vendas-ticket-medio',
    'Valor médio por venda',
    'Os representantes do maior para o menor valor médio por venda (vendido líquido dividido pelo número de vendas).',
    [{ chave: 'nome', titulo: 'Representante', tipo: 'texto' }],
    { um: 'representante', varios: 'representantes' },
  ),
  'vendas-curva-abc': {
    nome: 'vendas-curva-abc',
    grupo: 'estoque',
    titulo: 'Curva ABC dos produtos',
    explicacao:
      'Os produtos do que mais vendeu para o que menos vendeu, com a parte de cada um no total e a classe: A, B ou C. Clique num total para ver os produtos da classe.',
    filtros: [...FILTROS_DE_VENDAS],
    buscaRotulo: BUSCA,
    colunas: [
      POSICAO,
      FOTO,
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
      dinheiro('liquido', 'Vendido líquido'),
      { chave: 'percentual', titulo: 'Parte do total', tipo: 'percentual' },
      { chave: 'percentualAcumulado', titulo: 'Soma até aqui', tipo: 'percentual' },
      { chave: 'classe', titulo: 'Classe', tipo: 'texto' },
    ],
    totaisDeValor: {
      liquido: 'liquido',
      'classe-a': 'liquido',
      'classe-b': 'liquido',
      'classe-c': 'liquido',
    },
    rotulosDosTotais: {
      liquido: 'Vendido líquido de todos',
      'classe-a': 'Classe A (até 80%)',
      'classe-b': 'Classe B (de 80% a 95%)',
      'classe-c': 'Classe C (o resto)',
    },
    notaDaCotacao: T.cotacao,
    nota: T.curva,
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Nenhum produto vendido com estes filtros.',
  },
};

export const ORDEM_DOS_RELATORIOS_DE_VENDAS: NomeDoRelatorio[] = [
  'vendas-lista',
  'vendas-por-vendedor',
  'vendas-por-praca',
  'vendas-por-cliente',
  'vendas-por-produto',
  'vendas-por-categoria',
  'vendas-por-periodo',
  'vendas-ranking-vendedores',
  'vendas-ranking-pecas',
  'vendas-ticket-medio',
  'vendas-canceladas',
];

/** O que fica no menu do grupo Estoque, além dos relatórios de estoque. */
export const RELATORIOS_DE_VENDAS_NO_ESTOQUE: NomeDoRelatorio[] = ['vendas-curva-abc'];

/** Acrescenta às linhas o texto da tela (a situação da venda em palavras da loja, a praça que falta). */
export function prepararLinhaDeVendas(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
): Record<string, unknown> {
  switch (nome) {
    case 'vendas-lista':
    case 'vendas-canceladas': {
      const situacao = typeof linha.situacao === 'string' ? linha.situacao : '';
      return {
        ...linha,
        praca: typeof linha.praca === 'string' && linha.praca !== '' ? linha.praca : T.semPraca,
        situacaoNaTela: T.situacaoDaVenda[situacao] ?? '',
      };
    }
    default:
      return linha;
  }
}

// Em espanhol, os textos de cada definição saem da tabela de tradução (feita uma vez, ao abrir o programa).
if (idiomaAtual() === 'es') traducirDefinicion(RELATORIOS_DE_VENDAS);

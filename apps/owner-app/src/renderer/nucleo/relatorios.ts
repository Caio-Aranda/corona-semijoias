/**
 * Os relatórios de cadastro da dona (tarefa 1.12): nome na tela, filtros, colunas e de onde sai cada
 * total de valor. A tela e a exportação usam esta mesma definição, então o arquivo sempre bate com o
 * que está na tela.
 */
import type { NomeDoRelatorio, NomeDoRelatorioDeCadastro } from '../../shared/relatorios';
import { TEXTOS_RELATORIOS } from '../textos-relatorios';
import type { ColunaDoRelatorio } from './exportacao/grade';
import { RELATORIOS_DE_ESTOQUE, prepararLinhaDeEstoque } from './relatorios-estoque';
import { RELATORIOS_DE_DEVOLUCAO, prepararLinhaDeDevolucao } from './relatorios-devolucao';
import { RELATORIOS_DE_MALETA, prepararLinhaDeMaleta } from './relatorios-maleta';

export type FiltroDoRelatorio =
  | 'busca'
  | 'situacao'
  | 'categoria'
  | 'fornecedor'
  | 'praca'
  | 'vendedor'
  | 'restricao'
  | 'periodo'
  | 'semana'
  | 'mes'
  | 'usuario';

/** Os filtros que só um relatório tem (tarefa 1.13); a chave é o nome do filtro na API. */
export type ChaveDeExtra =
  | 'limiteDe'
  | 'limiteAte'
  | 'comRestricao'
  | 'estoque'
  | 'precoDe'
  | 'precoAte'
  | 'foto'
  | 'comProdutos'
  | 'comVendedor'
  | 'tipo'
  | 'resultado'
  | 'quem'
  | 'origem'
  | 'dias'
  | 'diasDeAtraso'
  | 'divergencia'
  | 'motivo'
  | 'forma';

export type ExtraDoRelatorio =
  | {
      chave: ChaveDeExtra;
      rotulo: string;
      tipo: 'escolha';
      opcoes: { valor: string; rotulo: string }[];
      /** Texto da opção "sem filtro". */
      qualquer: string;
    }
  | { chave: ChaveDeExtra; rotulo: string; tipo: 'valor' }
  /** Número inteiro (por exemplo, quantos dias). */
  | { chave: ChaveDeExtra; rotulo: string; tipo: 'inteiro'; padrao?: number };

/** Um jeito de abrir uma linha: outra tela de relatório com um filtro fixo tirado da linha. */
export interface AcaoDaLinha {
  rotulo: string;
  /** O relatório que abre. */
  abre: NomeDoRelatorio;
  /** O filtro fixo (nome na API) e o campo da linha que o preenche. */
  filtro: 'compraId' | 'inventarioId' | 'produtoId';
  campo: string;
  /** Como a pessoa chama o que foi aberto ("Compra 123 de Ouro Ltda"). */
  titulo: (linha: Record<string, unknown>) => string;
  /** A linha só tem esta ação quando isto vale. */
  quando?: (linha: Record<string, unknown>) => boolean;
}

export interface DefinicaoDeRelatorio {
  nome: NomeDoRelatorio;
  /** Nome do relatório na tela, no menu e no cabeçalho dos arquivos. */
  titulo: string;
  /** Uma frase dizendo o que o relatório mostra. */
  explicacao: string;
  filtros: FiltroDoRelatorio[];
  /** Filtros próprios deste relatório, além dos comuns. */
  extras?: ExtraDoRelatorio[];
  /** O relatório de produtos sem foto só separa ativos e inativos (a rota não tem "todos"). */
  semOpcaoTodos?: boolean;
  /** Situação que vale quando a pessoa não escolhe: o servidor mostra só ativos. */
  buscaRotulo: string;
  colunas: ColunaDoRelatorio[];
  /** Qual campo das linhas forma cada total de valor. */
  totaisDeValor: Record<string, string>;
  itens: { um: string; varios: string };
  vazio: string;
  /** Aviso fixo embaixo do título, se houver. */
  nota?: string;
  /** As opções do filtro de situação, se não forem as de produto; o valor vazio é o padrão do servidor. */
  situacoes?: { valor: string; rotulo: string }[];
  /** Rótulo na tela de cada total, pela chave (o texto do servidor tem termos que a tela não usa). */
  rotulosDosTotais?: Record<string, string>;
  /** Linhas que entram na soma em Dólar e Guarani de cada total de valor. */
  linhasDoTotal?: Record<string, (linha: Record<string, unknown>) => boolean>;
  /** Abrir uma linha (compra → peças; contagem → diferenças; peça → histórico de custo). */
  acoes?: AcaoDaLinha[];
  /** Grupo do menu: o dos relatórios de cadastro, de estoque ou de compras. */
  grupo?: 'estoque' | 'compras' | 'maletas' | 'devolucoes';
  /** Devolução e troca: a frase do rodapé dos arquivos sobre a cotação de cada lançamento. */
  notaDaCotacao?: string;
  /** Maletas: o campo da linha com o código da maleta; a linha ganha o botão "Ver a maleta". */
  fichaDaMaleta?: string;
  /** Fora do menu: só abre a partir de outra linha. */
  soAbreDeUmaLinha?: boolean;
}

const T = TEXTOS_RELATORIOS;

export const RELATORIOS: Record<NomeDoRelatorio, DefinicaoDeRelatorio> = {
  ...RELATORIOS_DE_ESTOQUE,
  ...RELATORIOS_DE_MALETA,
  ...RELATORIOS_DE_DEVOLUCAO,
  vendedores: {
    nome: 'vendedores',
    titulo: 'Vendedores: limite e situação',
    explicacao:
      'O limite de consignação, a restrição, a situação, a praça e a comissão de cada vendedor.',
    filtros: ['busca', 'situacao', 'praca', 'restricao', 'periodo'],
    extras: [
      { chave: 'limiteDe', rotulo: 'Limite a partir de (R$)', tipo: 'valor' },
      { chave: 'limiteAte', rotulo: 'Limite até (R$)', tipo: 'valor' },
      {
        chave: 'comRestricao',
        rotulo: 'Com restrição',
        tipo: 'escolha',
        qualquer: 'Tanto faz',
        opcoes: [
          { valor: 'SIM', rotulo: 'Só com restrição' },
          { valor: 'NAO', rotulo: 'Só sem restrição' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por nome, número, CPF ou telefone',
    colunas: [
      { chave: 'codigo', titulo: 'Número', tipo: 'inteiro' },
      { chave: 'nome', titulo: 'Vendedor', tipo: 'texto' },
      { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
      { chave: 'restricao', titulo: 'Restrição', tipo: 'texto' },
      { chave: 'bloqueiaRemessa', titulo: 'Impede montar maleta', tipo: 'simNao' },
      { chave: 'limiteConsignacao', titulo: 'Limite de consignação', tipo: 'dinheiro' },
      { chave: 'comissaoPercentual', titulo: 'Comissão (%)', tipo: 'percentual' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: { limite: 'limiteConsignacao' },
    itens: { um: 'vendedor', varios: 'vendedores' },
    vazio: 'Nenhum vendedor com estes filtros.',
  },
  clientes: {
    nome: 'clientes',
    titulo: 'Clientes cadastrados',
    explicacao: 'Os clientes, com o vendedor responsável e a praça dele.',
    filtros: ['busca', 'situacao', 'vendedor', 'praca', 'periodo'],
    extras: [
      {
        chave: 'comVendedor',
        rotulo: 'Vendedor responsável',
        tipo: 'escolha',
        qualquer: 'Tanto faz',
        opcoes: [
          { valor: 'SIM', rotulo: 'Só com vendedor' },
          { valor: 'NAO', rotulo: 'Só sem vendedor' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por nome, CPF ou telefone',
    colunas: [
      { chave: 'nome', titulo: 'Cliente', tipo: 'texto' },
      { chave: 'telefone', titulo: 'Telefone', tipo: 'texto' },
      { chave: 'whatsapp', titulo: 'WhatsApp', tipo: 'texto' },
      { chave: 'cidade', titulo: 'Cidade', tipo: 'texto' },
      { chave: 'uf', titulo: 'Estado', tipo: 'texto' },
      { chave: 'vendedor', titulo: 'Vendedor responsável', tipo: 'texto' },
      { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
      { chave: 'dataNascimento', titulo: 'Nascimento', tipo: 'data' },
      { chave: 'cadastradoEm', titulo: 'Cadastrado em', tipo: 'dataHora' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: {},
    itens: { um: 'cliente', varios: 'clientes' },
    vazio: 'Nenhum cliente com estes filtros.',
  },
  fornecedores: {
    nome: 'fornecedores',
    titulo: 'Fornecedores cadastrados',
    explicacao: 'Os fornecedores e quantos produtos ativos cada um tem.',
    filtros: ['busca', 'situacao', 'periodo'],
    extras: [
      {
        chave: 'comProdutos',
        rotulo: 'Produtos ativos',
        tipo: 'escolha',
        qualquer: 'Tanto faz',
        opcoes: [
          { valor: 'SIM', rotulo: 'Só quem tem produtos' },
          { valor: 'NAO', rotulo: 'Só quem não tem produtos' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por nome, CNPJ ou contato',
    colunas: [
      { chave: 'razaoSocial', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'cnpj', titulo: 'CNPJ', tipo: 'texto' },
      { chave: 'contato', titulo: 'Contato', tipo: 'texto' },
      { chave: 'telefone', titulo: 'Telefone', tipo: 'texto' },
      { chave: 'cidade', titulo: 'Cidade', tipo: 'texto' },
      { chave: 'uf', titulo: 'Estado', tipo: 'texto' },
      { chave: 'produtosAtivos', titulo: 'Produtos ativos', tipo: 'inteiro' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: {},
    itens: { um: 'fornecedor', varios: 'fornecedores' },
    vazio: 'Nenhum fornecedor com estes filtros.',
  },
  produtos: {
    nome: 'produtos',
    titulo: 'Produtos e custos',
    explicacao:
      'Os produtos com preço de venda e preço de custo. O custo aparece só aqui, para a dona.',
    filtros: ['busca', 'situacao', 'categoria', 'fornecedor', 'periodo'],
    extras: [
      {
        chave: 'estoque',
        rotulo: 'Estoque',
        tipo: 'escolha',
        qualquer: 'Qualquer',
        opcoes: [
          { valor: 'ZERADO', rotulo: 'Sem estoque' },
          { valor: 'ABAIXO_DO_MINIMO', rotulo: 'Abaixo do mínimo' },
          { valor: 'COM_ESTOQUE', rotulo: 'Com estoque' },
        ],
      },
      { chave: 'precoDe', rotulo: 'Preço de venda a partir de (R$)', tipo: 'valor' },
      { chave: 'precoAte', rotulo: 'Preço de venda até (R$)', tipo: 'valor' },
      {
        chave: 'foto',
        rotulo: 'Foto',
        tipo: 'escolha',
        qualquer: 'Tanto faz',
        opcoes: [
          { valor: 'COM', rotulo: 'Só com foto' },
          { valor: 'SEM', rotulo: 'Só sem foto' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'referencia', titulo: 'Referência', tipo: 'texto' },
      { chave: 'codigoPrincipal', titulo: 'Código', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'estoqueMinimo', titulo: 'Estoque mínimo', tipo: 'inteiro' },
      { chave: 'estoqueAtual', titulo: 'Estoque', tipo: 'inteiro' },
      { chave: 'precoVenda', titulo: 'Preço de venda', tipo: 'dinheiro' },
      { chave: 'precoCusto', titulo: 'Preço de custo', tipo: 'dinheiro' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: { 'preco-venda': 'precoVenda', custo: 'precoCusto' },
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Nenhum produto com estes filtros.',
  },
  'sem-foto': {
    nome: 'sem-foto',
    titulo: 'Produtos sem foto',
    explicacao: 'Os produtos que ainda não têm nenhuma foto. A falta de foto nunca impede a venda.',
    filtros: ['busca', 'situacao', 'categoria', 'fornecedor'],
    semOpcaoTodos: true,
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'referencia', titulo: 'Referência', tipo: 'texto' },
      { chave: 'codigoPrincipal', titulo: 'Código', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: {},
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Todos os produtos têm foto.',
  },
  'sem-codigo-de-barras': {
    nome: 'sem-codigo-de-barras',
    titulo: 'Produtos sem código de barras',
    explicacao:
      'Só para informar: o código de barras não é obrigatório, a loja pode usar só o código dela.',
    filtros: ['busca', 'situacao', 'categoria', 'fornecedor'],
    buscaRotulo: 'Buscar por nome, referência ou código',
    colunas: [
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'referencia', titulo: 'Referência', tipo: 'texto' },
      { chave: 'codigos', titulo: 'Códigos que tem', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
      { chave: 'temCodigoDoFornecedor', titulo: 'Tem código do fornecedor', tipo: 'simNao' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: {},
    itens: { um: 'produto', varios: 'produtos' },
    vazio: 'Todos os produtos têm código de barras.',
  },
  'codigos-em-conflito': {
    nome: 'codigos-em-conflito',
    titulo: 'Códigos repetidos ou parecidos',
    explicacao:
      'Códigos que podem se confundir na leitura da etiqueta. O sistema já não deixa gravar o mesmo código duas vezes.',
    filtros: ['busca', 'situacao', 'fornecedor'],
    buscaRotulo: 'Buscar por código ou produto',
    colunas: [
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'Tipo', tipo: 'texto' },
      { chave: 'descricao', titulo: 'O que chama atenção', tipo: 'texto' },
      { chave: 'produto', titulo: 'Produto', tipo: 'texto' },
      { chave: 'fornecedor', titulo: 'Fornecedor', tipo: 'texto' },
    ],
    totaisDeValor: {},
    itens: { um: 'código', varios: 'códigos' },
    vazio: 'Nenhum código repetido ou parecido.',
  },
  aniversariantes: {
    nome: 'aniversariantes',
    titulo: 'Aniversariantes do mês',
    explicacao: 'Os vendedores e os clientes que fazem aniversário no mês escolhido.',
    filtros: ['mes', 'busca', 'situacao', 'praca'],
    extras: [
      {
        chave: 'quem',
        rotulo: 'Quem mostrar',
        tipo: 'escolha',
        qualquer: 'Vendedores e clientes',
        opcoes: [
          { valor: 'VENDEDORES', rotulo: 'Só vendedores' },
          { valor: 'CLIENTES', rotulo: 'Só clientes' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por nome ou telefone',
    colunas: [
      { chave: 'dia', titulo: 'Dia', tipo: 'inteiro' },
      { chave: 'tipoNaTela', titulo: 'Quem', tipo: 'texto' },
      { chave: 'nome', titulo: 'Nome', tipo: 'texto' },
      { chave: 'dataNascimento', titulo: 'Nascimento', tipo: 'data' },
      { chave: 'whatsapp', titulo: 'WhatsApp', tipo: 'texto' },
      { chave: 'telefone', titulo: 'Telefone', tipo: 'texto' },
      { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    totaisDeValor: {},
    itens: { um: 'aniversariante', varios: 'aniversariantes' },
    vazio: 'Nenhum aniversariante neste mês.',
  },
  'alteracoes-de-produtos': {
    nome: 'alteracoes-de-produtos',
    titulo: 'Mudanças nos produtos',
    explicacao: 'Produtos cadastrados e preços alterados, com quem fez e quando.',
    filtros: ['busca', 'periodo'],
    buscaRotulo: 'Buscar por produto ou pessoa',
    nota: T.alteracoesSemImportacao,
    colunas: [
      { chave: 'quando', titulo: 'Quando', tipo: 'dataHora' },
      { chave: 'descricao', titulo: 'O que mudou', tipo: 'texto' },
      { chave: 'produto', titulo: 'Produto', tipo: 'texto' },
      { chave: 'quem', titulo: 'Quem fez', tipo: 'texto' },
    ],
    totaisDeValor: {},
    itens: { um: 'mudança', varios: 'mudanças' },
    vazio: 'Nenhuma mudança neste período.',
  },
  'resultado-das-importacoes': {
    nome: 'resultado-das-importacoes',
    titulo: 'Resultado das importações',
    explicacao:
      'Cada linha da planilha e cada foto importadas de uma vez: o que aconteceu, por que foi recusada, quem importou e quando.',
    filtros: ['busca', 'periodo'],
    extras: [
      {
        chave: 'tipo',
        rotulo: 'O que foi importado',
        tipo: 'escolha',
        qualquer: 'Planilhas e fotos',
        opcoes: [
          { valor: 'PLANILHA', rotulo: 'Planilha de produtos' },
          { valor: 'FOTOS', rotulo: 'Fotos em massa' },
        ],
      },
      {
        chave: 'resultado',
        rotulo: 'Resultado',
        tipo: 'escolha',
        qualquer: 'Todos',
        opcoes: [
          { valor: 'CRIADO', rotulo: 'Criado' },
          { valor: 'ATUALIZADO', rotulo: 'Atualizado' },
          { valor: 'JA_EXISTIA', rotulo: 'Já existia' },
          { valor: 'RECUSADO', rotulo: 'Recusado' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por linha, arquivo, motivo ou pessoa',
    nota: T.importacoesNota,
    colunas: [
      { chave: 'quando', titulo: 'Quando', tipo: 'dataHora' },
      { chave: 'tipoNaTela', titulo: 'O que foi importado', tipo: 'texto' },
      { chave: 'arquivo', titulo: 'Arquivo', tipo: 'texto' },
      { chave: 'referencia', titulo: 'Linha ou foto', tipo: 'texto' },
      { chave: 'resultadoNaTela', titulo: 'Resultado', tipo: 'texto' },
      { chave: 'motivo', titulo: 'Motivo', tipo: 'texto' },
      { chave: 'quem', titulo: 'Quem importou', tipo: 'texto' },
    ],
    totaisDeValor: {},
    itens: { um: 'linha', varios: 'linhas' },
    vazio: 'Nenhuma importação com estes filtros.',
  },
};

export const ORDEM_DOS_RELATORIOS: NomeDoRelatorioDeCadastro[] = [
  'vendedores',
  'clientes',
  'fornecedores',
  'produtos',
  'sem-foto',
  'sem-codigo-de-barras',
  'codigos-em-conflito',
  'aniversariantes',
  'alteracoes-de-produtos',
  'resultado-das-importacoes',
];

export interface TotalLido {
  chave: string;
  rotulo: string;
  quantidade: number;
  valorEmReais: string | null;
  /** Maletas: o total já convertido pelo servidor, pela cotação do envio de cada maleta. */
  valorEmDolar: string | null;
  valorEmGuarani: string | null;
  /** Soma de peças, nos totais que são de peças. */
  pecas: number | null;
}

export interface RelatorioLido {
  itens: Record<string, unknown>[];
  total: number;
  pagina: number;
  tamanho: number;
  totais: TotalLido[];
}

/** Lê a resposta do servidor; o que não tem o formato esperado é descartado. A rota antiga de "sem foto" não tem rodapé. */
export function lerRelatorio(bruto: unknown, nome: NomeDoRelatorio): RelatorioLido {
  const p = (typeof bruto === 'object' && bruto !== null ? bruto : {}) as Record<string, unknown>;
  const itens = (Array.isArray(p.itens) ? p.itens : []).filter(
    (i): i is Record<string, unknown> => typeof i === 'object' && i !== null,
  );
  const total = typeof p.total === 'number' ? p.total : itens.length;
  const totais: TotalLido[] = (Array.isArray(p.totais) ? p.totais : [])
    .filter(
      (t): t is Record<string, unknown> =>
        typeof t === 'object' && t !== null && typeof (t as { chave?: unknown }).chave === 'string',
    )
    .map((t) => ({
      chave: String(t.chave),
      rotulo: typeof t.rotulo === 'string' ? t.rotulo : String(t.chave),
      quantidade: typeof t.quantidade === 'number' ? t.quantidade : 0,
      valorEmReais: typeof t.valorEmReais === 'string' ? t.valorEmReais : null,
      valorEmDolar: typeof t.valorEmDolar === 'string' ? t.valorEmDolar : null,
      valorEmGuarani: typeof t.valorEmGuarani === 'string' ? t.valorEmGuarani : null,
      pecas: typeof t.pecas === 'number' ? t.pecas : null,
    }))
    .map((t) => ({ ...t, rotulo: RELATORIOS[nome].rotulosDosTotais?.[t.chave] ?? t.rotulo }));
  if (nome === 'sem-foto' && totais.length === 0) {
    totais.push({
      chave: 'todos',
      rotulo: 'Produtos sem foto',
      quantidade: total,
      valorEmReais: null,
      valorEmDolar: null,
      valorEmGuarani: null,
      pecas: null,
    });
  }
  return {
    itens,
    total,
    pagina: typeof p.pagina === 'number' ? p.pagina : 0,
    tamanho: typeof p.tamanho === 'number' && p.tamanho > 0 ? p.tamanho : 20,
    totais,
  };
}

export interface Opcao {
  id: string;
  nome: string;
}

export interface Opcoes {
  usuarios: Opcao[];
  categorias: Opcao[];
  fornecedores: Opcao[];
  pracas: Opcao[];
  restricoes: Opcao[];
  vendedores: Opcao[];
  semanas: Opcao[];
}

/** Acrescenta às linhas o que a tela mostra e a rota não manda: nomes por id e o tipo do código em palavras. */
export function prepararLinha(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
  opcoes: Opcoes,
): Record<string, unknown> {
  const nomeDe = (lista: Opcao[], id: unknown) =>
    typeof id === 'string' ? (lista.find((o) => o.id === id)?.nome ?? '') : '';
  switch (nome) {
    case 'sem-foto':
      return {
        ...linha,
        categoria: nomeDe(opcoes.categorias, linha.categoriaId),
        fornecedor: nomeDe(opcoes.fornecedores, linha.fornecedorId),
      };
    case 'codigos-em-conflito':
      return {
        ...linha,
        tipoNaTela: T.tiposDeCodigo[String(linha.tipo)] ?? '',
      };
    case 'aniversariantes':
      return { ...linha, tipoNaTela: T.tiposDeAniversariante[String(linha.tipo)] ?? '' };
    default:
      if (nome.startsWith('devolucao-')) return prepararLinhaDeDevolucao(nome, linha);
      return nome.startsWith('maleta-')
        ? prepararLinhaDeMaleta(nome, linha)
        : prepararLinhaDeEstoque(nome, linha);
  }
}

import type { NomeDoCadastro } from '../../../shared/cadastros';
import { TEXTOS } from '../../textos';

/** Um registro como a API devolve: só se garante `id` e `version`; o resto é lido com cuidado. */
export interface Registro {
  id: string;
  version: number;
  [campo: string]: unknown;
}

export interface CampoDaTela {
  /** Nome do campo na API (no endereço, só a parte final, ex.: `cep`). */
  nome: string;
  rotulo: string;
  tipo: 'texto' | 'numero' | 'data' | 'selecao' | 'sim-nao' | 'vendedor' | 'area';
  obrigatorio?: boolean;
  ajuda?: string;
  max?: number;
  opcoes?: readonly { valor: string; rotulo: string }[];
  /** Valor ao cadastrar um novo. */
  padrao?: string;
  /** O campo fica dentro do endereço. */
  endereco?: boolean;
}

export interface ColunaDaDefinicao {
  chave: string;
  titulo: string;
  valor: (r: Registro) => string;
}

export interface Definicao {
  cadastro: NomeDoCadastro;
  textos: {
    titulo: string;
    item: string;
    itens: readonly [string, string];
    novo: string;
    buscando: string;
    buscaRotulo: string;
    legenda: string;
    editar: string;
    excluir: string;
    colunaAcoes: string;
    vazio: string;
    semResultado: string;
    formulario: { tituloNovo: string; tituloEditar: string };
    confirmar: {
      titulo: (nome: string) => string;
      texto: (nome: string) => string;
      voltar: string;
    };
    feito: {
      criado: (nome: string) => string;
      editado: (nome: string) => string;
      excluido: (nome: string) => string;
    };
  };
  colunas: ColunaDaDefinicao[];
  campos: CampoDaTela[];
  nome: (r: Registro) => string;
}

const T = TEXTOS.cadastros;

export function texto(r: Registro, campo: string): string {
  const v = r[campo];
  return typeof v === 'string' || typeof v === 'number' ? String(v) : '';
}

/** "2026-11-09" → "09/11/2026", sem passar por fuso horário. */
export function dataNaTela(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
}

const enderecoDe = (r: Registro): Record<string, unknown> =>
  typeof r.endereco === 'object' && r.endereco !== null
    ? (r.endereco as Record<string, unknown>)
    : {};

/** Valor de um campo para preencher o formulário ao editar. */
export function valorInicial(r: Registro | null, c: CampoDaTela): string {
  if (!r) return c.padrao ?? '';
  const v = c.endereco ? enderecoDe(r)[c.nome] : r[c.nome];
  if (typeof v === 'boolean') return v ? 'sim' : 'nao';
  if (typeof v === 'string' || typeof v === 'number') return String(v);
  return c.padrao ?? '';
}

const CAMPOS_DE_ENDERECO: CampoDaTela[] = (
  [
    ['logradouro', 200],
    ['numero', 20],
    ['complemento', 100],
    ['bairro', 100],
    ['cidade', 100],
    ['uf', 20],
    ['cep', 20],
  ] as const
).map(([nome, max]) => ({
  nome,
  rotulo: T.endereco[nome],
  tipo: 'texto',
  max,
  endereco: true,
}));

const soNome = (rotulo: string): CampoDaTela => ({
  nome: 'nome',
  rotulo,
  tipo: 'texto',
  obrigatorio: true,
  max: 100,
});

export const DEFINICOES: Record<NomeDoCadastro, Definicao> = {
  fornecedores: {
    cadastro: 'fornecedores',
    textos: T.fornecedores,
    nome: (r) => texto(r, 'razaoSocial'),
    colunas: [
      { chave: 'nome', titulo: T.fornecedores.colunas.nome, valor: (r) => texto(r, 'razaoSocial') },
      { chave: 'cnpj', titulo: T.fornecedores.colunas.cnpj, valor: (r) => texto(r, 'cnpj') },
      {
        chave: 'contato',
        titulo: T.fornecedores.colunas.contato,
        valor: (r) => texto(r, 'contatoNome'),
      },
      {
        chave: 'telefone',
        titulo: T.fornecedores.colunas.telefone,
        valor: (r) => texto(r, 'telefone'),
      },
    ],
    campos: [
      {
        nome: 'razaoSocial',
        rotulo: T.fornecedores.campos.razaoSocial,
        tipo: 'texto',
        obrigatorio: true,
        max: 200,
      },
      {
        nome: 'cnpj',
        rotulo: T.fornecedores.campos.cnpj,
        tipo: 'texto',
        max: 30,
        ajuda: T.fornecedores.campos.cnpjAjuda,
      },
      { nome: 'contatoNome', rotulo: T.fornecedores.campos.contatoNome, tipo: 'texto', max: 100 },
      { nome: 'telefone', rotulo: T.fornecedores.campos.telefone, tipo: 'texto', max: 30 },
      { nome: 'email', rotulo: T.fornecedores.campos.email, tipo: 'texto', max: 200 },
      ...CAMPOS_DE_ENDERECO,
    ],
  },
  categorias: {
    cadastro: 'categorias',
    textos: T.categorias,
    nome: (r) => texto(r, 'nome'),
    colunas: [{ chave: 'nome', titulo: T.categorias.colunas.nome, valor: (r) => texto(r, 'nome') }],
    campos: [soNome(T.categorias.campos.nome)],
  },
  pracas: {
    cadastro: 'pracas',
    textos: T.pracas,
    nome: (r) => texto(r, 'nome'),
    colunas: [{ chave: 'nome', titulo: T.pracas.colunas.nome, valor: (r) => texto(r, 'nome') }],
    campos: [soNome(T.pracas.campos.nome)],
  },
  semanas: {
    cadastro: 'semanas',
    textos: T.semanas,
    nome: (r) => T.semanas.nome(texto(r, 'numero')),
    colunas: [
      { chave: 'numero', titulo: T.semanas.colunas.numero, valor: (r) => texto(r, 'numero') },
      {
        chave: 'inicio',
        titulo: T.semanas.colunas.inicio,
        valor: (r) => dataNaTela(texto(r, 'inicio')),
      },
      { chave: 'fim', titulo: T.semanas.colunas.fim, valor: (r) => dataNaTela(texto(r, 'fim')) },
      {
        chave: 'situacao',
        titulo: T.semanas.colunas.situacao,
        valor: (r) => T.semanas.situacao[texto(r, 'situacao')] ?? texto(r, 'situacao'),
      },
    ],
    campos: [
      { nome: 'numero', rotulo: T.semanas.campos.numero, tipo: 'numero', obrigatorio: true },
      { nome: 'inicio', rotulo: T.semanas.campos.inicio, tipo: 'data', obrigatorio: true },
      { nome: 'fim', rotulo: T.semanas.campos.fim, tipo: 'data', obrigatorio: true },
      {
        nome: 'situacao',
        rotulo: T.semanas.campos.situacao,
        tipo: 'selecao',
        padrao: 'ABERTA',
        opcoes: [
          { valor: 'ABERTA', rotulo: 'Aberta' },
          { valor: 'FECHADA', rotulo: 'Fechada' },
        ],
      },
    ],
  },
  restricoes: {
    cadastro: 'restricoes',
    textos: T.restricoes,
    nome: (r) => texto(r, 'descricao'),
    colunas: [
      { chave: 'codigo', titulo: T.restricoes.colunas.codigo, valor: (r) => texto(r, 'codigo') },
      {
        chave: 'descricao',
        titulo: T.restricoes.colunas.descricao,
        valor: (r) => texto(r, 'descricao'),
      },
      {
        chave: 'bloqueia',
        titulo: T.restricoes.colunas.bloqueia,
        valor: (r) => (r.bloqueiaRemessa === true ? T.sim : T.nao),
      },
    ],
    campos: [
      {
        nome: 'codigo',
        rotulo: T.restricoes.campos.codigo,
        tipo: 'texto',
        obrigatorio: true,
        max: 30,
      },
      {
        nome: 'descricao',
        rotulo: T.restricoes.campos.descricao,
        tipo: 'texto',
        obrigatorio: true,
        max: 200,
      },
      { nome: 'bloqueiaRemessa', rotulo: T.restricoes.campos.bloqueiaRemessa, tipo: 'sim-nao' },
    ],
  },
  clientes: {
    cadastro: 'clientes',
    textos: T.clientes,
    nome: (r) => texto(r, 'nome'),
    colunas: [
      { chave: 'nome', titulo: T.clientes.colunas.nome, valor: (r) => texto(r, 'nome') },
      { chave: 'cpf', titulo: T.clientes.colunas.cpf, valor: (r) => texto(r, 'cpf') },
      {
        chave: 'telefone',
        titulo: T.clientes.colunas.telefone,
        valor: (r) => texto(r, 'telefone'),
      },
      {
        chave: 'whatsapp',
        titulo: T.clientes.colunas.whatsapp,
        valor: (r) => texto(r, 'whatsapp'),
      },
    ],
    campos: [
      { nome: 'nome', rotulo: T.clientes.campos.nome, tipo: 'texto', obrigatorio: true, max: 200 },
      {
        nome: 'cpf',
        rotulo: T.clientes.campos.cpf,
        tipo: 'texto',
        max: 20,
        ajuda: T.clientes.campos.cpfAjuda,
      },
      { nome: 'telefone', rotulo: T.clientes.campos.telefone, tipo: 'texto', max: 30 },
      { nome: 'whatsapp', rotulo: T.clientes.campos.whatsapp, tipo: 'texto', max: 30 },
      {
        nome: 'vendedorResponsavelId',
        rotulo: T.clientes.campos.vendedorResponsavelId,
        tipo: 'vendedor',
      },
      { nome: 'observacoes', rotulo: T.clientes.campos.observacoes, tipo: 'area', max: 2000 },
      ...CAMPOS_DE_ENDERECO,
    ],
  },
};

export interface PaginaDeRegistros {
  itens: Registro[];
  pagina: number;
  tamanho: number;
  total: number;
}

export function lerPagina(bruto: unknown): PaginaDeRegistros {
  const p = (typeof bruto === 'object' && bruto !== null ? bruto : {}) as Record<string, unknown>;
  const itens = (Array.isArray(p.itens) ? p.itens : [])
    .filter(
      (i): i is Registro =>
        typeof i === 'object' && i !== null && typeof (i as Registro).id === 'string',
    )
    .map((i) => ({ ...i, version: typeof i.version === 'number' ? i.version : 0 }));
  return {
    itens,
    pagina: typeof p.pagina === 'number' ? p.pagina : 0,
    tamanho: typeof p.tamanho === 'number' && p.tamanho > 0 ? p.tamanho : 20,
    total: typeof p.total === 'number' ? p.total : itens.length,
  };
}

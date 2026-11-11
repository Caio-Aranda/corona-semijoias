/**
 * Os cinco relatórios de maleta da dona (tarefa 3.7, glossário "Relatórios da maleta"): nome na tela,
 * filtros, colunas, totais e a ficha que cada linha abre. A tela e a exportação usam esta definição. Os
 * dados vêm da API da tarefa 3.4; Dólar e Guarani chegam prontos em cada linha, pela cotação do dia em
 * que a maleta foi ENVIADA (nunca a de hoje), e o rodapé traz a soma já convertida.
 */
import type { NomeDoRelatorio, NomeDoRelatorioDeMaleta } from '../../shared/relatorios';
import { TEXTOS_DE_MALETAS } from '../textos-maletas';
import type { DefinicaoDeRelatorio, ExtraDoRelatorio } from './relatorios';

const TR = TEXTOS_DE_MALETAS.relatorios;

const CONVERTIDO = { dolar: 'valorEmDolar', guarani: 'valorEmGuarani', data: 'dataDaCotacao' };
const COTACAO = {
  chave: 'dataDaCotacao',
  titulo: 'Cotação do dia do envio',
  tipo: 'data',
} as const;
const FOTO = { chave: 'fotoPrincipalHash', titulo: 'Foto', tipo: 'foto' } as const;

const TIPO: ExtraDoRelatorio = {
  chave: 'tipo',
  rotulo: 'Tipo',
  tipo: 'escolha',
  qualquer: 'Todos',
  opcoes: [
    { valor: 'NOVA', rotulo: 'Nova' },
    { valor: 'REPOSICAO', rotulo: 'Reposição' },
  ],
};
const PRAZO: ExtraDoRelatorio = {
  chave: 'diasDeAtraso',
  rotulo: 'Prazo para atrasar (dias)',
  tipo: 'inteiro',
  padrao: 7,
};

const FILTROS_DA_MALETA = ['busca', 'semana', 'vendedor', 'praca', 'periodo', 'categoria'] as const;
const BUSCA = 'Buscar por vendedor, peça ou código';
const SITUACOES_DAS_REMESSAS = [
  { valor: '', rotulo: 'Enviadas' },
  { valor: 'CANCELADAS', rotulo: 'Canceladas' },
  { valor: 'TODAS', rotulo: 'Todas (canceladas marcadas)' },
];

const texto = (l: Record<string, unknown>, campo: string) =>
  typeof l[campo] === 'string' ? l[campo] : '';

const REMESSAS_COLUNAS: DefinicaoDeRelatorio['colunas'] = [
  { chave: 'numero', titulo: 'Número da maleta', tipo: 'texto' },
  { chave: 'vendedor', titulo: 'Vendedor', tipo: 'texto' },
  { chave: 'semana', titulo: 'Semana', tipo: 'inteiro' },
  { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
  { chave: 'tipoNaTela', titulo: 'Tipo', tipo: 'texto' },
  { chave: 'enviadaEm', titulo: 'Enviada', tipo: 'dataHora' },
  { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
  { chave: 'valorEmReais', titulo: 'Preço da maleta', tipo: 'dinheiro', convertido: CONVERTIDO },
  COTACAO,
  { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
];

export const RELATORIOS_DE_MALETA: Record<NomeDoRelatorioDeMaleta, DefinicaoDeRelatorio> = {
  'maleta-remessas': {
    nome: 'maleta-remessas',
    grupo: 'maletas',
    titulo: 'Maletas por vendedor e semana',
    explicacao:
      'Uma linha por maleta enviada, com o vendedor, a semana, as peças e o preço. As maletas canceladas aparecem marcadas e ficam fora do total.',
    filtros: [...FILTROS_DA_MALETA, 'situacao'],
    extras: [TIPO],
    situacoes: SITUACOES_DAS_REMESSAS,
    buscaRotulo: BUSCA,
    colunas: REMESSAS_COLUNAS,
    totaisDeValor: {
      geral: 'valorEmReais',
      novas: 'valorEmReais',
      reposicoes: 'valorEmReais',
      canceladas: 'valorEmReais',
    },
    rotulosDosTotais: {
      geral: 'Maletas enviadas (valem no total)',
      novas: 'Maletas novas',
      reposicoes: 'Reposições',
      canceladas: 'Maletas canceladas (fora do total)',
    },
    fichaDaMaleta: 'id',
    itens: { um: 'maleta', varios: 'maletas' },
    vazio: 'Nenhuma maleta com estes filtros.',
  },
  'maleta-reposicoes': {
    nome: 'maleta-reposicoes',
    grupo: 'maletas',
    titulo: 'Reposições',
    explicacao: 'As maletas de reposição enviadas, por vendedor e semana.',
    filtros: [...FILTROS_DA_MALETA, 'situacao'],
    situacoes: SITUACOES_DAS_REMESSAS,
    buscaRotulo: BUSCA,
    colunas: REMESSAS_COLUNAS,
    totaisDeValor: { geral: 'valorEmReais', canceladas: 'valorEmReais' },
    rotulosDosTotais: {
      geral: 'Reposições enviadas (valem no total)',
      canceladas: 'Reposições canceladas (fora do total)',
    },
    fichaDaMaleta: 'id',
    itens: { um: 'reposição', varios: 'reposições' },
    vazio: 'Nenhuma reposição com estes filtros.',
  },
  'maleta-saldo': {
    nome: 'maleta-saldo',
    grupo: 'maletas',
    titulo: 'O que ainda está com o vendedor, peça a peça',
    explicacao:
      'Cada peça de cada maleta: quantas foram enviadas, quantas voltaram e quantas ainda estão com o vendedor.',
    filtros: [...FILTROS_DA_MALETA, 'situacao'],
    extras: [TIPO],
    situacoes: [
      { valor: '', rotulo: 'Com peças' },
      { valor: 'ZERADAS', rotulo: 'Sem peças' },
      { valor: 'TODAS', rotulo: 'Todas' },
    ],
    buscaRotulo: 'Buscar por peça, código ou vendedor',
    colunas: [
      { chave: 'numero', titulo: 'Número da maleta', tipo: 'texto' },
      { chave: 'vendedor', titulo: 'Vendedor', tipo: 'texto' },
      FOTO,
      { chave: 'nome', titulo: 'Peça', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'enviada', titulo: 'Enviada', tipo: 'inteiro' },
      { chave: 'devolvida', titulo: 'Devolvida', tipo: 'inteiro' },
      { chave: 'saldo', titulo: 'Saldo', tipo: 'inteiro' },
      {
        chave: 'valorEmReais',
        titulo: 'Valor com o vendedor',
        tipo: 'dinheiro',
        convertido: CONVERTIDO,
      },
      COTACAO,
    ],
    totaisDeValor: { saldo: 'valorEmReais' },
    rotulosDosTotais: {
      enviadas: 'Peças enviadas',
      devolvidas: 'Peças devolvidas à dona',
      saldo: 'Peças ainda com o vendedor',
    },
    fichaDaMaleta: 'remessaId',
    itens: { um: 'linha', varios: 'linhas' },
    vazio: 'Nenhuma peça com estes filtros.',
  },
  'maleta-mercadoria': {
    nome: 'maleta-mercadoria',
    grupo: 'maletas',
    titulo: 'Mercadoria com cada vendedor',
    explicacao:
      'Quantas maletas e peças, e quanto em mercadoria, cada vendedor tem agora, o limite dele e quanto ainda sobra.',
    filtros: [...FILTROS_DA_MALETA],
    extras: [TIPO, PRAZO],
    buscaRotulo: 'Buscar por vendedor ou peça',
    colunas: [
      { chave: 'vendedor', titulo: 'Vendedor', tipo: 'texto' },
      { chave: 'maletasEmAberto', titulo: 'Maletas em aberto', tipo: 'inteiro' },
      { chave: 'maletasAtrasadas', titulo: 'Maletas atrasadas', tipo: 'inteiro' },
      { chave: 'pecasEmAberto', titulo: 'Peças com o vendedor', tipo: 'inteiro' },
      {
        chave: 'valorEmReais',
        titulo: 'Mercadoria em maletas',
        tipo: 'dinheiro',
        convertido: CONVERTIDO,
      },
      { chave: 'limite', titulo: 'Limite', tipo: 'dinheiro', soReal: true },
      { chave: 'sobraDoLimite', titulo: 'Sobra do limite', tipo: 'dinheiro', soReal: true },
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: { emAberto: 'valorEmReais' },
    rotulosDosTotais: {
      emAberto: 'Mercadoria em maletas',
      comAtraso: 'Vendedores com maleta atrasada',
      acimaDoLimite: 'Vendedores acima do limite',
    },
    itens: { um: 'vendedor', varios: 'vendedores' },
    vazio: 'Nenhum vendedor com maleta com estes filtros.',
  },
  'maleta-em-aberto': {
    nome: 'maleta-em-aberto',
    grupo: 'maletas',
    titulo: 'Maletas em aberto e atrasadas',
    explicacao:
      'As maletas que ainda têm peças com o vendedor. Ficam como "Atrasada" as enviadas há mais dias do que o prazo escolhido.',
    filtros: [...FILTROS_DA_MALETA, 'situacao'],
    extras: [TIPO, PRAZO],
    situacoes: [
      { valor: '', rotulo: 'Todas' },
      { valor: 'ATRASADAS', rotulo: 'Atrasadas' },
      { valor: 'NO_PRAZO', rotulo: 'No prazo' },
    ],
    buscaRotulo: BUSCA,
    colunas: [
      { chave: 'numero', titulo: 'Número da maleta', tipo: 'texto' },
      { chave: 'vendedor', titulo: 'Vendedor', tipo: 'texto' },
      { chave: 'semana', titulo: 'Semana', tipo: 'inteiro' },
      { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'Tipo', tipo: 'texto' },
      { chave: 'enviadaEm', titulo: 'Enviada', tipo: 'dataHora' },
      { chave: 'diasDesdeOEnvio', titulo: 'Dias desde o envio', tipo: 'inteiro' },
      { chave: 'pecasEmAberto', titulo: 'Peças com o vendedor', tipo: 'inteiro' },
      {
        chave: 'valorEmReais',
        titulo: 'Valor com o vendedor',
        tipo: 'dinheiro',
        convertido: CONVERTIDO,
      },
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: { emAberto: 'valorEmReais', atrasadas: 'valorEmReais', noPrazo: 'valorEmReais' },
    rotulosDosTotais: {
      emAberto: 'Maletas em aberto',
      atrasadas: 'Maletas atrasadas',
      noPrazo: 'Maletas no prazo',
    },
    fichaDaMaleta: 'remessaId',
    itens: { um: 'maleta', varios: 'maletas' },
    vazio: 'Nenhuma maleta em aberto com estes filtros.',
  },
};

export const ORDEM_DOS_RELATORIOS_DE_MALETA: NomeDoRelatorio[] = [
  'maleta-remessas',
  'maleta-saldo',
  'maleta-mercadoria',
  'maleta-em-aberto',
  'maleta-reposicoes',
];

/** Acrescenta às linhas o texto da tela (tipo e situação em palavras da loja; "Atrasada" é texto, não só cor). */
export function prepararLinhaDeMaleta(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
): Record<string, unknown> {
  const tipoNaTela = TR.tipos[texto(linha, 'tipo')] ?? '';
  switch (nome) {
    case 'maleta-remessas':
    case 'maleta-reposicoes':
      return {
        ...linha,
        tipoNaTela,
        situacaoNaTela: linha.cancelada === true ? TR.cancelada : TR.enviada,
      };
    case 'maleta-em-aberto':
      return {
        ...linha,
        tipoNaTela,
        situacaoNaTela: linha.atrasada === true ? TR.atrasada : TR.noPrazo,
      };
    case 'maleta-mercadoria': {
      const atrasadas = typeof linha.maletasAtrasadas === 'number' ? linha.maletasAtrasadas : 0;
      const acima = texto(linha, 'sobraDoLimite').startsWith('-');
      return {
        ...linha,
        situacaoNaTela: [atrasadas > 0 ? TR.comAtraso : '', acima ? TR.acimaDoLimite : '']
          .filter(Boolean)
          .join('; '),
      };
    }
    case 'maleta-saldo':
      return { ...linha, tipoNaTela };
    default:
      return linha;
  }
}

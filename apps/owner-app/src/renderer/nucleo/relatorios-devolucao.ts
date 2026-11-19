/**
 * Os sete relatórios de devolução e troca da dona (tarefa 6.10; glossário "Relatórios de devolução e troca"):
 * nome na tela, filtros, colunas e totais. A tela e a exportação usam esta mesma definição. Os dados vêm da API da
 * tarefa 6.10; Dólar e Guarani chegam prontos em cada linha, pela cotação do dia de CADA lançamento (da devolução, da
 * troca, de quando o crédito nasceu, do dinheiro devolvido, do pedido), e o rodapé traz a soma já convertida.
 * Nenhum custo aparece aqui.
 */
import type { NomeDoRelatorio, NomeDoRelatorioDeDevolucao } from '../../shared/relatorios';
import { TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO } from '../textos-relatorios-devolucao';
import type { DefinicaoDeRelatorio, ExtraDoRelatorio } from './relatorios';
import { idiomaAtual } from '../idioma/idioma';
import { traducirDefinicion } from '../idioma/es/relatorios-mapa';

const T = TEXTOS_DE_RELATORIOS_DE_DEVOLUCAO;

const COTACAO = {
  chave: 'dataDaCotacao',
  titulo: 'Cotação do dia',
  tipo: 'data',
} as const;
const FOTO = { chave: 'fotoPrincipalHash', titulo: 'Foto', tipo: 'foto' } as const;

/** Dólar e Guarani de uma coluna de dinheiro, pelos campos da linha que os trazem. */
const em = (base: string) => ({
  dolar: `${base}EmDolar`,
  guarani: `${base}EmGuarani`,
  data: 'dataDaCotacao',
});

const MOTIVO: ExtraDoRelatorio = {
  chave: 'motivo',
  rotulo: 'Motivo da devolução',
  tipo: 'escolha',
  qualquer: 'Todos',
  opcoes: [
    { valor: 'ARREPENDIMENTO', rotulo: 'Desistência' },
    { valor: 'DEFEITO', rotulo: 'Defeito' },
    { valor: 'TAMANHO', rotulo: 'Não serviu' },
    { valor: 'OUTRO', rotulo: 'Outro' },
  ],
};

const FILTROS = ['busca', 'vendedor', 'periodo'] as const;

const SITUACOES_DAS_DEVOLUCOES = [
  { valor: '', rotulo: 'Feitas' },
  { valor: 'EM_REVISAO', rotulo: 'Para revisão' },
  { valor: 'MANTIDA', rotulo: 'Mantidas' },
  { valor: 'NAO_ACEITA', rotulo: 'Não aceitas' },
  { valor: 'CANCELADAS', rotulo: 'Canceladas' },
  { valor: 'TODAS', rotulo: 'Todas (canceladas marcadas)' },
];

export const RELATORIOS_DE_DEVOLUCAO: Record<NomeDoRelatorioDeDevolucao, DefinicaoDeRelatorio> = {
  'devolucao-por-motivo': {
    nome: 'devolucao-por-motivo',
    grupo: 'devolucoes',
    titulo: 'Devoluções por representante e motivo',
    explicacao:
      'Uma linha por devolução, com o representante, o cliente, o motivo e o valor. As canceladas aparecem marcadas e ficam fora do total.',
    filtros: [...FILTROS, 'situacao'],
    extras: [
      MOTIVO,
      {
        chave: 'tipo',
        rotulo: 'O que foi',
        tipo: 'escolha',
        qualquer: 'Devoluções e trocas',
        opcoes: [
          { valor: 'DEVOLUCAO', rotulo: 'Só devoluções' },
          { valor: 'TROCA', rotulo: 'Só trocas' },
        ],
      },
    ],
    situacoes: SITUACOES_DAS_DEVOLUCOES,
    buscaRotulo: 'Buscar por representante ou cliente',
    colunas: [
      { chave: 'devolvidaEm', titulo: 'Devolvida em', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'cliente', titulo: 'Cliente', tipo: 'texto' },
      { chave: 'motivoNaTela', titulo: 'Motivo', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'O que foi', tipo: 'texto' },
      { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
      {
        chave: 'valorDevolvido',
        titulo: 'Valor devolvido',
        tipo: 'dinheiro',
        convertido: em('valorDevolvido'),
      },
      {
        chave: 'valorReembolsado',
        titulo: 'Voltou em dinheiro',
        tipo: 'dinheiro',
        convertido: em('valorReembolsado'),
      },
      {
        chave: 'valorEmCredito',
        titulo: 'Voltou em crédito',
        tipo: 'dinheiro',
        convertido: em('valorEmCredito'),
      },
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      geral: 'valorDevolvido',
      arrependimento: 'valorDevolvido',
      defeito: 'valorDevolvido',
      tamanho: 'valorDevolvido',
      outro: 'valorDevolvido',
      'em-dinheiro': 'valorReembolsado',
      'em-credito': 'valorEmCredito',
      'para-revisao': 'valorDevolvido',
      'nao-aceitas': 'valorDevolvido',
      'de-troca': 'valorDevolvido',
      canceladas: 'valorDevolvido',
    },
    rotulosDosTotais: {
      geral: 'Devoluções feitas (valem no total)',
      arrependimento: 'Por desistência',
      defeito: 'Por defeito',
      tamanho: 'Por não ter servido',
      outro: 'Por outro motivo',
      'em-dinheiro': 'Voltou em dinheiro',
      'em-credito': 'Voltou em crédito',
      'para-revisao': 'Para revisão',
      'nao-aceitas': 'Não aceitas',
      'de-troca': 'Trocas feitas',
      canceladas: 'Devoluções canceladas (fora do total)',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'devolução', varios: 'devoluções' },
    vazio: 'Nenhuma devolução com estes filtros.',
  },
  'devolucao-por-vendedor': {
    nome: 'devolucao-por-vendedor',
    grupo: 'devolucoes',
    titulo: 'Quanto cada representante devolveu do que vendeu',
    explicacao:
      'Por representante: quanto vendeu, quanto o cliente devolveu e a porcentagem. Aparece "Acima do limite" quando passa do que você definiu em Garantia e limites. A troca conta como uma devolução e uma venda.',
    filtros: [...FILTROS],
    buscaRotulo: 'Buscar por representante',
    colunas: [
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'vendas', titulo: 'Vendas', tipo: 'inteiro' },
      { chave: 'vendido', titulo: 'Vendido', tipo: 'dinheiro', convertido: em('vendido') },
      { chave: 'devolucoes', titulo: 'Devoluções', tipo: 'inteiro' },
      { chave: 'trocas', titulo: 'Trocas', tipo: 'inteiro' },
      { chave: 'devolvido', titulo: 'Devolvido', tipo: 'dinheiro', convertido: em('devolvido') },
      { chave: 'porDesistencia', titulo: 'Por desistência', tipo: 'inteiro' },
      { chave: 'porDefeito', titulo: 'Por defeito', tipo: 'inteiro' },
      { chave: 'porTamanho', titulo: 'Por não ter servido', tipo: 'inteiro' },
      { chave: 'porOutro', titulo: 'Por outro motivo', tipo: 'inteiro' },
      { chave: 'porcentagemDevolvida', titulo: 'Porcentagem devolvida', tipo: 'percentual' },
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      vendido: 'vendido',
      devolvido: 'devolvido',
      'com-devolucao': 'devolvido',
      acima: 'devolvido',
    },
    rotulosDosTotais: {
      vendido: 'Vendido',
      devolvido: 'Devolvido',
      'com-devolucao': 'Representantes com devolução',
      acima: 'Representantes acima do limite',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'representante', varios: 'representantes' },
    vazio: 'Nenhum representante com venda ou devolução neste período.',
  },
  'devolucao-defeitos': {
    nome: 'devolucao-defeitos',
    grupo: 'devolucoes',
    titulo: 'Peças que voltaram com defeito',
    explicacao:
      'Uma linha por peça que o cliente devolveu com defeito, com quantas vezes, de quantos representantes e o valor.',
    filtros: [...FILTROS, 'categoria'],
    buscaRotulo: 'Buscar por peça ou código',
    colunas: [
      FOTO,
      { chave: 'nome', titulo: 'Peça', tipo: 'texto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'pecas', titulo: 'Quantas voltaram', tipo: 'inteiro' },
      { chave: 'devolucoes', titulo: 'Devoluções', tipo: 'inteiro' },
      { chave: 'vendedores', titulo: 'Representantes', tipo: 'inteiro' },
      { chave: 'valor', titulo: 'Valor', tipo: 'dinheiro', convertido: em('valor') },
      { chave: 'ultimaVez', titulo: 'Última vez', tipo: 'dataHora' },
      COTACAO,
    ],
    totaisDeValor: { geral: 'valor' },
    rotulosDosTotais: { geral: 'Peças que voltaram com defeito' },
    notaDaCotacao: T.cotacao,
    itens: { um: 'peça', varios: 'peças' },
    vazio: 'Nenhuma peça voltou com defeito neste período.',
  },
  'devolucao-trocas': {
    nome: 'devolucao-trocas',
    grupo: 'devolucoes',
    titulo: 'Trocas feitas',
    explicacao:
      'Uma linha por troca: o que voltou, o que saiu, a diferença a pagar e o que ficou de crédito. As canceladas ficam marcadas e fora do total.',
    filtros: [...FILTROS, 'situacao'],
    situacoes: [
      { valor: '', rotulo: 'Feitas' },
      { valor: 'CANCELADAS', rotulo: 'Canceladas' },
      { valor: 'TODAS', rotulo: 'Todas' },
    ],
    buscaRotulo: 'Buscar por representante ou cliente',
    colunas: [
      { chave: 'trocadaEm', titulo: 'Trocada em', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'cliente', titulo: 'Cliente', tipo: 'texto' },
      { chave: 'pecasQueVoltaram', titulo: 'Peças que voltaram', tipo: 'inteiro' },
      { chave: 'pecasQueSairam', titulo: 'Peças que saíram', tipo: 'inteiro' },
      {
        chave: 'valorDevolvido',
        titulo: 'Valor que voltou',
        tipo: 'dinheiro',
        convertido: em('valorDevolvido'),
      },
      {
        chave: 'totalDaVendaNova',
        titulo: 'Valor que saiu',
        tipo: 'dinheiro',
        convertido: em('totalDaVendaNova'),
      },
      {
        chave: 'diferenca',
        titulo: 'Diferença (a pagar ou de crédito)',
        tipo: 'dinheiro',
        convertido: em('diferenca'),
      },
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      geral: 'valorDevolvido',
      'a-pagar': 'diferenca',
      credito: 'diferenca',
      canceladas: 'valorDevolvido',
    },
    rotulosDosTotais: {
      geral: 'Trocas feitas (valem no total)',
      'a-pagar': 'Diferença a pagar',
      credito: 'Ficou de crédito',
      canceladas: 'Trocas canceladas (fora do total)',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'troca', varios: 'trocas' },
    vazio: 'Nenhuma troca com estes filtros.',
  },
  'devolucao-creditos': {
    nome: 'devolucao-creditos',
    grupo: 'devolucoes',
    titulo: 'Créditos dos clientes: em aberto e usados',
    explicacao:
      'Uma linha por crédito: quanto foi gerado, quanto já foi usado e quanto ainda está em aberto. Créditos gerados menos usados é igual ao que está em aberto.',
    filtros: [...FILTROS, 'situacao'],
    extras: [
      {
        chave: 'tipo',
        rotulo: 'De onde veio',
        tipo: 'escolha',
        qualquer: 'Todos',
        opcoes: [
          { valor: 'TROCA', rotulo: 'Troca' },
          { valor: 'DEVOLUCAO', rotulo: 'Devolução' },
          { valor: 'TRANSFERENCIA', rotulo: 'Passado pela dona' },
        ],
      },
    ],
    situacoes: [
      { valor: '', rotulo: 'Em aberto e usados' },
      { valor: 'ABERTOS', rotulo: 'Só em aberto' },
      { valor: 'UTILIZADOS', rotulo: 'Só usados' },
      { valor: 'CANCELADOS', rotulo: 'Cancelados' },
      { valor: 'TODOS', rotulo: 'Todos' },
    ],
    buscaRotulo: 'Buscar por representante ou cliente',
    colunas: [
      { chave: 'geradoEm', titulo: 'Gerado em', tipo: 'dataHora' },
      { chave: 'cliente', titulo: 'Cliente', tipo: 'texto' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'origemNaTela', titulo: 'De onde veio', tipo: 'texto' },
      { chave: 'valor', titulo: 'Crédito gerado', tipo: 'dinheiro', convertido: em('valor') },
      { chave: 'utilizado', titulo: 'Já usado', tipo: 'dinheiro', convertido: em('utilizado') },
      { chave: 'saldo', titulo: 'Em aberto', tipo: 'dinheiro', convertido: em('saldo') },
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      gerados: 'valor',
      usados: 'utilizado',
      'em-aberto': 'saldo',
      cancelados: 'valor',
    },
    rotulosDosTotais: {
      gerados: 'Créditos gerados (valem no total)',
      usados: 'Créditos já usados',
      'em-aberto': 'Créditos em aberto',
      cancelados: 'Créditos cancelados (fora do total)',
    },
    notaDaCotacao: T.cotacaoDoCredito,
    itens: { um: 'crédito', varios: 'créditos' },
    vazio: 'Nenhum crédito com estes filtros.',
  },
  'devolucao-reembolsos': {
    nome: 'devolucao-reembolsos',
    grupo: 'devolucoes',
    titulo: 'Dinheiro devolvido aos clientes',
    explicacao:
      'Só o dinheiro que voltou ao cliente (dinheiro, transferência, cartão ou cheque); o crédito não entra. A devolução cancelada aparece com o valor negativo, para o total fechar.',
    filtros: [...FILTROS],
    extras: [
      MOTIVO,
      {
        chave: 'forma',
        rotulo: 'Forma',
        tipo: 'escolha',
        qualquer: 'Todas',
        opcoes: [
          { valor: 'DINHEIRO', rotulo: 'Dinheiro' },
          { valor: 'PIX', rotulo: 'Transferência' },
          { valor: 'CARTAO', rotulo: 'Cartão' },
          { valor: 'CHEQUE', rotulo: 'Cheque' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por representante ou cliente',
    colunas: [
      { chave: 'pagoEm', titulo: 'Quando', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'cliente', titulo: 'Cliente', tipo: 'texto' },
      { chave: 'motivoNaTela', titulo: 'Motivo', tipo: 'texto' },
      { chave: 'formaNaTela', titulo: 'Forma', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'O que foi', tipo: 'texto' },
      { chave: 'valor', titulo: 'Valor', tipo: 'dinheiro', convertido: em('valor') },
      COTACAO,
    ],
    totaisDeValor: {
      geral: 'valor',
      dinheiro: 'valor',
      pix: 'valor',
      cartao: 'valor',
      cheque: 'valor',
      desfeitos: 'valor',
    },
    rotulosDosTotais: {
      geral: 'Dinheiro devolvido (já sem o que foi desfeito)',
      dinheiro: 'Em dinheiro',
      pix: 'Em Transferência',
      cartao: 'No cartão',
      cheque: 'Em cheque',
      desfeitos: 'Devoluções canceladas (o dinheiro voltou para a venda)',
    },
    notaDaCotacao: T.cotacaoDoDinheiro,
    itens: { um: 'lançamento', varios: 'lançamentos' },
    vazio: 'Nenhum dinheiro devolvido com estes filtros.',
  },
  'devolucao-autorizacoes': {
    nome: 'devolucao-autorizacoes',
    grupo: 'devolucoes',
    titulo: 'Pedidos de autorização',
    explicacao:
      'Os pedidos que os representantes fizeram para devolver uma peça fora do prazo ou acima do valor que você aprova antes, e o que aconteceu com cada um.',
    filtros: [...FILTROS, 'situacao'],
    extras: [
      MOTIVO,
      {
        chave: 'tipo',
        rotulo: 'Por que pediu',
        tipo: 'escolha',
        qualquer: 'Todos',
        opcoes: [
          { valor: 'FORA_DO_PRAZO', rotulo: 'Passou do prazo da garantia' },
          { valor: 'ACIMA_DO_VALOR', rotulo: 'Passou do valor' },
        ],
      },
    ],
    situacoes: [
      { valor: '', rotulo: 'Todos' },
      { valor: 'PEDIDA', rotulo: 'Esperando você' },
      { valor: 'APROVADA', rotulo: 'Aprovados' },
      { valor: 'USADA', rotulo: 'Usados' },
      { valor: 'NEGADA', rotulo: 'Negados' },
      { valor: 'VENCIDA', rotulo: 'Vencidos' },
    ],
    buscaRotulo: 'Buscar por representante ou cliente',
    colunas: [
      { chave: 'pedidaEm', titulo: 'Pedido em', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'cliente', titulo: 'Cliente', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'Por que pediu', tipo: 'texto' },
      { chave: 'motivoNaTela', titulo: 'Motivo da devolução', tipo: 'texto' },
      { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
      {
        chave: 'valorPedido',
        titulo: 'Valor',
        tipo: 'dinheiro',
        convertido: em('valorPedido'),
      },
      { chave: 'decididaEm', titulo: 'Decidido em', tipo: 'dataHora' },
      { chave: 'validaAte', titulo: 'Vale até', tipo: 'data' },
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      geral: 'valorPedido',
      esperando: 'valorPedido',
      aprovados: 'valorPedido',
      usados: 'valorPedido',
      negados: 'valorPedido',
      vencidos: 'valorPedido',
    },
    rotulosDosTotais: {
      geral: 'Pedidos feitos',
      esperando: 'Esperando você',
      aprovados: 'Aprovados (ainda valem)',
      usados: 'Usados',
      negados: 'Negados',
      vencidos: 'Vencidos',
    },
    notaDaCotacao: T.cotacaoDoPedido,
    itens: { um: 'pedido', varios: 'pedidos' },
    vazio: 'Nenhum pedido com estes filtros.',
  },
};

export const ORDEM_DOS_RELATORIOS_DE_DEVOLUCAO: NomeDoRelatorio[] = [
  'devolucao-por-motivo',
  'devolucao-por-vendedor',
  'devolucao-defeitos',
  'devolucao-trocas',
  'devolucao-creditos',
  'devolucao-reembolsos',
  'devolucao-autorizacoes',
];

const texto = (l: Record<string, unknown>, campo: string) =>
  typeof l[campo] === 'string' ? l[campo] : '';

/** Acrescenta às linhas o texto da tela: motivo, situação e forma em palavras da loja (nunca o nome técnico). */
export function prepararLinhaDeDevolucao(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
): Record<string, unknown> {
  const motivoNaTela = T.motivos[texto(linha, 'motivo')] ?? '';
  switch (nome) {
    case 'devolucao-por-motivo':
      return {
        ...linha,
        motivoNaTela,
        tipoNaTela: T.tipos[texto(linha, 'tipo')] ?? '',
        situacaoNaTela:
          linha.cancelada === true ? T.cancelada : (T.situacoes[texto(linha, 'situacao')] ?? ''),
      };
    case 'devolucao-por-vendedor':
      return { ...linha, situacaoNaTela: linha.acimaDoLimite === true ? T.acimaDoLimite : '' };
    case 'devolucao-trocas':
      return {
        ...linha,
        situacaoNaTela: texto(linha, 'situacao') === 'CANCELADA' ? T.cancelada : T.feita,
      };
    case 'devolucao-creditos':
      return {
        ...linha,
        origemNaTela: T.origens[texto(linha, 'origem')] ?? '',
        situacaoNaTela: T.situacoesDoCredito[texto(linha, 'situacao')] ?? '',
      };
    case 'devolucao-reembolsos':
      return {
        ...linha,
        motivoNaTela,
        formaNaTela: T.formas[texto(linha, 'forma')] ?? '',
        tipoNaTela: texto(linha, 'tipo') === 'CANCELAMENTO' ? T.devolucaoCancelada : T.devolucao,
      };
    case 'devolucao-autorizacoes':
      return {
        ...linha,
        motivoNaTela,
        tipoNaTela: T.porQue[texto(linha, 'tipo')] ?? '',
        situacaoNaTela: T.situacoesDoPedido[texto(linha, 'situacao')] ?? '',
      };
    default:
      return linha;
  }
}

// Em espanhol, os textos de cada definição saem da tabela de tradução (feita uma vez, ao abrir o programa).
if (idiomaAtual() === 'es') traducirDefinicion(RELATORIOS_DE_DEVOLUCAO);

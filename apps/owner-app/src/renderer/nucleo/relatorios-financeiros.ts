/**
 * Os nove relatórios financeiros da dona (tarefa 7.12; glossário "Relatórios financeiros"): nome na tela, filtros, colunas e
 * totais. A tela e a exportação usam esta mesma definição. Os dados vêm da API da tarefa 7.12; Dólar e Guarani chegam prontos
 * em cada linha, pela cotação do dia de CADA lançamento (a gravada no fechamento, no que nasce do acerto), e o rodapé traz a
 * soma já convertida. Tudo é gravado em Real. Nenhum custo, cheque, cartão ou entrada aparece aqui.
 */
import type { NomeDoRelatorio, NomeDoRelatorioFinanceiro } from '../../shared/relatorios';
import { TEXTOS_DE_RELATORIOS_FINANCEIROS } from '../textos-relatorios-financeiros';
import type { ColunaDoRelatorio } from './exportacao/grade';
import type { DefinicaoDeRelatorio, ExtraDoRelatorio } from './relatorios';
import { idiomaAtual } from '../idioma/idioma';
import { traducirDefinicion } from '../idioma/es/relatorios-mapa';

const T = TEXTOS_DE_RELATORIOS_FINANCEIROS;

const COTACAO = { chave: 'dataDaCotacao', titulo: 'Cotação do dia', tipo: 'data' } as const;

/** Coluna de dinheiro cujos campos são `<chave>`, `<chave>EmDolar` e `<chave>EmGuarani` (o acerto). */
const dinheiro = (chave: string, titulo: string): ColunaDoRelatorio => ({
  chave,
  titulo,
  tipo: 'dinheiro',
  convertido: { dolar: `${chave}EmDolar`, guarani: `${chave}EmGuarani`, data: 'dataDaCotacao' },
});

/** Coluna de dinheiro cujos campos são `<base>EmReais`, `<base>EmDolar` e `<base>EmGuarani` (os demais). */
const reais = (base: string, titulo: string): ColunaDoRelatorio => ({
  chave: `${base}EmReais`,
  titulo,
  tipo: 'dinheiro',
  convertido: { dolar: `${base}EmDolar`, guarani: `${base}EmGuarani`, data: 'dataDaCotacao' },
});

const FORMA: ExtraDoRelatorio = {
  chave: 'forma',
  rotulo: 'Forma de pagamento',
  tipo: 'escolha',
  qualquer: 'Todas',
  opcoes: [
    { valor: 'DINHEIRO', rotulo: 'Dinheiro' },
    { valor: 'PIX', rotulo: 'Transferência' },
    { valor: 'BOLETO', rotulo: 'Boleto' },
  ],
};

const QUEM_DEVE: ExtraDoRelatorio = {
  chave: 'devedor',
  rotulo: 'Quem deve',
  tipo: 'escolha',
  qualquer: 'Clientes e representantes',
  opcoes: [
    { valor: 'CLIENTE', rotulo: 'Só clientes' },
    { valor: 'VENDEDOR', rotulo: 'Só representantes' },
  ],
};

const SITUACOES_DA_CONTA = [
  { valor: '', rotulo: 'Todas' },
  { valor: 'ABERTA', rotulo: 'Em aberto' },
  { valor: 'PARCIAL', rotulo: 'Pagas em parte' },
  { valor: 'PAGA', rotulo: 'Pagas' },
  { valor: 'VENCIDA', rotulo: 'Vencidas' },
  { valor: 'CANCELADA', rotulo: 'Canceladas' },
];

const COLUNAS_DA_CONTA: ColunaDoRelatorio[] = [
  { chave: 'vencimento', titulo: 'Vence em', tipo: 'data' },
  { chave: 'quemDeve', titulo: 'Quem', tipo: 'texto' },
  { chave: 'parte', titulo: 'Nome', tipo: 'texto' },
  { chave: 'parcelaNaTela', titulo: 'Parcela', tipo: 'texto' },
  reais('valor', 'Valor'),
  reais('pago', 'Já pago'),
  reais('cancelado', 'Cancelado'),
  reais('emAberto', 'Falta'),
  COTACAO,
  { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
];

const TOTAIS_DA_CONTA = {
  valor: 'valorEmReais',
  pago: 'pagoEmReais',
  cancelado: 'canceladoEmReais',
  aberto: 'emAbertoEmReais',
  vencido: 'emAbertoEmReais',
};

const dinheiroDoLucro = (chave: string, titulo: string): ColunaDoRelatorio => ({
  chave,
  titulo,
  tipo: 'dinheiro',
  convertido: { dolar: `${chave}EmDolar`, guarani: `${chave}EmGuarani`, data: 'dataDaCotacao' },
});

const CUSTO_DO_CADASTRO: ColunaDoRelatorio = {
  chave: 'custoDoCadastro',
  titulo: 'Custo do cadastro',
  tipo: 'simNao',
};

const LINHAS_DO_RESULTADO: ExtraDoRelatorio = {
  chave: 'linha',
  rotulo: 'Qual linha do resultado',
  tipo: 'escolha',
  qualquer: 'Escolha uma linha',
  opcoes: [
    { valor: 'VENDAS', rotulo: 'Vendas' },
    { valor: 'DEVOLUCOES', rotulo: 'Devoluções e trocas' },
    { valor: 'CUSTO', rotulo: 'Custo das peças vendidas' },
    { valor: 'COMISSOES', rotulo: 'Comissões' },
    { valor: 'PERDAS', rotulo: 'Perdas' },
    { valor: 'COBRADO', rotulo: 'Cobrado do representante' },
    { valor: 'CAIXA', rotulo: 'Outras entradas e despesas da loja' },
  ],
};

export const RELATORIOS_FINANCEIROS: Record<NomeDoRelatorioFinanceiro, DefinicaoDeRelatorio> = {
  'financeiro-resultado': {
    nome: 'financeiro-resultado',
    grupo: 'financeiro',
    titulo: 'Resultado do período',
    explicacao:
      'Quanto a loja ganhou ou perdeu no período: vendas, devoluções, custo das peças, comissões, perdas, o que foi cobrado dos representantes e as despesas da loja. Escolha de que dia até que dia.',
    filtros: ['periodo'],
    buscaRotulo: 'Buscar',
    colunas: [{ chave: 'tituloNaTela', titulo: 'Linha', tipo: 'texto' }, reais('valor', 'Valor')],
    totaisDeValor: {},
    notaDaCotacao: T.cotacao,
    nota: T.resultadoNota,
    itens: { um: 'linha', varios: 'linhas' },
    vazio: 'Escolha o período para ver o resultado.',
  },
  'financeiro-resultado-registros': {
    nome: 'financeiro-resultado-registros',
    grupo: 'financeiro',
    titulo: 'Registros do resultado',
    explicacao:
      'Os registros que formam uma linha do resultado do período: a venda, a devolução, a comissão, a perda, a cobrança ou o lançamento do caixa. Escolha o período e a linha.',
    filtros: ['periodo'],
    extras: [LINHAS_DO_RESULTADO],
    buscaRotulo: 'Buscar',
    colunas: [
      { chave: 'data', titulo: 'Dia', tipo: 'data' },
      { chave: 'descricao', titulo: 'O que é', tipo: 'texto' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'produto', titulo: 'Peça', tipo: 'texto' },
      { chave: 'quantidade', titulo: 'Peças', tipo: 'inteiro' },
      { chave: 'custoUnitario', titulo: 'Custo de cada peça', tipo: 'dinheiro', soReal: true },
      CUSTO_DO_CADASTRO,
      reais('valor', 'Valor'),
    ],
    totaisDeValor: { total: 'valorEmReais' },
    rotulosDosTotais: { total: 'Total da linha' },
    notaDaCotacao: T.cotacao,
    nota: T.resultadoNota,
    itens: { um: 'registro', varios: 'registros' },
    vazio: 'Nenhum registro com estes filtros.',
  },
  'financeiro-lucro-produto': {
    nome: 'financeiro-lucro-produto',
    grupo: 'financeiro',
    titulo: 'Lucro por produto',
    explicacao:
      'Quanto cada peça deu de lucro no período: o que entrou, o custo das peças vendidas, o lucro e a margem. Escolha de que dia até que dia.',
    filtros: ['busca', 'vendedor', 'praca', 'categoria', 'periodo'],
    buscaRotulo: 'Buscar por peça, código ou representante',
    colunas: [
      { chave: 'fotoPrincipalHash', titulo: 'Foto', tipo: 'foto' },
      { chave: 'codigo', titulo: 'Código', tipo: 'texto' },
      { chave: 'nome', titulo: 'Peça', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'pecas', titulo: 'Peças', tipo: 'inteiro' },
      dinheiroDoLucro('receita', 'Receita líquida'),
      dinheiroDoLucro('custo', 'Custo das peças'),
      dinheiroDoLucro('lucro', 'Lucro'),
      { chave: 'margem', titulo: 'Margem', tipo: 'percentual' },
      CUSTO_DO_CADASTRO,
    ],
    totaisDeValor: { receita: 'receita', custo: 'custo', lucro: 'lucro' },
    rotulosDosTotais: {
      receita: 'Receita líquida',
      custo: 'Custo das peças vendidas',
      lucro: 'Lucro',
    },
    notaDaCotacao: T.cotacao,
    nota: T.lucroNota,
    itens: { um: 'peça', varios: 'peças' },
    vazio: 'Nenhuma peça vendida neste período.',
  },
  'financeiro-lucro-vendedor': {
    nome: 'financeiro-lucro-vendedor',
    grupo: 'financeiro',
    titulo: 'Lucro por representante',
    explicacao:
      'Quanto cada representante deixou de lucro no período: receita líquida menos o custo das peças e menos a comissão do acerto. Escolha de que dia até que dia.',
    filtros: ['busca', 'vendedor', 'praca', 'periodo'],
    buscaRotulo: 'Buscar por representante',
    colunas: [
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'praca', titulo: 'Praça', tipo: 'texto' },
      dinheiroDoLucro('receita', 'Receita líquida'),
      dinheiroDoLucro('custo', 'Custo das peças'),
      dinheiroDoLucro('lucroBruto', 'Lucro bruto'),
      dinheiroDoLucro('comissao', 'Comissão'),
      dinheiroDoLucro('lucro', 'Lucro'),
      CUSTO_DO_CADASTRO,
    ],
    totaisDeValor: {
      receita: 'receita',
      custo: 'custo',
      lucroBruto: 'lucroBruto',
      comissao: 'comissao',
      lucro: 'lucro',
    },
    rotulosDosTotais: {
      receita: 'Receita líquida',
      custo: 'Custo das peças vendidas',
      lucroBruto: 'Lucro bruto',
      comissao: 'Comissão',
      lucro: 'Lucro',
    },
    notaDaCotacao: T.cotacao,
    nota: T.lucroNota,
    itens: { um: 'representante', varios: 'representantes' },
    vazio: 'Nenhum representante com venda ou comissão neste período.',
  },
  'financeiro-acertos': {
    nome: 'financeiro-acertos',
    grupo: 'financeiro',
    titulo: 'Acertos por representante e semana',
    explicacao:
      'Um acerto fechado por linha: quanto o representante vendeu, os descontos, o que voltou, a comissão, as faltas cobradas, o valor do acerto, quanto foi recebido e o que ficou devendo.',
    filtros: ['busca', 'semana', 'vendedor', 'periodo'],
    buscaRotulo: 'Buscar por representante',
    colunas: [
      { chave: 'fechadoEm', titulo: 'Fechado em', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'semana', titulo: 'Semana', tipo: 'inteiro' },
      dinheiro('vendidoBruto', 'Vendido'),
      dinheiro('descontos', 'Descontos'),
      dinheiro('devolvido', 'Devolvido'),
      dinheiro('comissao', 'Comissão'),
      { chave: 'percentual', titulo: 'Percentual da comissão', tipo: 'percentual' },
      dinheiro('faltasCobradas', 'Faltas cobradas'),
      dinheiro('liquido', 'Valor do acerto'),
      dinheiro('recebidoNoAcerto', 'Recebido no acerto'),
      dinheiro('ficouDevendo', 'Ficou devendo'),
      COTACAO,
    ],
    totaisDeValor: {
      vendido: 'vendidoBruto',
      descontos: 'descontos',
      devolvido: 'devolvido',
      comissao: 'comissao',
      faltas: 'faltasCobradas',
      liquido: 'liquido',
      recebido: 'recebidoNoAcerto',
      devendo: 'ficouDevendo',
    },
    rotulosDosTotais: {
      vendido: 'Vendido',
      descontos: 'Descontos concedidos',
      devolvido: 'Devolvido',
      comissao: 'Comissão do representante',
      faltas: 'Faltas cobradas',
      liquido: 'Valor dos acertos',
      recebido: 'Recebido nos acertos',
      devendo: 'Ficou devendo',
    },
    notaDaCotacao: T.cotacaoDoAcerto,
    itens: { um: 'acerto', varios: 'acertos' },
    vazio: 'Nenhum acerto fechado com estes filtros.',
  },
  'financeiro-pendencias': {
    nome: 'financeiro-pendencias',
    grupo: 'financeiro',
    titulo: 'Faltas e pendências',
    explicacao:
      'As peças que faltaram ou vieram com defeito nos acertos e o que aconteceu com cada uma: cobrada do representante, a loja assumiu, encontrada ou ainda em aberto.',
    filtros: ['busca', 'semana', 'vendedor', 'periodo', 'situacao'],
    extras: [
      {
        chave: 'tipo',
        rotulo: 'O que foi',
        tipo: 'escolha',
        qualquer: 'Todas',
        opcoes: [
          { valor: 'FALTA', rotulo: 'Peça que faltou' },
          { valor: 'DEFEITO', rotulo: 'Peça com defeito' },
          { valor: 'DEVOLUCAO_NAO_ACEITA', rotulo: 'Devolução não aceita' },
          { valor: 'CREDITO_SEM_SALDO_NAO_ACEITO', rotulo: 'Crédito sem saldo' },
        ],
      },
    ],
    situacoes: [
      { valor: '', rotulo: 'Todas' },
      { valor: 'ABERTA', rotulo: 'Em aberto' },
      { valor: 'COBRADA', rotulo: 'Cobradas do representante' },
      { valor: 'ASSUMIDA_PELA_LOJA', rotulo: 'A loja assumiu' },
      { valor: 'ENCONTRADA', rotulo: 'Peças encontradas' },
      { valor: 'APROVADA', rotulo: 'Defeitos aprovados' },
      { valor: 'RECUSADA', rotulo: 'Defeitos recusados' },
    ],
    buscaRotulo: 'Buscar por representante ou peça',
    colunas: [
      { chave: 'semana', titulo: 'Semana', tipo: 'inteiro' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'tipoNaTela', titulo: 'O que foi', tipo: 'texto' },
      { chave: 'produto', titulo: 'Peça', tipo: 'texto' },
      { chave: 'quantidade', titulo: 'Peças', tipo: 'inteiro' },
      reais('valor', 'Valor'),
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      geral: 'valorEmReais',
      abertas: 'valorEmReais',
      cobradas: 'valorEmReais',
      assumidas: 'valorEmReais',
      encontradas: 'valorEmReais',
    },
    rotulosDosTotais: {
      geral: 'Todas as pendências',
      abertas: 'Ainda em aberto',
      cobradas: 'Cobradas do representante',
      assumidas: 'A loja assumiu',
      encontradas: 'Peças encontradas',
    },
    notaDaCotacao: T.cotacaoDoAcerto,
    itens: { um: 'pendência', varios: 'pendências' },
    vazio: 'Nenhuma pendência com estes filtros.',
  },
  'financeiro-esperado-contado': {
    nome: 'financeiro-esperado-contado',
    grupo: 'financeiro',
    titulo: 'Esperado e contado',
    explicacao:
      'Por peça de cada maleta de um acerto: quantas eram esperadas, quantas foram contadas (boas e com defeito) e quantas faltaram, com o valor da falta.',
    filtros: ['busca', 'semana', 'vendedor', 'periodo'],
    buscaRotulo: 'Buscar por representante, peça ou referência',
    colunas: [
      { chave: 'semana', titulo: 'Semana', tipo: 'inteiro' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'produto', titulo: 'Peça', tipo: 'texto' },
      { chave: 'esperado', titulo: 'Esperadas', tipo: 'inteiro' },
      { chave: 'contadoBoa', titulo: 'Contadas boas', tipo: 'inteiro' },
      { chave: 'contadoDefeito', titulo: 'Contadas com defeito', tipo: 'inteiro' },
      { chave: 'falta', titulo: 'Faltaram', tipo: 'inteiro' },
      reais('valorDaFalta', 'Valor do que faltou'),
      COTACAO,
    ],
    totaisDeValor: { faltas: 'valorDaFaltaEmReais' },
    rotulosDosTotais: {
      esperado: 'Peças esperadas',
      contado: 'Peças contadas',
      faltas: 'Peças que faltaram',
    },
    notaDaCotacao: T.cotacaoDoAcerto,
    itens: { um: 'peça', varios: 'peças' },
    vazio: 'Nenhuma peça com estes filtros.',
  },
  'financeiro-a-receber': {
    nome: 'financeiro-a-receber',
    grupo: 'financeiro',
    titulo: 'Parcelas a receber',
    explicacao:
      'As parcelas que os clientes devem aos representantes e o que os representantes devem à loja: valor, o que já foi pago e o que falta.',
    filtros: ['busca', 'vendedor', 'periodo', 'situacao'],
    extras: [QUEM_DEVE],
    situacoes: SITUACOES_DA_CONTA,
    buscaRotulo: 'Buscar por cliente ou representante',
    colunas: COLUNAS_DA_CONTA,
    totaisDeValor: TOTAIS_DA_CONTA,
    rotulosDosTotais: {
      valor: 'Valor das parcelas',
      pago: 'Já recebido',
      cancelado: 'Cancelado',
      aberto: 'Falta receber',
      vencido: 'Vencido',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'parcela', varios: 'parcelas' },
    vazio: 'Nenhuma parcela com estes filtros.',
  },
  'financeiro-a-pagar': {
    nome: 'financeiro-a-pagar',
    grupo: 'financeiro',
    titulo: 'Parcelas a pagar',
    explicacao: 'As parcelas das compras que a loja deve aos fornecedores.',
    filtros: ['busca', 'fornecedor', 'periodo', 'situacao'],
    situacoes: SITUACOES_DA_CONTA,
    buscaRotulo: 'Buscar por fornecedor',
    colunas: COLUNAS_DA_CONTA.filter((c) => c.chave !== 'quemDeve'),
    totaisDeValor: TOTAIS_DA_CONTA,
    rotulosDosTotais: {
      valor: 'Valor das parcelas',
      pago: 'Já pago',
      cancelado: 'Cancelado',
      aberto: 'Falta pagar',
      vencido: 'Vencido',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'parcela', varios: 'parcelas' },
    vazio: 'Nenhuma parcela com estes filtros.',
  },
  'financeiro-inadimplencia': {
    nome: 'financeiro-inadimplencia',
    grupo: 'financeiro',
    titulo: 'Quem está devendo',
    explicacao:
      'Só as parcelas que já venceram e ainda não foram pagas, de clientes e de representantes, com os dias de atraso.',
    filtros: ['busca', 'vendedor', 'periodo'],
    extras: [QUEM_DEVE],
    buscaRotulo: 'Buscar por cliente ou representante',
    colunas: [
      { chave: 'vencimento', titulo: 'Venceu em', tipo: 'data' },
      { chave: 'diasDeAtraso', titulo: 'Dias de atraso', tipo: 'inteiro' },
      { chave: 'quemDeve', titulo: 'Quem', tipo: 'texto' },
      { chave: 'parte', titulo: 'Nome', tipo: 'texto' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'parcelaNaTela', titulo: 'Parcela', tipo: 'texto' },
      reais('emAberto', 'Falta pagar'),
      COTACAO,
    ],
    totaisDeValor: {
      vencido: 'emAbertoEmReais',
      ate30: 'emAbertoEmReais',
      de31a60: 'emAbertoEmReais',
      mais60: 'emAbertoEmReais',
    },
    rotulosDosTotais: {
      vencido: 'Total vencido',
      ate30: 'Vencido há até 30 dias',
      de31a60: 'Vencido há 31 a 60 dias',
      mais60: 'Vencido há mais de 60 dias',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'parcela vencida', varios: 'parcelas vencidas' },
    vazio: 'Ninguém está devendo parcela vencida com estes filtros.',
  },
  'financeiro-caixa': {
    nome: 'financeiro-caixa',
    grupo: 'financeiro',
    titulo: 'Fluxo do caixa',
    explicacao:
      'Tudo o que entrou e saiu do caixa. Entradas aparecem positivas e saídas negativas; o estorno vem marcado.',
    filtros: ['busca', 'vendedor', 'periodo'],
    extras: [
      {
        chave: 'tipo',
        rotulo: 'Entrada ou saída',
        tipo: 'escolha',
        qualquer: 'Todas',
        opcoes: [
          { valor: 'ENTRADA', rotulo: 'Só entradas' },
          { valor: 'SAIDA', rotulo: 'Só saídas' },
        ],
      },
      FORMA,
      {
        chave: 'origem',
        rotulo: 'De onde veio',
        tipo: 'escolha',
        qualquer: 'Todas',
        opcoes: [
          { valor: 'RECEBIMENTO_VENDEDOR', rotulo: 'Recebido do representante' },
          { valor: 'ACERTO_A_FAVOR_DO_VENDEDOR', rotulo: 'Pago ao representante no acerto' },
          { valor: 'PAGAMENTO_FORNECEDOR', rotulo: 'Pago a fornecedor' },
          { valor: 'MANUAL', rotulo: 'Lançado por você' },
        ],
      },
    ],
    buscaRotulo: 'Buscar pela descrição ou categoria',
    colunas: [
      { chave: 'data', titulo: 'Dia', tipo: 'data' },
      { chave: 'tipoNaTela', titulo: 'Entrada ou saída', tipo: 'texto' },
      { chave: 'formaNaTela', titulo: 'Forma', tipo: 'texto' },
      { chave: 'categoria', titulo: 'Categoria', tipo: 'texto' },
      { chave: 'origemNaTela', titulo: 'De onde veio', tipo: 'texto' },
      { chave: 'descricao', titulo: 'Descrição', tipo: 'texto' },
      reais('valor', 'Valor'),
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: { entradas: 'valorEmReais', saidas: 'valorEmReais', saldo: 'valorEmReais' },
    rotulosDosTotais: { entradas: 'Entradas', saidas: 'Saídas', saldo: 'Saldo do período' },
    notaDaCotacao: T.cotacao,
    itens: { um: 'lançamento', varios: 'lançamentos' },
    vazio: 'Nenhum lançamento no caixa com estes filtros.',
  },
  'financeiro-recebimentos': {
    nome: 'financeiro-recebimentos',
    grupo: 'financeiro',
    titulo: 'Recebimentos por forma de pagamento',
    explicacao:
      'O que os representantes repassaram à loja, separado em dinheiro, transferência e boleto. O estorno vem marcado e aparece negativo.',
    filtros: ['busca', 'vendedor', 'periodo'],
    extras: [
      FORMA,
      {
        chave: 'momento',
        rotulo: 'Quando',
        tipo: 'escolha',
        qualquer: 'Todos',
        opcoes: [
          { valor: 'ACERTO', rotulo: 'No acerto' },
          { valor: 'AVULSO', rotulo: 'Depois do acerto' },
        ],
      },
    ],
    buscaRotulo: 'Buscar por representante',
    colunas: [
      { chave: 'recebidoEm', titulo: 'Recebido em', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'formaNaTela', titulo: 'Forma', tipo: 'texto' },
      { chave: 'momentoNaTela', titulo: 'Quando', tipo: 'texto' },
      reais('valor', 'Valor'),
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: {
      DINHEIRO: 'valorEmReais',
      PIX: 'valorEmReais',
      BOLETO: 'valorEmReais',
      total: 'valorEmReais',
    },
    rotulosDosTotais: {
      DINHEIRO: 'Dinheiro',
      PIX: 'Transferência',
      BOLETO: 'Boleto',
      total: 'Total recebido',
    },
    notaDaCotacao: T.cotacao,
    itens: { um: 'recebimento', varios: 'recebimentos' },
    vazio: 'Nenhum recebimento com estes filtros.',
  },
  'financeiro-comissoes': {
    nome: 'financeiro-comissoes',
    grupo: 'financeiro',
    titulo: 'Comissões por representante e semana',
    explicacao:
      'A comissão de cada acerto fechado, com o vendido em Guarani, a meta da comissão e o percentual que ela decidiu (30% ou 40%). A comissão é paga abatendo no acerto.',
    filtros: ['busca', 'semana', 'vendedor', 'periodo'],
    buscaRotulo: 'Buscar por representante',
    colunas: [
      { chave: 'fechadoEm', titulo: 'Fechado em', tipo: 'dataHora' },
      { chave: 'vendedor', titulo: 'Representante', tipo: 'texto' },
      { chave: 'semana', titulo: 'Semana', tipo: 'inteiro' },
      { chave: 'vendidoEmGuarani', titulo: 'Vendido para a meta (Gs)', tipo: 'inteiro' },
      { chave: 'metaEmGuarani', titulo: 'Meta da comissão (Gs)', tipo: 'inteiro' },
      { chave: 'percentual', titulo: 'Percentual', tipo: 'percentual' },
      reais('comissao', 'Comissão'),
      COTACAO,
      { chave: 'situacaoNaTela', titulo: 'Situação', tipo: 'texto' },
    ],
    totaisDeValor: { comissao: 'comissaoEmReais', bateuAMeta: 'comissaoEmReais' },
    rotulosDosTotais: {
      comissao: 'Comissão paga',
      bateuAMeta: 'Comissão dos acertos que bateram a meta',
    },
    notaDaCotacao: T.cotacaoDoAcerto,
    itens: { um: 'acerto', varios: 'acertos' },
    vazio: 'Nenhum acerto fechado com estes filtros.',
  },
};

export const ORDEM_DOS_RELATORIOS_FINANCEIROS: NomeDoRelatorio[] = [
  'financeiro-acertos',
  'financeiro-pendencias',
  'financeiro-esperado-contado',
  'financeiro-comissoes',
  'financeiro-a-receber',
  'financeiro-a-pagar',
  'financeiro-inadimplencia',
  'financeiro-caixa',
  'financeiro-recebimentos',
  'financeiro-resultado',
  'financeiro-resultado-registros',
  'financeiro-lucro-produto',
  'financeiro-lucro-vendedor',
];

const texto = (l: Record<string, unknown>, campo: string) =>
  typeof l[campo] === 'string' ? l[campo] : '';

const numero = (l: Record<string, unknown>, campo: string) =>
  typeof l[campo] === 'number' ? String(l[campo]) : '';

/** Acrescenta às linhas o texto da tela (nunca o nome técnico da situação, da forma ou da origem). */
export function prepararLinhaFinanceira(
  nome: NomeDoRelatorio,
  linha: Record<string, unknown>,
): Record<string, unknown> {
  switch (nome) {
    case 'financeiro-pendencias':
      return {
        ...linha,
        tipoNaTela: T.tiposDaPendencia[texto(linha, 'tipo')] ?? '',
        situacaoNaTela: T.situacoesDaPendencia[texto(linha, 'situacao')] ?? '',
      };
    case 'financeiro-a-receber':
    case 'financeiro-a-pagar':
    case 'financeiro-inadimplencia':
      return {
        ...linha,
        quemDeve: T.quemDeve[texto(linha, 'devedorTipo')] ?? '',
        parcelaNaTela: `${numero(linha, 'parcela')}/${numero(linha, 'totalParcelas')}`,
        situacaoNaTela: T.situacoesDaConta[texto(linha, 'situacao')] ?? '',
      };
    case 'financeiro-caixa':
      return {
        ...linha,
        tipoNaTela: T.tiposDoCaixa[texto(linha, 'tipo')] ?? '',
        formaNaTela: T.formas[texto(linha, 'forma')] ?? '',
        origemNaTela: T.origensDoCaixa[texto(linha, 'origem')] ?? '',
        situacaoNaTela: linha.estorno === true ? T.estorno : '',
      };
    case 'financeiro-recebimentos':
      return {
        ...linha,
        formaNaTela: T.formas[texto(linha, 'forma')] ?? '',
        momentoNaTela: T.momentos[texto(linha, 'momento')] ?? '',
        situacaoNaTela: linha.estorno === true ? T.estorno : '',
      };
    case 'financeiro-comissoes':
      return { ...linha, situacaoNaTela: T.comissaoPaga };
    case 'financeiro-resultado':
      return {
        ...linha,
        tituloNaTela: `${T.sinaisDaLinha[texto(linha, 'operacao')] ?? ''}${texto(linha, 'titulo')}${
          linha.chave === 'RESULTADO' && texto(linha, 'valorEmReais').startsWith('-')
            ? ' (prejuízo)'
            : ''
        }`,
      };
    default:
      return linha;
  }
}

// Em espanhol, os textos de cada definição saem da tabela de tradução (feita uma vez, ao abrir o programa).
if (idiomaAtual() === 'es') traducirDefinicion(RELATORIOS_FINANCEIROS);

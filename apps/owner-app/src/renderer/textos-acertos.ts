/**
 * Textos das telas do acerto (tarefa 7.9). Linguagem da loja (acerto, maleta, vendedor, peça): sem termo técnico,
 * a dona é tratada por "você". Mensagens de regra vêm do servidor; aqui ficam só os textos das telas e os avisos
 * dos erros mais comuns, que dizem o que fazer.
 */
const pecas = (n: number) => (n === 1 ? '1 peça' : `${n} peças`);

export const TEXTOS_DE_ACERTOS = {
  menu: {
    grupo: 'Acerto',
    acertos: 'Acertos',
    pendencias: 'Pendências',
    comissao: 'Comissão e meta',
  },
  titulo: 'Acertos',
  fazerAcerto: 'Fazer acerto',
  pecas,
  situacoes: {
    EM_ACERTO: 'Em acerto',
    ACERTADO: 'Acertado',
    DESISTIDO: 'Desistido',
  } as Record<string, string>,
  lista: {
    comecar: 'Começar acerto',
    legenda: 'Acertos dos vendedores',
    vazia: 'Nenhum acerto por aqui. Use "Começar acerto" para fazer o primeiro.',
    filtros: {
      vendedor: 'Vendedor',
      todosVendedores: 'Todos os vendedores',
      situacao: 'Situação',
      todasSituacoes: 'Todas as situações',
    },
    colunas: {
      vendedor: 'Vendedor',
      semana: 'Semana',
      maletas: 'Maletas',
      comecou: 'Começou em',
      situacao: 'Situação',
      acoes: 'O que fazer',
    },
    semana: (numero: number | string) => `Semana ${numero}`,
    abrir: 'Abrir',
    abrirDe: (vendedor: string, semana: number | string) =>
      `Abrir acerto de ${vendedor}, semana ${semana}`,
    daVendedora: 'Pedido pelo vendedor',
  },
  comecar: {
    titulo: 'Começar acerto',
    ajuda:
      'Escolha o vendedor. O acerto conta o que voltou na maleta dele e fecha o dinheiro da semana.',
    vendedor: 'Vendedor',
    escolhaVendedor: 'Escolha o vendedor',
    vendedorAviso: 'Escolha o vendedor.',
    maisOpcoes: 'Mais opções',
    menosOpcoes: 'Menos opções',
    semana: 'Semana',
    semanaPadrao: 'A semana certa (o sistema escolhe)',
    semanaAjuda: 'Só escolha uma semana se o acerto for de outra semana.',
    nomeDaSemana: (numero: string) => `Semana ${numero}`,
    semMaleta:
      'Este vendedor não tem maleta com ele: o acerto vai ter só vendas e devoluções antigas.',
    comecar: 'Começar',
    comecando: 'Começando...',
    voltar: 'Voltar para a lista',
  },
  ficha: {
    voltar: 'Voltar para a lista',
    titulo: (vendedor: string, semana: number | string) =>
      `Acerto de ${vendedor}, semana ${semana}`,
    comecouEm: (quando: string) => `Começou em ${quando}.`,
    pedidoPeloVendedor: 'O vendedor pediu este acerto.',
    maletas: 'Maletas deste acerto',
    maleta: (numero: string) => (numero ? `Maleta ${numero}` : 'Maleta'),
    desistido: (motivo: string) => `Este acerto foi desfeito. Motivo: ${motivo}`,
    desistidoSemMotivo: 'Este acerto foi desfeito.',
    naoAbriu: 'Não deu para abrir o acerto. Tente de novo.',
  },
  contagem: {
    titulo: 'Contagem das peças',
    ajuda:
      'Leia cada peça que voltou na maleta. O sistema soma e compara com o que saiu. Leia de novo para somar mais uma.',
    estado: 'Como está a peça',
    boa: 'Peça boa',
    defeito: 'Com defeito',
    quantidade: 'Quantidade',
    quantidadeAviso: 'Use uma quantidade inteira, de 1 em diante.',
    leitor: {
      titulo: 'Ler uma peça',
      adicionar: 'Contar',
      ajuda: 'O leitor já aperta Enter sozinho. Ler a mesma peça de novo conta mais uma.',
    },
    legenda: 'Peças contadas neste acerto',
    vazia: 'Nenhuma peça contada ainda. Leia a primeira peça acima.',
    colunas: {
      peca: 'Peça',
      estado: 'Como está',
      quantidade: 'Contadas',
      acoes: 'O que fazer',
    },
    desfazer: 'Desfazer leitura',
    desfazerDe: (nome: string) => `Desfazer leitura de ${nome}`,
    vendaNova: (n: number) =>
      n === 1
        ? 'Chegou venda nova: confira a contagem. 1 venda chegou depois de começar o acerto.'
        : `Chegou venda nova: confira a contagem. ${n} vendas chegaram depois de começar o acerto.`,
    envios: (n: number) =>
      `O computador do vendedor ainda tem ${n === 1 ? '1 envio' : `${n} envios`} para mandar. Peça para ele conectar à internet antes de fechar.`,
    leituras: 'Últimas leituras',
    leitura: (
      peca: string,
      qtd: number,
      estado: string,
      nome: string,
      onde: string,
      quando: string,
    ) =>
      `${peca}: ${qtd > 0 ? '+' : ''}${qtd} (${estado}), por ${nome || 'alguém'} ${onde}, em ${quando}`,
    noComputadorDoVendedor: 'no computador do vendedor',
    noComputadorDaLoja: 'no computador da loja',
    semFoto: 'Sem foto',
    conferir: 'Conferir o acerto',
    desistir: 'Desistir do acerto',
  },
  desistir: {
    titulo: 'Desistir do acerto',
    texto:
      'As maletas voltam a ficar com o vendedor e nada de dinheiro é gravado. As leituras da contagem ficam guardadas.',
    motivo: 'Motivo',
    motivoAviso: 'Diga o motivo de desistir.',
    confirmar: 'Desistir do acerto',
    voltar: 'Continuar o acerto',
  },
  /** Erros mais comuns, pelo código do servidor, com o que fazer. */
  erros: {
    ACERTO_EM_ANDAMENTO: 'Este vendedor já está em acerto. Abra o acerto dele na lista.',
    ACERTO_DA_SEMANA_JA_EXISTE:
      'Este vendedor já fez o acerto desta semana. Escolha outra semana em "Mais opções".',
    SEMANA_FECHADA: 'Esta semana já está fechada. Escolha uma semana aberta.',
    PECA_NAO_ENCONTRADA: 'Esta peça não é deste acerto. Confira o código.',
    LEITURA_DEIXA_CONTAGEM_NEGATIVA:
      'Não dá para tirar mais do que foi contado. Confira a contagem.',
    ACERTO_NAO_ESTA_EM_ANDAMENTO:
      'Este acerto já terminou ou foi desfeito. Volte para a lista e abra de novo.',
  } as Record<string, string>,
} as const;

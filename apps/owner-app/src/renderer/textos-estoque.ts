import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_ESTOQUE } from './idioma/es/estoque';
/**
 * Textos das telas do estoque (menu Estoque → Estoque, Entradas e saídas, Está acabando e Contagem
 * do estoque). Vêm do glossário da interface (parte 12). Erros de regra vêm do servidor, em
 * português; aqui ficam só os textos de tela. Nada de termo técnico.
 */
const pecas = (n: number): string => (n === 1 ? '1 peça' : `${n} peças`);

export const PT_TEXTOS_DE_ESTOQUE = {
  menu: {
    estoque: 'Estoque',
    entradasESaidas: 'Entradas e saídas',
    acabando: 'Está acabando',
    contagem: 'Contagem do estoque',
  },
  comuns: {
    foto: 'Foto',
    peca: 'Peça',
    codigo: 'Código',
    buscando: 'Buscando...',
    voltar: 'Voltar',
    abrir: 'Abrir',
    todos: 'Todos',
    todas: 'Todas',
    limpar: 'Limpar filtros',
    buscar: 'Buscar',
    pecas,
    semFotoNaoTrava: 'Sem foto',
  },
  posicao: {
    titulo: 'Estoque',
    legenda: 'Peças e quantidades no estoque',
    filtros: {
      busca: 'Nome, referência ou código',
      categoria: 'Categoria',
      fornecedor: 'Fornecedor',
      soAcabando: 'Só os que estão acabando',
    },
    colunas: {
      noEstoque: 'No estoque',
      minimo: 'Estoque mínimo',
      situacao: 'Situação',
      acoes: 'O que fazer',
    },
    situacao: { acabando: 'Está acabando', emDia: 'Em dia' },
    abrirDe: (nome: string) => `Abrir ${nome}`,
    vazio: 'Ainda não há peças cadastradas.',
    semResultado: 'Nenhuma peça encontrada com esses filtros.',
    itens: ['peça', 'peças'] as const,
  },
  acabando: {
    titulo: 'Está acabando',
    ajuda: 'Peças que chegaram ao estoque mínimo ou ficaram com menos. Faça a reposição.',
    legenda: 'Peças que estão acabando',
    colunas: { faltam: 'Quanto falta' },
    vazio: 'Nenhum produto está acabando.',
    semResultado: 'Nenhuma peça encontrada com esses filtros.',
  },
  ficha: {
    carregando: 'Abrindo a peça...',
    naoEncontrada: 'Não encontramos esta peça. Volte e abra a lista de novo.',
    noEstoque: 'No estoque',
    minimo: 'Estoque mínimo',
    custo: 'Custo de cada peça',
    custoAjuda: 'Só você vê o custo.',
    acabando: 'Está acabando',
    entrada: 'Entrada',
    saida: 'Saída',
    perda: 'Perda',
    ajustar: 'Ajustar o estoque',
    mudarMinimo: 'Mudar o estoque mínimo',
    historico: 'Entradas e saídas desta peça',
    foiAtualizado: 'Pronto. O estoque desta peça foi atualizado.',
    minimoAtualizado: 'Pronto. O estoque mínimo foi atualizado.',
  },
  minimo: {
    titulo: 'Mudar o estoque mínimo',
    texto:
      'Quando o estoque chegar a esse número, a peça aparece em "Está acabando". Use zero para não ser avisada.',
    campo: 'Estoque mínimo',
    salvar: 'Salvar',
    cancelar: 'Cancelar',
  },
  historico: {
    legenda: 'Entradas e saídas',
    colunas: {
      data: 'Data',
      tipo: 'Tipo',
      quantidade: 'Quantidade',
      saldoDepois: 'Ficou com',
      quem: 'Quem',
      motivo: 'Motivo',
      situacao: 'Situação',
      acoes: 'O que fazer',
    },
    vazio: 'Ainda não há entradas e saídas.',
    semResultado: 'Nenhuma entrada ou saída com esses filtros.',
    itens: ['lançamento', 'lançamentos'] as const,
    desfeito: 'Desfeito',
    desfazDe: (descricao: string) => `Desfaz ${descricao}`,
    desfazUmAnterior: 'Desfaz um lançamento anterior',
    desfazer: 'Desfazer',
    desfazerDe: (tipo: string, quantidade: string, peca: string) =>
      `Desfazer lançamento: ${tipo} ${quantidade} de ${peca}`,
  },
  desfazer: {
    titulo: 'Desfazer este lançamento?',
    texto:
      'O sistema cria um novo lançamento que cancela este. O lançamento antigo continua no histórico.',
    motivo: 'Motivo',
    confirmar: 'Desfazer lançamento',
    voltar: 'Voltar',
  },
  lancar: {
    entrada: {
      titulo: 'Entrada no estoque',
      texto: 'Use quando entraram peças que não vieram de uma compra.',
      confirmar: 'Lançar entrada',
    },
    saida: {
      titulo: 'Saída do estoque',
      texto: 'Use quando saíram peças do estoque.',
      confirmar: 'Lançar saída',
    },
    perda: {
      titulo: 'Perda de peças',
      texto: 'Use para peça com defeito ou extraviada.',
      confirmar: 'Lançar perda',
    },
    quantidade: 'Quantidade',
    motivo: 'Motivo (se quiser)',
    noEstoqueAgora: (n: number) => `Agora há ${pecas(n)} no estoque.`,
    cancelar: 'Cancelar',
  },
  ajuste: {
    titulo: 'Ajustar o estoque',
    texto: 'Corrija a quantidade e diga o motivo. O lançamento antigo nunca é apagado.',
    forma: 'Como você quer ajustar?',
    contei: 'Eu contei',
    diferenca: 'Quero dizer a diferença',
    campoContei: 'Quantas peças você contou?',
    campoDiferenca: 'Diferença (3 para mais, -3 para menos)',
    motivo: 'Motivo do ajuste',
    confirmar: 'Ajustar o estoque',
    cancelar: 'Cancelar',
  },
  lancamentos: {
    titulo: 'Entradas e saídas',
    ajuda: 'Tudo o que entrou e saiu do estoque, de todas as peças.',
    filtros: {
      de: 'De',
      ate: 'Até',
      tipo: 'Tipo',
      usuario: 'Quem lançou',
      peca: 'Peça (código)',
      pecaAjuda: 'Digite o código da peça e aperte Enter.',
      tirarPeca: 'Tirar a peça',
      filtrandoPor: (nome: string) => `Só da peça: ${nome}.`,
      pecaNaoAchada: (codigo: string) => `Não encontramos nenhuma peça com o código ${codigo}.`,
      dataInvertida: 'A data final precisa ser depois da inicial.',
    },
    colunas: { peca: 'Peça' },
  },
  contagem: {
    titulo: 'Contagem do estoque',
    ajuda:
      'Confira o que há na loja lendo o código de cada peça. O sistema mostra o que falta ou sobra.',
    comecarTitulo: 'Começar a contagem',
    explicacao:
      'Ao começar, o sistema anota quantas peças esperava de cada produto. Entradas e saídas feitas depois continuam valendo; no fim, só a diferença é ajustada.',
    escopo: 'O que você vai contar?',
    escopos: {
      TODOS: 'Todas as peças',
      CATEGORIA: 'Uma categoria',
      FORNECEDOR: 'Um fornecedor',
    },
    escolhaCategoria: 'Escolha a categoria',
    escolhaFornecedor: 'Escolha o fornecedor',
    escolhaAviso: 'Escolha o que você vai contar.',
    observacoes: 'Observações (se quiser)',
    comecar: 'Começar a contagem',
    comecando: 'Começando a contagem...',
    anteriores: 'Contagens',
    legenda: 'Contagens do estoque',
    colunas: {
      inicio: 'Começou em',
      fim: 'Terminou em',
      o_que: 'O que foi contado',
      lidas: 'Peças lidas',
      situacao: 'Situação',
      acoes: 'O que fazer',
    },
    situacoes: {
      EM_ANDAMENTO: 'Em andamento',
      FECHADO: 'Fechada',
      CANCELADO: 'Cancelada',
    } as Record<string, string>,
    escopoDaLinha: (escopo: string, nome: string) =>
      escopo === 'CATEGORIA'
        ? `Categoria ${nome}`
        : escopo === 'FORNECEDOR'
          ? `Fornecedor ${nome}`
          : 'Todas as peças',
    lidasDe: (lidas: number, total: number) => `${lidas} de ${total}`,
    continuar: 'Continuar contando',
    ver: 'Ver a contagem',
    vazio: 'Ainda não há contagens.',
    itens: ['contagem', 'contagens'] as const,
    nova: 'Começar uma nova contagem',
  },
  contando: {
    titulo: 'Contando o estoque',
    leitor: {
      campo: 'Código da peça',
      ajuda: 'O leitor já aperta Enter sozinho. Cada leitura soma 1 peça contada.',
      ler: 'Ler a peça',
      lendo: 'Lendo a peça...',
      jaRegistrada: 'Esta leitura já estava registrada. Nada foi somado de novo.',
      somou: (nome: string, n: number) => `${nome}: agora são ${pecas(n)} contadas.`,
    },
    peca: {
      titulo: 'Última peça lida',
      esperado: 'Esperado',
      contado: 'Contado',
      diferenca: 'Diferença',
      corrigir: 'Quantidade contada',
      corrigirAjuda:
        'Se contou de outro jeito, digite a quantidade certa. Ela substitui a leitura.',
      definir: 'Definir a quantidade',
      quantidadeInvalida: 'Digite a quantidade com número inteiro. Pode ser zero.',
      jaRegistrada: 'Esta leitura já estava registrada. Nada foi somado de novo.',
      definida: (nome: string, n: number) => `${nome}: a quantidade contada agora é ${n}.`,
    },
    resumo: {
      lidas: (lidos: number, total: number) => `${lidos} de ${total} produtos lidos`,
      faltam: (n: number) => `Faltam ${pecas(n)}`,
      sobram: (n: number) => `Sobram ${pecas(n)}`,
      comDiferenca: (n: number) =>
        n === 1 ? '1 produto com diferença' : `${n} produtos com diferença`,
    },
    lista: {
      titulo: 'Esperado e contado',
      legenda: 'Peças da contagem: esperado e contado',
      mostrar: 'Mostrar',
      filtros: {
        DIVERGENTES: 'Só as que têm diferença',
        NAO_LIDOS: 'As que ainda não li',
        LIDOS: 'As que já li',
        TODOS: 'Todas as peças',
      } as Record<string, string>,
      busca: 'Buscar na lista',
      colunas: {
        esperado: 'Esperado',
        contado: 'Contado',
        diferenca: 'Diferença',
        acoes: 'O que fazer',
      },
      naoLido: 'Não lido',
      corrigir: 'Corrigir',
      corrigirDe: (nome: string) => `Corrigir a quantidade de ${nome}`,
      vazio: {
        DIVERGENTES: 'Nenhuma peça com diferença até agora.',
        NAO_LIDOS: 'Todas as peças já foram lidas.',
        LIDOS: 'Nenhuma peça foi lida ainda.',
        TODOS: 'Não há peças nesta contagem.',
      } as Record<string, string>,
      itens: ['peça', 'peças'] as const,
    },
    fechar: 'Fechar a contagem',
    cancelar: 'Cancelar a contagem',
    voltarLista: 'Voltar para as contagens',
    carregando: 'Abrindo a contagem...',
    naoEncontrada: 'Não encontramos esta contagem do estoque. Atualize a tela e tente de novo.',
  },
  fechar: {
    titulo: 'Fechar a contagem?',
    texto: (n: number) =>
      `O estoque será ajustado ${n === 1 ? 'em 1 produto' : `nos ${n} produtos`} com diferença. Depois de fechada, a contagem não muda; para corrigir, faça um ajuste.`,
    semAjustes: 'Nenhum produto tem diferença. O estoque não vai mudar.',
    resumoDosAjustes: 'O que vai mudar no estoque',
    legenda: 'Ajustes que serão feitos',
    naoLidos: (n: number) =>
      n === 1 ? '1 produto não foi lido.' : `${n} produtos não foram lidos.`,
    naoLidosPergunta: 'Eles devem ficar com zero peças ou ficam como estão?',
    zero: 'Ficam com zero peças',
    comoEstao: 'Ficam como estão',
    escolhaAviso: 'Escolha o que fazer com os produtos que não foram lidos.',
    confirmar: 'Fechar a contagem',
    continuar: 'Continuar contando',
    carregando: 'Conferindo os ajustes...',
    naoAjustadas: (nomes: string[]) =>
      `Sem ajuste, porque a peça foi excluída durante a contagem: ${nomes.join(', ')}. A diferença ficou registrada.`,
    fechada: (n: number) =>
      n === 0
        ? 'Contagem fechada. O estoque não precisou de ajuste.'
        : `Contagem fechada. O estoque foi ajustado ${n === 1 ? 'em 1 produto' : `em ${n} produtos`}.`,
  },
  cancelarContagem: {
    titulo: 'Cancelar esta contagem?',
    texto: 'Nada muda no estoque. A contagem fica na lista como cancelada.',
    motivo: 'Motivo (se quiser)',
    confirmar: 'Cancelar a contagem',
    voltar: 'Voltar',
    cancelada: 'Contagem cancelada. O estoque não mudou.',
  },
  encerrada: {
    fechada: (quando: string, quem: string) =>
      `Contagem fechada em ${quando}${quem ? ` por ${quem}` : ''}.`,
    cancelada: (quando: string) => `Contagem cancelada em ${quando}.`,
    motivo: (m: string) => `Motivo: ${m}`,
    ajustes: (n: number) =>
      n === 0 ? 'Nenhum ajuste foi feito.' : `${n === 1 ? '1 ajuste' : `${n} ajustes`} no estoque.`,
    zerou: 'As peças que não foram lidas ficaram com zero.',
    nZerou: 'As peças que não foram lidas ficaram como estavam.',
  },
  aviso: {
    verLista: 'Ver a lista',
  },
} as const;

export const TEXTOS_DE_ESTOQUE = textosVivos('estoque', PT_TEXTOS_DE_ESTOQUE, ES_TEXTOS_DE_ESTOQUE);

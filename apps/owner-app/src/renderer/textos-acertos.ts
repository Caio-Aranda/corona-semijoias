import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_ACERTOS } from './idioma/es/acertos';
/**
 * Textos das telas do acerto (tarefa 7.9). Linguagem da loja (acerto, maleta, vendedor, peça): sem termo técnico,
 * a dona é tratada por "você". Mensagens de regra vêm do servidor; aqui ficam só os textos das telas e os avisos
 * dos erros mais comuns, que dizem o que fazer.
 */
const pecas = (n: number) => (n === 1 ? '1 peça' : `${n} peças`);

export const PT_TEXTOS_DE_ACERTOS = {
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
    legenda: 'Acertos dos representantes',
    vazia: 'Nenhum acerto por aqui. Use "Começar acerto" para fazer o primeiro.',
    filtros: {
      vendedor: 'Representante',
      todosVendedores: 'Todos os representantes',
      situacao: 'Situação',
      todasSituacoes: 'Todas as situações',
    },
    colunas: {
      vendedor: 'Representante',
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
    daVendedora: 'Pedido pelo representante',
  },
  comecar: {
    titulo: 'Começar acerto',
    ajuda:
      'Escolha o representante. O acerto conta o que voltou na maleta dele e fecha o dinheiro da semana.',
    vendedor: 'Representante',
    escolhaVendedor: 'Escolha o representante',
    vendedorAviso: 'Escolha o representante.',
    maisOpcoes: 'Mais opções',
    menosOpcoes: 'Menos opções',
    semana: 'Semana',
    semanaPadrao: 'A semana certa (o sistema escolhe)',
    semanaAjuda: 'Só escolha uma semana se o acerto for de outra semana.',
    nomeDaSemana: (numero: string) => `Semana ${numero}`,
    semMaleta:
      'Este representante não tem maleta com ele: o acerto vai ter só vendas e devoluções antigas.',
    comecar: 'Começar',
    comecando: 'Começando...',
    voltar: 'Voltar para a lista',
  },
  ficha: {
    voltar: 'Voltar para a lista',
    titulo: (vendedor: string, semana: number | string) =>
      `Acerto de ${vendedor}, semana ${semana}`,
    comecouEm: (quando: string) => `Começou em ${quando}.`,
    pedidoPeloVendedor: 'O representante pediu este acerto.',
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
      `O computador do representante ainda tem ${n === 1 ? '1 envio' : `${n} envios`} para mandar. Peça para ele conectar à internet antes de fechar.`,
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
    noComputadorDoVendedor: 'no computador do representante',
    noComputadorDaLoja: 'no computador da loja',
    semFoto: 'Sem foto',
    conferir: 'Conferir o acerto',
    desistir: 'Desistir do acerto',
  },
  previa: {
    titulo: 'Conferência do acerto',
    ajuda:
      'Veja o que voltou, decida o que fazer com cada diferença e confira o dinheiro. Os números são do sistema: quando você decide, eles mudam na hora.',
    voltar: 'Voltar para a contagem',
    calculando: 'Calculando...',
    naoLeu: 'Não deu para ler o acerto. Tente de novo.',
    tentarDeNovo: 'Tentar de novo',
    motivoPendente: 'Escreva o motivo para o sistema refazer os números.',
    pecas: {
      titulo: 'Peças',
      legenda: 'Peças do acerto',
      colunas: {
        peca: 'Peça',
        saiu: 'Saiu na maleta',
        boa: 'Voltou boa',
        defeito: 'Voltou com defeito',
        faltou: 'Faltou',
        valorFalta: 'Valor da falta',
      },
      vazia: 'Nenhuma peça neste acerto.',
    },
    sobra: {
      titulo: 'Sobrou peça: confira as vendas ou registre o ajuste.',
      ajuda:
        'Contaram mais peças do que saíram na maleta. Corrija a venda errada na maleta ou faça o ajuste do estoque. Só dá para fechar o acerto depois disso.',
      linha: (nome: string, esperado: number, contado: number) =>
        `${nome}: eram ${esperado}, foram contadas ${contado}.`,
    },
    revisao: {
      titulo: 'Tem devolução esperando sua revisão. Revise antes de fechar o acerto.',
      botao: 'Revisar devoluções',
    },
    faltas: {
      titulo: 'O que fazer com o que faltou',
      ajuda:
        'Cada peça que não voltou pode ser cobrada agora, cobrada depois ou assumida pela loja.',
      decisao: (nome: string) => `O que fazer com a falta de ${nome}`,
      motivo: (nome: string) => `Motivo de a loja assumir: ${nome}`,
      motivoAviso: 'Diga o motivo de a loja assumir.',
      linha: (quantidade: number, valor: string, situacao: string) =>
        `Faltou ${quantidade === 1 ? '1 peça' : `${quantidade} peças`} (${valor}). ${situacao}`,
      situacoes: {
        COBRADA: 'Vai ser cobrada agora.',
        ABERTA: 'Fica para depois.',
        ASSUMIDA_PELA_LOJA: 'A loja assume.',
      } as Record<string, string>,
      semFalta: 'Com os defeitos aprovados, esta peça não tem mais falta.',
    },
    opcoesDeCobranca: {
      COBRAR_NO_ACERTO: 'Cobrar agora',
      COBRAR_DEPOIS: 'Cobrar depois',
      LOJA_ASSUME: 'A loja assume',
    } as Record<string, string>,
    defeitos: {
      titulo: 'Peças com defeito',
      ajuda: 'Diga quantas peças você aprova como defeito. As outras voltam como peça boa.',
      contadas: (n: number) => `Contadas com defeito: ${n}`,
      aprovar: (nome: string) => `Aprovar como defeito: ${nome}`,
      aviso: (max: number) => `Use um número de 0 até ${max}.`,
    },
    naoAceitas: {
      titulo: 'O que não foi aceito',
      ajuda:
        'Devoluções e créditos que você não aceitou. Escolha se o representante paga agora, depois ou se a loja assume.',
      decisao: (tipo: string, valor: string) =>
        tipo === 'CREDITO_SEM_SALDO_NAO_ACEITO'
          ? `O que fazer com o crédito sem saldo não aceito de ${valor}`
          : `O que fazer com a devolução não aceita de ${valor}`,
      motivo: (valor: string) => `Motivo de a loja assumir: ${valor}`,
    },
    antigas: {
      titulo: 'Pendências de acertos anteriores',
      ajuda: 'Peças que já faltaram antes. Você pode cobrar agora ou deixar para outro acerto.',
      decisao: (nome: string) => `O que fazer com a pendência antiga de ${nome}`,
      opcoes: { COBRAR_NO_ACERTO: 'Cobrar agora', MANTER: 'Deixar para depois' } as Record<
        string,
        string
      >,
      linha: (nome: string, quantidade: number, valor: string) =>
        `${nome}: ${quantidade === 1 ? '1 peça' : `${quantidade} peças`} (${valor})`,
      umaPeca: 'Uma peça',
    },
    comissao: {
      titulo: 'Comissão e meta',
      vendidoNaSemana: (vendido: string, meta: string) =>
        `Vendido na semana: ${vendido} de ${meta} (meta)`,
      faltaParaMeta: (guarani: string, real: string) => `Falta para a meta: ${guarani} (${real}).`,
      bateu: (percentual: string) => `Bateu a meta: comissão de ${percentual}.`,
      naoBateu: (percentual: string) => `Ainda não bateu a meta: comissão de ${percentual}.`,
      legenda: 'Comissão por venda',
      colunas: {
        venda: 'Venda',
        base: 'Valor',
        percentual: 'Comissão',
        valor: 'Comissão em reais',
      },
      venda: (n: number) => `Venda ${n}`,
      devolucao: (n: number) => `Devolução (venda ${n})`,
      vazia: 'Nenhuma venda neste acerto.',
    },
    totais: {
      titulo: 'Totais do acerto',
      vendido: 'Vendido',
      descontos: 'Descontos',
      devolvido: 'Devolvido',
      vendidoLiquido: 'Vendido depois dos descontos',
      comissao: 'Comissão do representante',
      faltasCobradas: 'Faltas cobradas',
      liquido: 'Valor do acerto',
      creditoUsado: 'Crédito do representante usado',
      aPagar: 'A pagar agora',
    },
    cotacao: {
      frase: (data: string) =>
        `Valores em Dólar e Guarani pela cotação de ${data}. Vale o valor em reais.`,
      sem: 'Sem cotação: os valores aparecem só em reais.',
    },
  },
  pagamento: {
    titulo: 'Pagamento do acerto',
    ajuda:
      'Diga quanto o representante pagou agora. O que sobrar vira conta a receber do representante, em parcelas. Os números mudam conforme você digita.',
    pagouAgora: 'Quanto o representante pagou agora',
    dinheiro: 'Dinheiro',
    transferencia: 'Transferência (PIX)',
    ajudaDoValor: '',
    boleto: {
      titulo: 'Boleto',
      valor: 'Boleto: valor',
      vencimento: 'Boleto: vencimento',
      ajuda:
        'O boleto vira uma conta do representante. Só entra no caixa quando você registrar que ele foi pago.',
      semVencimento: 'Escolha o vencimento do boleto.',
    },
    devendo: {
      titulo: 'O que ficou devendo',
      ajuda: 'O que o representante não pagou agora vira conta a receber, em parcelas.',
      parcelas: 'Em quantas parcelas',
      umaParcela: '1 parcela',
      parcelasDe: (n: number) => `${n} parcelas`,
      primeira: 'Data da primeira parcela',
      semData: 'Escolha a data de pagamento do que ficou devendo.',
      parcela: (numero: number, valor: string, data: string) =>
        `Parcela ${numero}: ${valor}, vence em ${data}`,
    },
    antigas: {
      titulo: 'Contas antigas do representante',
      ajuda:
        'Se o representante quiser, pague parcelas de acertos anteriores junto. Isto não entra no valor deste acerto.',
      conta: (numero: number, total: number, vencimento: string, emAberto: string) =>
        `Parcela ${numero} de ${total}, vence em ${vencimento}: falta pagar ${emAberto}`,
      pagar: (numero: number, total: number) => `Pagar agora a parcela ${numero} de ${total}`,
      como: (numero: number) => `Como pagou a parcela ${numero}`,
    },
    aFavor: {
      titulo: 'Acerto a favor do representante',
      deve: (valor: string) => `A loja deve ${valor} ao representante.`,
      credito: 'Guardar como crédito para o próximo acerto',
      pagarAgora: 'Pagar agora',
      como: 'Como a loja paga',
    },
    resumo: {
      recebido: 'Recebido agora',
      boleto: 'Boleto',
      ficouDevendo: 'Ficou devendo',
      creditoDisponivel: 'Crédito do representante disponível',
      pagoAoVendedor: 'Pago ao representante agora',
    },
  },
  fechar: {
    botao: 'Fechar acerto',
    titulo: 'Fechar acerto',
    texto: (vendedor: string) =>
      `Fechar o acerto de ${vendedor}? Depois de fechado, o acerto não muda mais.`,
    voltar: 'Voltar e conferir',
    fechando: 'Fechando...',
    fechado: 'Acerto fechado.',
    semConexao: 'Sem internet. O acerto não foi fechado. Tente de novo.',
    desatualizado: 'Os números mudaram desde que você abriu. Confira o acerto de novo.',
    erros: {
      FECHAMENTO_DESATUALIZADO:
        'Os números mudaram desde que você abriu. Confira o acerto de novo.',
      PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO:
        'O valor pago passa do que o representante deve. Confira os valores.',
      DATA_DO_RESTANTE_OBRIGATORIA: 'Escolha a data de pagamento do que ficou devendo.',
      SEMANA_FECHADA:
        'A semana deste acerto já está fechada, por isso o acerto não pode ser fechado. Fale com o suporte.',
      DATA_EM_SEMANA_FECHADA:
        'A semana deste acerto já está fechada, por isso o acerto não pode ser fechado. Fale com o suporte.',
    } as Record<string, string>,
  },
  fechada: {
    titulo: 'Fechamento do acerto',
    fechadoEm: (quando: string) => `Fechado em ${quando}.`,
    pecas: {
      defeitoAprovado: 'Defeito aprovado',
    },
    pendencias: {
      legenda: 'Pendências deste acerto',
      vazia: 'Nenhuma pendência neste acerto.',
      colunas: {
        peca: 'Peça',
        tipo: 'O que é',
        quantidade: 'Quantidade',
        valor: 'Valor',
        situacao: 'Situação',
      },
      tipos: {
        FALTA: 'Peça que faltou',
        DEFEITO: 'Peça com defeito',
        DEVOLUCAO_NAO_ACEITA: 'Devolução não aceita',
        CREDITO_SEM_SALDO_NAO_ACEITO: 'Crédito sem saldo não aceito',
      } as Record<string, string>,
      situacoes: {
        ABERTA: 'Em aberto',
        COBRADA: 'Cobrada',
        ASSUMIDA_PELA_LOJA: 'A loja assumiu',
        ENCONTRADA: 'Peça encontrada',
        APROVADA: 'Defeito aprovado',
        RECUSADA: 'Defeito recusado',
      } as Record<string, string>,
      semPeca: 'Valor sem peça',
    },
    totais: {
      recebido: 'Recebido no acerto',
      ficouDevendo: 'Ficou devendo',
      pagoAoVendedor: 'Pago ao representante',
    },
    recebimentos: {
      legenda: 'O que o representante pagou',
      vazia: 'O representante não pagou nada neste acerto.',
      colunas: { forma: 'Forma', valor: 'Valor', quando: 'Quando', situacao: 'Situação' },
      formas: { DINHEIRO: 'Dinheiro', PIX: 'Transferência (PIX)', BOLETO: 'Boleto' } as Record<
        string,
        string
      >,
      desfeito: 'Desfeito',
    },
    contas: {
      legenda: 'Contas que o acerto gerou',
      vazia: 'Este acerto não gerou contas a receber.',
      colunas: {
        parcela: 'Parcela',
        valor: 'Valor',
        vence: 'Vence em',
        forma: 'Forma prevista',
        situacao: 'Situação',
      },
      parcela: (numero: number, total: number) => `${numero} de ${total}`,
      situacoes: {
        ABERTA: 'Em aberto',
        PARCIAL: 'Paga em parte',
        PAGA: 'Paga',
        CANCELADA: 'Cancelada',
      } as Record<string, string>,
    },
    imprimir: 'Imprimir acerto',
    gerando: 'Preparando o acerto...',
    pronta: (nome: string) => `O acerto ${nome} foi preparado. Escolha onde guardar.`,
    erroDoPdf: 'Não foi possível preparar o acerto agora. Tente de novo.',
    naoLeu: 'Não deu para ler o fechamento deste acerto. Tente de novo.',
  },
  pendencias: {
    titulo: 'Pendências do acerto',
    ajuda: 'Peças que faltaram e valores que ficaram em aberto nos acertos dos representantes.',
    cobrar:
      'A pendência em aberto aparece no próximo acerto do representante, e é lá que ela é cobrada. Aqui você só resolve as que não vão ser cobradas.',
    legenda: 'Pendências dos representantes',
    vazia: 'Nenhuma pendência por aqui.',
    filtros: {
      vendedor: 'Representante',
      todosVendedores: 'Todos os representantes',
      situacao: 'Situação',
      todasSituacoes: 'Todas as situações',
    },
    colunas: {
      vendedor: 'Representante',
      peca: 'Peça',
      tipo: 'O que é',
      quantidade: 'Quantidade',
      valor: 'Valor',
      situacao: 'Situação',
      acoes: 'O que fazer',
    },
    resolver: 'Resolver',
    resolverDe: (nome: string) => `Resolver pendência de ${nome}`,
    dialogo: {
      titulo: 'Resolver pendência',
      texto: 'Escolha como resolver. Depois de resolvida, a pendência não volta a ser cobrada.',
      loja: 'A loja assume',
      apareceu: 'A peça apareceu',
      motivo: 'Motivo',
      motivoAviso: 'Diga o motivo de resolver.',
      confirmar: 'Resolver pendência',
      voltar: 'Voltar',
    },
    feito: 'Pendência resolvida.',
  },
  comissao: {
    titulo: 'Comissão e meta',
    ajuda:
      'A comissão do representante depende de quanto ele vendeu na semana. Se passar da meta, vale a comissão maior.',
    meta: 'Meta da semana, em Guarani',
    metaAviso: 'Digite a meta em Guarani, só com números.',
    abaixo: 'Comissão abaixo da meta, em %',
    naMeta: 'Comissão a partir da meta, em %',
    percentualAviso: 'Use um número de 0 a 100.',
    ordemAviso: 'A comissão abaixo da meta não pode ser maior que a comissão a partir da meta.',
    valeParaOsProximos: 'Vale para os próximos acertos. Acerto já fechado não muda.',
    salvar: 'Salvar',
    salvando: 'Salvando...',
    salvo: 'Comissão e meta salvas.',
    carregando: 'Carregando...',
  },
  desistir: {
    titulo: 'Desistir do acerto',
    texto:
      'As maletas voltam a ficar com o representante e nada de dinheiro é gravado. As leituras da contagem ficam guardadas.',
    motivo: 'Motivo',
    motivoAviso: 'Diga o motivo de desistir.',
    confirmar: 'Desistir do acerto',
    voltar: 'Continuar o acerto',
  },
  /** Erros mais comuns, pelo código do servidor, com o que fazer. */
  erros: {
    ACERTO_EM_ANDAMENTO: 'Este representante já está em acerto. Abra o acerto dele na lista.',
    ACERTO_DA_SEMANA_JA_EXISTE:
      'Este representante já fez o acerto desta semana. Escolha outra semana em "Mais opções".',
    SEMANA_FECHADA: 'Esta semana já está fechada. Escolha uma semana aberta.',
    PECA_NAO_ENCONTRADA: 'Esta peça não é deste acerto. Confira o código.',
    LEITURA_DEIXA_CONTAGEM_NEGATIVA:
      'Não dá para tirar mais do que foi contado. Confira a contagem.',
    ACERTO_NAO_ESTA_EM_ANDAMENTO:
      'Este acerto já terminou ou foi desfeito. Volte para a lista e abra de novo.',
  } as Record<string, string>,
} as const;

export const TEXTOS_DE_ACERTOS = textosVivos('acertos', PT_TEXTOS_DE_ACERTOS, ES_TEXTOS_DE_ACERTOS);

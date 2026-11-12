/**
 * Textos fixos que o servidor não consegue montar (por exemplo, quando nem chegamos a falar com ele) e os
 * rótulos das telas. Vêm do glossário da interface (docs/glossario-interface.md). Mensagens de erro da
 * API nunca são escritas aqui: a tela mostra o que o servidor devolveu.
 */
export const TEXTOS = {
  /** Atualização automática (Fase 9): a versão nova já foi baixada e entra quando a pessoa fecha o programa. */
  atualizacao: {
    pronta: 'Há uma versão nova. Ela será instalada quando você fechar o programa.',
  },
  semInternet: {
    titulo: 'Sem internet.',
    texto: 'Para entrar no Corona Vendas é preciso internet. Conecte-se e tente de novo.',
    curto: 'Sem internet. Conecte-se e tente de novo.',
  },
  algoDeuErrado:
    'Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema.',
  sessaoTerminou: {
    titulo: 'Sua sessão terminou.',
    texto: 'Entre de novo com seu usuário e sua senha para continuar.',
  },
  desatualizado: {
    titulo: 'Seu aplicativo está desatualizado.',
    texto: 'Atualize para continuar usando.',
  },
  senhaNova: {
    titulo: 'Crie sua senha nova.',
    texto:
      'A senha que você recebeu é temporária. Para continuar, crie uma senha nova que só você saiba.',
    regrasTitulo: 'Sua senha nova precisa ter:',
    regras: ['pelo menos 8 caracteres;', 'letras e números;', 'uma senha diferente da temporária.'],
    naoEstaBoa:
      'A senha nova ainda não está boa. Ela precisa ter pelo menos 8 caracteres, com letras e números.',
    naoSaoIguais: 'As duas senhas não são iguais. Digite a mesma senha nos dois campos.',
  },

  /** A cotação mostrada com a data (ADR-0006): a de hoje ou a última que o computador recebeu. */
  cotacao: {
    deHoje: (data: string) => `Cotação do Dólar e do Guarani de hoje, ${data}.`,
    ultima: (data: string) =>
      `Cotação de ${data}, a última que este computador recebeu. Conecte-se à internet para receber a mais nova.`,
    doDia: (data: string) => `Cotação de ${data}.`,
  },

  /** O indicador de envio (glossário, parte 7 e tarefa 5.7): palavras comuns, nunca "fila" nem "pendente". */
  envio: {
    area: 'Situação dos envios',
    tudoEnviado: 'Com internet. Tudo enviado.',
    enviando: 'Com internet. Enviando suas vendas…',
    semInternet: 'Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar.',
    vendasAguardando: (n: number) =>
      n === 1 ? '1 venda aguardando envio' : `${n} vendas aguardando envio`,
    itensAguardando: (n: number) =>
      n === 1 ? '1 item aguardando envio' : `${n} itens aguardando envio`,
    baixandoFotos: (feitas: number, total: number) => `Baixando fotos: ${feitas} de ${total}`,
    naoConseguiu: 'Uma venda não conseguiu ser enviada. Avise a dona.',
    naoAceitas: (n: number, soVendas: boolean) =>
      soVendas
        ? n === 1
          ? '1 venda não foi aceita. A dona já foi avisada.'
          : `${n} vendas não foram aceitas. A dona já foi avisada.`
        : n === 1
          ? '1 item não foi aceito. A dona já foi avisada.'
          : `${n} itens não foram aceitos. A dona já foi avisada.`,
    tituloDaLista: 'Envios que não foram aceitos',
    feitoEm: (quando: string) => `Feito em ${quando}`,
    motivo: 'Motivo',
    entendi: 'Entendi',
    entendiDe: (descricao: string) => `Entendi: ${descricao}`,
    dadosAte: (quando: string) => `Dados até ${quando}.`,
    semDados: 'Ainda não recebemos os dados da loja neste computador. Conecte-se à internet.',
  },

  /** Abrir sem internet e computador desligado (glossário, parte 6, tarefa 5.8). */
  semInternetNaEntrada: {
    titulo: 'Entrar sem internet',
    texto:
      'Digite a senha do seu usuário para abrir o Corona Vendas sem internet. Suas vendas continuam salvas.',
    botao: 'Entrar',
  },
  desligando: {
    titulo: 'Estamos enviando as suas vendas.',
    texto:
      'A dona desligou este computador. Mantenha o computador ligado e conectado à internet enquanto enviamos as suas vendas. Depois, os dados serão apagados.',
  },
  desligado: {
    titulo: 'Este computador foi desligado da loja.',
    texto: 'Os dados deste computador foram apagados. Fale com a dona.',
  },

  /** Navegação comum. */
  voltar: 'Voltar',
  carregando: 'Buscando...',
  tentarDeNovo: 'Tentar de novo',

  maleta: {
    titulo: 'Minha maleta',
    textoCarregando: 'Buscando a sua maleta...',
    vazia: 'Você ainda não tem maleta com peças. Quando a dona enviar uma, ela aparece aqui.',
    conferir: 'Conferir maleta',
    situacao: { ENVIADA: 'Enviada', CONFERIDA: 'Conferida', EM_ACERTO: 'Em acerto' } as Record<
      string,
      string
    >,
    tipo: { NOVA: 'Maleta', REPOSICAO: 'Reposição' } as Record<string, string>,
    pecas: 'peças com você',
    colunas: {
      peca: 'Peça',
      codigo: 'Código',
      enviada: 'Enviada',
      vendida: 'Vendida',
      devolvida: 'Devolvida',
      saldo: 'Com você',
      preco: 'Preço',
    },
    legenda: 'Peças da maleta',
    semPecas: 'Nenhuma peça nesta maleta.',
    cotacaoDeHoje: 'Dólar e Guarani pela cotação de hoje.',
    cotacaoSem: 'Sem cotação do Dólar e do Guarani por enquanto: os valores aparecem só em Real.',
  },

  /** Linhas a mais no painel da peça lida (Vender e Conferir maleta). */
  painelDaPeca: {
    naMaleta: 'Na maleta',
    codFabrica: 'COD Fábrica',
    categoria: 'Categoria',
    esperado: 'Esperado',
    /** Vai depois de "Esperado: ", como "Esperado: 3 · Contadas: 2". */
    esperadoEContadas: (esperado: number, contadas: number) =>
      `${esperado} · Contadas: ${contadas}`,
  },

  /** "A etiqueta não lê? Procure pelo nome." (só entre as peças da própria maleta). */
  buscaPorNome: {
    abrir: 'Procurar pelo nome',
    ajuda: 'A etiqueta não lê? Procure pelo nome.',
    campo: 'Nome, referência ou parte do código',
    buscando: 'Procurando...',
    nenhuma: 'Nenhuma peça da sua maleta com esse nome. Confira o que você digitou.',
    escolher: 'Escolher',
    escolherDe: (nome: string) => `Escolher ${nome}`,
    naMaleta: (n: number) => `Na maleta: ${n}`,
    codigo: (c: string) => `Código ${c}`,
    fechar: 'Fechar a busca',
    legenda: 'Peças da sua maleta encontradas',
  },

  conferencia: {
    titulo: 'Conferir maleta',
    ajuda: 'Leia o código de cada peça que você recebeu. Cada leitura conta uma peça.',
    campoCodigo: 'Ler o código da peça',
    lerAgora: 'Ler o código',
    legenda: 'Peças contadas',
    colunas: {
      peca: 'Peça',
      esperado: 'Na maleta',
      contadas: 'Você contou',
      diferenca: 'Diferença',
    },
    concluir: 'Concluir conferência',
    janelaTitulo: 'Concluir a conferência?',
    resumo: (contadas: number, esperadas: number) =>
      `Você contou ${contadas} de ${esperadas} peças.`,
    faltam: (n: number) => `Faltam ${n} peça(s) em relação à lista da maleta.`,
    sobram: (n: number) => `Sobram ${n} peça(s) em relação à lista da maleta.`,
    tudoCerto: 'As peças que você contou conferem com a lista da maleta.',
    avisoSemVolta:
      'Depois de concluir, a conferência não muda. A dona vê o resultado. Se contou errado, fale com ela.',
    feita: 'Conferência concluída.',
    feitaTudoCerto: 'Tudo certo: as peças conferem com a lista da maleta.',
    feitaComDiferenca: (n: number) =>
      `Há diferença em ${n} peça(s). A dona vai ver o resultado. Confira as peças abaixo.`,
    lida: (nome: string, n: number) => `${nome}: ${n} contada(s).`,
    outraEmBreve: 'Leia o código de uma peça por vez.',
  },

  venda: {
    titulo: 'Vender',
    textoCarregando: 'Preparando a venda...',
    semMaleta:
      'Você ainda não tem maleta para vender. Quando a dona enviar uma maleta, ela aparece aqui.',
    conferirAntes:
      'Esta maleta ainda não foi conferida. Confira todas as peças da maleta antes de vender.',
    maleta: 'Maleta',
    lerCodigo: 'Ler o código da peça',
    acabou: 'Esta peça acabou na sua maleta. Escolha outra peça ou fale com a dona.',
    peca: 'Peça',
    qtd: 'Qtd.',
    preco: 'Preço',
    desconto: 'Desconto (R$)',
    descontoInvalido: 'Digite o desconto em reais, por exemplo 5,00.',
    total: 'Total',
    carrinhoVazio: 'Leia o código de uma peça para começar a venda.',
    legendaCarrinho: 'Peças da venda',
    mais: 'Mais uma',
    menos: 'Tirar uma',
    tirar: 'Tirar peça',
    cliente: 'Cliente',
    buscarCliente: 'Buscar cliente (nome, telefone ou CPF)',
    nenhumCliente: 'Nenhum cliente encontrado. Cadastre o cliente novo.',
    cadastrarCliente: 'Cadastrar cliente novo',
    escolher: 'Escolher',
    clienteEscolhido: 'Cliente da venda:',
    trocarCliente: 'Trocar cliente',
    pagamento: 'Como o cliente pagou?',
    formas: {
      DINHEIRO: 'Dinheiro',
      PIX: 'Pix',
      CARTAO: 'Cartão',
      CHEQUE: 'Cheque',
    } as Record<string, string>,
    aPrazo: 'A prazo (fiado)',
    valorPago: 'Valor pago agora (R$)',
    valorPagoInvalido: 'Digite o valor pago em reais, por exemplo 50,00.',
    pagoMaiorQueTotal: 'O valor pago passa do total da venda. Confira o valor.',
    aReceber: 'Fica para receber',
    faltaCliente: 'Escolha o cliente da venda.',
    faltaPeca: 'Leia o código de pelo menos uma peça.',
    concluir: 'Concluir venda',
    janelaTitulo: 'Concluir a venda?',
    janelaTexto: (cliente: string) =>
      `A venda para ${cliente} será registrada e as peças saem da sua maleta. Se errar, você pode estornar a venda hoje.`,
    registrada: 'Venda registrada.',
    novaVenda: 'Nova venda',
    verVendas: 'Ver minhas vendas',
    totalDoServidor: 'Total da venda',
    cotacaoSem: 'Sem cotação do Dólar e do Guarani por enquanto: os valores aparecem só em Real.',
    cotacaoDe: (data: string) => `Cotação do Dólar e do Guarani de ${data}.`,
  },

  cliente: {
    titulo: 'Cadastrar cliente',
    tituloDaEdicao: 'Editar cliente',
    salvarEdicao: 'Salvar alterações',
    semMudanca: 'Você não mudou nada neste cadastro.',
    observacoes: 'Observações',
    nome: 'Nome',
    telefone: 'Telefone',
    whatsapp: 'WhatsApp',
    cpf: 'CPF (opcional)',
    nascimento: 'Data de nascimento (opcional)',
    salvar: 'Salvar cliente',
    faltaNome: 'Escreva o nome do cliente.',
    jaCadastrado: (nome: string) => `Este cliente já está cadastrado: ${nome}.`,
    usarExistente: 'Usar este cliente',
  },

  clientes: {
    titulo: 'Clientes',
    textoCarregando: 'Buscando os clientes...',
    buscar: 'Buscar (nome, telefone ou CPF)',
    vazio: 'Ainda não há clientes. Cadastre o primeiro.',
    nenhum: 'Nenhum cliente encontrado.',
    cadastrar: 'Cadastrar cliente',
    legenda: 'Clientes da loja',
    colunas: {
      nome: 'Nome',
      telefone: 'Telefone',
      cidade: 'Cidade',
      aReceber: 'A receber',
      acoes: '',
    },
    ver: 'Ver',
    detalhe: 'Cliente',
    contato: 'Contato',
    cpf: 'CPF',
    nascimento: 'Nascimento',
    observacoes: 'Observações',
    vendasDoCliente: 'Vendas deste cliente',
    semVendas: 'Este cliente ainda não comprou com você.',
    salvo: 'Cliente cadastrado.',
    editar: 'Editar cliente',
    atualizado: 'Cliente atualizado.',
    soQuemAtende: 'Só o vendedor que atende o cliente pode mudar o cadastro dele.',
  },

  numeros: {
    titulo: 'Meus números',
    textoCarregando: 'Buscando os seus números...',
    periodo: 'Período',
    de: 'De',
    ate: 'Até',
    aplicar: 'Ver números',
    periodoInvertido: 'A data inicial não pode ser depois da final.',
    vendasTitulo: 'Minhas vendas no período',
    agrupar: 'Mostrar por',
    agrupamentos: { cliente: 'Cliente', peca: 'Peça', dia: 'Dia' } as Record<string, string>,
    colunasVendas: {
      chave: 'Item',
      quantidade: 'Quantidade',
      total: 'Total',
      desconto: 'Descontos',
    },
    semVendas: 'Nenhuma venda neste período.',
    totalVendido: 'Total vendido',
    recebimentosTitulo: 'Recebimentos por forma de pagamento',
    recebimentosColunas: { forma: 'Forma', pagamentos: 'Pagamentos', total: 'Recebido' },
    semRecebimentos: 'Nenhum recebimento neste período.',
    totalRecebido: 'Total recebido (já sem os reembolsos)',
    aReceberTitulo: 'Quanto os clientes ainda devem',
    aReceberColunas: {
      cliente: 'Cliente',
      vendido: 'Vendido',
      pago: 'Pago',
      aReceber: 'A receber',
    },
    ninguemDeve: 'Nenhum cliente deve nada agora.',
    totalAReceber: 'Total a receber',
    minhasVendas: 'Ver minhas vendas',
    cotacaoDeCadaLancamento:
      'Dólar e Guarani pela cotação do dia de cada venda e de cada pagamento. Sem cotação, só Real.',
    vendasEstornadasNaoContam: 'Vendas estornadas não entram nas contas.',
  },

  vendas: {
    titulo: 'Minhas vendas',
    textoCarregando: 'Buscando as suas vendas...',
    vazia: 'Você ainda não fez nenhuma venda.',
    legenda: 'Minhas vendas',
    colunas: { data: 'Data', cliente: 'Cliente', total: 'Total', situacao: 'Situação', acoes: '' },
    situacao: { CONCLUIDA: 'Concluída', ESTORNADA: 'Estornada' } as Record<string, string>,
    ver: 'Ver',
    detalhe: 'Venda',
    pago: 'Pago',
    aReceber: 'A receber',
    pagamentos: 'Pagamentos',
    reembolso: 'Reembolso',
    estornar: 'Estornar venda',
    estornoTitulo: 'Estornar esta venda?',
    estornoTexto:
      'As peças voltam para a sua maleta e o dinheiro que o cliente pagou vira reembolso. Isso não pode ser desfeito.',
    motivo: 'Motivo do estorno',
    faltaMotivo: 'Diga o motivo do estorno.',
    estornada: 'Venda estornada.',
    motivoDoEstorno: 'Motivo',
  },
} as const;

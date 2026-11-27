import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DE_CONSIGNADO } from './idioma/es/consignado';
/**
 * Textos das telas do consignado ao cliente no Corona Gestão (tarefa 9.4; ESPECIFICACAO §23; ADR-0016): Fechamento da maleta,
 * notas, semanas, clientes e avisos. Linguagem da loja: quem usa o aplicativo de vendas é o **Representante** e o **Cliente** pega peças em
 * consignado. A dona é tratada por "você". Quem manda é o servidor nas mensagens de regra; aqui só os rótulos.
 */
const pecas = (n: number) => (n === 1 ? '1 peça' : `${n} peças`);

export const PT_TEXTOS_DE_CONSIGNADO = {
  menu: {
    grupo: 'Consignado',
    fechamento: 'Fechamento da maleta',
    notas: 'Notas dos clientes',
    semanas: 'Semanas dos representantes',
    clientes: 'Clientes dos representantes',
    avisos: 'Avisos do consignado',
  },
  pecas,
  semDados: '—',
  fechamento: {
    titulo: 'Fechamento da maleta',
    ajuda:
      'A maleta voltou? Escolha a maleta e leia o código de cada peça que chegou. O sistema compara com o que devia estar na maleta e mostra a diferença. O acerto em dinheiro com o representante não passa por aqui.',
    escolher: 'Maleta que voltou',
    escolha: 'Escolha a maleta',
    nome: (codigo: string, representante: string, semana: number, enviadaEm: string) =>
      `Maleta ${codigo}, de ${representante}, semana ${semana}${enviadaEm ? `, enviada em ${enviadaEm}` : ''}`,
    fechadaNaLista: ' (já fechada)',
    abrir: 'Abrir o fechamento',
    nenhuma:
      'Não há maleta enviada para fechar. Monte e envie a maleta ao representante; quando ela voltar, feche aqui.',
    montarMaleta: 'Montar maleta',
    carregando: 'Carregando o fechamento...',
    comecarTitulo: 'O fechamento desta maleta ainda não começou',
    comecarTexto:
      'Ao começar, você lê o código de cada peça que voltou. Nada muda no estoque até você concluir.',
    comecar: 'Começar o fechamento',
    comecando: 'Começando...',
    leitor: {
      titulo: 'Ler as peças que voltaram',
      ajuda: 'Leia o código de cada peça que chegou. Cada leitura soma 1 peça.',
      adicionar: 'Somar peça',
    },
    lida: (nome: string) => `Lida: ${nome}. Somou 1 peça.`,
    naoAchou: (codigo: string) =>
      `Não achei nenhuma peça com o código ${codigo}. Confira o código e leia de novo.`,
    salvando: 'Guardando a leitura...',
    voltarParaLista: 'Escolher outra maleta',
    emAndamento: 'Fechamento em andamento. Você pode ler, sair e voltar depois.',
    concluido: (quando: string) => `Fechamento concluído${quando ? ` em ${quando}` : ''}.`,
    aindaComClientes: (n: number) =>
      `Ainda há ${pecas(n)} com clientes. Você pode concluir: o acerto dessas notas acontece depois e as peças voltam à maleta fechada.`,
    /** C4: falta e sobra são avisos separados, com a lista das peças (uma não anula a outra). */
    faltam: (n: number) =>
      `${n === 1 ? 'Falta' : 'Faltam'} ${pecas(n)} em relação ao que devia estar na maleta:`,
    sobram: (n: number) =>
      `${n === 1 ? 'Sobra' : 'Sobram'} ${pecas(n)} em relação ao que devia estar na maleta. A sobra não volta ao estoque: confira se a peça é de outra maleta.`,
    linhaDaDiferenca: (nome: string, n: number) => `${nome}: ${pecas(n)}`,
    semDiferenca: 'Tudo o que devia estar na maleta foi lido.',
    /** C3: o que fazer com cada peça que falta (§23.10, item 1). */
    decidir: {
      titulo: 'O que fazer com cada peça que falta',
      ajuda:
        'Escolha uma opção para cada peça. Cobrar do representante: ele fica devendo a peça, pelo preço da maleta. Perda da loja: a loja assume a perda, e você diz o motivo.',
      peca: (nome: string, n: number) => `${nome}: falta ${pecas(n)}`,
      cobrar: 'Cobrar do representante',
      perda: 'Perda da loja',
      motivo: 'Motivo da perda',
      motivoAjuda: 'Por exemplo: quebrou, foi roubada, sumiu na viagem.',
      falta: 'Escolha o que fazer com cada peça que falta antes de concluir.',
      faltaMotivo: 'Diga o motivo da perda da loja.',
    },
    registradas: {
      titulo: 'Faltas registradas no fechamento',
      cobrar: (nome: string, n: number, valor: string) =>
        `${nome}: ${pecas(n)}. Cobrar do representante: ${valor}.`,
      perda: (nome: string, n: number, motivo: string) =>
        `${nome}: ${pecas(n)}. Perda da loja${motivo ? ` (${motivo})` : ''}.`,
      totalNestaMaleta: (representante: string, valor: string) =>
        `A cobrar de ${representante} nesta maleta: ${valor}.`,
      totalDasMaletas: (valor: string) => `Somando todas as maletas dele: ${valor}.`,
    },
    /** Receber a cobrança da falta (§23.10, item 1a). */
    receber: {
      pagoRotulo: 'Pago:',
      faltaRotulo: 'Falta pagar:',
      registrar: 'Registrar pagamento',
      registrarDe: (nome: string) => `Registrar pagamento de ${nome}`,
      valor: 'Valor pago',
      forma: 'Como pagou',
      formas: { DINHEIRO: 'Dinheiro', PIX: 'Transferência' } as Record<string, string>,
      confirmar: 'Registrar',
      registrando: 'Registrando...',
      cancelar: 'Cancelar',
      valorInvalido: 'Informe o valor pago, maior que zero.',
      registrado: 'Pagamento registrado. Ele entrou no caixa.',
      pagamento: (data: string, forma: string) => `${data} · ${forma} ·`,
      desfeito: (motivo: string) => `Desfeito${motivo ? `: ${motivo}` : ''}`,
      desfazer: 'Desfazer pagamento',
      desfazerTitulo: 'Desfazer este pagamento?',
      desfazerTexto:
        'O pagamento sai do caixa e o valor volta a faltar. Diga o motivo: ele fica guardado.',
      motivo: 'Motivo',
      motivoFalta: 'Diga o motivo de desfazer o pagamento.',
      desfazerSim: 'Desfazer',
      voltar: 'Voltar',
      desfeitoAgora: 'Pagamento desfeito. Ele saiu do caixa.',
      totalPago: (pago: string, falta: string) =>
        `Nesta maleta, pago: ${pago}; falta pagar: ${falta}.`,
      totalPagoDasMaletas: (falta: string) => `Falta pagar somando todas as maletas: ${falta}.`,
    },
    /** A tabela das faltas que vai só no PDF do relatório (depois das duas partes). */
    pdfDasFaltas: {
      titulo: 'Faltas registradas no fechamento',
      colunas: ['Peça', 'Quantidade', 'Preço da maleta', 'Valor', 'Decisão', 'Motivo'],
      decisoes: {
        COBRAR_DO_REPRESENTANTE: 'Cobrar do representante',
        PERDA_DA_LOJA: 'Perda da loja',
      } as Record<string, string>,
      totalACobrar: 'Total a cobrar do representante',
      totalDaPerda: 'Total da perda da loja',
      totalPago: 'Pago pelo representante',
      totalFaltaPagar: 'Falta pagar',
    },
    /** C2: peças que as clientes devolvem depois de a maleta ser fechada (§23.10, item 3). */
    depois: {
      titulo: 'Ler as peças que voltaram depois',
      ajuda:
        'A maleta já está fechada. Leia aqui as peças que as clientes devolveram depois: cada leitura devolve 1 peça ao estoque da loja.',
      adicionar: 'Devolver ao estoque',
      paraLer: (n: number) => `Peças que voltaram depois: ${pecas(n)} para ler.`,
      aindaComClientes: (n: number) => `Ainda com clientes: ${pecas(n)}.`,
      lida: (nome: string) => `Lida: ${nome}. Voltou 1 peça ao estoque.`,
    },
    concluir: 'Concluir o fechamento',
    concluindo: 'Concluindo...',
    confirmar: {
      titulo: 'Concluir o fechamento?',
      texto:
        'As peças lidas voltam ao estoque da loja, até o que a maleta devia ter, e a maleta fica fechada. Cada peça que falta fica registrada como você escolheu. Essa ação não tem volta.',
      concluir: 'Concluir',
      voltar: 'Ainda não',
    },
    concluiu: 'Fechamento concluído. As peças lidas voltaram ao estoque e a maleta está fechada.',
    maletaDoFechamento: (codigo: string, representante: string) =>
      `Maleta ${codigo}, de ${representante}`,
  },
  notas: {
    titulo: 'Notas dos clientes',
    ajuda:
      'As notas que os representantes fizeram para os clientes levarem peças em consignado. Aqui você só olha: as notas e os acertos nascem no aplicativo do representante.',
    filtros: {
      rotulo: 'Filtros das notas',
      representante: 'Representante',
      todos: 'Todos os representantes',
      situacao: 'Situação',
      todasAsSituacoes: 'Todas as situações',
      texto: 'Nome do cliente ou número da nota',
    },
    situacoes: {
      EM_ABERTO: 'Em aberto',
      PENDENTE: 'Pendente',
      FECHADO: 'Fechado',
      REMARCADO: 'Remarcado',
    } as Record<string, string>,
    colunas: {
      nota: 'Nota nº',
      cliente: 'Cliente',
      saida: 'Saída',
      cobranca: 'Data do retorno',
      itens: 'Itens DX',
      valor: 'Valor DX',
      situacao: 'Situação',
      acoes: 'Ações',
    },
    legenda: 'Notas dos clientes',
    vazio: 'Nenhuma nota com estes filtros.',
    ver: 'Ver a nota',
    verDaNota: (numero: string) => `Ver a nota ${numero}`,
    limite: 'Aparecem as 200 notas mais novas. Use os filtros para achar uma nota mais antiga.',
    carregando: 'Carregando as notas...',
    detalhe: {
      titulo: (numero: string) => `Nota ${numero}`,
      voltar: 'Voltar para as notas',
      cliente: 'Cliente',
      saida: 'Saída',
      cobranca: 'Data do retorno',
      situacao: 'Situação',
      pecas: 'Peças que o cliente levou',
      colunasPecas: {
        peca: 'Peça',
        quantidade: 'Qtd. DX',
        preco: 'Preço unitário',
        subtotal: 'Sub-total',
      },
      acertos: 'Acertos desta nota',
      semAcerto: 'Esta nota ainda não teve acerto.',
      acerto: (semana: number) => `Acerto da semana ${semana}`,
      estornado: (motivo: string) => `Acerto estornado${motivo ? `: ${motivo}` : ''}.`,
      dx: 'Valor DX',
      dv: 'Valor DV',
      vd: 'Valor VD',
      itens: (dx: number, dv: number, vd: number) => `Itens: DX ${dx}, DV ${dv}, VD ${vd}`,
      comissao: (percentual: string) => `Comissão (${percentual}%)`,
      liquido: 'Valor líquido',
      pendenciasAnteriores: 'Pendências anteriores',
      totalAReceber: 'Total a receber',
      totalPago: 'Total pago',
      ficouDevendo: 'Ficou devendo',
      saldoDoDebito: 'Ainda falta receber desta nota',
      recibos: 'Recibos',
      semRecibo: 'Nenhum pagamento registrado.',
      recibo: (semana: number, sequencia: number) =>
        `R ${String(semana).padStart(7, '0')}-${String(sequencia).padStart(2, '0')}`,
      aAplicar: (valor: string) => `Sobra a aplicar no próximo débito do cliente: ${valor}`,
      reciboEstornado: 'Recibo estornado',
      formas: {
        DINHEIRO: 'Dinheiro',
        PIX: 'Pix',
        DEBITO: 'Débito',
        CREDITO: 'Crédito à vista',
      } as Record<string, string>,
    },
  },
  semanas: {
    titulo: 'Semanas dos representantes',
    ajuda:
      'Escolha o representante para ver as semanas dele na praça: quantas notas, o que foi deixado (DX), o vendido (VD), a comissão dos clientes e o que foi recebido.',
    representante: 'Representante',
    escolha: 'Escolha o representante',
    colunas: {
      semana: 'Semana nº',
      situacao: 'Situação',
      notas: 'Notas',
      dx: 'Valor DX',
      vd: 'Valor VD',
      comissao: 'Comissão',
      recebido: 'Recebido',
      acoes: 'Ações',
    },
    situacoes: { ABERTA: 'Em andamento', FECHADA: 'Fechada' } as Record<string, string>,
    legenda: 'Semanas do representante',
    vazio:
      'Este representante ainda não abriu nenhuma semana. Ele abre a semana no computador dele, ao fazer a primeira nota.',
    verNotas: 'Ver as notas',
    verNotasDa: (semana: number) => `Ver as notas da semana ${semana}`,
    carregando: 'Carregando as semanas...',
    abertaEm: (quando: string) => `Aberta em ${quando}`,
  },
  clientes: {
    titulo: 'Clientes dos representantes',
    ajuda:
      'Cada representante tem a sua lista de clientes. Aqui você vê o cadastro, as fotos, a comissão, o que o cliente deve e as notas dele. Só leitura: o cadastro é feito no aplicativo do representante.',
    buscar: 'Nome do cliente',
    buscando: 'Procurando...',
    legenda: 'Clientes',
    colunas: { nome: 'Cliente', telefone: 'Telefone', acoes: 'Ações' },
    vazio: 'Nenhum cliente com este nome.',
    abrir: 'Ver a ficha',
    abrirDe: (nome: string) => `Ver a ficha de ${nome}`,
    voltar: 'Voltar para os clientes',
    carregando: 'Carregando a ficha...',
    ficha: {
      titulo: (nome: string) => `Ficha de ${nome}`,
      dados: 'Cadastro',
      telefone: 'Telefone',
      whatsapp: 'WhatsApp',
      endereco: 'Endereço',
      documento: 'Documento',
      documentoDe: (tipo: string, numero: string) => (tipo ? `${tipo}: ${numero}` : numero),
      indicadoPor: 'Indicado por',
      comoConheceu: 'Como conheceu',
      comissao: 'Comissão do cliente',
      comissaoValor: (p: string) => `${p}%`,
      representante: 'Representante',
      naoInformado: 'Não informado',
      pendencia: 'O cliente deve',
      semPendencia: 'Não deve nada.',
      pecasComEle: 'Peças que estão com o cliente agora',
      colunasPecas: { peca: 'Peça', quantidade: 'Quantidade' },
      semPecas: 'O cliente não está com nenhuma peça.',
      notas: 'Notas do cliente',
      semNotas: 'Este cliente ainda não tem nota.',
      fotos: 'Fotos',
      fotosAjuda:
        'Fotos do cliente e do documento são dados pessoais: só você e o representante dele veem.',
      foto: 'Foto do cliente',
      documentoFrente: 'Documento, frente',
      documentoVerso: 'Documento, verso',
      semFoto: 'A foto ainda não chegou.',
      carregandoFoto: 'Carregando a foto...',
      fotoNaoCarregou: 'Não deu para carregar a foto. Tente de novo.',
      ver: 'Ver a foto',
      verFoto: (qual: string) => `Ver a foto: ${qual}`,
      esconder: 'Esconder a foto',
    },
  },
  avisos: {
    titulo: 'Avisos do consignado',
    ajuda:
      'Coisas que aconteceram no balcão e que o sistema aceitou, mas que você deve olhar: peça sem saldo na maleta, preço diferente, valor recalculado, pagamento acima do devido e cadastro repetido.',
    soNaoVistos: 'Mostrar só os que ainda não vi',
    colunas: { quando: 'Quando', aviso: 'Aviso', visto: 'Visto', acoes: 'Ações' },
    legenda: 'Avisos do consignado',
    vazio: 'Nenhum aviso. Tudo o que veio do balcão bateu com o sistema.',
    naoVisto: 'Novo',
    visto: (quando: string) => `Visto em ${quando}`,
    marcar: 'Marcar como visto',
    marcarDe: (aviso: string) => `Marcar como visto: ${aviso}`,
    carregando: 'Carregando os avisos...',
    tipos: {
      PECA_SEM_SALDO_NA_MALETA: 'Peça saiu sem saldo na maleta.',
      PRECO_DIFERENTE_DA_MALETA: 'Preço da nota diferente do preço da maleta.',
      VALOR_DO_ACERTO_RECALCULADO: 'O valor do acerto foi recalculado pelo sistema.',
      PAGAMENTO_ACIMA_DO_DEVIDO: 'Pagamento acima do que o cliente devia.',
      DOCUMENTO_REPETIDO: 'Possível cliente cadastrado duas vezes (documento repetido).',
    } as Record<string, string>,
    tipoDesconhecido: 'Aviso do consignado. Abra a nota ou o cliente para conferir.',
  },
};

export const TEXTOS_DE_CONSIGNADO = textosVivos(
  'consignado',
  PT_TEXTOS_DE_CONSIGNADO,
  ES_TEXTOS_DE_CONSIGNADO,
);

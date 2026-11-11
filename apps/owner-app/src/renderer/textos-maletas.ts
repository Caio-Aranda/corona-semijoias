/**
 * Textos das telas de maletas (menu Maletas → Montar maleta). Vêm do glossário da interface
 * (parte 12). A palavra "remessa" não aparece em tela: é sempre "maleta". Mensagens de regra
 * (limite, saldo, restrição) vêm do servidor, nunca daqui.
 */
const pecas = (n: number) => (n === 1 ? '1 peça' : `${n} peças`);

export const TEXTOS_DE_MALETAS = {
  menu: { grupo: 'Maletas', montar: 'Montar maleta' },
  titulo: 'Montar maleta',
  pecas,
  nova: {
    titulo: 'Nova maleta',
    ajuda: 'Escolha o vendedor, a semana e o tipo. Depois você lê as peças com o leitor.',
    vendedor: 'Vendedor',
    escolhaVendedor: 'Escolha o vendedor',
    vendedorAviso: 'Escolha o vendedor.',
    semana: 'Semana',
    escolhaSemana: 'Escolha a semana',
    semanaAviso: 'Escolha a semana.',
    nomeDaSemana: (numero: string) => `Semana ${numero}`,
    praca: 'Praça',
    pracaDoVendedor: 'A praça do vendedor',
    tipo: 'Tipo da maleta',
    tipos: { NOVA: 'Maleta nova', REPOSICAO: 'Reposição' } as Record<string, string>,
    comecar: 'Começar a montar',
    comecando: 'Começando...',
  },
  rascunhos: {
    titulo: 'Maletas em montagem',
    vazia: 'Nenhuma maleta em montagem. Comece uma nova acima.',
    legenda: 'Maletas em montagem',
    colunas: {
      vendedor: 'Vendedor',
      semana: 'Semana',
      praca: 'Praça',
      pecas: 'Peças',
      valor: 'Valor',
      criada: 'Começada em',
      acoes: 'O que fazer',
    },
    retomar: 'Continuar montando',
    retomarDe: (vendedor: string) => `Continuar montando a maleta de ${vendedor}`,
    excluir: 'Excluir',
    excluirDe: (vendedor: string) => `Excluir a maleta de ${vendedor}`,
    confirmarExcluir: {
      titulo: 'Excluir esta maleta em montagem?',
      texto: (vendedor: string, quantas: string) =>
        `A maleta de ${vendedor} (${quantas}) será excluída. As peças continuam no estoque, nada foi enviado.`,
      excluir: 'Excluir a maleta',
      excluindo: 'Excluindo...',
      voltar: 'Voltar',
    },
  },
  leitor: {
    titulo: 'Ler uma peça',
    campo: 'Código da peça ou nome',
    ajuda: 'O leitor já aperta Enter sozinho. Ler a mesma peça de novo soma 1 na maleta.',
    adicionar: 'Colocar na maleta',
    procurando: 'Procurando a peça...',
    naoAchou: 'Peça não encontrada',
    naoAchouTexto: (codigo: string) =>
      `Não encontramos nenhuma peça com o código ${codigo}. Confira o código ou cadastre a peça em Cadastros → Produtos.`,
    desativada: (nome: string) =>
      `${nome} está desativada e não pode entrar na maleta. Reative a peça em Cadastros → Produtos.`,
    semEstoque: (nome: string) =>
      `${nome} está sem estoque. Receba uma compra ou faça uma entrada no estoque antes de montar a maleta.`,
    passaDoEstoque: (nome: string, saldo: number) =>
      `Só há ${pecas(saldo)} de ${nome} no estoque. A maleta já tem todas.`,
    entrou: (nome: string) => `${nome} entrou na maleta.`,
    somou: (nome: string, quantidade: number) => `${nome}: agora são ${quantidade} na maleta.`,
    varios: (n: number) => `Achamos ${n} peças com esse nome. Escolha qual vai na maleta.`,
    escolher: 'Escolher',
  },
  peca: {
    titulo: 'Última peça lida',
    nome: 'Peça',
    codigo: 'Código',
    estoque: 'No estoque',
    nestaMaleta: 'Nesta maleta',
    preco: 'Preço',
  },
  itens: {
    legenda: 'Peças da maleta',
    vazia: 'A maleta está vazia. Leia a primeira peça.',
    colunas: {
      foto: 'Foto',
      nome: 'Peça',
      codigo: 'Código',
      quantidade: 'Quantidade',
      preco: 'Preço',
      subtotal: 'Subtotal',
      acoes: 'O que fazer',
    },
    quantidadeDe: (nome: string) => `Quantidade de ${nome}`,
    quantidadeInvalida: 'Digite uma quantidade inteira, de 1 em diante.',
    atualizar: 'Atualizar',
    atualizarDe: (nome: string) => `Atualizar a quantidade de ${nome}`,
    tirar: 'Tirar',
    tirarDe: (nome: string) => `Tirar ${nome} da maleta`,
    salvando: 'Salvando...',
  },
  totais: {
    titulo: 'Totais',
    pecas: 'Peças na maleta',
    valor: 'Valor da maleta',
  },
  limite: {
    titulo: 'Limite do vendedor',
    limite: 'Limite do vendedor',
    jaComEle: 'Já está com ele',
    estaMaleta: 'Esta maleta',
    sobra: 'Sobra',
    semLimite: 'Sem limite cadastrado',
    passou: (quanto: string) =>
      `Esta maleta passa do limite em ${quanto}. Tire algumas peças ou aumente o limite na ficha do vendedor.`,
    carregando: 'Conferindo o limite...',
  },
  avisoDeRestricao: 'Atenção ao vendedor:',
  voltar: 'Voltar à lista',
  enviar: 'Enviar maleta',
  vazia: 'Coloque pelo menos uma peça na maleta antes de enviar.',
  confirmacao: {
    titulo: 'Enviar esta maleta?',
    texto: (vendedor: string, pecasTexto: string, valor: string) =>
      `Vão sair do estoque ${pecasTexto} para ${vendedor}, num valor de ${valor}. Depois de enviada, a maleta não pode mais ser alterada.`,
    enviar: 'Enviar maleta',
    enviando: 'Enviando...',
    voltar: 'Voltar e conferir',
  },
  enviada: {
    titulo: 'Maleta enviada',
    texto: (vendedor: string, pecasTexto: string, valor: string) =>
      `A maleta de ${vendedor} foi enviada: ${pecasTexto}, num valor de ${valor}. O estoque já foi atualizado.`,
    jaEstava: 'Esta maleta já estava enviada',
    tituloDaFicha: 'Maleta enviada',
    imprimir: 'Imprimir nota',
    montarOutra: 'Montar outra maleta',
    ficha: {
      vendedor: 'Vendedor',
      semana: 'Semana',
      praca: 'Praça',
      tipo: 'Tipo',
      situacao: 'Situação',
      enviadaEm: 'Enviada em',
      situacoes: {
        ENVIADA: 'Enviada',
        CONFERIDA: 'Conferida',
        EM_ACERTO: 'Em acerto',
        ACERTADA: 'Acertada',
        CANCELADA: 'Cancelada',
        RASCUNHO: 'Em montagem',
      } as Record<string, string>,
    },
  },
  carregando: 'Carregando...',
  naoAbriu: 'Não foi possível abrir esta maleta. Tente de novo.',
} as const;

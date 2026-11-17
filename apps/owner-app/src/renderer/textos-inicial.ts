import { textosVivos } from './idioma/idioma';
import { ES_TEXTOS_DA_INICIAL } from './idioma/es/inicial';
/** Textos da tela inicial da dona: linguagem da loja, sem termo técnico. */
export const PT_TEXTOS_DA_INICIAL = {
  saudacao: (nome: string | null) => (nome ? `Olá, ${nome}.` : 'Olá.'),
  oQueFazer: 'O que você quer fazer?',
  atalhos: {
    painel: { nome: 'Painel da semana', ajuda: 'ver o que foi enviado, devolvido e vendido' },
    montar: { nome: 'Montar maleta', ajuda: 'separar as peças para o vendedor' },
    receber: { nome: 'Receber compra', ajuda: 'dar entrada nas peças que chegaram' },
    acerto: { nome: 'Fazer acerto', ajuda: 'fechar as contas com o vendedor' },
    cadastrar: { nome: 'Cadastrar produto', ajuda: 'incluir uma peça nova, com foto e código' },
    relatorios: { nome: 'Relatórios', ajuda: 'ver os números da loja' },
  },
  cotacao: {
    rotulo: 'Cotação do dia',
    dolar: 'Dólar (1 US$):',
    guarani: 'Guarani (1 Gs.):',
    semCotacao: 'Sem cotação do dia',
  },
  resumo: {
    titulo: 'Resumo da semana',
    vendido: 'Vendido na semana',
    maletas: 'Maletas da semana',
    acertos: 'Acertos da semana',
    avisos: 'Avisos para ver',
  },
  tranquilo: 'Não há nada esperando por você agora.',
};

export const TEXTOS_DA_INICIAL = textosVivos('inicial', PT_TEXTOS_DA_INICIAL, ES_TEXTOS_DA_INICIAL);

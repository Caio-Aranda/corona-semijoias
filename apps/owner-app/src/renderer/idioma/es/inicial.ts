import type { NoMesmoFormato } from '../idioma';
import type { PT_TEXTOS_DA_INICIAL } from '../../textos-inicial';

export const ES_TEXTOS_DA_INICIAL: NoMesmoFormato<typeof PT_TEXTOS_DA_INICIAL> = {
  saudacao: (nome: string | null) => (nome ? `Hola, ${nome}.` : 'Hola.'),
  oQueFazer: '¿Qué quiere hacer?',
  atalhos: {
    painel: { nome: 'Panel de la semana', ajuda: 'ver lo que se envió, se devolvió y se vendió' },
    montar: { nome: 'Armar maleta', ajuda: 'separar las piezas para el representante' },
    receber: { nome: 'Recibir compra', ajuda: 'dar entrada a las piezas que llegaron' },
    acerto: { nome: 'Hacer acerto', ajuda: 'cerrar las cuentas con el representante' },
    cadastrar: { nome: 'Registrar producto', ajuda: 'agregar una pieza nueva, con foto y código' },
    relatorios: { nome: 'Informes', ajuda: 'ver los números de la tienda' },
  },
  cotacao: {
    rotulo: 'Cotización del día',
    dolar: '1 US$ =',
    guarani: 'R$ 1 =',
    semCotacao: 'Sin cotización del día',
    mudar: 'Cambiar la cotización',
  },
  resumo: {
    titulo: 'Resumen de la semana',
    vendido: 'Vendido en la semana',
    maletas: 'Maletas de la semana',
    acertos: 'Acertos de la semana',
    avisos: 'Avisos para ver',
  },
  tranquilo: 'No hay nada esperándola ahora.',
};

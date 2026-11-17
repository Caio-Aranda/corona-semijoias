import { textosVivos } from './idioma/idioma';

/** Textos do seletor de idioma (ES / PT). */
export const TEXTOS_DE_IDIOMA = textosVivos(
  'idioma',
  {
    rotulo: 'Idioma',
    alteracoesNaoSalvas:
      'Há alterações que ainda não foram salvas. Se trocar o idioma agora, elas se perdem. Trocar mesmo assim?',
  },
  {
    rotulo: 'Idioma',
    alteracoesNaoSalvas:
      'Hay cambios que todavía no se guardaron. Si cambia el idioma ahora, se pierden. ¿Cambiar de todos modos?',
  },
);

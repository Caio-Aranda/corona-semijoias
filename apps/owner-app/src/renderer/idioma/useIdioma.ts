import { useSyncExternalStore } from 'react';
import { idiomaAtual, ouvirIdioma, type Idioma } from './idioma';

/** O idioma de agora; a tela que usa este gancho se refaz quando a pessoa troca. */
export function useIdioma(): Idioma {
  return useSyncExternalStore(ouvirIdioma, idiomaAtual, idiomaAtual);
}

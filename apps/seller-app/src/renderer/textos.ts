import { idiomaAtual } from './idioma';
import { TEXTOS_ES } from './textos-es';
import { TEXTOS_PT } from './textos-pt';

export { TEXTOS_ES, TEXTOS_PT };

/**
 * Os textos de tela no idioma de agora (espanhol ou português). Cada leitura olha o idioma escolhido, então basta
 * a tela desenhar de novo quando a pessoa troca (o `App` assina `useIdioma`). Nenhum texto fica solto no componente.
 */
export const TEXTOS: typeof TEXTOS_PT = new Proxy({} as typeof TEXTOS_PT, {
  get: (_alvo, chave) =>
    ((idiomaAtual() === 'es' ? TEXTOS_ES : TEXTOS_PT) as Record<string | symbol, unknown>)[chave],
  has: (_alvo, chave) => chave in TEXTOS_PT,
  ownKeys: () => Reflect.ownKeys(TEXTOS_PT),
  getOwnPropertyDescriptor: (_alvo, chave) => ({
    enumerable: true,
    configurable: true,
    value: (idiomaAtual() === 'es' ? TEXTOS_ES : TEXTOS_PT)[chave as keyof typeof TEXTOS_PT],
  }),
});

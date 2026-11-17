import { useSyncExternalStore } from 'react';

/** Os dois idiomas do Corona Vendas. O padrão é o espanhol (decisão de 17/11/2026). */
export type Idioma = 'es' | 'pt';

export const IDIOMAS: readonly Idioma[] = ['es', 'pt'];
export const IDIOMA_PADRAO: Idioma = 'es';

/** Como cada idioma escreve datas e horas (o dinheiro continua R$, US$ e Gs., igual nos dois). */
export const LOCAL_DO_IDIOMA: Record<Idioma, string> = { es: 'es-PY', pt: 'pt-BR' };

const ehIdioma = (v: unknown): v is Idioma => v === 'es' || v === 'pt';

/**
 * Onde a escolha fica guardada: no computador, pelo processo principal (a tela nunca usa armazenamento do navegador,
 * ver `varredura.test.ts`). Sem esse guardador (nos testes), a escolha vale só até fechar.
 */
export interface GuardadorDoIdioma {
  lerIdioma(): Promise<Idioma | null>;
  gravarIdioma(idioma: Idioma): Promise<void>;
}

let guardador: GuardadorDoIdioma | null = null;
let atual: Idioma = IDIOMA_PADRAO;
const ouvintes = new Set<() => void>();

export function idiomaAtual(): Idioma {
  return atual;
}

/** Troca o idioma, guarda no computador e avisa as telas abertas (vale já, sem reiniciar). */
export function definirIdioma(novo: Idioma): void {
  if (!ehIdioma(novo)) return;
  // Não guardou: a troca vale até fechar o programa.
  void guardador?.gravarIdioma(novo)?.catch(() => undefined);
  if (novo === atual) return;
  atual = novo;
  if (typeof document !== 'undefined') document.documentElement.lang = LOCAL_DO_IDIOMA[novo];
  ouvintes.forEach((o) => o());
}

/**
 * Chamado uma vez, antes de desenhar a primeira tela: lê o idioma guardado neste computador (ou fica no padrão).
 * Sem resposta do processo principal, vale o padrão.
 */
export async function iniciarIdioma(novoGuardador: GuardadorDoIdioma): Promise<void> {
  guardador = novoGuardador;
  try {
    const guardado = await novoGuardador.lerIdioma();
    if (ehIdioma(guardado) && guardado !== atual) {
      atual = guardado;
      if (typeof document !== 'undefined')
        document.documentElement.lang = LOCAL_DO_IDIOMA[guardado];
      ouvintes.forEach((o) => o());
    }
  } catch {
    // Sem o que ler: fica o padrão.
  }
}

/** Só para os testes: volta ao padrão e solta o guardador. */
export function reiniciarIdioma(): void {
  guardador = null;
  atual = IDIOMA_PADRAO;
  ouvintes.forEach((o) => o());
}

function assinar(aviso: () => void): () => void {
  ouvintes.add(aviso);
  return () => ouvintes.delete(aviso);
}

/** O idioma de agora; a tela que usa isto desenha de novo quando a pessoa troca. */
export function useIdioma(): Idioma {
  return useSyncExternalStore(assinar, idiomaAtual, idiomaAtual);
}

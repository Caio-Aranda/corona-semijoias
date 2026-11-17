/**
 * Idioma do Corona Gestão: espanhol (padrão) e português.
 * A escolha fica guardada neste computador. Os textos vêm de dicionários `es` e `pt` com o mesmo formato
 * (`textosVivos`); o teste `idioma.test.ts` falha se faltar chave num dos dois.
 */
export type Idioma = 'es' | 'pt';

export const IDIOMAS: readonly Idioma[] = ['es', 'pt'];
export const IDIOMA_PADRAO: Idioma = 'es';
const CHAVE_GUARDADA = 'corona.gestao.idioma';

function lerGuardado(): Idioma {
  try {
    const v = globalThis.localStorage?.getItem(CHAVE_GUARDADA);
    return v === 'es' || v === 'pt' ? v : IDIOMA_PADRAO;
  } catch {
    return IDIOMA_PADRAO;
  }
}

let atual: Idioma = lerGuardado();
const ouvintes = new Set<() => void>();

export function idiomaAtual(): Idioma {
  return atual;
}

/** Muda o idioma, guarda a escolha neste computador e avisa quem está ouvindo. */
export function definirIdioma(novo: Idioma): void {
  if (novo === atual) return;
  atual = novo;
  try {
    globalThis.localStorage?.setItem(CHAVE_GUARDADA, novo);
  } catch {
    // Sem armazenamento: vale só até fechar o programa.
  }
  if (typeof document !== 'undefined') document.documentElement.lang = novo;
  for (const o of [...ouvintes]) o();
}

export function ouvirIdioma(ouvinte: () => void): () => void {
  ouvintes.add(ouvinte);
  return () => ouvintes.delete(ouvinte);
}

if (typeof document !== 'undefined') document.documentElement.lang = atual;

/** Formato de datas e números de cada idioma (o dinheiro continua R$, US$ e Gs.). */
export function localeAtual(): string {
  return atual === 'es' ? 'es-PY' : 'pt-BR';
}

/** Escolhe entre duas versões de um texto solto, no idioma de agora. */
export function porIdioma<T>(es: T, pt: T): T {
  return atual === 'es' ? es : pt;
}

// --- Dicionários ---

type Dicionario = Record<string | symbol, unknown>;

const registro: { nome: string; pt: object; es: object }[] = [];

/** Os dicionários registrados (para o teste de chaves faltando). */
export function dicionariosRegistrados(): readonly { nome: string; pt: object; es: object }[] {
  return registro;
}

/**
 * Junta o dicionário em português e o em espanhol num só objeto, com o formato do português.
 * A cada leitura devolve o texto do idioma de agora, então quem importa `TEXTOS` não precisa saber do idioma.
 * O espanhol precisa ter exatamente o mesmo formato (o TypeScript e o teste conferem).
 */
export function textosVivos<T extends object>(nome: string, pt: T, es: NoMesmoFormato<T>): T {
  registro.push({ nome, pt, es });
  const dic = () => (atual === 'es' ? es : pt) as Dicionario;
  return new Proxy(pt, {
    get: (_, chave) => dic()[chave],
    has: (_, chave) => chave in dic(),
    ownKeys: () => Reflect.ownKeys(dic()),
    getOwnPropertyDescriptor: (_, chave) => {
      const d = Object.getOwnPropertyDescriptor(dic(), chave);
      return d ? { ...d, configurable: true } : undefined;
    },
  });
}

/** O mesmo formato de `T`, mas com os textos soltos (sem fixar a palavra exata). */
export type NoMesmoFormato<T> = T extends (...a: infer A) => infer R
  ? (...a: A) => NoMesmoFormato<R>
  : T extends string
    ? string
    : T extends number | boolean | null | undefined
      ? T
      : T extends readonly (infer U)[]
        ? T extends readonly [unknown, ...unknown[]]
          ? { [K in keyof T]: NoMesmoFormato<T[K]> }
          : readonly NoMesmoFormato<U>[]
        : T extends object
          ? { [K in keyof T]: NoMesmoFormato<T[K]> }
          : T;

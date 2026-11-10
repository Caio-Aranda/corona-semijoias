/**
 * Fotos de produto já baixadas, na memória da tela (tarefa 1.13). Quem guarda para sempre, em disco, é o
 * processo principal; aqui ficam só os endereços (`blob:`) das fotos que a tela está usando ou usou há pouco.
 *
 * - A mesma foto (hash + tamanho) só é pedida uma vez.
 * - Cada `blob:` ocupa memória até ser revogado: quando passa do limite, o mais antigo que ninguém está
 *   mostrando é revogado (`URL.revokeObjectURL`) e esquecido.
 * - A miniatura que já chegou aparece na hora enquanto a média ou a grande carrega.
 */
import type { PonteDoCorona } from '../../shared/ponte';
import type { TamanhoDeFoto } from '../../shared/produtos';

/** Quantas fotos ficam na memória da tela antes de revogar as mais antigas sem uso. */
export const LIMITE_NA_MEMORIA = 300;

interface Entrada {
  promessa: Promise<string | null>;
  url: string | null;
  /** Quantos componentes estão mostrando esta foto agora. */
  usos: number;
}

const entradas = new Map<string, Entrada>();

const chaveDe = (hash: string, tamanho: TamanhoDeFoto) => `${hash}/${tamanho}`;

function revogarAntigas(): void {
  if (entradas.size <= LIMITE_NA_MEMORIA) return;
  for (const [chave, e] of entradas) {
    if (entradas.size <= LIMITE_NA_MEMORIA) break;
    if (e.usos > 0 || e.url === null) continue;
    URL.revokeObjectURL(e.url);
    entradas.delete(chave);
  }
}

function buscar(ponte: PonteDoCorona, hash: string, tamanho: TamanhoDeFoto): Entrada {
  const chave = chaveDe(hash, tamanho);
  const existente = entradas.get(chave);
  if (existente) {
    // Foi usada agora: vai para o fim da fila de descarte.
    entradas.delete(chave);
    entradas.set(chave, existente);
    return existente;
  }
  const entrada: Entrada = { promessa: Promise.resolve(null), url: null, usos: 0 };
  entrada.promessa = ponte
    .chamarApi({ operacao: 'produtos.fotos.imagem', parametros: { hash, tamanho } })
    .then((r) => {
      if (!r.ok) return null;
      const f = r.dados as { tipo?: string; bytes?: Uint8Array };
      if (!(f.bytes instanceof Uint8Array)) return null;
      entrada.url = URL.createObjectURL(
        new Blob([f.bytes as BlobPart], { type: f.tipo ?? 'image/jpeg' }),
      );
      revogarAntigas();
      return entrada.url;
    })
    .catch(() => null)
    .then((url) => {
      // Falha não fica guardada: da próxima vez tenta de novo.
      if (url === null && entradas.get(chave) === entrada) entradas.delete(chave);
      return url;
    });
  entradas.set(chave, entrada);
  return entrada;
}

export interface UsoDeImagem {
  promessa: Promise<string | null>;
  /** Avisa que a tela parou de mostrar esta foto (o endereço pode ser revogado depois). */
  liberar(): void;
}

/** Pede a foto para mostrá-la. Chame `liberar` quando o componente sair da tela ou trocar de foto. */
export function usarImagem(
  ponte: PonteDoCorona,
  hash: string,
  tamanho: TamanhoDeFoto,
): UsoDeImagem {
  const entrada = buscar(ponte, hash, tamanho);
  entrada.usos += 1;
  let liberada = false;
  return {
    promessa: entrada.promessa,
    liberar() {
      if (liberada) return;
      liberada = true;
      entrada.usos -= 1;
      revogarAntigas();
    },
  };
}

/** A foto, se já chegou (sem esperar nada); a tela usa a miniatura como provisória da foto maior. */
export function imagemPronta(hash: string, tamanho: TamanhoDeFoto): string | null {
  return entradas.get(chaveDe(hash, tamanho))?.url ?? null;
}

/** Começa a buscar as miniaturas de uma página da lista antes de a linha aparecer. Não segura nenhuma. */
export function preBuscarImagens(
  ponte: PonteDoCorona,
  hashes: (string | null | undefined)[],
  tamanho: TamanhoDeFoto = 'miniatura',
): void {
  for (const hash of new Set(hashes)) {
    if (hash) void buscar(ponte, hash, tamanho).promessa;
  }
}

/** Revoga tudo (ao sair da sessão e nos testes). */
export function esquecerImagens(): void {
  for (const e of entradas.values()) {
    if (e.url) URL.revokeObjectURL(e.url);
  }
  entradas.clear();
}

/** Só para os testes. */
export function quantasNaMemoria(): number {
  return entradas.size;
}

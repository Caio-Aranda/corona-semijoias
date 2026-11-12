import { createHash, randomBytes } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  utimesSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import type { TamanhoDeFoto } from '../../shared/operacoes';

/** O cache do tamanho grande: passou disso, sai o menos usado (ADR-0013, item 9). */
export const LIMITE_DO_CACHE_GRANDE_BYTES = 200 * 1024 * 1024;

const HASH = /^[0-9a-f]{64}$/;
const SUFIXO_TEMPORARIO = '.parcial';

export type ResultadoDaGravacao = 'gravada' | 'corrompida';

export interface OpcoesDoCache {
  /** A pasta `fotos` do aplicativo (a mesma que o apagar dos dados remove). */
  pasta: string;
  limiteGrandeBytes?: number;
}

/**
 * As fotos no disco do computador, fora do banco: `fotos/<hash>/<tamanho>.jpg` (tarefa 5.9). Um arquivo só passa a
 * existir inteiro e conferido: grava num arquivo temporário e renomeia no fim; arquivo pela metade nunca vale.
 */
export class CacheDeFotos {
  private readonly limiteGrande: number;

  constructor(private readonly o: OpcoesDoCache) {
    this.limiteGrande = o.limiteGrandeBytes ?? LIMITE_DO_CACHE_GRANDE_BYTES;
  }

  get pasta(): string {
    return this.o.pasta;
  }

  caminho(hash: string, tamanho: TamanhoDeFoto): string | null {
    return HASH.test(hash) ? join(this.o.pasta, hash, `${tamanho}.jpg`) : null;
  }

  tem(hash: string, tamanho: TamanhoDeFoto): boolean {
    const c = this.caminho(hash, tamanho);
    return c !== null && existsSync(c);
  }

  /** Os bytes da foto, ou `null` se não há cópia. Marca o uso do grande (para saber qual é o menos usado). */
  ler(hash: string, tamanho: TamanhoDeFoto): Buffer | null {
    const c = this.caminho(hash, tamanho);
    if (!c) return null;
    try {
      const bytes = readFileSync(c);
      if (tamanho === 'grande') {
        const agora = new Date();
        utimesSync(c, agora, agora);
      }
      return bytes;
    } catch {
      return null;
    }
  }

  /**
   * Grava a foto. Com `sha256` (o do servidor para aquele tamanho), confere antes de renomear; se não bater, nada fica
   * no disco. Erro de disco (cheio, sem permissão) sobe para quem chamou.
   */
  gravar(
    hash: string,
    tamanho: TamanhoDeFoto,
    bytes: Buffer,
    sha256: string | null,
  ): ResultadoDaGravacao {
    const destino = this.caminho(hash, tamanho);
    if (!destino || bytes.length === 0) return 'corrompida';
    if (sha256 && createHash('sha256').update(bytes).digest('hex') !== sha256.toLowerCase()) {
      return 'corrompida';
    }
    mkdirSync(join(this.o.pasta, hash), { recursive: true });
    const temporario = `${destino}.${randomBytes(4).toString('hex')}${SUFIXO_TEMPORARIO}`;
    try {
      writeFileSync(temporario, bytes);
      renameSync(temporario, destino);
    } catch (erro) {
      rmSync(temporario, { force: true });
      throw erro;
    }
    if (tamanho === 'grande') this.enxugarGrandes();
    return 'gravada';
  }

  /** Apaga tudo de uma foto (os três tamanhos). */
  remover(hash: string): void {
    if (!HASH.test(hash)) return;
    rmSync(join(this.o.pasta, hash), { recursive: true, force: true });
  }

  /** Os hashes que têm pasta no disco. */
  hashesNoDisco(): string[] {
    try {
      return readdirSync(this.o.pasta).filter((n) => HASH.test(n));
    } catch {
      return [];
    }
  }

  /** Tira os arquivos parciais de downloads que caíram no meio. */
  limparParciais(): void {
    for (const hash of this.hashesNoDisco()) {
      try {
        for (const nome of readdirSync(join(this.o.pasta, hash))) {
          if (nome.endsWith(SUFIXO_TEMPORARIO)) {
            rmSync(join(this.o.pasta, hash, nome), { force: true });
          }
        }
      } catch {
        /* a pasta sumiu no meio: nada a limpar */
      }
    }
  }

  /** Total em disco, para medir. */
  bytesTotais(): number {
    let total = 0;
    for (const hash of this.hashesNoDisco()) {
      try {
        for (const nome of readdirSync(join(this.o.pasta, hash))) {
          total += statSync(join(this.o.pasta, hash, nome)).size;
        }
      } catch {
        /* ignora */
      }
    }
    return total;
  }

  /** Quanto o tamanho grande ocupa hoje. */
  bytesDosGrandes(): number {
    return this.grandes().reduce((soma, g) => soma + g.bytes, 0);
  }

  /** Passou do limite: apaga os grandes menos usados até caber. */
  enxugarGrandes(): void {
    const todos = this.grandes().sort((a, b) => a.usadoEm - b.usadoEm);
    let soma = todos.reduce((s, g) => s + g.bytes, 0);
    for (const g of todos) {
      if (soma <= this.limiteGrande) break;
      rmSync(g.caminho, { force: true });
      soma -= g.bytes;
    }
  }

  private grandes(): { caminho: string; bytes: number; usadoEm: number }[] {
    const lista: { caminho: string; bytes: number; usadoEm: number }[] = [];
    for (const hash of this.hashesNoDisco()) {
      const caminho = join(this.o.pasta, hash, 'grande.jpg');
      try {
        const s = statSync(caminho);
        lista.push({ caminho, bytes: s.size, usadoEm: s.mtimeMs });
      } catch {
        /* sem grande nesta foto */
      }
    }
    return lista;
  }
}

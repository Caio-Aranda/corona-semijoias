import { createHash } from 'node:crypto';
import type { BancoAberto } from '../banco/banco-local';
import type { ComToken } from '../sync/ciclo';
import type { TamanhoDeFoto } from '../../shared/operacoes';
import type { CacheDeFotos } from './cache-de-fotos';
import type { TransporteDeFotos } from './transporte-de-fotos';

/** Quantos downloads ao mesmo tempo (ADR-0013, item 9). */
export const DOWNLOADS_AO_MESMO_TEMPO = 3;
/** Foto que chegou estragada: tenta de novo até esta quantidade de vezes no mesmo ciclo. */
export const TENTATIVAS_POR_FOTO = 3;

/** Os tamanhos que ficam sempre no computador, na ordem: primeiro a miniatura de tudo, depois a média. */
const TAMANHOS_FIXOS: readonly TamanhoDeFoto[] = ['miniatura', 'media'];

export interface ProgressoDasFotos {
  /** Fotos das maletas abertas que já têm miniatura e média no computador. */
  baixadas: number;
  /** Fotos das maletas abertas. */
  total: number;
}

export interface OpcoesDaFila {
  banco: () => BancoAberto | null;
  transporte: TransporteDeFotos;
  comToken: ComToken;
  cache: CacheDeFotos;
  simultaneos?: number;
  /** O progresso mudou (a tela mostra "Baixando fotos: 45 de 120"). */
  aoMudar?: () => void;
  registrar?: (mensagem: string) => void;
}

/** Os hashes das fotos das peças que estão em maletas abertas (enviada, conferida ou em acerto). */
export function hashesDasMaletasAbertas(db: BancoAberto): string[] {
  return (
    db
      .prepare(
        `SELECT DISTINCT f.arquivo_hash AS hash
           FROM itens_remessa i
           JOIN remessas r ON r.id = i.remessa_id AND r.deleted_at IS NULL
                          AND r.status IN ('ENVIADA', 'CONFERIDA', 'EM_ACERTO')
           JOIN produtos_fotos f ON f.produto_id = i.produto_id AND f.deleted_at IS NULL
                                AND f.arquivo_hash IS NOT NULL
          WHERE i.deleted_at IS NULL
          ORDER BY f.arquivo_hash`,
      )
      .all() as { hash: string }[]
  ).map((l) => l.hash);
}

/**
 * O download das fotos (tarefa 5.9), separado do envio e do recebimento: depois de cada ciclo com internet, olha o que
 * as maletas abertas precisam, baixa a miniatura de tudo e só depois a média (3 ao mesmo tempo), confere o SHA-256 de
 * cada tamanho e apaga as fotos que não servem mais (foto trocada, maleta acertada ou cancelada). Nada daqui trava a
 * venda: sem a foto, a tela mostra "sem foto".
 */
export class FilaDeFotos {
  private rodando = false;
  private denovo = false;
  private pronto: ProgressoDasFotos = { baixadas: 0, total: 0 };
  /** Hashes que o servidor disse não ter (404) nesta execução do aplicativo: não insiste. */
  private readonly inexistentes = new Set<string>();
  /** As fotos com miniatura e média no disco. */
  private readonly prontas = new Set<string>();

  constructor(private readonly o: OpcoesDaFila) {}

  progresso(): ProgressoDasFotos {
    return this.pronto;
  }

  /** Uma passada. Chamar de novo durante uma passada marca outra logo depois, nunca duas ao mesmo tempo. */
  async rodar(): Promise<void> {
    if (this.rodando) {
      this.denovo = true;
      return;
    }
    this.rodando = true;
    try {
      do {
        this.denovo = false;
        await this.passada();
      } while (this.denovo);
    } finally {
      this.rodando = false;
    }
  }

  /** Conta de novo, olhando o disco (uma vez por passada). */
  private recontar(hashes: string[]): void {
    this.prontas.clear();
    for (const h of hashes) {
      if (TAMANHOS_FIXOS.every((t) => this.o.cache.tem(h, t))) this.prontas.add(h);
    }
    this.mudarProgresso({ baixadas: this.prontas.size, total: hashes.length });
  }

  /** Chegou um arquivo: só esta foto é conferida (contar tudo a cada download custaria milhões de leituras). */
  private chegou(hash: string): void {
    if (!this.prontas.has(hash) && TAMANHOS_FIXOS.every((t) => this.o.cache.tem(hash, t))) {
      this.prontas.add(hash);
      this.mudarProgresso({ baixadas: this.prontas.size, total: this.pronto.total });
    }
  }

  private mudarProgresso(novo: ProgressoDasFotos): void {
    if (novo.baixadas === this.pronto.baixadas && novo.total === this.pronto.total) return;
    this.pronto = novo;
    this.o.aoMudar?.();
  }

  private async passada(): Promise<void> {
    const db = this.o.banco();
    if (!db) return;
    let hashes: string[];
    try {
      hashes = hashesDasMaletasAbertas(db);
    } catch {
      return; // banco fechando (sair, apagar): a próxima passada refaz
    }
    const cache = this.o.cache;
    cache.limparParciais();
    // Limpeza: foto trocada, maleta acertada ou cancelada. Sai do disco o que nenhuma maleta aberta usa.
    const queroAgora = new Set(hashes);
    for (const h of cache.hashesNoDisco()) {
      if (!queroAgora.has(h)) cache.remover(h);
    }
    this.recontar(hashes);

    // Miniatura de tudo, depois a média.
    const trabalho: { hash: string; tamanho: TamanhoDeFoto }[] = [];
    for (const tamanho of TAMANHOS_FIXOS) {
      for (const hash of hashes) {
        if (!cache.tem(hash, tamanho) && !this.inexistentes.has(hash)) {
          trabalho.push({ hash, tamanho });
        }
      }
    }
    if (trabalho.length === 0) return;

    let parar = false;
    let proximo = 0;
    const trabalhador = async (): Promise<void> => {
      while (!parar) {
        const item = trabalho[proximo++];
        if (!item) return;
        const desfecho = await this.baixarUma(item.hash, item.tamanho);
        if (desfecho === 'sem-internet') parar = true;
        else if (desfecho === 'ok') this.chegou(item.hash);
      }
    };
    const n = Math.max(1, this.o.simultaneos ?? DOWNLOADS_AO_MESMO_TEMPO);
    await Promise.all(Array.from({ length: n }, () => trabalhador()));
  }

  private async baixarUma(
    hash: string,
    tamanho: TamanhoDeFoto,
  ): Promise<'ok' | 'pulou' | 'sem-internet'> {
    for (let tentativa = 1; tentativa <= TENTATIVAS_POR_FOTO; tentativa++) {
      const r = await this.o.comToken((token) => this.o.transporte.baixar(token, hash, tamanho));
      if (!r.ok) return 'sem-internet'; // rede, sessão ou versão: o resto espera o próximo ciclo
      switch (r.dados.situacao) {
        case 'inexistente':
          this.inexistentes.add(hash);
          return 'pulou';
        case 'indisponivel':
          return 'pulou';
        case 'baixada': {
          try {
            const gravou = this.o.cache.gravar(
              hash,
              tamanho,
              r.dados.foto.bytes,
              r.dados.foto.sha256,
            );
            if (gravou === 'gravada') return 'ok';
          } catch (erro) {
            // Disco cheio ou sem permissão: não adianta insistir agora.
            this.o.registrar?.(
              `Fotos: não consegui gravar (${erro instanceof Error ? erro.name : 'erro'}).`,
            );
            return 'sem-internet';
          }
          this.o.registrar?.('Fotos: uma foto chegou estragada e será baixada de novo.');
        }
      }
    }
    return 'pulou';
  }
}

/** O SHA-256 em texto hexadecimal (usado pelos testes e pela conferência). */
export function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

/**
 * Cache em disco das fotos de produto (tarefa 1.13, §20), no processo principal.
 *
 * - Uma foto é identificada pelo hash do conteúdo e nunca muda: o arquivo `<hash>.<tamanho>` é imutável,
 *   então nunca se pergunta ao servidor se mudou. Se a dona trocar a foto, o hash é outro.
 * - Só entra aqui o que o servidor entregou a uma sessão autenticada da dona; a leitura também só acontece
 *   dentro de uma chamada já autenticada. Ao sair da sessão a pasta é apagada (a foto de produto não fica
 *   no computador de quem não está logado).
 * - Tem limite de tamanho: passou, descarta o mais antigo.
 */
import { mkdir, readFile, readdir, rm, stat, utimes, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const HASH = /^[0-9a-f]{64}$/;
const TAMANHOS = ['miniatura', 'media', 'grande'];

export interface FotoEmDisco {
  tipo: string;
  bytes: Uint8Array;
}

export interface OpcoesDoCache {
  pasta: string;
  /** Soma máxima dos arquivos, em bytes. */
  limiteEmBytes: number;
}

export class CacheDeFotos {
  /** Escritas em andamento, em fila: duas fotos ao mesmo tempo não disputam o descarte. */
  private fila: Promise<void> = Promise.resolve();

  constructor(private readonly opcoes: OpcoesDoCache) {}

  private caminho(hash: string, tamanho: string): string | null {
    // Hash e tamanho viram nome de arquivo: só valem os formatos exatos (nada de "../").
    if (!HASH.test(hash) || !TAMANHOS.includes(tamanho)) return null;
    return join(this.opcoes.pasta, `${hash}.${tamanho}`);
  }

  /** A foto guardada, ou `null` se não está no disco. Ler renova a "idade" do arquivo (usado por último). */
  async ler(hash: string, tamanho: string): Promise<FotoEmDisco | null> {
    const caminho = this.caminho(hash, tamanho);
    if (!caminho) return null;
    try {
      const dados = await readFile(caminho);
      if (dados.length < 2) return null;
      const agora = new Date();
      void utimes(caminho, agora, agora).catch(() => undefined);
      return { tipo: tipoPelosBytes(dados), bytes: new Uint8Array(dados) };
    } catch {
      return null;
    }
  }

  /** Guarda uma foto e, se passou do limite, descarta as mais antigas. Falhar aqui nunca atrapalha a tela. */
  gravar(hash: string, tamanho: string, bytes: Uint8Array): Promise<void> {
    const caminho = this.caminho(hash, tamanho);
    if (!caminho || bytes.length === 0 || bytes.length > this.opcoes.limiteEmBytes) {
      return Promise.resolve();
    }
    this.fila = this.fila.then(async () => {
      try {
        await mkdir(this.opcoes.pasta, { recursive: true });
        await writeFile(caminho, bytes);
        await this.descartarOExcedente(caminho);
      } catch {
        // Sem disco ou sem permissão: a foto só não fica guardada.
      }
    });
    return this.fila;
  }

  private async descartarOExcedente(recemGravado: string): Promise<void> {
    const nomes = await readdir(this.opcoes.pasta);
    const arquivos: { caminho: string; tamanho: number; quando: number }[] = [];
    for (const nome of nomes) {
      const caminho = join(this.opcoes.pasta, nome);
      try {
        const s = await stat(caminho);
        if (s.isFile()) arquivos.push({ caminho, tamanho: s.size, quando: s.mtimeMs });
      } catch {
        // Sumiu no meio do caminho.
      }
    }
    let soma = arquivos.reduce((t, a) => t + a.tamanho, 0);
    arquivos.sort((a, b) => a.quando - b.quando);
    for (const a of arquivos) {
      if (soma <= this.opcoes.limiteEmBytes) break;
      if (a.caminho === recemGravado) continue;
      await rm(a.caminho, { force: true });
      soma -= a.tamanho;
    }
  }

  /** Apaga tudo (ao sair da sessão). */
  async limpar(): Promise<void> {
    await this.fila.catch(() => undefined);
    await rm(this.opcoes.pasta, { recursive: true, force: true });
  }
}

/** As fotos são sempre JPEG (o servidor recodifica); o tipo só muda se o arquivo disser outra coisa. */
function tipoPelosBytes(b: Uint8Array): string {
  if (b[0] === 0x89 && b[1] === 0x50) return 'image/png';
  if (b[0] === 0x52 && b[1] === 0x49) return 'image/webp';
  return 'image/jpeg';
}

/** Deixa só N tarefas em andamento ao mesmo tempo; as outras esperam a vez, na ordem em que chegaram. */
export class LimiteDeBuscas {
  private emAndamento = 0;
  private readonly espera: (() => void)[] = [];

  constructor(private readonly maximo: number) {}

  async executar<T>(tarefa: () => Promise<T>): Promise<T> {
    if (this.emAndamento >= this.maximo) {
      await new Promise<void>((liberar) => this.espera.push(liberar));
    }
    this.emAndamento += 1;
    try {
      return await tarefa();
    } finally {
      this.emAndamento -= 1;
      this.espera.shift()?.();
    }
  }
}

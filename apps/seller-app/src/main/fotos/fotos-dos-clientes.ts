import { createCipheriv, createDecipheriv, createHash, hkdfSync, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  TIPOS_DE_FOTO_DO_CLIENTE,
  type TipoDeFotoDoCliente,
} from '../../shared/operacoes-do-consignado';
import type { BancoAberto } from '../banco/banco-local';
import type { ComToken } from '../sync/ciclo';
import type { Resultado } from '../tipos';
import type { FotosDoClienteLocais } from '../local/consultas-do-consignado';

/** Quantas fotos sobem (ou descem) ao mesmo tempo (ADR-0016, item 9). */
export const FOTOS_DO_CLIENTE_AO_MESMO_TEMPO = 3;
const MAXIMO_DE_BYTES = 10 * 1024 * 1024;
const JPEG = Buffer.from([0xff, 0xd8, 0xff]);
const IV = 12;
const ETIQUETA = 16;
const INFO_DA_CHAVE = 'corona-vendas:fotos-dos-clientes:v1';

/** A chave das fotos: derivada da chave do banco local (HKDF-SHA-256), nunca guardada em disco. */
export function chaveDasFotosDe(chaveDoBanco: Uint8Array): Buffer {
  return Buffer.from(hkdfSync('sha256', chaveDoBanco, Buffer.alloc(0), INFO_DA_CHAVE, 32));
}

export interface RespostaDeFotoDoCliente {
  status: number;
  bytes?: Buffer;
}

/** As rotas de foto do cliente do vendedor (ADR-0016, item 9): o servidor confere o SHA-256 contra o cadastro. */
export interface TransporteDeFotosDoCliente {
  enviar(
    token: string,
    clienteId: string,
    tipo: TipoDeFotoDoCliente,
    bytes: Buffer,
  ): Promise<Resultado<RespostaDeFotoDoCliente>>;
  baixar(
    token: string,
    clienteId: string,
    tipo: TipoDeFotoDoCliente,
  ): Promise<Resultado<RespostaDeFotoDoCliente>>;
}

export interface OpcoesDasFotosDosClientes {
  /** A pasta das fotos criptografadas: `<dados>/fotos-clientes`; o apagar dos dados a remove. */
  pasta: string;
  /** A chave de 32 bytes derivada da do banco; `null` com o banco fechado. */
  chave: () => Buffer | null;
  banco: () => BancoAberto | null;
  transporte: TransporteDeFotosDoCliente;
  comToken: ComToken;
  simultaneos?: number;
  registrar?: (mensagem: string) => void;
}

export function sha256Hex(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

/**
 * As fotos do cliente e do documento no computador (tarefa 9.2; ADR-0016, item 9): JPEG → SHA-256 → arquivo
 * CRIPTOGRAFADO (AES-256-GCM, chave derivada da do banco), porque o documento é dado pessoal sensível. A fila de envio é a
 * tabela `fila_fotos_cliente` (separada da outbox, recomeça sozinha depois de uma queda): o cadastro leva só o hash e a foto
 * sobe quando há internet; a falta de envio NUNCA trava nota nem acerto.
 */
export class FotosDosClientes implements FotosDoClienteLocais {
  private rodando = false;
  private denovo = false;

  constructor(private readonly o: OpcoesDasFotosDosClientes) {}

  private caminho(hash: string): string {
    return join(this.o.pasta, `${hash}.bin`);
  }

  /** Guarda a foto (JPEG em endereço de dados), criptografada; devolve o SHA-256, ou `null` se não serve. */
  guardar(dataUrl: string): string | null {
    const m = /^data:image\/jpeg;base64,([A-Za-z0-9+/=]+)$/.exec(dataUrl);
    if (!m) return null;
    const bytes = Buffer.from(m[1] as string, 'base64');
    if (bytes.length < 4 || bytes.length > MAXIMO_DE_BYTES || !bytes.subarray(0, 3).equals(JPEG))
      return null;
    const hash = sha256Hex(bytes);
    return this.gravarArquivo(hash, bytes) ? hash : null;
  }

  private gravarArquivo(hash: string, bytes: Buffer): boolean {
    const chave = this.o.chave();
    if (!chave) return false;
    mkdirSync(this.o.pasta, { recursive: true });
    const destino = this.caminho(hash);
    if (existsSync(destino)) return true;
    const iv = randomBytes(IV);
    const cifra = createCipheriv('aes-256-gcm', chave, iv);
    const dentro = Buffer.concat([cifra.update(bytes), cifra.final()]);
    const arquivo = Buffer.concat([iv, cifra.getAuthTag(), dentro]);
    const temporario = `${destino}.parcial`;
    writeFileSync(temporario, arquivo, { mode: 0o600 });
    renameSync(temporario, destino);
    return true;
  }

  /** Os bytes da foto, decifrados e conferidos; `null` se não há arquivo, a chave não serve ou o arquivo está estragado. */
  lerBytes(hash: string): Buffer | null {
    const chave = this.o.chave();
    if (!chave || !/^[0-9a-f]{64}$/.test(hash)) return null;
    try {
      const arquivo = readFileSync(this.caminho(hash));
      if (arquivo.length <= IV + ETIQUETA) return null;
      const decifra = createDecipheriv('aes-256-gcm', chave, arquivo.subarray(0, IV));
      decifra.setAuthTag(arquivo.subarray(IV, IV + ETIQUETA));
      const bytes = Buffer.concat([
        decifra.update(arquivo.subarray(IV + ETIQUETA)),
        decifra.final(),
      ]);
      return sha256Hex(bytes) === hash ? bytes : null;
    } catch {
      return null;
    }
  }

  ver(hash: string): string | null {
    const bytes = this.lerBytes(hash);
    return bytes ? `data:image/jpeg;base64,${bytes.toString('base64')}` : null;
  }

  /** Uma passada: sobe as fotos que faltam e baixa as dos clientes que vieram do servidor. Nunca duas ao mesmo tempo. */
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

  private async passada(): Promise<void> {
    const db = this.o.banco();
    if (!db) return;
    let paraSubir: { cliente_id: string; tipo: TipoDeFotoDoCliente; hash: string }[];
    const paraBaixar: { id: string; tipo: TipoDeFotoDoCliente; hash: string }[] = [];
    try {
      paraSubir = db
        .prepare(
          `SELECT cliente_id, tipo, hash FROM fila_fotos_cliente WHERE enviada_em IS NULL ORDER BY tentativas, cliente_id, tipo`,
        )
        .all() as typeof paraSubir;
      const colunas: Record<TipoDeFotoDoCliente, string> = {
        cliente: 'foto_hash',
        'documento-frente': 'foto_documento_frente_hash',
        'documento-verso': 'foto_documento_verso_hash',
      };
      for (const tipo of TIPOS_DE_FOTO_DO_CLIENTE) {
        const linhas = db
          .prepare(
            `SELECT id, ${colunas[tipo]} AS hash FROM clientes c
              WHERE deleted_at IS NULL AND ${colunas[tipo]} IS NOT NULL AND origem_local = 'SERVIDOR'
                AND NOT EXISTS (SELECT 1 FROM fila_fotos_cliente f WHERE f.cliente_id = c.id AND f.tipo = ?)`,
          )
          .all(tipo) as { id: string; hash: string }[];
        for (const l of linhas)
          if (!existsSync(this.caminho(l.hash))) paraBaixar.push({ ...l, tipo });
      }
    } catch {
      return; // banco fechando (sair, apagar): a próxima passada refaz
    }
    const trabalho: (() => Promise<'ok' | 'pulou' | 'sem-internet'>)[] = [
      ...paraSubir.map((f) => () => this.subir(db, f.cliente_id, f.tipo, f.hash)),
      ...paraBaixar.map((f) => () => this.baixar(f.id, f.tipo, f.hash)),
    ];
    let parar = false;
    let proximo = 0;
    const trabalhador = async (): Promise<void> => {
      while (!parar) {
        const item = trabalho[proximo++];
        if (!item) return;
        if ((await item()) === 'sem-internet') parar = true;
      }
    };
    const n = Math.max(1, this.o.simultaneos ?? FOTOS_DO_CLIENTE_AO_MESMO_TEMPO);
    await Promise.all(Array.from({ length: n }, () => trabalhador()));
  }

  private async subir(
    db: BancoAberto,
    clienteId: string,
    tipo: TipoDeFotoDoCliente,
    hash: string,
  ): Promise<'ok' | 'pulou' | 'sem-internet'> {
    const bytes = this.lerBytes(hash);
    if (!bytes) {
      this.o.registrar?.('Fotos do cliente: um arquivo não está legível neste computador.');
      return 'pulou';
    }
    const r = await this.o.comToken((token) =>
      this.o.transporte.enviar(token, clienteId, tipo, bytes),
    );
    if (!r.ok) return 'sem-internet'; // rede, sessão ou versão: espera o próximo ciclo
    try {
      if (r.dados.status === 200 || r.dados.status === 201) {
        db.prepare(
          `UPDATE fila_fotos_cliente SET enviada_em = ? WHERE cliente_id = ? AND tipo = ? AND hash = ?`,
        ).run(new Date().toISOString(), clienteId, tipo, hash);
        return 'ok';
      }
      // 409 (o cadastro ainda não chegou ao servidor), 404 ou erro do servidor: tenta de novo no próximo ciclo.
      db.prepare(
        `UPDATE fila_fotos_cliente SET tentativas = tentativas + 1 WHERE cliente_id = ? AND tipo = ?`,
      ).run(clienteId, tipo);
    } catch {
      return 'sem-internet';
    }
    return 'pulou';
  }

  private async baixar(
    clienteId: string,
    tipo: TipoDeFotoDoCliente,
    hash: string,
  ): Promise<'ok' | 'pulou' | 'sem-internet'> {
    const r = await this.o.comToken((token) => this.o.transporte.baixar(token, clienteId, tipo));
    if (!r.ok) return 'sem-internet';
    const bytes = r.dados.bytes;
    if (r.dados.status !== 200 || !bytes || sha256Hex(bytes) !== hash) return 'pulou';
    try {
      this.gravarArquivo(hash, bytes);
    } catch {
      return 'sem-internet';
    }
    return 'ok';
  }

  /** O apagar dos dados remove a pasta inteira; aqui só para os testes. */
  apagarTudo(): void {
    rmSync(this.o.pasta, { recursive: true, force: true });
  }
}

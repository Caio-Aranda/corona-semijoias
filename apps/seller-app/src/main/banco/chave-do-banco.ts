import {
  createCipheriv,
  createDecipheriv,
  randomBytes,
  scrypt as scryptCallback,
} from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

/**
 * A chave do banco local (ADR-0005, linha datada de 11/11/2026; ADR-0013, linha datada 2).
 *
 * - 256 bits aleatórios, gerados uma vez, nunca em claro no disco nem na tela.
 * - **Embrulhada pela senha do vendedor**: chave de embrulho derivada com **scrypt** (N = 2^17, r = 8, p = 1: cerca de
 *   128 MB de memória, verificador lento; nunca hash rápido) e AES-256-GCM. A etiqueta do GCM é o verificador: senha
 *   errada não abre. O Corona Vendas pede a senha ao abrir, também sem internet.
 * - **5 erros seguidos bloqueiam até conectar**: só a entrada com internet (senha conferida pelo servidor) zera a conta.
 * - **Segunda proteção pelo cofre do Windows (DPAPI) só para resgate**: quando a dona redefine a senha, o vendedor entra
 *   com internet e a senha nova, a chave sai pelo cofre e é embrulhada de novo pela senha nova.
 *
 * Arquivo `dados/chave.json` (gravado num temporário e renomeado: nunca fica pela metade).
 */

/** Parâmetros do scrypt. Os testes usam valores menores, só pela velocidade. */
export interface ParametrosDaSenha {
  N: number;
  r: number;
  p: number;
}

export const PARAMETROS_PADRAO: ParametrosDaSenha = Object.freeze({ N: 2 ** 17, r: 8, p: 1 });
export const ERROS_ATE_BLOQUEAR = 5;

/** O cofre do sistema (no Electron, o `safeStorage`; no Windows, o DPAPI). */
export interface CofreDoSistema {
  disponivel(): boolean | Promise<boolean>;
  cifrar(texto: string): Uint8Array | Promise<Uint8Array>;
  decifrar(dados: Uint8Array): string | Promise<string>;
}

export type ResultadoDaSenha =
  | { ok: true; chave: Buffer }
  | { ok: false; motivo: 'senha-errada'; restantes: number }
  | { ok: false; motivo: 'bloqueado' }
  | { ok: false; motivo: 'sem-chave' };

/** A chave não pôde ser recuperada nem pela senha nem pelo cofre (Windows reinstalado, pasta copiada...). */
export class ChavePerdida extends Error {
  constructor(opcoes?: { cause?: unknown }) {
    super('A chave do banco local não pôde ser recuperada.', opcoes);
    this.name = 'ChavePerdida';
  }
}

interface ArquivoDaChave {
  formato: 1;
  kdf: { nome: 'scrypt'; N: number; r: number; p: number; sal: string };
  pelaSenha: { iv: string; dados: string; etiqueta: string };
  /** A chave cifrada pelo cofre do sistema (base64), ou vazio se o cofre não estava disponível. */
  resgate: string | null;
  errosSeguidos: number;
}

const DADOS_ADICIONAIS = Buffer.from('corona-vendas/chave-do-banco/1', 'utf8');

export class ChaveDoBanco {
  private readonly parametros: ParametrosDaSenha;

  constructor(
    private readonly arquivo: string,
    private readonly cofre: CofreDoSistema,
    parametros: ParametrosDaSenha = PARAMETROS_PADRAO,
  ) {
    this.parametros = parametros;
  }

  existe(): boolean {
    return existsSync(this.arquivo);
  }

  /** Cria a chave do banco novo, embrulhada pela senha (e pelo cofre, se houver). Nunca sobrescreve uma que exista. */
  async criar(senha: string): Promise<Buffer> {
    if (this.existe())
      throw new Error('A chave do banco local já existe: não se cria outra por cima.');
    const chave = randomBytes(32);
    this.gravar(await this.embrulhar(chave, senha, await this.resgateDe(chave), 0));
    return chave;
  }

  /**
   * Abre pela senha, sem internet. Errou: conta o erro (5 seguidos bloqueiam até entrar com internet). Acertou: zera a
   * conta.
   */
  async abrirComSenha(senha: string): Promise<ResultadoDaSenha> {
    const guardado = this.ler();
    if (!guardado) return { ok: false, motivo: 'sem-chave' };
    if (guardado.errosSeguidos >= ERROS_ATE_BLOQUEAR) return { ok: false, motivo: 'bloqueado' };
    const chave = await this.desembrulhar(guardado, senha);
    if (!chave) {
      const erros = guardado.errosSeguidos + 1;
      this.gravar({ ...guardado, errosSeguidos: erros });
      return erros >= ERROS_ATE_BLOQUEAR
        ? { ok: false, motivo: 'bloqueado' }
        : { ok: false, motivo: 'senha-errada', restantes: ERROS_ATE_BLOQUEAR - erros };
    }
    if (guardado.errosSeguidos > 0) this.gravar({ ...guardado, errosSeguidos: 0 });
    return { ok: true, chave };
  }

  /**
   * Depois de uma entrada COM internet (a senha foi conferida pelo servidor): abre pela senha; se a senha mudou (a dona
   * redefiniu), tira a chave pelo cofre e a embrulha de novo pela senha nova. Zera os erros seguidos.
   *
   * @throws ChavePerdida se nem a senha nem o cofre abrem a chave
   */
  async confirmarComInternet(senha: string): Promise<Buffer> {
    const guardado = this.ler();
    if (!guardado) throw new ChavePerdida();
    const pelaSenha = await this.desembrulhar(guardado, senha);
    if (pelaSenha) {
      // O resgate pode ter faltado (cofre indisponível antes): tenta pôr agora.
      const resgate = guardado.resgate ?? (await this.resgateDe(pelaSenha));
      if (guardado.errosSeguidos > 0 || resgate !== guardado.resgate) {
        this.gravar({ ...guardado, resgate, errosSeguidos: 0 });
      }
      return pelaSenha;
    }
    const pelaCofre = await this.abrirPeloCofre(guardado);
    this.gravar(await this.embrulhar(pelaCofre, senha, guardado.resgate, 0));
    return pelaCofre;
  }

  /** Erros seguidos de senha guardados (para a tela dizer quantas tentativas restam). */
  errosSeguidos(): number {
    return this.ler()?.errosSeguidos ?? 0;
  }

  /** Apaga o arquivo da chave: sem ele o banco fica ilegível (o primeiro passo de "apagar os dados", tarefa 5.8). */
  apagar(): void {
    rmSync(this.arquivo, { force: true });
  }

  /** Guarda o arquivo da chave ao lado do banco ilegível, sem apagar (o banco novo terá chave nova). */
  moverPara(destino: string): void {
    if (this.existe()) renameSync(this.arquivo, destino);
  }

  // --- internos ---

  private ler(): ArquivoDaChave | null {
    if (!this.existe()) return null;
    try {
      const dados = JSON.parse(readFileSync(this.arquivo, 'utf8')) as ArquivoDaChave;
      if (dados.formato !== 1 || dados.kdf?.nome !== 'scrypt' || !dados.pelaSenha) return null;
      return dados;
    } catch {
      return null;
    }
  }

  private gravar(dados: ArquivoDaChave): void {
    mkdirSync(dirname(this.arquivo), { recursive: true });
    const temporario = `${this.arquivo}.novo`;
    writeFileSync(temporario, JSON.stringify(dados), { mode: 0o600 });
    renameSync(temporario, this.arquivo);
  }

  private async embrulhar(
    chave: Buffer,
    senha: string,
    resgate: string | null,
    errosSeguidos: number,
  ): Promise<ArquivoDaChave> {
    const sal = randomBytes(16);
    const { N, r, p } = this.parametros;
    const kek = await derivar(senha, sal, this.parametros);
    const iv = randomBytes(12);
    const cifra = createCipheriv('aes-256-gcm', kek, iv);
    cifra.setAAD(DADOS_ADICIONAIS);
    const dados = Buffer.concat([cifra.update(chave), cifra.final()]);
    kek.fill(0);
    return {
      formato: 1,
      kdf: { nome: 'scrypt', N, r, p, sal: sal.toString('base64') },
      pelaSenha: {
        iv: iv.toString('base64'),
        dados: dados.toString('base64'),
        etiqueta: cifra.getAuthTag().toString('base64'),
      },
      resgate,
      errosSeguidos,
    };
  }

  private async desembrulhar(guardado: ArquivoDaChave, senha: string): Promise<Buffer | null> {
    const { N, r, p, sal } = guardado.kdf;
    const kek = await derivar(senha, Buffer.from(sal, 'base64'), { N, r, p });
    try {
      const decifra = createDecipheriv(
        'aes-256-gcm',
        kek,
        Buffer.from(guardado.pelaSenha.iv, 'base64'),
      );
      decifra.setAAD(DADOS_ADICIONAIS);
      decifra.setAuthTag(Buffer.from(guardado.pelaSenha.etiqueta, 'base64'));
      const chave = Buffer.concat([
        decifra.update(Buffer.from(guardado.pelaSenha.dados, 'base64')),
        decifra.final(),
      ]);
      return chave.length === 32 ? chave : null;
    } catch {
      return null;
    } finally {
      kek.fill(0);
    }
  }

  private async resgateDe(chave: Buffer): Promise<string | null> {
    try {
      if (!(await this.cofre.disponivel())) return null;
      const cifrada = await this.cofre.cifrar(chave.toString('hex'));
      return Buffer.from(cifrada).toString('base64');
    } catch {
      return null;
    }
  }

  private async abrirPeloCofre(guardado: ArquivoDaChave): Promise<Buffer> {
    if (!guardado.resgate) throw new ChavePerdida();
    try {
      if (!(await this.cofre.disponivel())) throw new Error('cofre indisponível');
      const hex = await this.cofre.decifrar(Buffer.from(guardado.resgate, 'base64'));
      const chave = Buffer.from(hex, 'hex');
      if (chave.length !== 32) throw new Error('chave do cofre com tamanho errado');
      return chave;
    } catch (erro) {
      throw new ChavePerdida({ cause: erro });
    }
  }
}

function derivar(senha: string, sal: Buffer, p: ParametrosDaSenha): Promise<Buffer> {
  return new Promise((resolver, rejeitar) => {
    scryptCallback(
      senha.normalize('NFC'),
      sal,
      32,
      { N: p.N, r: p.r, p: p.p, maxmem: 256 * 1024 * 1024 },
      (erro, chave) => (erro ? rejeitar(erro) : resolver(chave)),
    );
  });
}

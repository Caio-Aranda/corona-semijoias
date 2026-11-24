import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { chaveDasFotosDe } from '../fotos/fotos-dos-clientes';
import { join } from 'node:path';
import {
  abrirBancoLocal,
  type BancoAberto,
  type CodigoDoBanco,
  ErroDoBanco,
  ESPACO_MINIMO_EM_BYTES,
  espacoLivre,
  MENSAGENS_DO_BANCO,
  moverParaIlegivel,
} from './banco-local';
import {
  ChaveDoBanco,
  ChavePerdida,
  type CofreDoSistema,
  type ParametrosDaSenha,
  PARAMETROS_PADRAO,
} from './chave-do-banco';
import type { MigracaoLocal } from './migracoes';

/** Textos do glossário para a senha conferida no computador (sem internet). */
export const MENSAGENS_DA_SENHA = Object.freeze({
  SENHA_ERRADA: 'Usuário ou senha incorretos. Confira o que você digitou e tente de novo.',
  SENHA_BLOQUEADA:
    'A senha foi digitada errada muitas vezes. Conecte-se à internet e entre de novo para continuar.',
  OUTRO_USUARIO:
    'Este computador já está com os dados de outra pessoa. Entre com o usuário mostrado na tela. Se este computador agora é seu, use "Este computador é de outra pessoa". Se tiver dúvida, fale com a dona da loja.',
  BANCO_NAO_ABRIU:
    'Não foi possível abrir os dados deste computador. Feche o Corona Vendas e abra de novo. Se continuar assim, fale com a dona da loja.',
  SEM_DADOS:
    'Para entrar no Corona Vendas pela primeira vez é preciso internet. Conecte-se e tente de novo.',
});

export type CodigoDaAbertura = CodigoDoBanco | keyof typeof MENSAGENS_DA_SENHA;

export interface FalhaDaAbertura {
  ok: false;
  codigo: CodigoDaAbertura;
  mensagem: string;
  /** Senha errada sem internet: quantas tentativas restam antes de bloquear. */
  restantes?: number;
}

export type ResultadoDaAbertura =
  | {
      ok: true;
      /** O banco anterior não abriu e foi guardado à parte: começou vazio e o computador baixa tudo de novo. */
      reiniciado: boolean;
      /** Menos de 500 MB livres no disco (ADR-0013, item 15). */
      poucoEspaco: boolean;
    }
  | FalhaDaAbertura;

export interface OpcoesDoServicoDoBanco {
  /** Pasta dos dados do app (`<userData>/dados`): o banco, a chave e as cópias moram aqui. */
  pasta: string;
  cofre: CofreDoSistema;
  parametros?: ParametrosDaSenha;
  migracoes?: readonly MigracaoLocal[];
  agora?: () => Date;
  /**
   * Tarefa 5.8: chamado depois de cada entrada COM internet, com o banco já aberto. O app guarda aqui o contato com o
   * servidor do login, para o prazo sem internet já valer desde o primeiro minuto.
   */
  aoAbrirComInternet?: (db: BancoAberto) => void | Promise<void>;
}

/**
 * O banco local do Corona Vendas no processo principal (tarefa 5.2). Só ele abre o arquivo, só ele conhece a chave; a
 * tela recebe apenas estados e textos (ADR-0004, item 3; ADR-0013, item 7).
 *
 * - **Com internet** (depois de o servidor conferir a senha): cria a chave e o banco na primeira vez; senão abre pela
 *   senha, ou pelo cofre do Windows se a dona redefiniu a senha. Se nem a chave nem o banco abrem, guarda o que havia
 *   como `.ilegivel` (nunca apaga, nunca cria chave nova sobre o banco antigo) e começa vazio.
 * - **Sem internet** (tarefa 5.8 usa): só pela senha, com o limite de 5 erros. Nada é movido sem internet: sem ela
 *   não haveria como baixar os dados de novo.
 */
export class ServicoDoBanco {
  private db: BancoAberto | null = null;
  private chaveDeFotos: Buffer | null = null;
  private readonly chave: ChaveDoBanco;
  private readonly caminhoDoBanco: string;
  private readonly caminhoDaChave: string;

  constructor(private readonly o: OpcoesDoServicoDoBanco) {
    this.caminhoDoBanco = join(o.pasta, 'banco.sqlite');
    this.caminhoDaChave = join(o.pasta, 'chave.json');
    this.chave = new ChaveDoBanco(this.caminhoDaChave, o.cofre, o.parametros ?? PARAMETROS_PADRAO);
  }

  /** O banco aberto, só para o processo principal (motor de envio e telas no banco local, tarefas 5.6 e 5.7). */
  get banco(): BancoAberto | null {
    return this.db;
  }

  /** A chave das fotos dos clientes (derivada da do banco), só enquanto o banco está aberto. */
  get chaveDasFotos(): Buffer | null {
    return this.db ? this.chaveDeFotos : null;
  }

  async prepararComInternet(senha: string): Promise<ResultadoDaAbertura> {
    const r = await this.prepararInterno(senha);
    if (r.ok && this.db) await this.o.aoAbrirComInternet?.(this.db);
    return r;
  }

  private async prepararInterno(senha: string): Promise<ResultadoDaAbertura> {
    mkdirSync(this.o.pasta, { recursive: true });
    let reiniciado = false;
    let chave: Buffer;
    if (!this.chave.existe()) {
      // Sem chave e com banco: a chave se perdeu. O banco antigo fica guardado à parte; nasce outro.
      if (this.existeBanco()) {
        this.fecharSeAberto();
        moverParaIlegivel(this.caminhoDoBanco, this.agora());
        reiniciado = true;
      }
      chave = await this.chave.criar(senha);
    } else {
      try {
        chave = await this.chave.confirmarComInternet(senha);
      } catch (erro) {
        if (!(erro instanceof ChavePerdida)) throw erro;
        this.fecharSeAberto();
        const destino = moverParaIlegivel(this.caminhoDoBanco, this.agora());
        this.chave.moverPara(`${destino}.chave.json`);
        chave = await this.chave.criar(senha);
        reiniciado = true;
      }
    }
    if (this.db) return { ok: true, reiniciado, poucoEspaco: this.poucoEspaco() };
    try {
      this.db = abrirBancoLocal(this.opcoesDoBanco(chave));
    } catch (erro) {
      if (!(erro instanceof ErroDoBanco)) throw erro;
      if (erro.codigo !== 'BANCO_ILEGIVEL') return this.falha(erro.codigo);
      // A chave abriu, mas o arquivo não (estragado): guarda à parte e começa vazio com a mesma chave.
      moverParaIlegivel(this.caminhoDoBanco, this.agora());
      reiniciado = true;
      try {
        this.db = abrirBancoLocal(this.opcoesDoBanco(chave));
      } catch (deNovo) {
        if (deNovo instanceof ErroDoBanco) return this.falha(deNovo.codigo);
        throw deNovo;
      }
    } finally {
      chave.fill(0);
    }
    return { ok: true, reiniciado, poucoEspaco: this.poucoEspaco() };
  }

  async abrirSemInternet(senha: string): Promise<ResultadoDaAbertura> {
    if (this.db) return { ok: true, reiniciado: false, poucoEspaco: this.poucoEspaco() };
    const r = await this.chave.abrirComSenha(senha);
    if (!r.ok) {
      if (r.motivo === 'senha-errada')
        return { ...this.falha('SENHA_ERRADA'), restantes: r.restantes };
      return this.falha(r.motivo === 'bloqueado' ? 'SENHA_BLOQUEADA' : 'SEM_DADOS');
    }
    try {
      this.db = abrirBancoLocal(this.opcoesDoBanco(r.chave));
    } catch (erro) {
      if (erro instanceof ErroDoBanco) return this.falha(erro.codigo);
      throw erro;
    } finally {
      r.chave.fill(0);
    }
    return { ok: true, reiniciado: false, poucoEspaco: this.poucoEspaco() };
  }

  /** Guarda (no banco aberto) quem entrou: o login e o nome, para abrir sem internet. Sem banco aberto, não faz nada. */
  lembrar(chave: 'vendedor_login' | 'vendedor_nome', valor: string): void {
    if (!this.db) return;
    // O login não é segredo: fica também em arquivo simples, para a tela de senha saber de quem é este computador
    // antes de o banco abrir.
    if (chave === 'vendedor_login') {
      try {
        writeFileSync(join(this.o.pasta, 'usuario.txt'), valor, 'utf8');
      } catch {
        /* sem o arquivo, o login só é lido de dentro do banco */
      }
    }
    this.db
      .prepare(
        'INSERT INTO estado_sync (chave, valor) VALUES (?, ?) ON CONFLICT(chave) DO UPDATE SET valor = excluded.valor',
      )
      .run(chave, valor);
  }

  /** O login de quem usa este computador, lido sem abrir o banco. */
  usuarioDoComputador(): string | null {
    try {
      const v = readFileSync(join(this.o.pasta, 'usuario.txt'), 'utf8').trim();
      return v || null;
    } catch {
      return null;
    }
  }

  lembrado(chave: 'vendedor_login' | 'vendedor_nome'): string | null {
    if (!this.db) return null;
    const r = this.db.prepare('SELECT valor FROM estado_sync WHERE chave = ?').get(chave) as
      { valor: string | null } | undefined;
    return r?.valor ?? null;
  }

  get bancoAberto(): boolean {
    return this.db !== null;
  }

  /** Há banco e chave neste computador (depois de uma entrada com internet)? Decide se dá para entrar sem internet. */
  get temDadosLocais(): boolean {
    return this.chave.existe() && this.existeBanco();
  }

  /**
   * Só para o apagar (tarefa 5.8): abre o banco pela chave do cofre do Windows, sem senha, para ler o que ainda não foi
   * enviado. O computador já foi desligado pela dona e os dados serão apagados em seguida.
   */
  async abrirParaApagar(): Promise<BancoAberto | null> {
    if (this.db) return this.db;
    const chave = await this.chave.abrirSoPeloCofre();
    if (!chave) return null;
    try {
      this.db = abrirBancoLocal(this.opcoesDoBanco(chave));
    } catch (erro) {
      if (erro instanceof ErroDoBanco) return null;
      throw erro;
    } finally {
      chave.fill(0);
    }
    return this.db;
  }

  fechar(): void {
    this.fecharSeAberto();
  }

  /**
   * "Este computador é de outra pessoa": o banco e a chave antigos vão para o lado (`.ilegivel-...`, nunca apagados, como
   * na chave perdida) e o usuario.txt sai. Só com o banco fechado. Devolve se mexeu em algo.
   */
  liberarParaOutraPessoa(): boolean {
    if (this.db) return false;
    if (this.existeBanco()) {
      const destino = moverParaIlegivel(this.caminhoDoBanco, this.agora());
      this.chave.moverPara(`${destino}.chave.json`);
    } else if (this.chave.existe()) {
      this.chave.moverPara(`${this.caminhoDoBanco}.ilegivel-sem-banco.chave.json`);
    }
    rmSync(join(this.o.pasta, 'usuario.txt'), { force: true });
    return true;
  }

  // --- internos ---

  private opcoesDoBanco(chave: Uint8Array) {
    // A chave das fotos dos clientes (criptografadas no disco) sai da do banco e só vive enquanto o banco está aberto.
    this.chaveDeFotos?.fill(0);
    this.chaveDeFotos = chaveDasFotosDe(chave);
    return {
      caminho: this.caminhoDoBanco,
      chave,
      ...(this.o.migracoes ? { migracoes: this.o.migracoes } : {}),
    };
  }

  private existeBanco(): boolean {
    return existsSync(this.caminhoDoBanco);
  }

  private fecharSeAberto(): void {
    this.chaveDeFotos?.fill(0);
    this.chaveDeFotos = null;
    if (this.db) {
      try {
        this.db.close();
      } catch {
        /* já fechado */
      }
      this.db = null;
    }
  }

  private poucoEspaco(): boolean {
    try {
      return espacoLivre(this.o.pasta) < ESPACO_MINIMO_EM_BYTES;
    } catch {
      return false;
    }
  }

  private agora(): Date {
    return this.o.agora?.() ?? new Date();
  }

  private falha(codigo: CodigoDaAbertura): FalhaDaAbertura {
    const mensagem =
      codigo in MENSAGENS_DA_SENHA
        ? MENSAGENS_DA_SENHA[codigo as keyof typeof MENSAGENS_DA_SENHA]
        : MENSAGENS_DO_BANCO[codigo as CodigoDoBanco];
    return { ok: false, codigo, mensagem };
  }
}

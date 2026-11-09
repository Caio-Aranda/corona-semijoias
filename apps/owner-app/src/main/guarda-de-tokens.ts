import type {
  ApiDeAutenticacao,
  ArquivoDoToken,
  Cofre,
  Credenciais,
  Falha,
  Relogio,
  Resultado,
} from './tipos';

/** Renova com esta folga antes de o token de acesso (15 minutos) vencer. */
const FOLGA_DA_RENOVACAO_MS = 60_000;
/** Uma terceira apresentação do mesmo token de renovação revoga a sessão inteira (ADR-0004, item 2). */
const MAX_APRESENTACOES = 2;

export type SituacaoDaSessao =
  /** Sem sessão: precisa entrar. */
  | 'fora'
  /** Entrou com senha temporária: só pode criar a senha nova. */
  | 'restrita'
  /** Sessão em dia. */
  | 'dentro'
  /** Há sessão guardada, mas não deu para renová-la agora (sem internet ou servidor fora do ar). */
  | 'pendente';

export interface AvisoDeSessaoPerdida {
  estado: 'fora' | 'app-desatualizado';
  mensagem?: string;
}

export interface OpcoesDaGuarda {
  api: ApiDeAutenticacao;
  cofre: Cofre;
  arquivo: ArquivoDoToken;
  relogio: Relogio;
  /** Chamado quando a sessão termina sem a pessoa ter pedido (renovação recusada de vez, 401). */
  aoPerderASessao?: (aviso: AvisoDeSessaoPerdida) => void;
}

/**
 * Guarda dos tokens (ADR-0004, item 3). Vive só no processo principal.
 * - Acesso: na memória.
 * - Renovação: na memória e, cifrado pelo cofre do sistema, em disco. Sem cofre, não grava nada em claro.
 * - Nenhum método devolve o valor de um token: quem precisa de token passa uma função para `comToken`.
 * - Renova uma vez por vez (fila única): a 3ª apresentação do mesmo token revoga a família.
 */
export class GuardaDeTokens {
  private acesso: string | null = null;
  private acessoVenceEm = 0;
  private restrita = false;
  private renovacao: string | null = null;
  private apresentacoes = 0;
  private renovando: Promise<boolean> | null = null;
  private cancelarAgendamento: (() => void) | null = null;

  constructor(private readonly o: OpcoesDaGuarda) {}

  situacao(): SituacaoDaSessao {
    if (this.restrita && this.acesso) return 'restrita';
    if (this.acesso) return 'dentro';
    if (this.renovacao) return 'pendente';
    return 'fora';
  }

  /** Na abertura do aplicativo: tenta continuar a sessão guardada no disco. */
  async restaurar(): Promise<void> {
    if (!(await this.o.cofre.disponivel())) {
      // Sem cofre não se grava em claro; se sobrou algo de antes, não serve.
      await this.apagarArquivo();
      return;
    }
    let dados: Uint8Array | null;
    try {
      dados = await this.o.arquivo.ler();
    } catch {
      return;
    }
    if (!dados) return;
    try {
      this.renovacao = await this.o.cofre.decifrar(dados);
    } catch {
      this.renovacao = null;
      await this.apagarArquivo();
      return;
    }
    this.apresentacoes = 0;
    await this.renovar();
  }

  async entrar(usuario: string, senha: string): Promise<Resultado<'entrou' | 'trocar-senha'>> {
    const r = await this.o.api.entrar(usuario, senha);
    if (!r.ok) return r;
    return { ok: true, dados: await this.aceitar(r.dados) };
  }

  /** Troca obrigatória: usa o token restrito, que só existe na memória. */
  async trocarSenha(senhaNova: string): Promise<Resultado<'entrou' | 'trocar-senha'>> {
    const token = this.acesso;
    if (!token || !this.restrita) return { ok: false, falha: { tipo: 'recusada', status: 401 } };
    const r = await this.o.api.trocarSenha(token, senhaNova);
    if (!r.ok) {
      if (r.falha.tipo === 'recusada' && r.falha.status === 401) await this.perderASessao();
      return r;
    }
    return { ok: true, dados: await this.aceitar(r.dados) };
  }

  /** Sai: avisa o servidor (que revoga a sessão) e apaga tudo daqui. */
  async sair(): Promise<void> {
    let token = this.acesso;
    if (token && !this.acessoValido() && !this.restrita) {
      token = (await this.renovar()) ? this.acesso : null;
    }
    this.limpar();
    await this.apagarArquivo();
    if (token) await this.o.api.sair(token).catch(() => undefined);
  }

  /**
   * Faz uma chamada com o token de acesso, sem entregá-lo a quem chama.
   * Renova antes se o token estiver perto de vencer. Em 401, renova uma única vez e repete uma vez.
   */
  async comToken<T>(chamada: (token: string) => Promise<Resultado<T>>): Promise<Resultado<T>> {
    const token = await this.tokenValido();
    if (!token) return this.semSessao();

    const primeira = await chamada(token);
    if (!ehNaoAutorizado(primeira)) return primeira;

    // Várias chamadas podem falhar juntas com o mesmo token: só a primeira renova, as outras esperam.
    const jaRenovado = this.acesso !== null && this.acesso !== token;
    const renovou = jaRenovado ? true : await this.renovar();
    if (!renovou || !this.acesso) return this.semSessao();

    const segunda = await chamada(this.acesso);
    if (ehNaoAutorizado(segunda)) {
      await this.perderASessao();
      return this.semSessao();
    }
    return segunda;
  }

  /** Renova o token de acesso. Uma renovação por vez: quem chega durante uma espera pelo resultado dela. */
  renovar(): Promise<boolean> {
    this.renovando ??= this.executarRenovacao().finally(() => {
      this.renovando = null;
    });
    return this.renovando;
  }

  // --- internos ---

  private async tokenValido(): Promise<string | null> {
    if (this.acesso && this.acessoValido()) return this.acesso;
    if (this.restrita) return null;
    if (!this.renovacao) return null;
    return (await this.renovar()) ? this.acesso : null;
  }

  private acessoValido(): boolean {
    // Com token de renovação, troca com folga; sem ele (troca de senha), usa o token até o fim.
    const folga = this.renovacao ? FOLGA_DA_RENOVACAO_MS : 5_000;
    return this.o.relogio.agora() < this.acessoVenceEm - folga;
  }

  private semSessao<T>(): Resultado<T> {
    const falha: Falha =
      this.situacao() === 'fora' ? { tipo: 'recusada', status: 401 } : { tipo: 'sem-conexao' };
    return { ok: false, falha };
  }

  private async executarRenovacao(): Promise<boolean> {
    const token = this.renovacao;
    if (!token || this.restrita) return false;
    if (this.apresentacoes >= MAX_APRESENTACOES) {
      // Uma terceira apresentação revogaria a sessão inteira no servidor: melhor pedir nova entrada.
      await this.perderASessao();
      return false;
    }
    this.apresentacoes += 1;

    const r = await this.o.api.renovar(token);
    if (r.ok) {
      if (!r.dados.tokenDeRenovacao || r.dados.trocaDeSenhaObrigatoria) {
        await this.perderASessao();
        return false;
      }
      await this.aceitar(r.dados);
      return true;
    }
    const f = r.falha;
    if (f.tipo === 'app-desatualizado') {
      this.limpar();
      await this.apagarArquivo();
      this.o.aoPerderASessao?.({ estado: 'app-desatualizado', mensagem: f.mensagem });
      return false;
    }
    if (f.tipo === 'recusada' && [400, 401, 403, 404].includes(f.status)) {
      await this.perderASessao();
      return false;
    }
    // Sem internet, servidor fora do ar ou excesso de tentativas: a sessão continua guardada.
    return false;
  }

  private async aceitar(c: Credenciais): Promise<'entrou' | 'trocar-senha'> {
    this.cancelarAgendamento?.();
    this.cancelarAgendamento = null;
    this.acesso = c.tokenDeAcesso;
    this.acessoVenceEm = this.o.relogio.agora() + c.validadeEmSegundos * 1000;
    if (c.trocaDeSenhaObrigatoria) {
      this.restrita = true;
      this.renovacao = null;
      this.apresentacoes = 0;
      await this.apagarArquivo();
      return 'trocar-senha';
    }
    this.restrita = false;
    this.renovacao = c.tokenDeRenovacao ?? null;
    this.apresentacoes = 0;
    await this.guardarNoDisco();
    this.agendarRenovacao(c.validadeEmSegundos * 1000);
    return 'entrou';
  }

  private agendarRenovacao(validadeMs: number): void {
    if (!this.renovacao) return;
    const espera =
      validadeMs > 2 * FOLGA_DA_RENOVACAO_MS ? validadeMs - FOLGA_DA_RENOVACAO_MS : validadeMs / 2;
    this.cancelarAgendamento = this.o.relogio.agendar(espera, () => {
      void this.renovar();
    });
  }

  private async guardarNoDisco(): Promise<void> {
    if (!this.renovacao) return;
    if (!(await this.o.cofre.disponivel())) {
      // Nunca em claro. Esta sessão vale até fechar o aplicativo; depois, nova entrada.
      await this.apagarArquivo();
      return;
    }
    try {
      await this.o.arquivo.gravar(await this.o.cofre.cifrar(this.renovacao));
    } catch {
      await this.apagarArquivo();
    }
  }

  private async apagarArquivo(): Promise<void> {
    try {
      await this.o.arquivo.apagar();
    } catch {
      /* nada a fazer: o arquivo só tem o token cifrado */
    }
  }

  private limpar(): void {
    this.cancelarAgendamento?.();
    this.cancelarAgendamento = null;
    this.acesso = null;
    this.acessoVenceEm = 0;
    this.restrita = false;
    this.renovacao = null;
    this.apresentacoes = 0;
  }

  private async perderASessao(): Promise<void> {
    this.limpar();
    await this.apagarArquivo();
    this.o.aoPerderASessao?.({ estado: 'fora' });
  }
}

function ehNaoAutorizado<T>(r: Resultado<T>): boolean {
  return !r.ok && r.falha.tipo === 'recusada' && r.falha.status === 401;
}

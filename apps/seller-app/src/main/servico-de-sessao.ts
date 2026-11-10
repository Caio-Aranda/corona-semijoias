import type {
  EstadoDaSessao,
  OperacaoDaApi,
  ResultadoDeChamada,
  ResultadoDeEntrada,
} from '../shared/ponte';
import type { DadosDoUsuario } from './api';
import type { GuardaDeTokens } from './guarda-de-tokens';
import type { Falha, Resultado } from './tipos';

/** Chamadas de negócio que o processo principal faz em nome da tela. Hoje só existe "eu". */
export interface ChamadasDaApi {
  eu(tokenDeAcesso: string): Promise<Resultado<DadosDoUsuario>>;
}

/**
 * Une a guarda de tokens ao que a ponte oferece. Tudo que sai daqui para a tela é "sem token":
 * só estados, dados da pessoa e as mensagens que a API devolveu.
 */
export class ServicoDeSessao {
  private nome: string | null = null;
  private desatualizado: string | null = null;

  constructor(
    private readonly guarda: GuardaDeTokens,
    private readonly api: ChamadasDaApi,
    /** Termina quando a sessão guardada no disco já foi retomada (ou descartada). */
    private readonly prontidao: Promise<void> = Promise.resolve(),
  ) {}

  /** Chamado quando a guarda avisa que o aplicativo está desatualizado. */
  marcarDesatualizado(mensagem: string): void {
    this.desatualizado = mensagem;
  }

  async entrar(usuario: string, senha: string): Promise<ResultadoDeEntrada> {
    this.desatualizado = null;
    const r = await this.guarda.entrar(usuario, senha);
    return this.paraResultado(r);
  }

  async trocarSenha(senhaNova: string): Promise<ResultadoDeEntrada> {
    const r = await this.guarda.trocarSenha(senhaNova);
    return this.paraResultado(r);
  }

  async sair(): Promise<void> {
    this.nome = null;
    this.desatualizado = null;
    await this.guarda.sair();
  }

  async estouLogado(): Promise<EstadoDaSessao> {
    await this.prontidao;
    if (this.desatualizado) return { estado: 'app-desatualizado', mensagem: this.desatualizado };
    let situacao = this.guarda.situacao();
    if (situacao === 'pendente') {
      await this.guarda.renovar();
      if (this.desatualizado) return { estado: 'app-desatualizado', mensagem: this.desatualizado };
      situacao = this.guarda.situacao();
    }
    switch (situacao) {
      case 'fora':
        return { estado: 'fora' };
      case 'restrita':
        return { estado: 'trocar-senha' };
      case 'pendente':
        return { estado: 'sem-conexao' };
      case 'dentro':
        return { estado: 'dentro', usuario: await this.usuario() };
    }
  }

  async chamarApi(operacao: OperacaoDaApi): Promise<ResultadoDeChamada> {
    switch (operacao) {
      case 'eu': {
        const r = await this.guarda.comToken((token) => this.api.eu(token));
        return r.ok ? { ok: true, dados: r.dados } : this.falhaDaChamada(r.falha);
      }
    }
  }

  private async usuario(): Promise<{ nome: string } | null> {
    if (this.nome) return { nome: this.nome };
    const r = await this.guarda.comToken((token) => this.api.eu(token));
    if (!r.ok || !r.dados.nome) return null;
    this.nome = r.dados.nome;
    return { nome: this.nome };
  }

  private paraResultado(r: Resultado<'entrou' | 'trocar-senha'>): ResultadoDeEntrada {
    if (r.ok) return { tipo: r.dados === 'entrou' ? 'entrou' : 'trocar-senha' };
    const f = r.falha;
    switch (f.tipo) {
      case 'app-desatualizado':
        this.desatualizado = f.mensagem;
        return { tipo: 'app-desatualizado', mensagem: f.mensagem };
      case 'sem-conexao':
        return { tipo: 'sem-conexao' };
      case 'recusada':
        return f.mensagem
          ? { tipo: 'recusado', mensagem: f.mensagem }
          : { tipo: 'erro-inesperado' };
      case 'resposta-invalida':
        return { tipo: 'erro-inesperado' };
    }
  }

  private falhaDaChamada(f: Falha): ResultadoDeChamada {
    switch (f.tipo) {
      case 'app-desatualizado':
        this.desatualizado = f.mensagem;
        return { ok: false, motivo: 'app-desatualizado', mensagem: f.mensagem };
      case 'sem-conexao':
        return { ok: false, motivo: 'sem-conexao' };
      case 'recusada':
        if (f.status === 401) return { ok: false, motivo: 'sessao-terminou' };
        return f.mensagem
          ? { ok: false, motivo: 'erro', mensagem: f.mensagem }
          : { ok: false, motivo: 'erro' };
      case 'resposta-invalida':
        return { ok: false, motivo: 'erro' };
    }
  }
}

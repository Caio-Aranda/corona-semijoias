import type {
  EstadoDaSessao,
  PedidoDeChamada,
  ResultadoDeChamada,
  ResultadoDeEntrada,
} from '../shared/ponte';
import type { FotoPronta } from '../shared/dados';
import type { TamanhoDeFoto } from '../shared/operacoes';
import type { DadosDoUsuario } from './api';
import { ehConsultaLocal } from './local/consultas-locais';
import type { GuardaDeTokens } from './guarda-de-tokens';
import { MENSAGENS_DA_SENHA } from './banco/servico-do-banco';
import type { Falha, Resultado } from './tipos';

/** O banco local (tarefa 5.2): preparado depois de cada entrada com internet, com a senha que o servidor conferiu. */
export interface PreparoDoBancoLocal {
  prepararComInternet(senha: string): Promise<{ ok: boolean; codigo?: string }>;
  /** Tarefa 5.8: há banco e chave neste computador? (decide se dá para entrar sem internet) */
  readonly temDadosLocais?: boolean;
  /** O banco está aberto agora? Sem ele (app reaberto, só com a sessão guardada) não há venda nem chave. */
  readonly bancoAberto?: boolean;
  /** Tarefa 5.8: abre o banco só pela senha, sem internet (5 erros seguidos bloqueiam até conectar). */
  abrirSemInternet?(senha: string): Promise<{ ok: boolean; codigo?: string; mensagem?: string }>;
  fechar?(): void;
  /** Tarefa 5.8: quem entrou, guardado no próprio banco, para conferir o usuário ao abrir sem internet. */
  lembrar?(chave: 'vendedor_login' | 'vendedor_nome', valor: string): void;
  /** O login de quem usa este computador, lido sem abrir o banco (não é segredo). */
  usuarioDoComputador?(): string | null;
  lembrado?(chave: 'vendedor_login' | 'vendedor_nome'): string | null;
}

/** O apagar os dados do computador desligado (tarefa 5.8). */
export interface ControleDoApagar {
  receberOrdem(): Promise<void>;
}

export type EstadoDoDesligamento = 'NENHUM' | 'DESLIGANDO' | 'DESLIGADO';

/** O motor de envio e recebimento (tarefa 5.6): liga com o banco aberto e a sessão válida, desliga ao sair. */
export interface ControleDoMotor {
  ligar(): void;
  desligar(): void;
}

/**
 * As telas leem e gravam no banco do computador (tarefa 5.7). Com o banco aberto, tudo que não é foto nem "quem sou eu"
 * é atendido aqui, sem rede; sem banco (uma falha na abertura), a chamada ainda segue pela internet.
 */
export interface ConsultasDoBancoLocal {
  readonly disponivel: boolean;
  chamar(pedido: PedidoDeChamada): Resultado<unknown>;
}

/** Chamadas de negócio que o processo principal faz em nome da tela. */
export interface ChamadasDaApi {
  eu(tokenDeAcesso: string): Promise<Resultado<DadosDoUsuario>>;
  chamar(tokenDeAcesso: string, pedido: PedidoDeChamada): Promise<Resultado<unknown>>;
}

/**
 * Une a guarda de tokens ao que a ponte oferece. Tudo que sai daqui para a tela é "sem token":
 * só estados, dados da pessoa e as mensagens que a API devolveu.
 */
export class ServicoDeSessao {
  private nome: string | null = null;
  private desatualizado: string | null = null;
  /** Abriu sem internet, só com a senha (tarefa 5.8): vale como "dentro" até a sessão voltar sozinha. */
  private modoLocal = false;
  private desligamento: EstadoDoDesligamento = 'NENHUM';
  /** Quem acabou de digitar o usuário (a troca da senha temporária vem depois, sem o usuário). */
  private loginDaEntrada: string | null = null;

  constructor(
    private readonly guarda: GuardaDeTokens,
    private readonly api: ChamadasDaApi,
    /** Termina quando a sessão guardada no disco já foi retomada (ou descartada). */
    private readonly prontidao: Promise<void> = Promise.resolve(),
    private readonly bancoLocal?: PreparoDoBancoLocal,
    private readonly motor?: ControleDoMotor,
    private readonly consultas?: ConsultasDoBancoLocal,
    private readonly apagador?: ControleDoApagar,
    /** Avisa a tela que o computador está sendo desligado ou já foi (tarefa 5.8). */
    private readonly aoMudarODesligamento?: (estado: EstadoDoDesligamento) => void,
    /** As fotos do disco do computador (tarefa 5.9); sem isto a foto vem da internet, como antes. */
    private readonly fotos?: {
      ver(hash: string, tamanho: TamanhoDeFoto): Promise<Resultado<FotoPronta>>;
    },
  ) {}

  /**
   * O servidor mandou apagar os dados (computador bloqueado ou vendedor desativado; ADR-0013, item 14). Para o motor, manda
   * as vendas que faltam para a quarentena, apaga tudo e avisa a tela. Repetir a ordem não faz nada de novo.
   */
  async ordemDeApagar(): Promise<void> {
    if (!this.apagador || this.desligamento !== 'NENHUM') return;
    this.desligamento = 'DESLIGANDO';
    this.motor?.desligar();
    this.aoMudarODesligamento?.('DESLIGANDO');
    try {
      await this.apagador.receberOrdem();
    } catch (erro) {
      console.warn('Apagar os dados não terminou:', erro instanceof Error ? erro.name : 'erro');
      // A marca fica em disco: ao abrir o aplicativo de novo, continua de onde parou.
      return;
    }
    this.guarda.esquecer();
    this.nome = null;
    this.modoLocal = false;
    this.desligamento = 'DESLIGADO';
    this.aoMudarODesligamento?.('DESLIGADO');
  }

  /**
   * O computador recebeu a ordem de apagar (troca ou bloqueio) e está enviando ou já apagou (issue #127). A partir do
   * `DESLIGANDO`, que `ordemDeApagar` marca antes do primeiro `await`, nenhuma gravação nova entra: o que o apagar leu é
   * tudo o que existe.
   */
  get desligado(): boolean {
    return this.desligamento !== 'NENHUM';
  }

  /** Chamado quando a guarda avisa que o aplicativo está desatualizado. */
  marcarDesatualizado(mensagem: string): void {
    this.desatualizado = mensagem;
  }

  async entrar(usuario: string, senha: string): Promise<ResultadoDeEntrada> {
    this.desatualizado = null;
    // Este computador guarda as vendas de uma pessoa: outra não entra por cima (o banco antigo seria posto de lado
    // e as vendas ainda não enviadas ficariam presas). Só vale com o banco fechado; aberto, já é a mesma pessoa.
    const dono = this.donoDoComputador();
    if (dono && dono !== usuario.trim().toLowerCase()) {
      return {
        tipo: 'recusado',
        codigo: 'OUTRO_USUARIO',
        mensagem: MENSAGENS_DA_SENHA.OUTRO_USUARIO,
      };
    }
    this.loginDaEntrada = usuario.trim().toLowerCase();
    const r = await this.guarda.entrar(usuario, senha);
    if (r.ok && r.dados === 'entrou') {
      if (!(await this.prepararBancoLocal(senha)) && this.bancoLocal?.temDadosLocais) {
        return {
          tipo: 'recusado',
          codigo: 'BANCO_NAO_ABRIU',
          mensagem: MENSAGENS_DA_SENHA.BANCO_NAO_ABRIU,
        };
      }
      this.bancoLocal?.lembrar?.('vendedor_login', usuario.trim().toLowerCase());
    }
    if (!r.ok && r.falha.tipo === 'sem-conexao' && this.bancoLocal?.temDadosLocais) {
      return this.entrarSemInternet(usuario, senha);
    }
    if (!r.ok && r.falha.tipo === 'recusada' && r.falha.acao === 'APAGAR_DADOS') {
      void this.ordemDeApagar();
    }
    return this.paraResultado(r);
  }

  /**
   * Sem internet e com dados neste computador (tarefa 5.8; decisão de 11/11/2026): a senha é conferida aqui, com o
   * verificador lento da chave do banco. Usuário ou senha errados dão o mesmo texto; 5 erros seguidos bloqueiam até
   * entrar com internet.
   */
  private async entrarSemInternet(usuario: string, senha: string): Promise<ResultadoDeEntrada> {
    const banco = this.bancoLocal;
    if (!banco?.abrirSemInternet) return { tipo: 'sem-conexao' };
    const aberto = await banco.abrirSemInternet(senha);
    if (!aberto.ok) {
      return aberto.mensagem
        ? { tipo: 'recusado', mensagem: aberto.mensagem, codigo: aberto.codigo }
        : { tipo: 'erro-inesperado' };
    }
    const guardado = banco.lembrado?.('vendedor_login');
    if (guardado && guardado !== usuario.trim().toLowerCase()) {
      banco.fechar?.();
      return {
        tipo: 'recusado',
        mensagem: MENSAGENS_DA_SENHA.SENHA_ERRADA,
        codigo: 'SENHA_ERRADA',
      };
    }
    this.modoLocal = true;
    this.nome = banco.lembrado?.('vendedor_nome') ?? null;
    this.motor?.ligar();
    return { tipo: 'entrou' };
  }

  /** Quem usa este computador, quando há dados dele guardados e o banco está fechado. */
  private donoDoComputador(): string | null {
    const b = this.bancoLocal;
    if (!b?.temDadosLocais || b.bancoAberto !== false) return null;
    return b.usuarioDoComputador?.() ?? null;
  }

  async trocarSenha(senhaNova: string): Promise<ResultadoDeEntrada> {
    const r = await this.guarda.trocarSenha(senhaNova);
    if (r.ok && r.dados === 'entrou') {
      await this.prepararBancoLocal(senhaNova);
      if (this.loginDaEntrada) this.bancoLocal?.lembrar?.('vendedor_login', this.loginDaEntrada);
    }
    return this.paraResultado(r);
  }

  /**
   * A senha acabou de ser conferida pelo servidor: cria ou abre o banco local com ela (e a embrulha de novo, se a dona
   * a redefiniu). Uma falha aqui não derruba a entrada: as telas ainda usam a internet até a tarefa 5.7. A senha nunca
   * é guardada nem registrada.
   */
  private async prepararBancoLocal(senha: string): Promise<boolean> {
    if (!this.bancoLocal) return true;
    try {
      const r = await this.bancoLocal.prepararComInternet(senha);
      if (!r.ok) {
        console.warn(`Banco local não abriu: ${r.codigo ?? 'sem código'}`);
        return false;
      }
      // Banco aberto e sessão válida (acabou de entrar): o motor começa a enviar e receber.
      this.motor?.ligar();
      return true;
    } catch (erro) {
      console.warn('Banco local não abriu:', erro instanceof Error ? erro.name : 'erro');
      return false;
    }
  }

  async sair(): Promise<void> {
    this.motor?.desligar();
    this.modoLocal = false;
    this.nome = null;
    this.desatualizado = null;
    await this.guarda.sair();
  }

  async estouLogado(): Promise<EstadoDaSessao> {
    await this.prontidao;
    if (this.desligamento !== 'NENHUM') {
      return { estado: this.desligamento === 'DESLIGADO' ? 'desligado' : 'desligando' };
    }
    if (this.modoLocal) {
      return { estado: 'dentro', usuario: this.nome ? { nome: this.nome } : null };
    }
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
        // Há sessão guardada, mas sem internet: com dados neste computador, entra só com a senha (tarefa 5.8).
        return this.bancoLocal?.temDadosLocais
          ? { estado: 'entrar-sem-internet' }
          : { estado: 'sem-conexao' };
      case 'dentro':
        // Reabriu o aplicativo e a sessão voltou sozinha com internet: o banco local só abre com a senha (a chave não
        // fica guardada em claro), então a tela principal não aparece antes de a senha ser digitada.
        if (this.bancoLocal?.temDadosLocais && this.bancoLocal.bancoAberto === false) {
          return { estado: 'reabrir', usuario: this.bancoLocal.usuarioDoComputador?.() ?? null };
        }
        return { estado: 'dentro', usuario: await this.usuario() };
    }
  }

  async chamarApi(pedido: PedidoDeChamada): Promise<ResultadoDeChamada> {
    if (this.consultas?.disponivel && ehConsultaLocal(pedido)) {
      const local = this.consultas.chamar(pedido);
      return local.ok ? { ok: true, dados: local.dados } : this.falhaDaChamada(local.falha);
    }
    if (pedido.operacao === 'fotos.ver' && this.fotos && this.consultas?.disponivel) {
      const foto = await this.fotos.ver(pedido.hash, pedido.tamanho);
      return foto.ok ? { ok: true, dados: foto.dados } : this.falhaDaChamada(foto.falha);
    }
    const r =
      pedido.operacao === 'eu'
        ? await this.guarda.comToken((token) => this.api.eu(token))
        : await this.guarda.comToken((token) => this.api.chamar(token, pedido));
    return r.ok ? { ok: true, dados: r.dados } : this.falhaDaChamada(r.falha);
  }

  private async usuario(): Promise<{ nome: string } | null> {
    if (this.nome) return { nome: this.nome };
    const r = await this.guarda.comToken((token) => this.api.eu(token));
    if (!r.ok || !r.dados.nome) return null;
    this.nome = r.dados.nome;
    this.bancoLocal?.lembrar?.('vendedor_nome', this.nome);
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
          ? { tipo: 'recusado', mensagem: f.mensagem, ...(f.codigo ? { codigo: f.codigo } : {}) }
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
        return {
          ok: false,
          motivo: 'erro',
          ...(f.mensagem ? { mensagem: f.mensagem } : {}),
          ...(f.codigo ? { codigo: f.codigo } : {}),
          ...(f.campos ? { campos: f.campos } : {}),
          ...(f.clienteExistente ? { clienteExistente: f.clienteExistente } : {}),
        };
      case 'resposta-invalida':
        return { ok: false, motivo: 'erro' };
    }
  }
}

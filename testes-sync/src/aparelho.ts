import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  montarCoronaVendas,
  type CoronaVendasMontado,
} from '../../apps/seller-app/src/main/montagem';
import type { Cofre, Relogio } from '../../apps/seller-app/src/main/tipos';
import type {
  AvisoDeSessao,
  EstadoDaSincronizacao,
  ResultadoDeChamada,
  ResultadoDeEntrada,
} from '../../apps/seller-app/src/shared/ponte';
import type { PedidoDeChamada } from '../../apps/seller-app/src/shared/operacoes';
import { validarChamada } from '../../apps/seller-app/src/shared/validacao';
import { URL_DA_API, VERSAO_DO_APP } from '../ambiente/ambiente';
import { esperarAte } from './esperar';
import type { Livro } from './livro';
import { marcadoresNaResposta } from './marcadores';
import {
  iniciarProxyDeRedeRuim,
  PLANO_BOM,
  PLANO_SEM_INTERNET,
  type PlanoDeRede,
  type ProxyDeRedeRuim,
} from './proxy-de-rede-ruim';

/** Linha de uma cópia local: o que o oráculo compara. */
export interface LinhaLocal {
  id: string;
  version: number;
  deleted_at: string | null;
  origem_local: string;
}

/**
 * O cofre do Windows (DPAPI) não existe no Node: no lugar dele, AES-256-GCM com uma chave que só vive na memória deste
 * "computador". Basta para o banco local ser cifrado de verdade (SQLCipher) e o resgate pela chave do cofre funcionar.
 */
function cofreDeTeste(): Cofre {
  const chave = randomBytes(32);
  return {
    disponivel: () => Promise.resolve(true),
    cifrar: (texto) => {
      const iv = randomBytes(12);
      const c = createCipheriv('aes-256-gcm', chave, iv);
      const corpo = Buffer.concat([c.update(texto, 'utf8'), c.final()]);
      return Promise.resolve(new Uint8Array(Buffer.concat([iv, c.getAuthTag(), corpo])));
    },
    decifrar: (dados) => {
      const b = Buffer.from(dados);
      const d = createDecipheriv('aes-256-gcm', chave, b.subarray(0, 12));
      d.setAuthTag(b.subarray(12, 28));
      return Promise.resolve(Buffer.concat([d.update(b.subarray(28)), d.final()]).toString('utf8'));
    },
  };
}

/**
 * Relógio do aparelho: a hora é a real mais um deslocamento (relógio do computador adiantado ou atrasado, prazo sem
 * internet), e as esperas do motor andam `aceleracao` vezes mais rápido (os 2 s depois de gravar viram 200 ms; a espera
 * crescente de até 5 min vira 30 s). O código do motor não muda; a agenda de verdade tem os próprios testes (5.6).
 */
class RelogioDoAparelho implements Relogio {
  deslocamentoMs = 0;
  constructor(readonly aceleracao: number) {}
  agora(): number {
    return Date.now() + this.deslocamentoMs;
  }
  agendar(ms: number, fn: () => void): () => void {
    const id = setTimeout(fn, Math.max(0, Math.round(ms / this.aceleracao)));
    return () => clearTimeout(id);
  }
}

export interface OpcoesDoAparelho {
  nome: string;
  semente: number;
  /** Versão que o app informa (padrão a do ambiente). */
  versaoDoApp?: string;
  aceleracao?: number;
  /**
   * O relógio do computador já nasce adiantado (+) ou atrasado (−) por este tanto, desde antes do primeiro contato. Um
   * relógio que anda para trás depois de um contato é "relógio mexido" e bloqueia a gravação (ADR-0013, item 12): o relógio
   * errado "de nascença" é o caso da Fase 6 (ADR-0014, item 5).
   */
  deslocamentoMs?: number;
  /** Pasta de dados que já existe (abrir o mesmo computador de novo); omitida, uma pasta nova. */
  pasta?: string;
}

/**
 * Um "computador do vendedor" em Node: a montagem de verdade do Corona Vendas (`montagem.ts`), com banco SQLCipher de
 * verdade numa pasta só dele, falando com a API de verdade por um proxy de rede ruim só dele. O teste fala com o app
 * pelos mesmos pedidos que a tela manda pela ponte (conferidos pela mesma validação da ponte) e só **lê** o banco local.
 */
export class Aparelho {
  readonly avisos: AvisoDeSessao[] = [];
  readonly registro: string[] = [];
  ultimoEstado: EstadoDaSincronizacao | null = null;
  /** Marcadores proibidos achados nas respostas do servidor (custo, rotas e nomes da dona). Tem de ficar vazio. */
  marcadoresVistos: string[] = [];
  /** O vendedor deste computador e o livro do cenário (preenchidos pelo cenário). */
  vendedorId = '';
  livro: Livro | null = null;

  private constructor(
    readonly nome: string,
    readonly pasta: string,
    readonly proxy: ProxyDeRedeRuim,
    readonly relogio: RelogioDoAparelho,
    private app: CoronaVendasMontado,
    private readonly opcoes: OpcoesDoAparelho,
  ) {}

  static async ligar(o: OpcoesDoAparelho): Promise<Aparelho> {
    const pasta = o.pasta ?? mkdtempSync(join(tmpdir(), `corona-sync-${o.nome}-`));
    const vistos: string[] = [];
    const proxy = await iniciarProxyDeRedeRuim({
      destino: URL_DA_API,
      semente: o.semente,
      plano: PLANO_BOM,
      // Toda resposta do servidor que passou pela rede deste computador é varrida, não só o que o teste lembrou de olhar.
      aoResponder: (r) => {
        for (const achado of marcadoresNaResposta(r)) vistos.push(achado);
      },
    });
    const relogio = new RelogioDoAparelho(o.aceleracao ?? 10);
    relogio.deslocamentoMs = o.deslocamentoMs ?? 0;
    const aparelho = new Aparelho(o.nome, pasta, proxy, relogio, null as never, o);
    aparelho.marcadoresVistos = vistos;
    aparelho.app = await aparelho.montar();
    return aparelho;
  }

  private montar(): Promise<CoronaVendasMontado> {
    return montarCoronaVendas({
      pastaDeDados: this.pasta,
      enderecoDoServidor: `http://127.0.0.1:${this.proxy.porta}`,
      versaoDoApp: this.opcoes.versaoDoApp ?? VERSAO_DO_APP,
      cofre: COFRES.get(this.pasta) ?? guardarCofre(this.pasta),
      fetch: globalThis.fetch,
      relogio: this.relogio,
      avisarATela: (aviso) => this.avisos.push(aviso),
      avisarSincronizacao: (estado) => {
        this.ultimoEstado = estado;
      },
      registrar: (mensagem) => this.registro.push(`${new Date().toISOString()} ${mensagem}`),
    });
  }

  // --- a rede ---

  semInternet(): void {
    this.proxy.trocarPlano(PLANO_SEM_INTERNET);
  }

  /** Rede boa de novo; o "net.isOnline()" do Electron avisa o motor. */
  comInternet(plano: PlanoDeRede = PLANO_BOM): void {
    this.proxy.trocarPlano(plano);
    this.app.motor.internetVoltou();
  }

  rede(plano: PlanoDeRede): void {
    this.proxy.trocarPlano(plano);
  }

  // --- a tela ---

  entrar(usuario: string, senha: string): Promise<ResultadoDeEntrada> {
    return this.app.servico.entrar(usuario, senha);
  }

  trocarSenha(senhaNova: string): Promise<ResultadoDeEntrada> {
    return this.app.servico.trocarSenha(senhaNova);
  }

  estouLogado() {
    return this.app.servico.estouLogado();
  }

  /** Entra pela primeira vez: senha temporária, troca obrigatória e banco local criado. */
  async primeiraEntrada(login: string, senhaTemporaria: string, senhaNova: string): Promise<void> {
    const r = await this.entrar(login, senhaTemporaria);
    if (r.tipo !== 'trocar-senha')
      throw new Error(
        `${this.nome}: entrar devolveu ${JSON.stringify(r)}, esperava trocar a senha.`,
      );
    const t = await this.trocarSenha(senhaNova);
    if (t.tipo !== 'entrou')
      throw new Error(`${this.nome}: trocar a senha devolveu ${JSON.stringify(t)}.`);
  }

  /** O mesmo pedido que a tela manda pela ponte, conferido pela mesma validação da ponte. */
  async chamar(pedido: PedidoDeChamada): Promise<ResultadoDeChamada> {
    const v = validarChamada(pedido);
    if (!v)
      throw new Error(`${this.nome}: a ponte recusaria este pedido: ${JSON.stringify(pedido)}`);
    return this.app.servico.chamarApi(v);
  }

  /** Igual a `chamar`, mas exige sucesso. */
  async pedir<T = unknown>(pedido: PedidoDeChamada): Promise<T> {
    const r = await this.chamar(pedido);
    if (!r.ok)
      throw new Error(
        `${this.nome}: ${pedido.operacao} não deu certo: ${r.motivo}${r.mensagem ? ` (${r.mensagem})` : ''}`,
      );
    return r.dados as T;
  }

  // --- o motor ---

  get motor() {
    return this.app.motor;
  }

  estado(): EstadoDaSincronizacao {
    return this.app.motor.estado();
  }

  /**
   * Roda ciclos com a rede atual até um ciclo que começou depois deste pedido terminar com tudo enviado e recebido
   * (nada aguardando). Falha com a explicação se não chegar lá a tempo.
   */
  async sincronizar(limiteMs = 60_000): Promise<void> {
    await esperarAte(
      async () => {
        const antes = this.app.motor.envioEmAndamento();
        if (antes) await antes;
        // Cede a vez antes de pedir o ciclo: quando o ciclo acaba, a agenda do motor ainda está "rodando" até o `.then`
        // dela rodar. Pedir nesse meio-tempo só marcava "pedido durante o ciclo" e a volta seguinte esperava o ciclo que a
        // própria agenda disparava, de novo e de novo (giro em falso, issue #130). Depois de ceder, a agenda já terminou:
        // o pedido abre um ciclo novo, ou o ciclo que ela acabou de abrir (já depois deste pedido) vale como o nosso.
        await new Promise<void>((ok) => setImmediate(ok));
        this.app.motor.internetVoltou();
        const nosso = this.app.motor.envioEmAndamento();
        if (!nosso) return false;
        const desfecho = await nosso;
        const e = this.app.motor.estado();
        return desfecho === 'ok' && e.comInternet === true && e.aguardando === 0;
      },
      {
        limiteMs,
        intervaloMs: 50,
        descricao: () =>
          `${this.nome} enviar e receber tudo (estado: ${JSON.stringify(this.app.motor.estado())})`,
      },
    );
  }

  /** Espera as fotos das maletas abertas chegarem ao disco. */
  async esperarFotos(limiteMs = 60_000): Promise<void> {
    await esperarAte(
      () => {
        const e = this.app.motor.estado();
        return e.fotosTotal > 0 && e.fotosBaixadas === e.fotosTotal;
      },
      {
        limiteMs,
        descricao: () =>
          `${this.nome} baixar as fotos (${JSON.stringify(this.app.motor.estado())})`,
      },
    );
  }

  // --- leitura do banco local (nunca escreve) ---

  get bancoAberto(): boolean {
    return this.app.bancoLocal.banco !== null;
  }

  ler<T = Record<string, unknown>>(sql: string, ...parametros: unknown[]): T[] {
    const db = this.app.bancoLocal.banco;
    if (!db) throw new Error(`${this.nome}: o banco local está fechado.`);
    if (!/^\s*(SELECT|WITH)\b/i.test(sql)) throw new Error('Só leitura no banco local.');
    return db.prepare(sql).all(...parametros) as T[];
  }

  /** Operações da outbox (o "livro" do próprio app). */
  outbox(): Array<{
    operacao_id: string;
    tipo: string;
    situacao: string;
    codigo_do_resultado: string | null;
  }> {
    return this.ler(
      'SELECT operacao_id, tipo, situacao, codigo_do_resultado FROM outbox ORDER BY ordem',
    );
  }

  /** Arquivos que sobraram na pasta de dados do computador (depois de apagar, nenhum). */
  arquivos(): string[] {
    if (!existsSync(this.pasta)) return [];
    const lista: string[] = [];
    const andar = (p: string) => {
      for (const nome of readdirSync(p)) {
        const c = join(p, nome);
        if (statSync(c).isDirectory()) andar(c);
        else lista.push(c.slice(this.pasta.length + 1));
      }
    };
    andar(this.pasta);
    return lista;
  }

  /** Fecha o aplicativo (o computador continua com a pasta). */
  async fechar(): Promise<void> {
    this.app.fechar();
    await this.esperarCicloParar();
  }

  /** Abre o aplicativo de novo na mesma pasta (outra versão, se pedido), com o mesmo proxy. */
  async reabrir(versaoDoApp?: string): Promise<void> {
    if (versaoDoApp) this.opcoes.versaoDoApp = versaoDoApp;
    this.app = await this.montar();
  }

  private async esperarCicloParar(): Promise<void> {
    const emCurso = this.app.motor.envioEmAndamento();
    if (emCurso) await emCurso.catch(() => undefined);
  }

  /** Desliga tudo e apaga a pasta temporária. */
  async desligar(): Promise<void> {
    try {
      this.app.fechar();
      await this.esperarCicloParar();
    } finally {
      await this.proxy.close();
      rmSync(this.pasta, { recursive: true, force: true });
      COFRES.delete(this.pasta);
    }
  }
}

/** O cofre é do "computador" (da pasta): reabrir o app na mesma pasta usa o mesmo cofre. */
const COFRES = new Map<string, Cofre>();
function guardarCofre(pasta: string): Cofre {
  const c = cofreDeTeste();
  COFRES.set(pasta, c);
  return c;
}

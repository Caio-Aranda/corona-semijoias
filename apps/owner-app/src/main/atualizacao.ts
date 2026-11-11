import type { AvisoDeAtualizacao } from '../shared/ponte';
import type { Relogio } from './tipos';

/**
 * Atualização automática do Corona Gestão (ADR-0009, item 3). Usa o `electron-updater` sobre o MESMO canal
 * fixado da API: o certificado do servidor é conferido pelo verificador da sessão (conexao-fixada.ts) e o
 * SHA-512 do instalador, publicado em latest.yml, é conferido pelo próprio atualizador antes de instalar.
 * Instalador com hash diferente é descartado. A pessoa nunca reinstala: a versão nova é baixada em segundo
 * plano e instalada quando ela fecha o programa.
 */

/** O pedaço do `autoUpdater` do electron-updater que interessa (facilita o teste). */
export interface AtualizadorDoElectron {
  autoDownload: boolean;
  autoInstallOnAppQuit: boolean;
  allowDowngrade: boolean;
  allowPrerelease: boolean;
  disableDifferentialDownload: boolean;
  logger: unknown;
  setFeedURL(opcoes: { provider: 'generic'; url: string; channel: string }): void;
  checkForUpdates(): Promise<unknown>;
  on(evento: 'update-downloaded', ouvinte: (info: { version?: string }) => void): unknown;
  on(evento: 'error', ouvinte: (erro: Error) => void): unknown;
}

interface Opcoes {
  atualizador: AtualizadorDoElectron;
  /** Endereço do servidor (https://IP), o mesmo da API. */
  endereco: string;
  relogio: Relogio;
  aoMudar: (aviso: AvisoDeAtualizacao) => void;
  /** Só para o registro técnico (nunca vai para a tela). */
  registrar?: (mensagem: string) => void;
}

const SEGUNDOS = 1_000;
const HORAS = 3_600_000;
/** Primeira conferência pouco depois de abrir (deixa a janela e o login irem primeiro). */
const ESPERA_AO_ABRIR = 20 * SEGUNDOS;
/** Depois, a cada 2 a 6 horas (o sorteio evita que todas as máquinas batam juntas no servidor). */
const INTERVALO_MINIMO = 2 * HORAS;
const INTERVALO_MAXIMO = 6 * HORAS;

/**
 * O electron-updater baixa por uma sessão PRÓPRIA do Chromium (partição "electron-updater", sem cache), não
 * pela sessão padrão. O certificado fixado precisa estar instalado nela também, senão o servidor autoassinado
 * é recusado. Um teste confere que este nome é o mesmo que a biblioteca usa (troca de versão dela).
 */
export const PARTICAO_DO_ATUALIZADOR = 'electron-updater';

export class ServicoDeAtualizacao {
  private atual: AvisoDeAtualizacao = { estado: 'nenhuma' };
  private conferindo = false;

  constructor(private readonly opcoes: Opcoes) {}

  estado(): AvisoDeAtualizacao {
    return this.atual;
  }

  iniciar(): void {
    const a = this.opcoes.atualizador;
    a.autoDownload = true;
    // A versão nova é instalada quando a pessoa fecha o programa, nunca no meio do trabalho.
    a.autoInstallOnAppQuit = true;
    // Só versão MAIOR, só a versão final (nunca pré-lançamento), e sempre o instalador inteiro (menos peças).
    a.allowDowngrade = false;
    a.allowPrerelease = false;
    a.disableDifferentialDownload = true;
    a.logger = null;
    a.setFeedURL({
      provider: 'generic',
      url: `${this.opcoes.endereco.replace(/\/+$/, '')}/atualizacoes/`,
      channel: 'latest',
    });

    a.on('update-downloaded', (info) => {
      const versao = /^\d+\.\d+\.\d+$/.test(info.version ?? '') ? (info.version ?? '') : '';
      this.atual = { estado: 'pronta', versao };
      this.opcoes.aoMudar(this.atual);
    });
    // Falha de rede, certificado diferente ou hash errado: nada de tela de erro para a dona; tenta de novo depois.
    a.on('error', (erro) => {
      this.opcoes.registrar?.(`Falha na atualização: ${erro.message}`);
    });

    this.agendar(ESPERA_AO_ABRIR);
  }

  /** Função à parte: o estado muda por evento do atualizador, e o TypeScript não enxerga isso no meio de um método. */
  private estaPronta(): boolean {
    return this.atual.estado === 'pronta';
  }

  private agendar(ms: number): void {
    this.opcoes.relogio.agendar(ms, () => void this.conferir());
  }

  private async conferir(): Promise<void> {
    // Versão já baixada: nada a procurar até reiniciar.
    if (this.estaPronta() || this.conferindo) return;
    this.conferindo = true;
    try {
      await this.opcoes.atualizador.checkForUpdates();
    } catch (erro) {
      this.opcoes.registrar?.(
        `Não foi possível procurar versão nova: ${erro instanceof Error ? erro.message : String(erro)}`,
      );
    } finally {
      this.conferindo = false;
      if (!this.estaPronta()) {
        this.agendar(INTERVALO_MINIMO + Math.random() * (INTERVALO_MAXIMO - INTERVALO_MINIMO));
      }
    }
  }
}

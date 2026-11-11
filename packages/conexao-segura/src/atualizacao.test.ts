import { describe, expect, it, vi } from 'vitest';
import { ServicoDeAtualizacao, type AtualizadorDoElectron, type Relogio } from './atualizacao';

type Atualizador = AtualizadorDoElectron & {
  checkForUpdates: ReturnType<typeof vi.fn<() => Promise<unknown>>>;
  setFeedURL: ReturnType<typeof vi.fn<AtualizadorDoElectron['setFeedURL']>>;
};

function atualizadorDeMentira() {
  const ouvintes = new Map<string, (...args: never[]) => void>();
  const atualizador: Atualizador = {
    autoDownload: false,
    autoInstallOnAppQuit: false,
    allowDowngrade: true,
    allowPrerelease: true,
    disableDifferentialDownload: false,
    logger: undefined,
    setFeedURL: vi.fn<AtualizadorDoElectron['setFeedURL']>(),
    checkForUpdates: vi.fn<() => Promise<unknown>>(() => Promise.resolve(null)),
    on: (evento: string, fn: (...args: never[]) => void) => {
      ouvintes.set(evento, fn);
      return atualizador;
    },
  };
  const emitir = (evento: string, ...args: unknown[]) =>
    (ouvintes.get(evento) as ((...a: unknown[]) => void) | undefined)?.(...args);
  return { atualizador, emitir };
}

function relogioDeMentira() {
  const tarefas: { ms: number; fn: () => void; ativa: boolean }[] = [];
  const relogio: Relogio = {
    agora: () => 0,
    agendar(ms, fn) {
      const tarefa = { ms, fn, ativa: true };
      tarefas.push(tarefa);
      return () => {
        tarefa.ativa = false;
      };
    },
  };
  return { relogio, tarefas };
}

describe('atualização automática', () => {
  it('configura o atualizador de forma segura e aponta para o servidor da Corona', () => {
    const { atualizador } = atualizadorDeMentira();
    const { relogio } = relogioDeMentira();
    const servico = new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      relogio,
      aoMudar: () => undefined,
    });
    servico.iniciar();
    expect(atualizador.autoDownload).toBe(true);
    expect(atualizador.autoInstallOnAppQuit).toBe(true);
    expect(atualizador.allowDowngrade).toBe(false);
    expect(atualizador.allowPrerelease).toBe(false);
    expect(atualizador.disableDifferentialDownload).toBe(true);
    expect(atualizador.logger).toBeNull();
    expect(atualizador.setFeedURL).toHaveBeenCalledWith({
      provider: 'generic',
      url: 'https://203.0.113.10/atualizacoes/',
      channel: 'latest',
    });
  });

  it('o canal de cada aplicativo tem a sua pasta no servidor', () => {
    const { atualizador } = atualizadorDeMentira();
    const { relogio } = relogioDeMentira();
    new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      pasta: 'atualizacoes/vendas',
      relogio,
      aoMudar: () => undefined,
    }).iniciar();
    expect(atualizador.setFeedURL).toHaveBeenCalledWith(
      expect.objectContaining({ url: 'https://203.0.113.10/atualizacoes/vendas/' }),
    );
  });

  it('confere ao abrir (depois de uns segundos) e repete a cada poucas horas', async () => {
    const { atualizador } = atualizadorDeMentira();
    const { relogio, tarefas } = relogioDeMentira();
    const servico = new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      relogio,
      aoMudar: () => undefined,
    });
    servico.iniciar();
    expect(atualizador.checkForUpdates).not.toHaveBeenCalled();
    expect(tarefas).toHaveLength(1);
    expect(tarefas[0]!.ms).toBeLessThanOrEqual(30_000);

    tarefas[0]!.fn();
    await Promise.resolve();
    expect(atualizador.checkForUpdates).toHaveBeenCalledTimes(1);

    // Depois da primeira conferência, agenda a próxima em algumas horas (entre 2 e 6).
    await vi.waitFor(() => expect(tarefas).toHaveLength(2));
    expect(tarefas[1]!.ms).toBeGreaterThanOrEqual(2 * 3_600_000);
    expect(tarefas[1]!.ms).toBeLessThanOrEqual(6 * 3_600_000);
  });

  it('quando a versão nova termina de baixar, avisa a tela e guarda o estado', () => {
    const { atualizador, emitir } = atualizadorDeMentira();
    const { relogio } = relogioDeMentira();
    const avisos: unknown[] = [];
    const servico = new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      relogio,
      aoMudar: (a) => avisos.push(a),
    });
    servico.iniciar();
    expect(servico.estado()).toEqual({ estado: 'nenhuma' });
    emitir('update-downloaded', { version: '1.0.1' });
    expect(servico.estado()).toEqual({ estado: 'pronta', versao: '1.0.1' });
    expect(avisos).toEqual([{ estado: 'pronta', versao: '1.0.1' }]);
  });

  it('erro de rede ou de arquivo não aparece para a dona e a próxima conferência continua agendada', async () => {
    const { atualizador, emitir } = atualizadorDeMentira();
    atualizador.checkForUpdates.mockRejectedValue(new Error('net::ERR_CONNECTION_REFUSED'));
    const { relogio, tarefas } = relogioDeMentira();
    const avisos: unknown[] = [];
    const registro = vi.fn();
    const servico = new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      relogio,
      aoMudar: (a) => avisos.push(a),
      registrar: registro,
    });
    servico.iniciar();
    tarefas[0]!.fn();
    await vi.waitFor(() => expect(tarefas).toHaveLength(2));
    emitir('error', new Error('sha512 checksum mismatch'));
    expect(avisos).toEqual([]);
    expect(servico.estado()).toEqual({ estado: 'nenhuma' });
    expect(registro).toHaveBeenCalled();
  });

  it('depois de pronta, para de procurar (a próxima versão vem depois do reinício)', async () => {
    const { atualizador, emitir } = atualizadorDeMentira();
    const { relogio, tarefas } = relogioDeMentira();
    const servico = new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      relogio,
      aoMudar: () => undefined,
    });
    servico.iniciar();
    emitir('update-downloaded', { version: '1.0.1' });
    tarefas[0]!.fn();
    await Promise.resolve();
    expect(atualizador.checkForUpdates).not.toHaveBeenCalled();
  });

  it('só mostra aviso para versão com três números', () => {
    const { atualizador, emitir } = atualizadorDeMentira();
    const { relogio } = relogioDeMentira();
    const servico = new ServicoDeAtualizacao({
      atualizador,
      endereco: 'https://203.0.113.10',
      relogio,
      aoMudar: () => undefined,
    });
    servico.iniciar();
    emitir('update-downloaded', { version: '<script>' });
    expect(servico.estado()).toEqual({ estado: 'pronta', versao: '' });
  });
});

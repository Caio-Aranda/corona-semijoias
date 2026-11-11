import type { IpcMainInvokeEvent } from 'electron';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CANAIS, FUNCOES_DA_PONTE } from '../shared/ponte';

const exposto: Record<string, unknown> = {};
const invocar = vi.fn();
const ouvintes = new Map<string, (...args: unknown[]) => void>();
const manipuladores = new Map<string, (e: IpcMainInvokeEvent, ...args: unknown[]) => unknown>();

vi.mock('electron', () => ({
  contextBridge: {
    exposeInMainWorld: (nome: string, valor: unknown) => {
      exposto[nome] = valor;
    },
  },
  ipcRenderer: {
    invoke: (...args: unknown[]) => invocar(...args) as unknown,
    on: (canal: string, fn: (...args: unknown[]) => void) => {
      ouvintes.set(canal, fn);
    },
    removeListener: (canal: string) => {
      ouvintes.delete(canal);
    },
  },
  ipcMain: {
    handle: (canal: string, fn: (e: IpcMainInvokeEvent, ...args: unknown[]) => unknown) => {
      manipuladores.set(canal, fn);
    },
  },
}));

beforeEach(() => {
  invocar.mockReset();
  ouvintes.clear();
  manipuladores.clear();
});

describe('ponte (preload)', () => {
  it('expõe só a lista fixa de funções, e nada mais', async () => {
    await import('../preload/index');
    const ponte = exposto['corona'] as Record<string, unknown>;
    expect(Object.keys(ponte).sort()).toEqual([...FUNCOES_DA_PONTE].sort());
    for (const nome of FUNCOES_DA_PONTE) expect(typeof ponte[nome]).toBe('function');
  });

  it('só expõe a ponte com o nome "corona"', async () => {
    await import('../preload/index');
    expect(Object.keys(exposto)).toEqual(['corona']);
  });

  it('o ouvinte da sessão recebe só o aviso limpo, nunca o evento do Electron', async () => {
    await import('../preload/index');
    const ponte = exposto['corona'] as {
      aoMudarASessao: (f: (a: unknown) => void) => () => void;
    };
    const recebido = vi.fn();
    const cancelar = ponte.aoMudarASessao(recebido);
    const escuta = ouvintes.get(CANAIS.sessaoMudou);
    escuta?.({ sender: 'objeto-do-electron' }, { estado: 'fora', extra: 'não passa' });
    expect(recebido).toHaveBeenCalledWith({ estado: 'fora' });
    cancelar();
    expect(ouvintes.has(CANAIS.sessaoMudou)).toBe(false);
  });
});

describe('atualização automática na ponte', () => {
  it('o ouvinte recebe o estado de agora e as mudanças, só limpos, e para de ouvir ao cancelar', async () => {
    await import('../preload/index');
    invocar.mockResolvedValue({ estado: 'pronta', versao: '1.0.1', extra: 'não passa' });
    const ponte = exposto['corona'] as {
      aoMudarAAtualizacao: (f: (a: unknown) => void) => () => void;
    };
    const recebido = vi.fn();
    const cancelar = ponte.aoMudarAAtualizacao(recebido);
    await vi.waitFor(() =>
      expect(recebido).toHaveBeenCalledWith({ estado: 'pronta', versao: '1.0.1' }),
    );
    expect(invocar).toHaveBeenCalledWith(CANAIS.atualizacaoEstado);
    ouvintes.get(CANAIS.atualizacaoMudou)?.({ sender: 'x' }, { estado: 'nenhuma' });
    expect(recebido).toHaveBeenLastCalledWith({ estado: 'nenhuma' });
    cancelar();
    expect(ouvintes.has(CANAIS.atualizacaoMudou)).toBe(false);
  });

  it('o processo principal só responde o estado da atualização para a janela do app', async () => {
    const { registrarAtualizacao } = await import('./ipc');
    const servico = { estado: () => ({ estado: 'nenhuma' }) };
    registrarAtualizacao(servico as never, () => true);
    const aceita = manipuladores.get(CANAIS.atualizacaoEstado)!;
    await expect(aceita({} as IpcMainInvokeEvent)).resolves.toEqual({ estado: 'nenhuma' });
    manipuladores.clear();
    registrarAtualizacao(servico as never, () => false);
    await expect(
      manipuladores.get(CANAIS.atualizacaoEstado)!({} as IpcMainInvokeEvent),
    ).rejects.toThrow('Chamada recusada.');
  });
});

describe('ponte (processo principal)', () => {
  const evento = {} as IpcMainInvokeEvent;

  async function registrar(confiavel = true) {
    const servico = {
      entrar: vi.fn(() => Promise.resolve({ tipo: 'entrou' })),
      trocarSenha: vi.fn(() => Promise.resolve({ tipo: 'entrou' })),
      sair: vi.fn(() => Promise.resolve()),
      estouLogado: vi.fn(() => Promise.resolve({ estado: 'fora' })),
      chamarApi: vi.fn(() => Promise.resolve({ ok: true, dados: { nome: 'Maria' } })),
    };
    const { registrarPonte } = await import('./ipc');
    registrarPonte(servico as never, () => confiavel);
    return servico;
  }

  it('registra exatamente os canais da ponte', async () => {
    await registrar();
    // Os canais de aviso (sessão e atualização) são só do processo principal para a tela; o estado da
    // atualização é registrado à parte (registrarAtualizacao) e tem teste próprio.
    const doProcessoPrincipal: string[] = [
      CANAIS.sessaoMudou,
      CANAIS.atualizacaoMudou,
      CANAIS.atualizacaoEstado,
    ];
    const esperados = Object.values(CANAIS).filter((c) => !doProcessoPrincipal.includes(c));
    expect([...manipuladores.keys()].sort()).toEqual([...esperados].sort());
  });

  it('recusa quem não é a janela do aplicativo', async () => {
    const servico = await registrar(false);
    const entrar = manipuladores.get(CANAIS.entrar);
    await expect(
      Promise.resolve(entrar?.(evento, { usuario: 'maria', senha: 'x' })),
    ).rejects.toThrow();
    expect(servico.entrar).not.toHaveBeenCalled();
  });

  it('confere o formato antes de chamar o serviço', async () => {
    const servico = await registrar();
    const entrar = manipuladores.get(CANAIS.entrar);
    const maus: unknown[] = [
      null,
      'texto',
      {},
      { usuario: 'maria' },
      { usuario: 1, senha: 'x' },
      { usuario: '', senha: 'x' },
      { usuario: 'a'.repeat(201), senha: 'x' },
      { usuario: 'maria', senha: 'x'.repeat(1001) },
      [],
    ];
    for (const mau of maus) {
      expect(await entrar?.(evento, mau)).toEqual({ tipo: 'erro-inesperado' });
    }
    expect(servico.entrar).not.toHaveBeenCalled();

    await entrar?.(evento, { usuario: 'maria', senha: 'segredo', fora: 'ignorado' });
    expect(servico.entrar).toHaveBeenCalledWith('maria', 'segredo');
  });

  it('só faz as consultas da lista fixa de operações', async () => {
    const servico = await registrar();
    const chamar = manipuladores.get(CANAIS.chamarApi);
    expect(await chamar?.(evento, { operacao: '/api/vendedor/qualquer-coisa' })).toEqual({
      ok: false,
      motivo: 'erro',
    });
    expect(await chamar?.(evento, { operacao: 'eu', url: 'http://outro.site' })).toEqual({
      ok: true,
      dados: { nome: 'Maria' },
    });
    expect(servico.chamarApi).toHaveBeenCalledTimes(1);
    expect(servico.chamarApi).toHaveBeenCalledWith('eu');
  });

  it('o serviço nunca devolve token à tela', async () => {
    await registrar();
    const respostas = await Promise.all([
      manipuladores.get(CANAIS.entrar)?.(evento, { usuario: 'maria', senha: 'x' }),
      manipuladores.get(CANAIS.estouLogado)?.(evento),
      manipuladores.get(CANAIS.chamarApi)?.(evento, { operacao: 'eu' }),
    ]);
    expect(JSON.stringify(respostas).toLowerCase()).not.toMatch(/token|bearer|authorization/);
  });
});

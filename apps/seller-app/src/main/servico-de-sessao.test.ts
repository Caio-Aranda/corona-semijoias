import { describe, expect, it, vi } from 'vitest';
import type { GuardaDeTokens } from './guarda-de-tokens';
import { ServicoDeSessao } from './servico-de-sessao';

/** O banco local é preparado com a senha que o servidor acabou de conferir (tarefa 5.2), e nunca derruba a entrada. */

function guarda(resultado: 'entrou' | 'trocar-senha' | 'recusada'): GuardaDeTokens {
  const r =
    resultado === 'recusada'
      ? {
          ok: false as const,
          falha: {
            tipo: 'recusada' as const,
            status: 401,
            mensagem: 'Usuário ou senha incorretos.',
          },
        }
      : { ok: true as const, dados: resultado };
  return {
    entrar: vi.fn(() => Promise.resolve(r)),
    trocarSenha: vi.fn(() => Promise.resolve(r)),
    sair: vi.fn(() => Promise.resolve()),
  } as unknown as GuardaDeTokens;
}

const api = { eu: vi.fn(), chamar: vi.fn() };

describe('banco local na entrada', () => {
  it('entrar com internet prepara o banco com a senha conferida', async () => {
    const banco = { prepararComInternet: vi.fn(() => Promise.resolve({ ok: true })) };
    const s = new ServicoDeSessao(guarda('entrou'), api, Promise.resolve(), banco);
    expect(await s.entrar('ana', 'Senha-1')).toEqual({ tipo: 'entrou' });
    expect(banco.prepararComInternet).toHaveBeenCalledWith('Senha-1');
  });

  it('a troca obrigatória prepara com a senha nova; senha temporária e recusa não preparam', async () => {
    const banco = { prepararComInternet: vi.fn(() => Promise.resolve({ ok: true })) };
    await new ServicoDeSessao(guarda('trocar-senha'), api, Promise.resolve(), banco).entrar(
      'ana',
      'Temporaria-1',
    );
    await new ServicoDeSessao(guarda('recusada'), api, Promise.resolve(), banco).entrar(
      'ana',
      'errada',
    );
    expect(banco.prepararComInternet).not.toHaveBeenCalled();
    await new ServicoDeSessao(guarda('entrou'), api, Promise.resolve(), banco).trocarSenha(
      'Nova-Senha-2',
    );
    expect(banco.prepararComInternet).toHaveBeenCalledWith('Nova-Senha-2');
  });

  it('banco que não abre não impede a entrada e a senha não vai para o registro', async () => {
    const aviso = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const banco = { prepararComInternet: vi.fn(() => Promise.reject(new Error('Senha-1 disco'))) };
    expect(
      await new ServicoDeSessao(guarda('entrou'), api, Promise.resolve(), banco).entrar(
        'ana',
        'Senha-1',
      ),
    ).toEqual({
      tipo: 'entrou',
    });
    expect(JSON.stringify(aviso.mock.calls)).not.toContain('Senha-1');
    aviso.mockRestore();
  });
});

describe('motor de envio na sessão (tarefa 5.6)', () => {
  it('liga só com o banco aberto depois de entrar e desliga ao sair', async () => {
    const motor = { ligar: vi.fn(), desligar: vi.fn() };
    const aberto = { prepararComInternet: vi.fn(() => Promise.resolve({ ok: true })) };
    const s = new ServicoDeSessao(guarda('entrou'), api, Promise.resolve(), aberto, motor);
    await s.entrar('ana', 'Senha-1');
    expect(motor.ligar).toHaveBeenCalledTimes(1);
    await s.sair();
    expect(motor.desligar).toHaveBeenCalledTimes(1);

    const fechado = {
      prepararComInternet: vi.fn(() => Promise.resolve({ ok: false, codigo: 'DISCO_CHEIO' })),
    };
    const outro = { ligar: vi.fn(), desligar: vi.fn() };
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await new ServicoDeSessao(guarda('entrou'), api, Promise.resolve(), fechado, outro).entrar(
      'ana',
      'Senha-1',
    );
    await new ServicoDeSessao(guarda('recusada'), api, Promise.resolve(), aberto, outro).entrar(
      'ana',
      'errada',
    );
    expect(outro.ligar).not.toHaveBeenCalled();
  });
});

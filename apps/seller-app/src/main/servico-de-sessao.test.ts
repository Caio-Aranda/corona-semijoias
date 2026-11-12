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

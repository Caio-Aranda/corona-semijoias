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

describe('telas no banco local (tarefa 5.7)', () => {
  const guardaComToken = {
    comToken: vi.fn(() => Promise.resolve({ ok: true as const, dados: 'da-internet' })),
  } as unknown as GuardaDeTokens;

  it('com o banco aberto, venda, maleta e clientes são atendidos no computador, sem passar pela internet', async () => {
    const consultas = {
      disponivel: true,
      chamar: vi.fn(() => ({ ok: true as const, dados: ['do-banco'] })),
    };
    const s = new ServicoDeSessao(
      guardaComToken,
      api,
      Promise.resolve(),
      undefined,
      undefined,
      consultas,
    );
    for (const operacao of ['maletas.listar', 'cotacao.hoje', 'relatorios.aReceber'] as const) {
      expect(await s.chamarApi({ operacao })).toEqual({ ok: true, dados: ['do-banco'] });
    }
    expect(guardaComToken.comToken).not.toHaveBeenCalled();
  });

  it('foto e "quem sou eu" continuam na internet', async () => {
    const consultas = { disponivel: true, chamar: vi.fn() };
    const s = new ServicoDeSessao(
      guardaComToken,
      api,
      Promise.resolve(),
      undefined,
      undefined,
      consultas,
    );
    await s.chamarApi({ operacao: 'eu' });
    await s.chamarApi({ operacao: 'fotos.ver', hash: 'a'.repeat(64), tamanho: 'miniatura' });
    expect(consultas.chamar).not.toHaveBeenCalled();
    expect(guardaComToken.comToken).toHaveBeenCalledTimes(2);
  });

  it('foto: com o banco aberto vem do disco do computador, sem internet; sem cópia, falha comum', async () => {
    vi.mocked(guardaComToken.comToken).mockClear();
    const consultas = { disponivel: true, chamar: vi.fn() };
    const fotos = {
      ver: vi
        .fn()
        .mockResolvedValueOnce({ ok: true, dados: { dataUrl: 'data:image/jpeg;base64,AA==' } })
        .mockResolvedValueOnce({ ok: false, falha: { tipo: 'sem-conexao' } }),
    };
    const s = new ServicoDeSessao(
      guardaComToken,
      api,
      Promise.resolve(),
      undefined,
      undefined,
      consultas,
      undefined,
      undefined,
      fotos,
    );
    const pedido = { operacao: 'fotos.ver', hash: 'a'.repeat(64), tamanho: 'media' } as const;
    expect(await s.chamarApi(pedido)).toEqual({
      ok: true,
      dados: { dataUrl: 'data:image/jpeg;base64,AA==' },
    });
    expect(await s.chamarApi(pedido)).toEqual({ ok: false, motivo: 'sem-conexao' });
    expect(guardaComToken.comToken).not.toHaveBeenCalled();
  });

  it('a recusa do banco chega à tela com a mensagem em português e o cliente que já existe', async () => {
    const consultas = {
      disponivel: true,
      chamar: vi.fn(() => ({
        ok: false as const,
        falha: {
          tipo: 'recusada' as const,
          status: 409,
          mensagem: 'Este cliente já está cadastrado: Ana.',
          clienteExistente: { id: 'x', nome: 'Ana' },
        },
      })),
    };
    const s = new ServicoDeSessao(
      guardaComToken,
      api,
      Promise.resolve(),
      undefined,
      undefined,
      consultas,
    );
    expect(await s.chamarApi({ operacao: 'envios.naoAceitos' })).toEqual({
      ok: false,
      motivo: 'erro',
      mensagem: 'Este cliente já está cadastrado: Ana.',
      clienteExistente: { id: 'x', nome: 'Ana' },
    });
  });

  it('sem banco aberto, a chamada segue pela internet', async () => {
    const consultas = { disponivel: false, chamar: vi.fn() };
    const s = new ServicoDeSessao(
      guardaComToken,
      api,
      Promise.resolve(),
      undefined,
      undefined,
      consultas,
    );
    expect(await s.chamarApi({ operacao: 'maletas.listar' })).toEqual({
      ok: true,
      dados: 'da-internet',
    });
    expect(consultas.chamar).not.toHaveBeenCalled();
  });
});

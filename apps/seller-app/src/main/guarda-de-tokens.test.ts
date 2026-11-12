import { describe, expect, it, vi } from 'vitest';
import { GuardaDeTokens } from './guarda-de-tokens';
import type {
  ApiDeAutenticacao,
  ArquivoDoToken,
  Cofre,
  Credenciais,
  Falha,
  Relogio,
  Resultado,
} from './tipos';

const MIN = 60_000;

function credenciais(n: number, extra: Partial<Credenciais> = {}): Credenciais {
  return {
    tokenDeAcesso: `acesso-${n}`,
    tokenDeRenovacao: `renovacao-${n}`,
    validadeEmSegundos: 900,
    trocaDeSenhaObrigatoria: false,
    ...extra,
  };
}

const ok = <T>(dados: T): Resultado<T> => ({ ok: true, dados });
const falhou = (falha: Falha): Resultado<never> => ({ ok: false, falha });
const naoAutorizado = falhou({ tipo: 'recusada', status: 401 });

/** Relógio de mentira: o tempo só anda quando o teste manda, e os agendamentos ficam à vista. */
function relogioFalso() {
  let agora = 1_000_000;
  const agendados: { ms: number; fn: () => void; cancelado: boolean }[] = [];
  const relogio: Relogio = {
    agora: () => agora,
    agendar(ms, fn) {
      const item = { ms, fn, cancelado: false };
      agendados.push(item);
      return () => {
        item.cancelado = true;
      };
    },
  };
  return {
    relogio,
    agendados,
    avancar: (ms: number) => {
      agora += ms;
    },
  };
}

function arquivoFalso(inicial: Uint8Array | null = null) {
  const estado = { conteudo: inicial, gravacoes: 0, apagamentos: 0 };
  const arquivo: ArquivoDoToken = {
    ler: () => Promise.resolve(estado.conteudo),
    gravar: (dados) => {
      estado.conteudo = dados;
      estado.gravacoes += 1;
      return Promise.resolve();
    },
    apagar: () => {
      estado.conteudo = null;
      estado.apagamentos += 1;
      return Promise.resolve();
    },
  };
  return { arquivo, estado };
}

/** "Cifra" de mentira que embaralha o texto: se o teste achar o token em claro no disco, falha. */
function cofreFalso(disponivel = true): Cofre {
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  return {
    disponivel: () => Promise.resolve(disponivel),
    cifrar: (texto) => Promise.resolve(enc.encode(`cifrado:${[...texto].reverse().join('')}`)),
    decifrar: (dados) =>
      Promise.resolve([...dec.decode(dados).replace('cifrado:', '')].reverse().join('')),
  };
}

function montar(
  opcoes: {
    cofreDisponivel?: boolean;
    api?: Partial<ApiDeAutenticacao>;
    aoContatar?: (c: Credenciais) => void | Promise<void>;
  } = {},
) {
  const tempo = relogioFalso();
  const disco = arquivoFalso();
  let contador = 1;
  const api: ApiDeAutenticacao = {
    entrar: vi.fn(() => Promise.resolve(ok(credenciais(contador)))),
    renovar: vi.fn(() => Promise.resolve(ok(credenciais(++contador)))),
    trocarSenha: vi.fn(() => Promise.resolve(ok(credenciais(++contador)))),
    sair: vi.fn(() => Promise.resolve(ok(null))),
    ...opcoes.api,
  };
  const aoPerder = vi.fn();
  const guarda = new GuardaDeTokens({
    api,
    cofre: cofreFalso(opcoes.cofreDisponivel ?? true),
    arquivo: disco.arquivo,
    relogio: tempo.relogio,
    aoPerderASessao: aoPerder,
    ...(opcoes.aoContatar ? { aoContatarOServidor: opcoes.aoContatar } : {}),
  });
  return { guarda, api, disco, tempo, aoPerder };
}

describe('guarda de tokens: onde ficam os tokens', () => {
  it('guarda o token de renovação cifrado em disco, nunca em claro', async () => {
    const { guarda, disco } = montar();
    await guarda.entrar('maria', 'senha');
    expect(disco.estado.gravacoes).toBe(1);
    const gravado = new TextDecoder().decode(disco.estado.conteudo ?? new Uint8Array());
    expect(gravado.startsWith('cifrado:')).toBe(true);
    expect(gravado).not.toContain('renovacao-1');
  });

  it('sem o cofre do sistema não grava nada em claro e a sessão vale só até fechar o app', async () => {
    const { guarda, disco } = montar({ cofreDisponivel: false });
    const r = await guarda.entrar('maria', 'senha');
    expect(r).toEqual({ ok: true, dados: 'entrou' });
    expect(disco.estado.gravacoes).toBe(0);
    expect(disco.estado.conteudo).toBeNull();
    expect(guarda.situacao()).toBe('dentro');
  });

  it('sem o cofre, descarta o que sobrou no disco e pede novo login ao abrir', async () => {
    const { guarda, disco, api } = montar({ cofreDisponivel: false });
    disco.estado.conteudo = new TextEncoder().encode('qualquer coisa');
    await guarda.restaurar();
    expect(disco.estado.conteudo).toBeNull();
    expect(api.renovar).not.toHaveBeenCalled();
    expect(guarda.situacao()).toBe('fora');
  });

  it('arquivo que não abre (cofre mudou) é apagado e a pessoa entra de novo', async () => {
    const { disco } = montar();
    disco.estado.conteudo = new Uint8Array([1, 2, 3]);
    const decifrarQuebrado: Cofre = {
      disponivel: () => Promise.resolve(true),
      cifrar: () => Promise.resolve(new Uint8Array()),
      decifrar: () => Promise.reject(new Error('cofre diferente')),
    };
    const g = new GuardaDeTokens({
      api: montar().api,
      cofre: decifrarQuebrado,
      arquivo: disco.arquivo,
      relogio: relogioFalso().relogio,
    });
    await g.restaurar();
    expect(disco.estado.conteudo).toBeNull();
    expect(g.situacao()).toBe('fora');
  });

  it('abre o app, decifra o token guardado e renova a sessão', async () => {
    const primeira = montar();
    await primeira.guarda.entrar('maria', 'senha');
    const segunda = montar();
    segunda.disco.estado.conteudo = primeira.disco.estado.conteudo;
    await segunda.guarda.restaurar();
    expect(segunda.api.renovar).toHaveBeenCalledWith('renovacao-1');
    expect(segunda.guarda.situacao()).toBe('dentro');
  });

  it('com senha temporária guarda só o token restrito, na memória, e nada em disco', async () => {
    const { guarda, disco } = montar({
      api: {
        entrar: () =>
          Promise.resolve(
            ok(credenciais(1, { trocaDeSenhaObrigatoria: true, tokenDeRenovacao: undefined })),
          ),
      },
    });
    const r = await guarda.entrar('maria', 'temporaria');
    expect(r).toEqual({ ok: true, dados: 'trocar-senha' });
    expect(guarda.situacao()).toBe('restrita');
    expect(disco.estado.conteudo).toBeNull();
  });

  it('nenhum método da guarda devolve o valor de um token', async () => {
    const { guarda } = montar();
    const resultados = [
      await guarda.entrar('maria', 'senha'),
      await guarda.comToken(() => Promise.resolve(ok('dados'))),
      guarda.situacao(),
    ];
    expect(JSON.stringify(resultados)).not.toMatch(/acesso-|renovacao-/);
  });
});

describe('guarda de tokens: renovação', () => {
  it('renova uma vez por vez: três pedidos juntos viram uma única chamada de renovação', async () => {
    let liberar: (r: Resultado<Credenciais>) => void = () => undefined;
    const renovar = vi.fn(
      () =>
        new Promise<Resultado<Credenciais>>((resolver) => {
          liberar = resolver;
        }),
    );
    const { guarda } = montar({ api: { renovar } });
    await guarda.entrar('maria', 'senha');

    const tres = [guarda.renovar(), guarda.renovar(), guarda.renovar()];
    expect(renovar).toHaveBeenCalledTimes(1);
    liberar(ok(credenciais(2)));
    expect(await Promise.all(tres)).toEqual([true, true, true]);
    expect(renovar).toHaveBeenCalledTimes(1);
    expect(renovar).toHaveBeenCalledWith('renovacao-1');
  });

  it('cada renovação usa o token novo (rotação), nunca o antigo de novo', async () => {
    const { guarda, api } = montar();
    await guarda.entrar('maria', 'senha');
    await guarda.renovar();
    await guarda.renovar();
    expect(vi.mocked(api.renovar).mock.calls.map((c) => c[0])).toEqual([
      'renovacao-1',
      'renovacao-2',
    ]);
  });

  it('um 401 dispara uma única renovação, mesmo com várias chamadas falhando juntas', async () => {
    const { guarda, api } = montar();
    await guarda.entrar('maria', 'senha');

    // As três chamadas saem com o token 1; o servidor recusa as três.
    const chamada = vi.fn((token: string) =>
      Promise.resolve(token === 'acesso-1' ? naoAutorizado : ok('certo')),
    );
    const resultados = await Promise.all([
      guarda.comToken(chamada),
      guarda.comToken(chamada),
      guarda.comToken(chamada),
    ]);

    expect(api.renovar).toHaveBeenCalledTimes(1);
    expect(resultados.every((r) => r.ok)).toBe(true);
    // Cada chamada foi repetida uma vez só, agora com o token novo.
    expect(chamada.mock.calls.filter((c) => c[0] === 'acesso-2')).toHaveLength(3);
  });

  it('se depois da renovação ainda vier 401, a sessão acaba (sem laço de tentativas)', async () => {
    const { guarda, api, aoPerder } = montar();
    await guarda.entrar('maria', 'senha');
    const r = await guarda.comToken(() => Promise.resolve(naoAutorizado));
    expect(r.ok).toBe(false);
    expect(api.renovar).toHaveBeenCalledTimes(1);
    expect(guarda.situacao()).toBe('fora');
    expect(aoPerder).toHaveBeenCalledWith({ estado: 'fora' });
  });

  it('renova antes de vencer: o token de acesso quase vencido é trocado antes da chamada', async () => {
    const { guarda, api, tempo } = montar();
    await guarda.entrar('maria', 'senha');
    tempo.avancar(14.9 * MIN);
    const chamada = vi.fn((token: string) => Promise.resolve(ok(token)));
    const r = await guarda.comToken(chamada);
    expect(api.renovar).toHaveBeenCalledTimes(1);
    expect(r).toEqual({ ok: true, dados: 'acesso-2' });
  });

  it('agenda a renovação 1 minuto antes de o token de acesso vencer', async () => {
    const { guarda, tempo, api } = montar();
    await guarda.entrar('maria', 'senha');
    expect(tempo.agendados).toHaveLength(1);
    expect(tempo.agendados[0]?.ms).toBe(14 * MIN);
    tempo.agendados[0]?.fn();
    await vi.waitFor(() => expect(api.renovar).toHaveBeenCalledTimes(1));
  });

  it('renovação recusada de vez leva à tela de entrar e apaga o que estava guardado', async () => {
    const { guarda, disco, aoPerder } = montar({
      api: { renovar: () => Promise.resolve(naoAutorizado) },
    });
    await guarda.entrar('maria', 'senha');
    expect(await guarda.renovar()).toBe(false);
    expect(guarda.situacao()).toBe('fora');
    expect(disco.estado.conteudo).toBeNull();
    expect(aoPerder).toHaveBeenCalledWith({ estado: 'fora' });
  });

  it('sem internet na renovação a sessão continua guardada (não derruba a pessoa)', async () => {
    const { guarda, aoPerder, disco } = montar({
      api: { renovar: () => Promise.resolve(falhou({ tipo: 'sem-conexao' })) },
    });
    await guarda.entrar('maria', 'senha');
    expect(await guarda.renovar()).toBe(false);
    expect(aoPerder).not.toHaveBeenCalled();
    expect(disco.estado.conteudo).not.toBeNull();
  });

  it('nunca apresenta o mesmo token pela terceira vez (revogaria a sessão inteira)', async () => {
    // O servidor viu a apresentação (respondeu 503): conta. Sem resposta nenhuma (sem internet), não conta (tarefa 5.8).
    const renovar = vi.fn(() => Promise.resolve(falhou({ tipo: 'recusada', status: 503 })));
    const { guarda, aoPerder } = montar({ api: { renovar } });
    await guarda.entrar('maria', 'senha');
    await guarda.renovar();
    await guarda.renovar();
    await guarda.renovar();
    expect(renovar).toHaveBeenCalledTimes(2);
    expect(guarda.situacao()).toBe('fora');
    expect(aoPerder).toHaveBeenCalledWith({ estado: 'fora' });
  });

  it('aplicativo desatualizado na renovação avisa a tela com a mensagem da API', async () => {
    const { guarda, aoPerder } = montar({
      api: {
        renovar: () =>
          Promise.resolve(falhou({ tipo: 'app-desatualizado', mensagem: 'Texto da API.' })),
      },
    });
    await guarda.entrar('maria', 'senha');
    await guarda.renovar();
    expect(aoPerder).toHaveBeenCalledWith({
      estado: 'app-desatualizado',
      mensagem: 'Texto da API.',
    });
  });
});

describe('guarda de tokens: troca de senha e saída', () => {
  it('a troca de senha usa o token restrito e depois guarda a sessão normal', async () => {
    const { guarda, api, disco } = montar({
      api: {
        entrar: () =>
          Promise.resolve(
            ok(credenciais(1, { trocaDeSenhaObrigatoria: true, tokenDeRenovacao: undefined })),
          ),
        trocarSenha: vi.fn(() => Promise.resolve(ok(credenciais(2)))),
      },
    });
    await guarda.entrar('maria', 'temporaria');
    const r = await guarda.trocarSenha('Senha-nova-123');
    expect(r).toEqual({ ok: true, dados: 'entrou' });
    expect(api.trocarSenha).toHaveBeenCalledWith('acesso-1', 'Senha-nova-123');
    expect(guarda.situacao()).toBe('dentro');
    expect(disco.estado.conteudo).not.toBeNull();
  });

  it('não troca senha sem ter entrado com a senha temporária', async () => {
    const { guarda, api } = montar();
    await guarda.entrar('maria', 'senha');
    const r = await guarda.trocarSenha('Senha-nova-123');
    expect(r.ok).toBe(false);
    expect(api.trocarSenha).not.toHaveBeenCalled();
  });

  it('sair avisa o servidor (que revoga a sessão) e apaga tudo daqui', async () => {
    const { guarda, api, disco } = montar();
    await guarda.entrar('maria', 'senha');
    await guarda.sair();
    expect(api.sair).toHaveBeenCalledWith('acesso-1');
    expect(guarda.situacao()).toBe('fora');
    expect(disco.estado.conteudo).toBeNull();
  });

  it('sair funciona mesmo sem internet: apaga o local e não trava', async () => {
    const { guarda, disco } = montar({
      api: { sair: () => Promise.reject(new Error('sem rede')) },
    });
    await guarda.entrar('maria', 'senha');
    await expect(guarda.sair()).resolves.toBeUndefined();
    expect(guarda.situacao()).toBe('fora');
    expect(disco.estado.conteudo).toBeNull();
  });
});

describe('guarda de tokens: último contato com o servidor (prazo offline)', () => {
  it('avisa a cada resposta válida de entrar e de renovar, com a hora e o prazo do servidor', async () => {
    const aoContatar = vi.fn();
    const { guarda, api } = montar({ aoContatar });
    vi.mocked(api.entrar).mockResolvedValueOnce(
      ok(credenciais(1, { horaDoServidor: '2026-11-09T12:00:00Z', tempoMaximoOfflineDias: 10 })),
    );
    await guarda.entrar('maria', 'senha');
    await guarda.renovar();
    expect(aoContatar).toHaveBeenCalledTimes(2);
    expect(aoContatar.mock.calls[0]?.[0]).toMatchObject({
      horaDoServidor: '2026-11-09T12:00:00Z',
      tempoMaximoOfflineDias: 10,
    });
  });

  it('falha ao guardar o contato não derruba a entrada', async () => {
    const { guarda } = montar({ aoContatar: () => Promise.reject(new Error('disco cheio')) });
    expect(await guarda.entrar('maria', 'senha')).toEqual({ ok: true, dados: 'entrou' });
  });

  it('entrada recusada não conta como contato', async () => {
    const aoContatar = vi.fn();
    const { guarda } = montar({
      aoContatar,
      api: { entrar: vi.fn(() => Promise.resolve(naoAutorizado)) },
    });
    await guarda.entrar('maria', 'errada');
    expect(aoContatar).not.toHaveBeenCalled();
  });
});

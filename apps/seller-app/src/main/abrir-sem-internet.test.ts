import { describe, expect, it, vi } from 'vitest';
import { GuardaDeTokens, type OpcoesDaGuarda } from './guarda-de-tokens';
import { ServicoDeSessao } from './servico-de-sessao';
import type { ApiDeAutenticacao, Credenciais, Resultado } from './tipos';

/**
 * Abrir sem internet e a ordem de apagar na sessão (tarefa 5.8; ADR-0013, itens 12 e 14): a senha é conferida no
 * computador, a sessão guardada sobrevive a vários "abrir sem internet" e o computador desligado assume o apagar.
 */

const semInternet: Resultado<never> = { ok: false, falha: { tipo: 'sem-conexao' } };
const ordem: Resultado<never> = {
  ok: false,
  falha: { tipo: 'recusada', status: 403, codigo: 'APARELHO_BLOQUEADO', acao: 'APAGAR_DADOS' },
};
const credenciais = (extra: Partial<Credenciais> = {}): Credenciais => ({
  tokenDeAcesso: 'acesso-1',
  tokenDeRenovacao: 'renovacao-1',
  validadeEmSegundos: 900,
  trocaDeSenhaObrigatoria: false,
  ...extra,
});

function relogioParado() {
  return { agora: () => 0, agendar: () => () => undefined };
}

function guardaReal(api: Partial<ApiDeAutenticacao>, opcoes: Partial<OpcoesDaGuarda> = {}) {
  let arquivo: Uint8Array | null = new TextEncoder().encode('renovacao-guardada');
  return new GuardaDeTokens({
    api: {
      entrar: vi.fn(() => Promise.resolve(semInternet)),
      renovar: vi.fn(() => Promise.resolve(semInternet)),
      trocarSenha: vi.fn(),
      sair: vi.fn(() => Promise.resolve({ ok: true as const, dados: null })),
      ...api,
    },
    cofre: {
      disponivel: () => Promise.resolve(true),
      cifrar: (t) => Promise.resolve(new TextEncoder().encode(t)),
      decifrar: (d) => Promise.resolve(new TextDecoder().decode(d)),
    },
    arquivo: {
      ler: () => Promise.resolve(arquivo),
      gravar: (d) => {
        arquivo = d;
        return Promise.resolve();
      },
      apagar: () => {
        arquivo = null;
        return Promise.resolve();
      },
    },
    relogio: relogioParado(),
    ...opcoes,
  });
}

const api = { eu: vi.fn(), chamar: vi.fn() };

function bancoLocal(opcoes: { temDados?: boolean; senhaCerta?: string; login?: string } = {}) {
  let aberto = false;
  return {
    prepararComInternet: vi.fn(() => {
      aberto = true;
      return Promise.resolve({ ok: true });
    }),
    temDadosLocais: opcoes.temDados ?? true,
    get bancoAberto() {
      return aberto;
    },
    abrirSemInternet: vi.fn((senha: string) => {
      if (senha === (opcoes.senhaCerta ?? 'Senha-1')) {
        aberto = true;
        return Promise.resolve({ ok: true });
      }
      return Promise.resolve({
        ok: false,
        codigo: 'SENHA_ERRADA',
        mensagem: 'Usuário ou senha incorretos. Confira o que você digitou e tente de novo.',
      });
    }),
    fechar: vi.fn(() => {
      aberto = false;
    }),
    lembrar: vi.fn(),
    lembrado: vi.fn((chave: string) =>
      chave === 'vendedor_login' ? (opcoes.login ?? 'ana') : 'Ana Souza',
    ),
    estaAberto: () => aberto,
  };
}

describe('reabrir o aplicativo com internet (defeito 2 do teste 9.10)', () => {
  const renovaBem = () =>
    guardaReal({
      renovar: vi.fn(() => Promise.resolve({ ok: true as const, dados: credenciais() })),
      entrar: vi.fn(() => Promise.resolve({ ok: true as const, dados: credenciais() })),
    });

  it('sessão renovada com internet, mas banco fechado: pede a senha, nunca mostra a tela principal', async () => {
    const guarda = renovaBem();
    await guarda.restaurar();
    const s = new ServicoDeSessao(guarda, api, Promise.resolve(), bancoLocal());
    expect(await s.estouLogado()).toEqual({ estado: 'reabrir' });
  });

  it('com a senha certa o banco abre, o motor liga e entra de verdade', async () => {
    const banco = bancoLocal();
    const motor = { ligar: vi.fn(), desligar: vi.fn() };
    const guarda = renovaBem();
    await guarda.restaurar();
    const s = new ServicoDeSessao(
      guarda,
      {
        ...api,
        eu: vi.fn(() => Promise.resolve({ ok: true as const, dados: { nome: 'Ana Souza' } })),
      },
      Promise.resolve(),
      banco,
      motor,
    );
    expect(await s.entrar('ana', 'Senha-1')).toEqual({ tipo: 'entrou' });
    expect(banco.prepararComInternet).toHaveBeenCalledWith('Senha-1');
    expect(motor.ligar).toHaveBeenCalled();
    expect(await s.estouLogado()).toMatchObject({ estado: 'dentro' });
  });

  it('sem dados locais (primeira vez), a sessão renovada segue direto', async () => {
    const guarda = renovaBem();
    await guarda.restaurar();
    const s = new ServicoDeSessao(
      guarda,
      { ...api, eu: vi.fn(() => Promise.resolve({ ok: true as const, dados: { nome: 'Ana' } })) },
      Promise.resolve(),
      bancoLocal({ temDados: false }),
    );
    expect(await s.estouLogado()).toMatchObject({ estado: 'dentro' });
  });
});

describe('abrir sem internet', () => {
  it('com sessão guardada e dados no computador, a tela pede só a senha (nada de "é preciso internet")', async () => {
    const guarda = guardaReal({});
    await guarda.restaurar();
    const s = new ServicoDeSessao(guarda, api, Promise.resolve(), bancoLocal());
    expect(await s.estouLogado()).toEqual({ estado: 'entrar-sem-internet' });
  });

  it('sem dados no computador (primeira vez), continua o "sem conexão"', async () => {
    const guarda = guardaReal({});
    await guarda.restaurar();
    const s = new ServicoDeSessao(guarda, api, Promise.resolve(), bancoLocal({ temDados: false }));
    expect(await s.estouLogado()).toEqual({ estado: 'sem-conexao' });
  });

  it('senha certa abre o banco, liga o motor e a pessoa fica dentro com o nome guardado', async () => {
    const banco = bancoLocal();
    const motor = { ligar: vi.fn(), desligar: vi.fn() };
    const s = new ServicoDeSessao(guardaReal({}), api, Promise.resolve(), banco, motor);
    expect(await s.entrar('Ana', 'Senha-1')).toEqual({ tipo: 'entrou' });
    expect(banco.abrirSemInternet).toHaveBeenCalledWith('Senha-1');
    expect(motor.ligar).toHaveBeenCalledTimes(1);
    expect(await s.estouLogado()).toEqual({ estado: 'dentro', usuario: { nome: 'Ana Souza' } });
  });

  it('senha errada mostra o texto do glossário e não abre nada', async () => {
    const banco = bancoLocal();
    const motor = { ligar: vi.fn(), desligar: vi.fn() };
    const s = new ServicoDeSessao(guardaReal({}), api, Promise.resolve(), banco, motor);
    expect(await s.entrar('ana', 'errada')).toEqual({
      tipo: 'recusado',
      mensagem: 'Usuário ou senha incorretos. Confira o que você digitou e tente de novo.',
      codigo: 'SENHA_ERRADA',
    });
    expect(motor.ligar).not.toHaveBeenCalled();
  });

  it('usuário diferente do que usa este computador recebe a mesma mensagem e o banco fecha', async () => {
    const banco = bancoLocal({ login: 'ana' });
    const s = new ServicoDeSessao(guardaReal({}), api, Promise.resolve(), banco);
    const r = await s.entrar('outra', 'Senha-1');
    expect(r).toMatchObject({ tipo: 'recusado' });
    expect(banco.fechar).toHaveBeenCalled();
  });

  it('internet voltando, a entrada normal continua valendo e não usa a senha do computador', async () => {
    const banco = bancoLocal();
    const guarda = guardaReal({
      entrar: vi.fn(() => Promise.resolve({ ok: true as const, dados: credenciais() })),
    });
    const s = new ServicoDeSessao(guarda, api, Promise.resolve(), banco);
    expect(await s.entrar('ana', 'Senha-1')).toEqual({ tipo: 'entrou' });
    expect(banco.abrirSemInternet).not.toHaveBeenCalled();
    expect(banco.prepararComInternet).toHaveBeenCalledWith('Senha-1');
  });

  it('abrir o aplicativo várias vezes sem internet não gasta a sessão guardada', async () => {
    const renovar = vi.fn(() => Promise.resolve(semInternet));
    const guarda = guardaReal({ renovar });
    await guarda.restaurar();
    for (let i = 0; i < 6; i++) await guarda.renovar();
    expect(guarda.situacao()).toBe('pendente');
    // Quando a internet volta, o mesmo token ainda vale: nenhuma terceira "apresentação" foi contada.
    renovar.mockImplementationOnce((() =>
      Promise.resolve({
        ok: true as const,
        dados: credenciais({ tokenDeRenovacao: 'renovacao-2' }),
      })) as never);
    expect(await guarda.renovar()).toBe(true);
    expect(guarda.situacao()).toBe('dentro');
  });
});

describe('ordem de apagar na sessão', () => {
  it('a renovação com a ordem de apagar mantém o token para a quarentena e avisa', async () => {
    const aoReceber = vi.fn();
    const guarda = guardaReal(
      { renovar: vi.fn(() => Promise.resolve(ordem)) },
      { aoReceberOrdemDeApagar: aoReceber },
    );
    await guarda.restaurar();
    expect(aoReceber).toHaveBeenCalledTimes(1);
    // O token continua guardado (só ele prova ao servidor que este é o computador bloqueado)…
    expect(guarda.situacao()).toBe('pendente');
    // …mas só uma função o vê: nunca é devolvido.
    expect(await guarda.comTokenDeRenovacao((t) => Promise.resolve(t.length))).toBe(
      'renovacao-guardada'.length,
    );
  });

  it('o login do computador bloqueado manda apagar; a tela passa a "desligando" e depois "desligado"', async () => {
    let terminar: () => void = () => undefined;
    const apagador = {
      receberOrdem: vi.fn(() => new Promise<void>((r) => (terminar = r))),
    };
    const avisos: string[] = [];
    const motor = { ligar: vi.fn(), desligar: vi.fn() };
    const s = new ServicoDeSessao(
      guardaReal({ entrar: vi.fn(() => Promise.resolve(ordem)) }),
      api,
      Promise.resolve(),
      bancoLocal(),
      motor,
      undefined,
      apagador,
      (e) => avisos.push(e),
    );
    await s.entrar('ana', 'Senha-1');
    expect(apagador.receberOrdem).toHaveBeenCalledTimes(1);
    expect(motor.desligar).toHaveBeenCalled();
    expect(await s.estouLogado()).toEqual({ estado: 'desligando' });

    terminar();
    await vi.waitFor(async () => {
      expect(await s.estouLogado()).toEqual({ estado: 'desligado' });
    });
    expect(avisos).toEqual(['DESLIGANDO', 'DESLIGADO']);
  });

  it('repetir a ordem enquanto apaga não apaga duas vezes', () => {
    const apagador = { receberOrdem: vi.fn(() => new Promise<void>(() => undefined)) };
    const s = new ServicoDeSessao(
      guardaReal({}),
      api,
      Promise.resolve(),
      bancoLocal(),
      undefined,
      undefined,
      apagador,
    );
    void s.ordemDeApagar();
    void s.ordemDeApagar();
    expect(apagador.receberOrdem).toHaveBeenCalledTimes(1);
  });

  it('o apagar que não termina deixa a marca em disco e a tela continua em "desligando"', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const apagador = { receberOrdem: vi.fn(() => Promise.reject(new Error('disco'))) };
    const s = new ServicoDeSessao(
      guardaReal({}),
      api,
      Promise.resolve(),
      bancoLocal(),
      undefined,
      undefined,
      apagador,
    );
    await s.ordemDeApagar();
    expect(await s.estouLogado()).toEqual({ estado: 'desligando' });
  });
});

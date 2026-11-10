import { describe, expect, it, vi } from 'vitest';
import type { GuardaDeTokens } from './guarda-de-tokens';
import { ServicoDeSessao, type ChamadasDaApi } from './servico-de-sessao';
import type { Falha, Resultado } from './tipos';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';

function montar(resposta: Resultado<unknown>) {
  const executar = vi.fn(() => Promise.resolve(resposta));
  const api: ChamadasDaApi = {
    eu: vi.fn(() => Promise.resolve({ ok: true as const, dados: { nome: 'Maria' } })),
    executar,
  };
  const guarda = {
    comToken: <T>(chamada: (t: string) => Promise<Resultado<T>>) => chamada('acesso-secreto'),
    situacao: () => 'dentro',
  } as unknown as GuardaDeTokens;
  return { servico: new ServicoDeSessao(guarda, api), executar };
}

const recusada = (f: Omit<Extract<Falha, { tipo: 'recusada' }>, 'tipo'>): Resultado<never> => ({
  ok: false,
  falha: { tipo: 'recusada', ...f },
});

describe('serviço de sessão: chamadas da dona', () => {
  it('passa os dados conferidos à API e devolve só o que ela respondeu', async () => {
    const { servico, executar } = montar({ ok: true, dados: { itens: [] } });
    const r = await servico.chamarApi('usuarios.desativar', { id: ID, extra: 'ignorado' });
    expect(r).toEqual({ ok: true, dados: { itens: [] } });
    expect(executar).toHaveBeenCalledWith('acesso-secreto', {
      operacao: 'usuarios.desativar',
      id: ID,
    });
  });

  it('dados fora do formato nem chegam à API', async () => {
    const { servico, executar } = montar({ ok: true, dados: {} });
    expect(await servico.chamarApi('usuarios.desativar', { id: 'x' })).toEqual({
      ok: false,
      motivo: 'erro',
    });
    expect(executar).not.toHaveBeenCalled();
  });

  it('409 com código e campos chega à tela com o texto do servidor', async () => {
    const { servico } = montar(
      recusada({
        status: 409,
        codigo: 'CADASTRO_ALTERADO',
        mensagem: 'Este cadastro foi alterado por outra pessoa. Atualize a tela.',
        campos: [{ campo: 'nome', mensagem: 'Confira este campo.' }],
      }),
    );
    expect(await servico.chamarApi('avisos.listar')).toEqual({
      ok: false,
      motivo: 'erro',
      status: 409,
      mensagem: 'Este cadastro foi alterado por outra pessoa. Atualize a tela.',
      codigo: 'CADASTRO_ALTERADO',
      campos: [{ campo: 'nome', mensagem: 'Confira este campo.' }],
    });
  });

  it('401 vira "sessão terminou"; falta de internet vira "sem conexão"', async () => {
    const a = montar(recusada({ status: 401 }));
    expect(await a.servico.chamarApi('avisos.listar')).toEqual({
      ok: false,
      motivo: 'sessao-terminou',
    });
    const b = montar({ ok: false, falha: { tipo: 'sem-conexao' } });
    expect(await b.servico.chamarApi('avisos.listar')).toEqual({
      ok: false,
      motivo: 'sem-conexao',
    });
  });
});

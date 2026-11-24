import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PonteDoCorona } from '../shared/ponte';
import { chamar } from './chamadas';
import { definirIdioma } from './idioma';

const GERAL_PT = 'Alguns dados não estão corretos. Revise o que preencheu e tente de novo.';
const GERAL_ES = 'Algunos datos no están correctos. Revisá lo que completaste e intentá de nuevo.';

function ponteQueRecusa(campos?: { campo: string; mensagem: string }[]) {
  return {
    chamarApi: vi.fn(() =>
      Promise.resolve({
        ok: false as const,
        motivo: 'erro' as const,
        codigo: 'DADOS_INVALIDOS',
        mensagem: GERAL_PT,
        ...(campos ? { campos } : {}),
      }),
    ),
  } as unknown as PonteDoCorona;
}

const PEDIDO = { operacao: 'envios.naoAceitos' } as const;

afterEach(() => definirIdioma('pt'));

describe('Corona Vendas: o motivo da recusa na tela (A1)', () => {
  it('em português a mensagem traz a frase geral e o motivo do servidor', async () => {
    definirIdioma('pt');
    const r = await chamar(
      ponteQueRecusa([{ campo: 'data', mensagem: 'A data não pode ser no futuro.' }]),
      PEDIDO,
    );
    expect(r).toEqual({ ok: false, mensagem: `${GERAL_PT} A data não pode ser no futuro.` });
  });

  it('em espanhol a frase geral sai em espanhol e o motivo como o servidor manda', async () => {
    definirIdioma('es');
    const r = await chamar(
      ponteQueRecusa([{ campo: 'data', mensagem: 'A data não pode ser no futuro.' }]),
      PEDIDO,
    );
    expect(r).toEqual({ ok: false, mensagem: `${GERAL_ES} A data não pode ser no futuro.` });
  });

  it('com mais de um campo mostra todos os motivos, sem repetir', async () => {
    definirIdioma('pt');
    const r = await chamar(
      ponteQueRecusa([
        { campo: 'a', mensagem: 'Confira a data.' },
        { campo: 'b', mensagem: 'O valor precisa ser maior que zero.' },
        { campo: 'c', mensagem: 'Confira a data.' },
      ]),
      PEDIDO,
    );
    expect(r).toEqual({
      ok: false,
      mensagem: `${GERAL_PT} Confira a data. O valor precisa ser maior que zero.`,
    });
  });

  it('erro sem campos continua igual', async () => {
    definirIdioma('pt');
    expect(await chamar(ponteQueRecusa(), PEDIDO)).toEqual({ ok: false, mensagem: GERAL_PT });
    expect(await chamar(ponteQueRecusa([]), PEDIDO)).toEqual({ ok: false, mensagem: GERAL_PT });
  });
});

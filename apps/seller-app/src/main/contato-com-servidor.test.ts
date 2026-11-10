import { describe, expect, it } from 'vitest';
import type { ArquivoDeTexto } from './arquivo-de-texto';
import { RegistroDeContato } from './contato-com-servidor';

function arquivoFalso(inicial: string | null = null) {
  const estado = { texto: inicial };
  const arquivo: ArquivoDeTexto = {
    ler: () => Promise.resolve(estado.texto),
    gravar: (t) => {
      estado.texto = t;
      return Promise.resolve();
    },
  };
  return { arquivo, estado };
}

describe('último contato com o servidor e prazo offline (ADR-0004, item 5)', () => {
  it('guarda a hora do servidor e o prazo, e lê de volta', async () => {
    const { arquivo } = arquivoFalso();
    const registro = new RegistroDeContato(arquivo);
    await registro.registrar({
      horaDoServidor: '2026-11-09T12:00:00Z',
      tempoMaximoOfflineDias: 10,
    });
    expect(await registro.ler()).toEqual({
      horaDoServidor: '2026-11-09T12:00:00Z',
      tempoMaximoOfflineDias: 10,
    });
  });

  it('uma resposta sem prazo mantém o prazo de antes e atualiza a hora', async () => {
    const { arquivo } = arquivoFalso();
    const registro = new RegistroDeContato(arquivo);
    await registro.registrar({ horaDoServidor: '2026-11-09T12:00:00Z', tempoMaximoOfflineDias: 7 });
    await registro.registrar({ horaDoServidor: '2026-11-10T08:00:00Z' });
    expect(await registro.ler()).toEqual({
      horaDoServidor: '2026-11-10T08:00:00Z',
      tempoMaximoOfflineDias: 7,
    });
  });

  it('resposta sem hora do servidor não grava nada (nunca usa o relógio do computador)', async () => {
    const { arquivo, estado } = arquivoFalso();
    await new RegistroDeContato(arquivo).registrar({ tempoMaximoOfflineDias: 10 });
    expect(estado.texto).toBeNull();
  });

  it('arquivo estragado vale como se não existisse', async () => {
    const { arquivo } = arquivoFalso('{ não é json');
    expect(await new RegistroDeContato(arquivo).ler()).toBeNull();
  });
});

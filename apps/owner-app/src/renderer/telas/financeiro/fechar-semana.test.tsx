// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  ID_SEMANA,
  abrir,
  chamadasDe,
  ehUuid,
  montar,
  pagina,
  ultima,
  type Manipulador,
} from '../acertos/apoio-de-teste';

const ID_FECHADA = 'f2000000-ceea-4672-a1b9-3a1a0f7c4d22';

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'semanas.listar': () =>
      pagina([
        {
          id: ID_SEMANA,
          version: 0,
          numero: 45,
          inicio: '2026-11-02',
          fim: '2026-11-08',
          situacao: 'ABERTA',
        },
        {
          id: ID_FECHADA,
          version: 1,
          numero: 44,
          inicio: '2026-10-26',
          fim: '2026-11-01',
          situacao: 'FECHADA',
        },
      ]),
    'semanas.fechar': () => ({ ok: true, dados: {}, criado: true }),
    ...extra,
  });
}

async function abrirSemanas(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, 'Semanas');
  await screen.findByRole('button', { name: 'Fechar a semana 45' });
  return u;
}

describe('Fechar semana', () => {
  it('só a semana aberta tem o botão Fechar semana', async () => {
    await abrirSemanas(servidor());
    expect(screen.getAllByRole('button', { name: /^Fechar a semana/ })).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Fechar a semana 45' })).toBeInTheDocument();
  });

  it('pergunta antes e voltar não manda nada', async () => {
    const m = servidor();
    const u = await abrirSemanas(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a semana 45' }));
    expect(
      screen.getByText(
        'Fechar a semana 45? Depois de fechada, ela não aceita mais lançamentos nem mudanças. Isso não pode ser desfeito.',
      ),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Voltar' }));
    expect(chamadasDe(m, 'semanas.fechar')).toHaveLength(0);
    expect(screen.queryByText(/Isso não pode ser desfeito/)).toBeNull();
  });

  it('confirmar manda o id da semana e o id do comando, e recarrega a lista', async () => {
    const m = servidor();
    const u = await abrirSemanas(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a semana 45' }));
    await u.click(screen.getByRole('button', { name: 'Fechar semana' }));
    await waitFor(() => expect(chamadasDe(m, 'semanas.fechar')).toHaveLength(1));
    const p = ultima(m, 'semanas.fechar');
    expect(p).toMatchObject({ id: ID_SEMANA });
    expect(ehUuid(p?.comandoId)).toBe(true);
    expect(Object.keys(p ?? {}).sort()).toEqual(['comandoId', 'id']);
    expect(await screen.findByText('Semana fechada.')).toBeInTheDocument();
    await waitFor(() => expect(chamadasDe(m, 'semanas.listar').length).toBeGreaterThan(1));
  });

  it('sem internet, tentar de novo manda o mesmo id', async () => {
    let tentativas = 0;
    const m = servidor({
      'semanas.fechar': () => {
        tentativas += 1;
        return tentativas === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: {}, criado: true };
      },
    });
    const u = await abrirSemanas(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a semana 45' }));
    await u.click(screen.getByRole('button', { name: 'Fechar semana' }));
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Fechar semana' }));
    await waitFor(() => expect(chamadasDe(m, 'semanas.fechar')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'semanas.fechar').map((c) => c.parametros?.comandoId);
    expect(b).toBe(a);
    expect(await screen.findByText('Semana fechada.')).toBeInTheDocument();
  });

  it('resposta 200 (já estava fechada) avisa com outras palavras', async () => {
    const m = servidor({ 'semanas.fechar': () => ({ ok: true, dados: {}, criado: false }) });
    const u = await abrirSemanas(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a semana 45' }));
    await u.click(screen.getByRole('button', { name: 'Fechar semana' }));
    expect(await screen.findByText('Esta semana já estava fechada.')).toBeInTheDocument();
  });

  it('mostra a recusa do servidor em português e deixa a janela aberta', async () => {
    const m = servidor({
      'semanas.fechar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'SEMANA_COM_ACERTO_EM_ANDAMENTO',
        mensagem: 'x',
      }),
    });
    const u = await abrirSemanas(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a semana 45' }));
    await u.click(screen.getByRole('button', { name: 'Fechar semana' }));
    expect(
      await screen.findByText(
        'Há acerto em andamento nesta semana. Termine ou desista do acerto antes de fechar a semana.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Fechar semana' })).toBeInTheDocument();
  });
});

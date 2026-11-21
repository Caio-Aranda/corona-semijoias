// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { EstadoDaSincronizacao, PonteDoCorona } from '../../../shared/ponte';
import { useConsulta } from './apoio';
import { useRelatorioDaTela } from './Relatorio';

const ESTADO: EstadoDaSincronizacao = {
  comInternet: true,
  enviando: false,
  aguardando: 0,
  vendasAguardando: 0,
  recusadas: 0,
  dadosAte: null,
  naoConseguiuEnviar: false,
  prazo: { situacao: 'EM_DIA', diasRestantes: 0, motivo: null },
  fotosBaixadas: 0,
  fotosTotal: 0,
  appDesatualizado: false,
};

function Tela({ ponte }: { ponte: PonteDoCorona }) {
  const { dados } = useConsulta<{ nome: string }>(ponte, { operacao: 'consignado.maletas' }, []);
  return <p>{dados ? dados.nome : 'carregando'}</p>;
}

describe('useConsulta depois do pull', () => {
  it('recarrega quando dadosAte muda, sem apagar o que já estava na tela e sem repetir à toa', async () => {
    let nome = 'Cliente antigo';
    let ouvinte: ((e: EstadoDaSincronizacao) => void) | null = null;
    const chamarApi = vi.fn(() => Promise.resolve({ ok: true as const, dados: { nome } }));
    const ponte = {
      chamarApi,
      aoMudarASincronizacao: (o: (e: EstadoDaSincronizacao) => void) => {
        ouvinte = o;
        return () => undefined;
      },
    } as unknown as PonteDoCorona;
    render(<Tela ponte={ponte} />);
    await screen.findByText('Cliente antigo');
    // Primeira entrega do estado, ainda sem pull: não recarrega.
    act(() => ouvinte?.({ ...ESTADO, dadosAte: null }));
    expect(chamarApi).toHaveBeenCalledTimes(1);
    nome = 'Cliente do pull';
    act(() => ouvinte?.({ ...ESTADO, dadosAte: '2026-11-10T12:00:00Z' }));
    // Mantém o dado antigo até o novo chegar (não pisca) e depois mostra o novo.
    expect(screen.queryByText('carregando')).toBeNull();
    await screen.findByText('Cliente do pull');
    expect(chamarApi).toHaveBeenCalledTimes(2);
    // O mesmo `dadosAte` de novo (outro aviso do envio) não recarrega.
    act(() => ouvinte?.({ ...ESTADO, dadosAte: '2026-11-10T12:00:00Z', aguardando: 3 }));
    expect(chamarApi).toHaveBeenCalledTimes(2);
  });

  it('estado inicial que já chega com dadosAte preenchido não duplica a chamada', async () => {
    const chamarApi = vi.fn(() => Promise.resolve({ ok: true as const, dados: { nome: 'A' } }));
    const ponte = {
      chamarApi,
      aoMudarASincronizacao: (o: (e: EstadoDaSincronizacao) => void) => {
        setTimeout(() => o({ ...ESTADO, dadosAte: '2026-11-10T12:00:00Z' }), 0);
        return () => undefined;
      },
    } as unknown as PonteDoCorona;
    render(<Tela ponte={ponte} />);
    await screen.findByText('A');
    await act(() => new Promise((r) => setTimeout(r, 30)));
    expect(chamarApi).toHaveBeenCalledTimes(1);
  });
});

function TelaRelatorio({ ponte }: { ponte: PonteDoCorona }) {
  const r = useRelatorioDaTela(ponte, { tipo: 'TOTAIS_DA_SEMANA' } as never);
  return <p>{r ? (r as unknown as { nome: string }).nome : 'carregando'}</p>;
}

describe('useRelatorioDaTela depois do pull', () => {
  function montar(inicial: boolean) {
    let nome = 'Relatório antigo';
    let ouvinte: ((e: EstadoDaSincronizacao) => void) | null = null;
    const chamarApi = vi.fn(() =>
      Promise.resolve({ ok: true as const, dados: { tipo: 'TOTAIS_DA_SEMANA', nome } }),
    );
    const ponte = {
      chamarApi,
      aoMudarASincronizacao: (o: (e: EstadoDaSincronizacao) => void) => {
        ouvinte = o;
        if (inicial) setTimeout(() => o({ ...ESTADO, dadosAte: '2026-11-10T12:00:00Z' }), 0);
        return () => undefined;
      },
    } as unknown as PonteDoCorona;
    return {
      ponte,
      chamarApi,
      mudar: (n: string, dadosAte: string | null) => {
        nome = n;
        act(() => ouvinte?.({ ...ESTADO, dadosAte }));
      },
    };
  }

  it('recarrega quando dadosAte muda', async () => {
    const t = montar(false);
    render(<TelaRelatorio ponte={t.ponte} />);
    await screen.findByText('Relatório antigo');
    t.mudar('Relatório antigo', null);
    t.mudar('Relatório novo', '2026-11-10T12:00:00Z');
    await screen.findByText('Relatório novo');
    expect(t.chamarApi).toHaveBeenCalledTimes(2);
  });

  it('estado inicial já com dadosAte não duplica a chamada', async () => {
    const t = montar(true);
    render(<TelaRelatorio ponte={t.ponte} />);
    await screen.findByText('Relatório antigo');
    await act(() => new Promise((r) => setTimeout(r, 30)));
    expect(t.chamarApi).toHaveBeenCalledTimes(1);
  });
});

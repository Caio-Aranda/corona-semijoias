// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { EstadoDaSincronizacao, PonteDoCorona } from '../../../shared/ponte';
import { useConsulta } from './apoio';

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
});

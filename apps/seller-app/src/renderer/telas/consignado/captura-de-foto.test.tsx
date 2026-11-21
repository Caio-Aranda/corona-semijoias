// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PonteDoCorona } from '../../../shared/ponte';
import { definirIdioma } from '../../idioma';
import { TEXTOS } from '../../textos';
import { CapturaDeFoto } from './CapturaDeFoto';

const T = new Proxy({} as typeof TEXTOS.consignado.cadastro, {
  get: (_a, chave: string) =>
    (TEXTOS.consignado.cadastro as Record<string, unknown>)[chave],
});

function criarFluxo() {
  const parar = vi.fn();
  return { fluxo: { getTracks: () => [{ stop: parar }] } as unknown as MediaStream, parar };
}

function criarPonte() {
  const chamarApi = vi.fn((pedido: { operacao: string }) =>
    Promise.resolve(
      pedido.operacao === 'consignado.fotoGuardar'
        ? { ok: true as const, dados: { hash: 'abc123' } }
        : { ok: false as const, motivo: 'erro' as const },
    ),
  );
  return { ponte: { chamarApi } as unknown as PonteDoCorona, chamarApi };
}

function usarCamera(getUserMedia: () => Promise<MediaStream>) {
  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
}

beforeEach(() => {
  definirIdioma('pt');
  // O jsdom não toca vídeo nem desenha: os dois pontos que a tela usa ficam simulados.
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
    drawImage: vi.fn(),
  } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/jpeg;base64,QQ==');
});
afterEach(() => vi.restoreAllMocks());

describe('CapturaDeFoto: câmera (defeito 3 do relatório 9.10)', () => {
  it('liga o fluxo da câmera no vídeo assim que ele aparece na tela', async () => {
    const { fluxo } = criarFluxo();
    usarCamera(() => Promise.resolve(fluxo));
    const { ponte } = criarPonte();
    render(<CapturaDeFoto ponte={ponte} rotulo="Foto do cliente" hash={null} aoGuardar={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: T.tirarFoto }));
    const video = await screen.findByLabelText('Foto do cliente', { selector: 'video' });
    await waitFor(() => expect((video as HTMLVideoElement).srcObject).toBe(fluxo));
  });

  it('funciona mesmo quando o cronômetro vence a tela (a corrida do relatório: setTimeout antes do vídeo existir)', async () => {
    const { fluxo } = criarFluxo();
    usarCamera(() => Promise.resolve(fluxo));
    const { ponte } = criarPonte();
    // Pior ordem possível: qualquer setTimeout dispara na hora, antes de o React desenhar o <video>.
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((fn: () => void) => {
      fn();
      return 0;
    }) as unknown as typeof setTimeout);
    render(<CapturaDeFoto ponte={ponte} rotulo="Foto do cliente" hash={null} aoGuardar={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: T.tirarFoto }));
    const video = await screen.findByLabelText('Foto do cliente', { selector: 'video' });
    await waitFor(() => expect((video as HTMLVideoElement).srcObject).toBe(fluxo));
  });

  it('"Usar esta foto" captura o quadro, guarda e segue o fluxo', async () => {
    const { fluxo, parar } = criarFluxo();
    usarCamera(() => Promise.resolve(fluxo));
    const { ponte, chamarApi } = criarPonte();
    const aoGuardar = vi.fn();
    render(<CapturaDeFoto ponte={ponte} rotulo="Foto do cliente" hash={null} aoGuardar={aoGuardar} />);
    await userEvent.click(screen.getByRole('button', { name: T.tirarFoto }));
    const video = (await screen.findByLabelText('Foto do cliente', {
      selector: 'video',
    })) as HTMLVideoElement;
    Object.defineProperty(video, 'videoWidth', { value: 640 });
    Object.defineProperty(video, 'videoHeight', { value: 480 });
    await userEvent.click(screen.getByRole('button', { name: T.capturar }));
    await waitFor(() => expect(aoGuardar).toHaveBeenCalledWith('abc123'));
    expect(chamarApi).toHaveBeenCalledWith({
      operacao: 'consignado.fotoGuardar',
      dataUrl: 'data:image/jpeg;base64,QQ==',
    });
    expect(parar).toHaveBeenCalled();
  });

  it('se a imagem ainda não chegou, "Usar esta foto" explica em vez de não fazer nada', async () => {
    const { fluxo } = criarFluxo();
    usarCamera(() => Promise.resolve(fluxo));
    const { ponte, chamarApi } = criarPonte();
    render(<CapturaDeFoto ponte={ponte} rotulo="Foto do cliente" hash={null} aoGuardar={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: T.tirarFoto }));
    await userEvent.click(await screen.findByRole('button', { name: T.capturar }));
    expect(await screen.findByText(T.cameraAbrindo)).toBeInTheDocument();
    expect(chamarApi).not.toHaveBeenCalled();
  });

  it.each([
    ['NotAllowedError', 'cameraNegada'],
    ['NotFoundError', 'cameraAusente'],
    ['AbortError', 'semCamera'],
  ] as const)('erro %s mostra a mensagem certa e a venda segue (arquivo continua)', async (nome, chave) => {
    const texto = T[chave];
    usarCamera(() => Promise.reject(new DOMException('x', nome)));
    const { ponte } = criarPonte();
    render(<CapturaDeFoto ponte={ponte} rotulo="Foto do cliente" hash={null} aoGuardar={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: T.tirarFoto }));
    expect(await screen.findByText(texto)).toBeInTheDocument();
    expect(screen.getByText(T.escolherArquivo)).toBeInTheDocument();
  });
});

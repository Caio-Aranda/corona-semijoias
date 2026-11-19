import { Aviso, Botao } from '@corona/ui';
import { useEffect, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar } from '../../chamadas';
import { TEXTOS } from '../../textos';
import { FotoDoCliente } from './apoio';

/** O lado maior da foto guardada: o suficiente para ler um documento, sem encher o disco. */
const LADO_MAXIMO = 1600;

/** Desenha a imagem num quadro reduzido e devolve um JPEG em endereço de dados. */
function paraJpeg(fonte: CanvasImageSource, largura: number, altura: number): string | null {
  const escala = Math.min(1, LADO_MAXIMO / Math.max(largura, altura));
  const quadro = document.createElement('canvas');
  quadro.width = Math.max(1, Math.round(largura * escala));
  quadro.height = Math.max(1, Math.round(altura * escala));
  const contexto = quadro.getContext('2d');
  if (!contexto) return null;
  contexto.drawImage(fonte, 0, 0, quadro.width, quadro.height);
  return quadro.toDataURL('image/jpeg', 0.85);
}

/**
 * Uma das três fotos do cadastro (cliente, frente e verso do documento): câmera na hora ou arquivo (§23.5). A foto é
 * guardada criptografada neste computador e sobe quando houver internet; sem internet, a pessoa segue trabalhando.
 */
export function CapturaDeFoto({
  ponte,
  rotulo,
  hash,
  aoGuardar,
}: {
  ponte: PonteDoCorona;
  rotulo: string;
  hash: string | null;
  /** Avisa o hash da foto já guardada pelo computador. */
  aoGuardar: (hash: string) => void | Promise<void>;
}) {
  const T = TEXTOS.consignado.cadastro;
  const [camera, setCamera] = useState(false);
  const [erro, setErro] = useState<string | null>(null);
  const [recemTirada, setRecemTirada] = useState<string | null>(null);
  const video = useRef<HTMLVideoElement>(null);
  const fluxo = useRef<MediaStream | null>(null);

  useEffect(() => () => fluxo.current?.getTracks().forEach((t) => t.stop()), []);

  function pararCamera(): void {
    fluxo.current?.getTracks().forEach((t) => t.stop());
    fluxo.current = null;
    setCamera(false);
  }

  async function abrirCamera(): Promise<void> {
    setErro(null);
    try {
      const f = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
      fluxo.current = f;
      setCamera(true);
      // O quadro de vídeo só existe depois de desenhar a tela com a câmera aberta.
      setTimeout(() => {
        if (video.current) {
          video.current.srcObject = f;
          void video.current.play().catch(() => undefined);
        }
      }, 0);
    } catch {
      setErro(T.semCamera);
    }
  }

  async function guardar(endereco: string | null): Promise<void> {
    if (!endereco) {
      setErro(TEXTOS.consignado.cadastro.semCamera);
      return;
    }
    const r = await chamar<{ hash: string }>(ponte, { operacao: 'consignado.fotoGuardar', dataUrl: endereco });
    if (!r.ok) {
      setErro(r.mensagem);
      return;
    }
    setErro(null);
    setRecemTirada(endereco);
    await aoGuardar(r.dados.hash);
  }

  async function capturar(): Promise<void> {
    const v = video.current;
    if (!v || v.videoWidth === 0) return;
    const endereco = paraJpeg(v, v.videoWidth, v.videoHeight);
    pararCamera();
    await guardar(endereco);
  }

  async function doArquivo(arquivo: File | undefined): Promise<void> {
    if (!arquivo) return;
    try {
      const imagem = await createImageBitmap(arquivo);
      const endereco = paraJpeg(imagem, imagem.width, imagem.height);
      imagem.close();
      await guardar(endereco);
    } catch {
      setErro(TEXTOS.consignado.cadastro.semCamera);
    }
  }

  return (
    <div className="consignado-captura" role="group" aria-label={rotulo}>
      <p className="vendas-texto">
        <strong>{rotulo}</strong>
      </p>
      {camera ? (
        <div className="consignado-camera">
          <video ref={video} className="consignado-camera__video" muted playsInline aria-label={rotulo} />
          <div className="consignado-camera__acoes">
            <Botao variante="primario" onClick={() => void capturar()}>
              {T.capturar}
            </Botao>
            <Botao onClick={pararCamera}>{T.cancelarCamera}</Botao>
          </div>
        </div>
      ) : (
        <>
          {recemTirada ? (
            <img className="consignado-foto" src={recemTirada} alt={rotulo} />
          ) : (
            <FotoDoCliente ponte={ponte} hash={hash} rotulo={rotulo} />
          )}
          <div className="consignado-camera__acoes">
            <Botao onClick={() => void abrirCamera()}>{T.tirarFoto}</Botao>
            <label className="cs-botao cs-botao--secundario consignado-arquivo">
              {T.escolherArquivo}
              <input
                type="file"
                accept="image/*"
                onChange={(e) => {
                  void doArquivo(e.target.files?.[0]);
                  e.target.value = '';
                }}
              />
            </label>
          </div>
        </>
      )}
      {hash && !camera ? <p className="vendas-texto vendas-texto--pequeno">{T.fotoPronta}</p> : null}
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
    </div>
  );
}

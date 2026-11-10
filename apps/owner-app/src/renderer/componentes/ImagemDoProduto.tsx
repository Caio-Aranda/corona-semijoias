import { useEffect, useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import type { TamanhoDeFoto } from '../../shared/produtos';
import { imagemPronta, usarImagem } from '../nucleo/imagens';
import { TEXTOS } from '../textos';

const T = TEXTOS.produtos.foto;

/** Só para os testes. */
export { esquecerImagens } from '../nucleo/imagens';

export interface ImagemDoProdutoProps {
  ponte: PonteDoCorona;
  /** Sem hash o produto não tem foto: aparece o marcador neutro. */
  hash: string | null | undefined;
  tamanho: TamanhoDeFoto;
  nome: string;
  className?: string;
}

/**
 * Foto do produto vinda da API. Falta de foto, foto que não carregou ou internet fora mostram o
 * marcador "Sem foto" e nunca travam nada.
 */
export function ImagemDoProduto({ ponte, hash, tamanho, nome, className }: ImagemDoProdutoProps) {
  const [url, setUrl] = useState<string | null>(null);
  const [carregando, setCarregando] = useState(Boolean(hash));
  // Enquanto a média ou a grande carrega, a miniatura que já chegou aparece na hora.
  const [mini, setMini] = useState<string | null>(null);
  const provisoria = !url
    ? (mini ?? (hash && tamanho !== 'miniatura' ? imagemPronta(hash, 'miniatura') : null))
    : null;

  useEffect(() => {
    let valida = true;
    setUrl(null);
    setMini(null);
    if (!hash) {
      setCarregando(false);
      return;
    }
    setCarregando(true);
    const uso = usarImagem(ponte, hash, tamanho);
    // Segura a miniatura provisória enquanto ela estiver na tela, para o endereço não ser revogado.
    // A miniatura é pequena e quase sempre já está no disco: chega antes e aparece enquanto a maior carrega.
    const miniatura = tamanho !== 'miniatura' ? usarImagem(ponte, hash, 'miniatura') : null;
    void miniatura?.promessa.then((u) => {
      if (valida) setMini(u);
    });
    void uso.promessa.then((u) => {
      if (!valida) return;
      setUrl(u);
      setCarregando(false);
    });
    return () => {
      valida = false;
      uso.liberar();
      miniatura?.liberar();
    };
  }, [ponte, hash, tamanho]);

  const classes = `gestao-foto gestao-foto--${tamanho}${className ? ` ${className}` : ''}`;
  if (url) return <img className={classes} src={url} alt={T.alt(nome)} />;
  if (provisoria) {
    return (
      <img className={`${classes} gestao-foto--provisoria`} src={provisoria} alt={T.alt(nome)} />
    );
  }
  return (
    <span
      className={`${classes} gestao-foto--vazia`}
      role="img"
      aria-label={carregando ? T.carregando : T.semFoto}
    >
      {carregando ? '' : T.semFoto}
    </span>
  );
}

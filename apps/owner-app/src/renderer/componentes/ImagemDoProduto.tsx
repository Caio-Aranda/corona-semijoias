import { useEffect, useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import type { TamanhoDeFoto } from '../../shared/produtos';
import { TEXTOS } from '../textos';

const T = TEXTOS.produtos.foto;

/** Fotos já buscadas: a mesma foto (mesmo hash) só é baixada uma vez por tamanho. */
const guardadas = new Map<string, Promise<string | null>>();

function buscarImagem(
  ponte: PonteDoCorona,
  hash: string,
  tamanho: TamanhoDeFoto,
): Promise<string | null> {
  const chave = `${hash}/${tamanho}`;
  let promessa = guardadas.get(chave);
  if (!promessa) {
    promessa = ponte
      .chamarApi({ operacao: 'produtos.fotos.imagem', parametros: { hash, tamanho } })
      .then((r) => {
        if (!r.ok) return null;
        const f = r.dados as { tipo?: string; bytes?: Uint8Array };
        if (!(f.bytes instanceof Uint8Array)) return null;
        return URL.createObjectURL(
          new Blob([f.bytes as BlobPart], { type: f.tipo ?? 'image/jpeg' }),
        );
      })
      .catch(() => null);
    guardadas.set(chave, promessa);
    // Falha não fica guardada: da próxima vez tenta de novo.
    void promessa.then((url) => {
      if (url === null) guardadas.delete(chave);
    });
  }
  return promessa;
}

/** Só para os testes. */
export function esquecerImagens(): void {
  guardadas.clear();
}

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

  useEffect(() => {
    let valida = true;
    setUrl(null);
    if (!hash) {
      setCarregando(false);
      return;
    }
    setCarregando(true);
    void buscarImagem(ponte, hash, tamanho).then((u) => {
      if (!valida) return;
      setUrl(u);
      setCarregando(false);
    });
    return () => {
      valida = false;
    };
  }, [ponte, hash, tamanho]);

  const classes = `gestao-foto gestao-foto--${tamanho}${className ? ` ${className}` : ''}`;
  if (url) return <img className={classes} src={url} alt={T.alt(nome)} />;
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

import { Aviso, Botao } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { imprimirNotaDaMaleta } from '../../nucleo/nota-da-maleta';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';

const T = TEXTOS_DE_MALETAS;

/** "Imprimir nota": busca a nota da maleta enviada, monta o PDF e deixa a pessoa escolher onde guardar. */
export function BotaoImprimirNota({
  ponte,
  maletaId,
  vendedorNome,
}: {
  ponte: PonteDoCorona;
  maletaId: string;
  vendedorNome: string;
}) {
  const [fazendo, setFazendo] = useState(false);
  const [resultado, setResultado] = useState<{ tipo: 'sucesso' | 'erro'; texto: string } | null>(
    null,
  );
  async function imprimir() {
    if (fazendo) return;
    setFazendo(true);
    setResultado(null);
    const r = await imprimirNotaDaMaleta(ponte, maletaId);
    setResultado(
      r.ok
        ? { tipo: 'sucesso', texto: T.nota.pronta(r.arquivo) }
        : { tipo: 'erro', texto: r.mensagem },
    );
    setFazendo(false);
  }
  return (
    <>
      <Botao
        disabled={fazendo}
        aria-label={`${T.enviada.imprimir}: ${vendedorNome}`}
        onClick={() => void imprimir()}
      >
        {fazendo ? T.nota.gerando : T.enviada.imprimir}
      </Botao>
      {resultado ? <Aviso tipo={resultado.tipo}>{resultado.texto}</Aviso> : null}
    </>
  );
}

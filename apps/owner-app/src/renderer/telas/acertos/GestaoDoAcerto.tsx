import { Aviso, Botao } from '@corona/ui';
import { useEffect, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { dataEHora } from '../sincronizacao/dados';
import { ContagemDoAcerto } from './ContagemDoAcerto';
import { DesistirDoAcerto } from './DesistirDoAcerto';
import { PreviaDoAcerto } from './PreviaDoAcerto';
import { lerFicha, type FichaDoAcerto } from './dados';

const T = TEXTOS_DE_ACERTOS;

export interface GestaoDoAcertoProps {
  ponte: PonteDoCorona;
  id: string;
  /** Uma ficha que já está na mão (acabou de começar): evita buscar de novo. */
  inicial?: FichaDoAcerto | null;
  aoVoltar: () => void;
}

/** A ficha do acerto: em andamento mostra a contagem; desistido mostra o motivo. */
export function GestaoDoAcerto({ ponte, id, inicial, aoVoltar }: GestaoDoAcertoProps) {
  const busca = useBusca(() => chamar(ponte, 'acertos.ver', { id }), `acerto-${id}`);
  const [ficha, setFicha] = useState<FichaDoAcerto | null>(inicial ?? null);
  const [desistindo, setDesistindo] = useState(false);
  const [conferindo, setConferindo] = useState(false);

  useEffect(() => {
    const lida = busca.dados ? lerFicha(busca.dados) : null;
    if (lida) setFicha(lida);
  }, [busca.dados]);

  return (
    <div>
      <div className="gestao-acoes-da-linha">
        <Botao onClick={aoVoltar}>{T.ficha.voltar}</Botao>
      </div>
      {busca.erro && !ficha ? (
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
      ) : null}
      {busca.dados && !ficha ? <Aviso tipo="erro">{T.ficha.naoAbriu}</Aviso> : null}
      {ficha ? (
        <>
          <h2 className="gestao-subtitulo">
            {T.ficha.titulo(ficha.vendedorNome, ficha.semanaNumero)}
          </h2>
          <p className="gestao-texto">{T.ficha.comecouEm(dataEHora(ficha.iniciadoEm))}</p>
          {ficha.pedidoPeloVendedor ? (
            <p className="gestao-texto">{T.ficha.pedidoPeloVendedor}</p>
          ) : null}
          {ficha.maletas.length > 0 ? (
            <p className="gestao-texto">
              {T.ficha.maletas}: {ficha.maletas.map((m) => T.ficha.maleta(m.numero)).join(', ')}
            </p>
          ) : null}
          {ficha.status === 'EM_ACERTO' && conferindo ? (
            <PreviaDoAcerto ponte={ponte} acerto={ficha} aoVoltar={() => setConferindo(false)} />
          ) : null}
          {ficha.status === 'EM_ACERTO' && !conferindo ? (
            <>
              <ContagemDoAcerto
                ponte={ponte}
                ficha={ficha}
                aoMudar={setFicha}
                aoPerder={busca.recarregar}
              />
              <div className="gestao-acoes-da-linha">
                <Botao variante="primario" onClick={() => setConferindo(true)}>
                  {T.contagem.conferir}
                </Botao>
                <Botao onClick={() => setDesistindo(true)}>{T.contagem.desistir}</Botao>
              </div>
            </>
          ) : null}
          {ficha.status === 'DESISTIDO' ? (
            <Aviso tipo="informacao">
              {ficha.motivoDesistencia
                ? T.ficha.desistido(ficha.motivoDesistencia)
                : T.ficha.desistidoSemMotivo}
            </Aviso>
          ) : null}
          {desistindo ? (
            <DesistirDoAcerto
              ponte={ponte}
              acertoId={ficha.id}
              aoFeito={aoVoltar}
              aoCancelar={() => setDesistindo(false)}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}

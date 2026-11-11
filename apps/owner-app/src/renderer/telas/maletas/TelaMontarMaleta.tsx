import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { FichaDaMaleta } from './FichaDaMaleta';
import { ListaDeRascunhos } from './ListaDeRascunhos';
import { MontagemDaMaleta } from './MontagemDaMaleta';
import { NovaMaleta } from './NovaMaleta';
import type { Maleta } from './dados';

type Passo =
  | { passo: 'inicio' }
  | { passo: 'montando'; maleta: Maleta }
  | { passo: 'enviada'; maleta: Maleta; jaEstava: boolean };

/** Maletas → Montar maleta: começar uma nova ou continuar um rascunho, montar pelo leitor e enviar. */
export function TelaMontarMaleta({ ponte }: { ponte: PonteDoCorona }) {
  const [passo, setPasso] = useState<Passo>({ passo: 'inicio' });
  // Muda a cada volta ao início: a lista de rascunhos é buscada de novo.
  const [volta, setVolta] = useState(0);
  const voltar = () => {
    setVolta((v) => v + 1);
    setPasso({ passo: 'inicio' });
  };
  const abrir = (maleta: Maleta) =>
    setPasso(
      maleta.status === 'RASCUNHO'
        ? { passo: 'montando', maleta }
        : { passo: 'enviada', maleta, jaEstava: false },
    );

  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{TEXTOS_DE_MALETAS.titulo}</h1>
      </div>
      {passo.passo === 'inicio' ? (
        <>
          <NovaMaleta ponte={ponte} aoCriar={abrir} />
          <ListaDeRascunhos ponte={ponte} aoRetomar={abrir} chave={String(volta)} />
        </>
      ) : null}
      {passo.passo === 'montando' ? (
        <MontagemDaMaleta
          key={passo.maleta.id}
          ponte={ponte}
          inicial={passo.maleta}
          aoVoltar={voltar}
          aoEnviar={(maleta, jaEstava) => setPasso({ passo: 'enviada', maleta, jaEstava })}
        />
      ) : null}
      {passo.passo === 'enviada' ? (
        <FichaDaMaleta
          ponte={ponte}
          maleta={passo.maleta}
          recemEnviada
          jaEstavaEnviada={passo.jaEstava}
          aoMontarOutra={voltar}
        />
      ) : null}
    </div>
  );
}

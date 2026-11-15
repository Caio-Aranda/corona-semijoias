import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { ComecarAcerto } from './ComecarAcerto';
import { GestaoDoAcerto } from './GestaoDoAcerto';
import { ListaDeAcertos } from './ListaDeAcertos';
import type { FichaDoAcerto } from './dados';

type Passo =
  | { passo: 'lista' }
  | { passo: 'comecar' }
  | { passo: 'ficha'; id: string; inicial: FichaDoAcerto | null };

/** Acertos → Acertos: lista, começar e a ficha de cada acerto. */
export function TelaAcertos({ ponte }: { ponte: PonteDoCorona }) {
  const [passo, setPasso] = useState<Passo>({ passo: 'lista' });
  const [volta, setVolta] = useState(0);
  const voltarParaLista = () => {
    setVolta((v) => v + 1);
    setPasso({ passo: 'lista' });
  };
  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{TEXTOS_DE_ACERTOS.titulo}</h1>
      </div>
      {passo.passo === 'lista' ? (
        <ListaDeAcertos
          ponte={ponte}
          chave={String(volta)}
          aoAbrir={(id) => setPasso({ passo: 'ficha', id, inicial: null })}
          aoComecar={() => setPasso({ passo: 'comecar' })}
        />
      ) : null}
      {passo.passo === 'comecar' ? (
        <ComecarAcerto
          ponte={ponte}
          aoComecar={(ficha) => setPasso({ passo: 'ficha', id: ficha.id, inicial: ficha })}
          aoVoltar={voltarParaLista}
        />
      ) : null}
      {passo.passo === 'ficha' ? (
        <GestaoDoAcerto
          key={passo.id}
          ponte={ponte}
          id={passo.id}
          inicial={passo.inicial}
          aoVoltar={voltarParaLista}
        />
      ) : null}
    </div>
  );
}

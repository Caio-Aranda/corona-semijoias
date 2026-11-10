import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { TEXTOS_DE_COMPRAS } from '../../textos-compras';
import { ImportacaoDeCompra } from './ImportacaoDeCompra';
import { ReceberPelaLeitura } from './ReceberPelaLeitura';

const T = TEXTOS_DE_COMPRAS.receber;

export interface TelaReceberCompraProps {
  ponte: PonteDoCorona;
  aoVerLista: () => void;
}

type Aba = 'leitor' | 'planilha';

/** Estoque → Receber compra: pela leitura das peças ou por planilha. */
export function TelaReceberCompra({ ponte, aoVerLista }: TelaReceberCompraProps) {
  const [aba, setAba] = useState<Aba>('leitor');
  const abas: { chave: Aba; rotulo: string }[] = [
    { chave: 'leitor', rotulo: T.abas.leitor },
    { chave: 'planilha', rotulo: T.abas.planilha },
  ];
  return (
    <div className="gestao-tela">
      <div className="gestao-tela__topo">
        <h1 className="gestao-titulo">{T.titulo}</h1>
      </div>
      <div className="gestao-abas" role="tablist" aria-label={T.abas.rotulo}>
        {abas.map((a) => (
          <button
            key={a.chave}
            type="button"
            role="tab"
            aria-selected={aba === a.chave}
            className={`gestao-aba-botao${aba === a.chave ? ' gestao-aba-botao--atual' : ''}`}
            onClick={() => setAba(a.chave)}
          >
            {a.rotulo}
          </button>
        ))}
      </div>
      {/* As duas ficam montadas: trocar de aba não apaga o que já foi lido. */}
      <div hidden={aba !== 'leitor'}>
        <ReceberPelaLeitura ponte={ponte} aoVerLista={aoVerLista} />
      </div>
      <div hidden={aba !== 'planilha'}>
        <ImportacaoDeCompra ponte={ponte} />
      </div>
    </div>
  );
}

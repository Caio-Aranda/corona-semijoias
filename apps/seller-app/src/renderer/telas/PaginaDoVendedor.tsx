import { Botao, CabecalhoDeMarca } from '@corona/ui';
import type { ReactNode } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { IndicadorDeEnvio } from '../IndicadorDeEnvio';
import { SeletorDeIdioma } from '../SeletorDeIdioma';
import { TEXTOS } from '../textos';

export interface PaginaDoVendedorProps {
  ponte: PonteDoCorona;
  titulo: string;
  aoVoltar: () => void;
  children: ReactNode;
}

/** Moldura das telas de trabalho: marca no alto, título, botão Voltar e o conteúdo. */
export function PaginaDoVendedor({ ponte, titulo, aoVoltar, children }: PaginaDoVendedorProps) {
  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Vendas"
        acoes={
          <>
            <SeletorDeIdioma />
            <Botao onClick={aoVoltar}>{TEXTOS.voltar}</Botao>
          </>
        }
      />
      <main className="vendas-conteudo">
        <h1 className="vendas-titulo">{titulo}</h1>
        <IndicadorDeEnvio ponte={ponte} />
        {children}
      </main>
    </div>
  );
}

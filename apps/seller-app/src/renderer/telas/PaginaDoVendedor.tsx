import { Botao, CabecalhoDeMarca } from '@corona/ui';
import type { ReactNode } from 'react';
import { TEXTOS } from '../textos';

export interface PaginaDoVendedorProps {
  titulo: string;
  aoVoltar: () => void;
  children: ReactNode;
}

/** Moldura das telas de trabalho: marca no alto, título, botão Voltar e o conteúdo. */
export function PaginaDoVendedor({ titulo, aoVoltar, children }: PaginaDoVendedorProps) {
  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Vendas"
        acoes={<Botao onClick={aoVoltar}>{TEXTOS.voltar}</Botao>}
      />
      <main className="vendas-conteudo">
        <h1 className="vendas-titulo">{titulo}</h1>
        {children}
      </main>
    </div>
  );
}

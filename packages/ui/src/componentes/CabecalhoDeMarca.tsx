import type { ReactNode } from 'react';

export interface CabecalhoDeMarcaProps {
  /** Nome do app, ex.: "Corona Vendas". Opcional. */
  nomeDoApp?: string;
  /** Área à direita (nome da pessoa, botão Sair...). */
  acoes?: ReactNode;
}

/** Cabeçalho: símbolo da loja, o nome em letra de marca e o nome do app (§18). */
export function CabecalhoDeMarca({ nomeDoApp, acoes }: CabecalhoDeMarcaProps) {
  return (
    <header className="cs-cabecalho">
      <span className="cs-cabecalho__simbolo" aria-hidden="true" />
      <p className="cs-cabecalho__marca">Corona Semijoias</p>
      {nomeDoApp ? <span className="cs-cabecalho__app">{nomeDoApp}</span> : null}
      {acoes ? <div style={{ marginLeft: 'auto' }}>{acoes}</div> : null}
    </header>
  );
}

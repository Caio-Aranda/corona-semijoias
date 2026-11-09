import type { ReactNode } from 'react';

export interface CabecalhoDeMarcaProps {
  /** Nome do app, ex.: "Corona Vendas". Opcional. */
  nomeDoApp?: string;
  /** Área à direita (nome da pessoa, botão Sair...). */
  acoes?: ReactNode;
}

/** Cabeçalho com o nome da loja em texto, no lugar do logotipo (a imagem corrigida ainda não existe, §18). */
export function CabecalhoDeMarca({ nomeDoApp, acoes }: CabecalhoDeMarcaProps) {
  return (
    <header className="cs-cabecalho">
      <p className="cs-cabecalho__marca">Corona Semijoias</p>
      {nomeDoApp ? <span className="cs-cabecalho__app">{nomeDoApp}</span> : null}
      {acoes ? <div style={{ marginLeft: 'auto' }}>{acoes}</div> : null}
    </header>
  );
}

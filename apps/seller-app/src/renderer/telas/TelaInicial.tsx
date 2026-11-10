import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { useState } from 'react';

const BOTOES = ['Vender', 'Minha maleta', 'Meus clientes', 'Meus números'] as const;

export interface TelaInicialProps {
  /** Nome da pessoa, quando já se sabe. */
  nome: string | null;
  aoSair: () => void | Promise<void>;
}

/** Tela inicial do vendedor (§19.7): quatro botões grandes. Ainda sem função: mostram "Em breve". */
export function TelaInicial({ nome, aoSair }: TelaInicialProps) {
  const [emBreve, setEmBreve] = useState<string | null>(null);

  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Vendas"
        acoes={
          <>
            {nome ? <span>{nome} </span> : null}
            <Botao onClick={() => void aoSair()}>Sair</Botao>
          </>
        }
      />
      <main className="vendas-conteudo">
        <h1 className="vendas-titulo">{nome ? `Olá, ${nome}.` : 'Olá.'}</h1>
        {emBreve ? (
          <Aviso tipo="informacao" titulo="Em breve.">
            {`${emBreve} ainda está sendo preparado. Logo você poderá usar por aqui.`}
          </Aviso>
        ) : null}
        <nav className="vendas-botoes" aria-label="O que você quer fazer?">
          {BOTOES.map((nomeDoBotao) => (
            <Botao
              key={nomeDoBotao}
              variante="primario"
              grande
              onClick={() => setEmBreve(nomeDoBotao)}
            >
              {nomeDoBotao}
            </Botao>
          ))}
        </nav>
      </main>
    </div>
  );
}

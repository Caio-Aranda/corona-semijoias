import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { useState } from 'react';

const BOTOES = [
  'Montar maleta',
  'Receber compra',
  'Fazer acerto',
  'Cadastrar produto',
  'Relatórios',
] as const;

export interface TelaInicialProps {
  /** Nome da pessoa, quando já se sabe. */
  nome: string | null;
  aoSair: () => void | Promise<void>;
}

/** Tela inicial da dona (§19.3): botões grandes e o bloco "O que precisa da sua atenção". */
export function TelaInicial({ nome, aoSair }: TelaInicialProps) {
  const [emBreve, setEmBreve] = useState<string | null>(null);

  return (
    <div className="gestao-pagina">
      <CabecalhoDeMarca
        nomeDoApp="Corona Gestão"
        acoes={
          <>
            {nome ? <span>{nome} </span> : null}
            <Botao onClick={() => void aoSair()}>Sair</Botao>
          </>
        }
      />
      <main className="gestao-conteudo">
        <h1 className="gestao-titulo">{nome ? `Olá, ${nome}.` : 'Olá.'}</h1>
        {emBreve ? (
          <Aviso tipo="informacao" titulo="Em breve.">
            {`${emBreve} ainda está sendo preparado. Logo você poderá usar por aqui.`}
          </Aviso>
        ) : null}
        <nav className="gestao-botoes" aria-label="O que você quer fazer?">
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
        <section className="gestao-atencao" aria-labelledby="titulo-atencao">
          <h2 id="titulo-atencao">O que precisa da sua atenção</h2>
          <p>Tudo em dia por aqui. Quando algo precisar de você, aparece neste espaço.</p>
        </section>
      </main>
    </div>
  );
}

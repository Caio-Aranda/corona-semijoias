import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { IndicadorDeEnvio } from '../IndicadorDeEnvio';

export type Destino = 'vender' | 'maleta' | 'clientes' | 'numeros';

const BOTOES: readonly { destino: Destino; nome: string }[] = [
  { destino: 'vender', nome: 'Vender' },
  { destino: 'maleta', nome: 'Minha maleta' },
  { destino: 'clientes', nome: 'Clientes' },
  { destino: 'numeros', nome: 'Meus números' },
];

export interface TelaInicialProps {
  /** Para o indicador de envio; sem ela (testes de tela), o indicador não aparece. */
  ponte?: PonteDoCorona;
  /** Nome da pessoa, quando já se sabe. */
  nome: string | null;
  aoSair: () => void | Promise<void>;
  /** Abre uma das funções. Quem ainda não existe fica fora de `prontos` e mostra "Em breve". */
  aoEscolher?: (destino: Destino) => void;
  prontos?: readonly Destino[];
}

/** Tela inicial do vendedor (§19.7): quatro botões grandes. O que ainda não existe mostra "Em breve". */
export function TelaInicial({ ponte, nome, aoSair, aoEscolher, prontos = [] }: TelaInicialProps) {
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
        {ponte ? <IndicadorDeEnvio ponte={ponte} /> : null}
        {emBreve ? (
          <Aviso tipo="informacao" titulo="Em breve.">
            {`${emBreve} ainda está sendo preparado. Logo você poderá usar por aqui.`}
          </Aviso>
        ) : null}
        <nav className="vendas-botoes" aria-label="O que você quer fazer?">
          {BOTOES.map(({ destino, nome: nomeDoBotao }) => (
            <Botao
              key={destino}
              variante="primario"
              grande
              onClick={() => {
                if (aoEscolher && prontos.includes(destino)) aoEscolher(destino);
                else setEmBreve(nomeDoBotao);
              }}
            >
              {nomeDoBotao}
            </Botao>
          ))}
        </nav>
      </main>
    </div>
  );
}

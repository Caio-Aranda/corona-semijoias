import { Botao, CabecalhoDeMarca, MenuLateral } from '@corona/ui';
import type { PonteDoCorona } from '../../shared/ponte';
import { RodapeDaCotacao } from '../nucleo/cotacao';
import { MENU, rotuloDaRota, useRota, type Rota } from '../nucleo/rotas';
import { TEXTOS } from '../textos';
import { TelaEmBreve } from './TelaEmBreve';
import { TelaInicial } from './TelaInicial';
import { TelaUsuarios } from './usuarios/TelaUsuarios';

export interface EstruturaProps {
  ponte: PonteDoCorona;
  nome: string | null;
  aoSair: () => void | Promise<void>;
}

function Conteudo({
  rota,
  ponte,
  nome,
}: {
  rota: Rota;
  ponte: PonteDoCorona;
  nome: string | null;
}) {
  switch (rota) {
    case 'inicio':
      return <TelaInicial ponte={ponte} nome={nome} />;
    case 'pessoas/usuarios':
      return <TelaUsuarios ponte={ponte} />;
    default:
      return <TelaEmBreve nome={rotuloDaRota(rota)} />;
  }
}

/** Cabeçalho, menu lateral, a tela escolhida e a linha da cotação do dia no rodapé. */
export function Estrutura({ ponte, nome, aoSair }: EstruturaProps) {
  const [rota, irPara] = useRota();
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
      <div className="gestao-corpo">
        <MenuLateral
          grupos={MENU}
          atual={rota}
          rotulo={TEXTOS.menu.rotulo}
          aoEscolher={(chave) => irPara(chave as Rota)}
        />
        <main className="gestao-conteudo">
          {/* A chave faz a tela começar do zero ao trocar de item: senhas e formulários não vazam. */}
          <Conteudo key={rota} rota={rota} ponte={ponte} nome={nome} />
          <RodapeDaCotacao />
        </main>
      </div>
    </div>
  );
}

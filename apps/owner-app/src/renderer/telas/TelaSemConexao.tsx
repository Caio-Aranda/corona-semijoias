import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { TEXTOS } from '../textos';

export function TelaSemConexao({ aoTentarDeNovo }: { aoTentarDeNovo: () => void }) {
  return (
    <div className="gestao-pagina">
      <CabecalhoDeMarca nomeDoApp="Corona Gestão" />
      <main className="gestao-conteudo">
        <div className="gestao-cartao">
          <Aviso tipo="atencao" titulo={TEXTOS.semInternet.titulo}>
            {TEXTOS.semInternet.texto}
          </Aviso>
          <Botao variante="primario" grande onClick={aoTentarDeNovo}>
            Tentar de novo
          </Botao>
        </div>
      </main>
    </div>
  );
}

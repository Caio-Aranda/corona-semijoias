import { Aviso, Botao, CabecalhoDeMarca } from '@corona/ui';
import { TEXTOS } from '../textos';
import { SeletorDeIdioma } from '../SeletorDeIdioma';

export function TelaSemConexao({ aoTentarDeNovo }: { aoTentarDeNovo: () => void }) {
  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca nomeDoApp="Corona Vendas" acoes={<SeletorDeIdioma />} />
      <main className="vendas-conteudo">
        <div className="vendas-cartao">
          <Aviso tipo="atencao" titulo={TEXTOS.semInternet.titulo}>
            {TEXTOS.semInternet.texto}
          </Aviso>
          <Botao variante="primario" grande onClick={aoTentarDeNovo}>
            {TEXTOS.tentarDeNovo}
          </Botao>
        </div>
      </main>
    </div>
  );
}

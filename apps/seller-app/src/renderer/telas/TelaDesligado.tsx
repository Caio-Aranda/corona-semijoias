import { Aviso, CabecalhoDeMarca } from '@corona/ui';
import { TEXTOS } from '../textos';
import { SeletorDeIdioma } from '../SeletorDeIdioma';

/**
 * O computador foi desligado pela dona (tarefa 5.8; ADR-0013, item 14). Primeiro as vendas que faltavam seguem para a
 * dona; depois os dados são apagados. Sem botões: não há nada para a pessoa fazer aqui.
 */
export function TelaDesligado({ apagado }: { apagado: boolean }) {
  const T = apagado ? TEXTOS.desligado : TEXTOS.desligando;
  return (
    <div className="vendas-pagina">
      <CabecalhoDeMarca nomeDoApp="Corona Vendas" acoes={<SeletorDeIdioma />} />
      <main className="vendas-conteudo">
        <div className="vendas-cartao">
          <Aviso tipo="atencao" titulo={T.titulo}>
            {T.texto}
          </Aviso>
        </div>
      </main>
    </div>
  );
}

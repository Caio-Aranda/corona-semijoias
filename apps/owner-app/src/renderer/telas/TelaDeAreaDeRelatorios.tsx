import { cartoesDaArea, rotuloDaArea, type ChaveDeArea } from '../nucleo/areas-de-relatorios';
import { TEXTOS_DE_AREAS_DE_RELATORIOS as T } from '../textos-areas-de-relatorios';
import { IconeDoAtalho } from './IconeDoAtalho';

/** Página de uma área do menu "Relatórios": um cartão por relatório; o clique abre o relatório. */
export function TelaDeAreaDeRelatorios({
  area,
  aoAbrir,
}: {
  area: ChaveDeArea;
  aoAbrir: (rota: string) => void;
}) {
  return (
    <div className="gestao-tela">
      <h1 className="gestao-titulo">{T.tituloDaPagina(rotuloDaArea(area))}</h1>
      <p className="gestao-texto">{T.ajudaDaPagina}</p>
      <div className="gestao-cartoes-de-relatorio" role="group" aria-label={T.rotuloDosCartoes}>
        {cartoesDaArea(area).map((c) => (
          <button
            key={c.rota}
            type="button"
            className="gestao-cartao-de-relatorio"
            onClick={() => aoAbrir(c.rota)}
          >
            <span className="gestao-cartao-de-relatorio__icone">
              <IconeDoAtalho nome="relatorios" />
            </span>
            <span className="gestao-cartao-de-relatorio__titulo">{c.titulo}</span>
            <span className="gestao-cartao-de-relatorio__texto">{c.explicacao}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

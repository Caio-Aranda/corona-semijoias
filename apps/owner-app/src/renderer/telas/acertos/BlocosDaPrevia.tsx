import { Aviso, Botao, Tabela } from '@corona/ui';
import type { PonteDoCorona } from '../../../shared/ponte';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { formatarGuarani } from '../../nucleo/moedas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import type { ProdutoDoAcerto } from './dados';
import type { PreviaDoFechamento } from './previa';
import { ValorDoAcerto, percentualDoAcerto } from './Valor';

const T = TEXTOS_DE_ACERTOS.previa;

/** Foto pequena e o nome da peça; sem foto aparece o quadro "Sem foto" e nada trava. */
export function PecaComFoto({
  ponte,
  produto,
}: {
  ponte: PonteDoCorona;
  produto: ProdutoDoAcerto;
}) {
  return (
    <span className="gestao-opcao-de-peca">
      <ImagemDoProduto
        ponte={ponte}
        hash={produto.fotoHash}
        tamanho="miniatura"
        nome={produto.nome}
      />
      <span>
        {produto.nome} ({produto.codigo})
      </span>
    </span>
  );
}

export function PecasDaPrevia({
  ponte,
  previa,
}: {
  ponte: PonteDoCorona;
  previa: PreviaDoFechamento;
}) {
  const C = T.pecas.colunas;
  return (
    <section className="gestao-grupo" aria-labelledby="previa-pecas">
      <h3 id="previa-pecas" className="gestao-subtitulo">
        {T.pecas.titulo}
      </h3>
      <Tabela
        legenda={T.pecas.legenda}
        textoVazio={T.pecas.vazia}
        colunas={[
          { chave: 'peca', titulo: C.peca },
          { chave: 'saiu', titulo: C.saiu, numerica: true },
          { chave: 'boa', titulo: C.boa, numerica: true },
          { chave: 'defeito', titulo: C.defeito, numerica: true },
          { chave: 'faltou', titulo: C.faltou, numerica: true },
          { chave: 'valor', titulo: C.valorFalta, numerica: true },
        ]}
        linhas={previa.itens.map((i) => ({
          peca: <PecaComFoto ponte={ponte} produto={i.produto} />,
          saiu: i.esperado,
          boa: i.contadoBoa,
          defeito: i.contadoDefeito,
          faltou: i.falta,
          valor: i.falta > 0 ? <ValorDoAcerto valor={i.valorDaFalta} /> : '',
        }))}
      />
    </section>
  );
}

/** Os dois motivos que impedem fechar (sobra de peça e devolução esperando revisão). */
export function ImpedimentosDaPrevia({
  previa,
  nomeDaPeca,
}: {
  previa: PreviaDoFechamento;
  nomeDaPeca: (id: string) => string;
}) {
  return (
    <>
      {previa.sobras.length > 0 ? (
        <Aviso tipo="erro" titulo={T.sobra.titulo}>
          <p>{T.sobra.ajuda}</p>
          <ul>
            {previa.sobras.map((s) => (
              <li key={s.produtoId}>
                {T.sobra.linha(nomeDaPeca(s.produtoId), s.esperado, s.contado)}
              </li>
            ))}
          </ul>
        </Aviso>
      ) : null}
      {previa.revisoesPendentes.length > 0 ? (
        <Aviso tipo="erro">
          <p>{T.revisao.titulo}</p>
          <Botao
            onClick={() => {
              window.location.hash = '/devolucoes/revisar';
            }}
          >
            {T.revisao.botao}
          </Botao>
        </Aviso>
      ) : null}
    </>
  );
}

/** Meta e comissão: tudo o que a API calculou (soma em Guarani, meta, percentual e a comissão de cada venda). */
export function ComissaoDaPrevia({ previa }: { previa: PreviaDoFechamento }) {
  const C = T.comissao;
  const percentual = percentualDoAcerto(previa.percentualComissao);
  // Só compara os dois números que a API mandou, do mesmo jeito que ela decide (soma >= meta).
  const bateu = previa.vendidoParaMetaGuarani >= previa.metaGuarani;
  let n = 0;
  return (
    <section className="gestao-grupo" aria-labelledby="previa-comissao">
      <h3 id="previa-comissao" className="gestao-subtitulo">
        {C.titulo}
      </h3>
      <p className="gestao-texto">
        {C.vendidoNaSemana(
          formatarGuarani(BigInt(previa.vendidoParaMetaGuarani)),
          formatarGuarani(BigInt(previa.metaGuarani)),
        )}
      </p>
      <p className="gestao-texto">{bateu ? C.bateu(percentual) : C.naoBateu(percentual)}</p>
      <Tabela
        legenda={C.legenda}
        textoVazio={C.vazia}
        colunas={[
          { chave: 'venda', titulo: C.colunas.venda },
          { chave: 'base', titulo: C.colunas.base, numerica: true },
          { chave: 'percentual', titulo: C.colunas.percentual, numerica: true },
          { chave: 'valor', titulo: C.colunas.valor, numerica: true },
        ]}
        linhas={previa.comissoes.map((l) => {
          n += 1;
          return {
            venda: l.tipo === 'ESTORNO' ? C.devolucao(n) : C.venda(n),
            base: <ValorDoAcerto valor={l.base} />,
            percentual: percentualDoAcerto(l.percentual),
            valor: <ValorDoAcerto valor={l.valor} />,
          };
        })}
      />
    </section>
  );
}

/** Os totais, com os rótulos da loja. Cada valor é o que a API devolveu. */
export function TotaisDaPrevia({
  previa,
  calculando,
}: {
  previa: PreviaDoFechamento;
  calculando: boolean;
}) {
  const R = T.totais;
  const linhas: [string, string][] = [
    [R.vendido, previa.vendidoBruto],
    [R.descontos, previa.descontos],
    [R.devolvido, previa.devolvido],
    [R.vendidoLiquido, previa.vendidoLiquido],
    [R.comissao, previa.comissao],
    [R.faltasCobradas, previa.faltasCobradas],
    [R.liquido, previa.liquido],
    [R.creditoUsado, previa.creditoUsado],
    [R.aPagar, previa.aPagarNoAcerto],
  ];
  return (
    <section
      className={`gestao-grupo${calculando ? ' gestao-calculando' : ''}`}
      aria-labelledby="previa-totais"
      aria-busy={calculando}
    >
      <h3 id="previa-totais" className="gestao-subtitulo">
        {R.titulo}
      </h3>
      <dl className="gestao-totais">
        {linhas.map(([rotulo, valor]) => (
          <div key={rotulo} className="gestao-totais__linha">
            <dt>{rotulo}</dt>
            <dd>
              <ValorDoAcerto valor={valor} />
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

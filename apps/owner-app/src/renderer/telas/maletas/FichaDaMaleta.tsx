import { Aviso, Botao, Tabela } from '@corona/ui';
import type { PonteDoCorona } from '../../../shared/ponte';
import { ImagemDoProduto } from '../../componentes/ImagemDoProduto';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { dataNaTela } from '../cadastros/definicoes';
import { BotaoImprimirNota } from './BotaoImprimirNota';
import { Dinheiro } from './Dinheiro';
import type { Maleta } from './dados';

const T = TEXTOS_DE_MALETAS;

export interface FichaDaMaletaProps {
  ponte: PonteDoCorona;
  maleta: Maleta;
  /** O envio acabou de acontecer (a mensagem de sucesso aparece no alto). */
  recemEnviada?: boolean;
  /** O servidor respondeu 200: a maleta já estava enviada (reenvio). */
  jaEstavaEnviada?: boolean;
  aoMontarOutra: () => void;
}

/** A maleta enviada, só para ler: não há como alterar peças depois do envio. */
export function FichaDaMaleta({
  ponte,
  maleta,
  recemEnviada,
  jaEstavaEnviada,
  aoMontarOutra,
}: FichaDaMaletaProps) {
  const E = T.enviada;
  return (
    <div>
      {recemEnviada && !jaEstavaEnviada ? (
        <Aviso tipo="sucesso" titulo={E.titulo}>
          {E.texto(maleta.vendedorNome, T.pecas(maleta.totalPecas), maleta.totalValor)}
        </Aviso>
      ) : null}
      {jaEstavaEnviada ? <Aviso tipo="atencao">{E.jaEstava}</Aviso> : null}
      <section className="gestao-grupo" aria-labelledby="titulo-ficha-maleta">
        <h2 id="titulo-ficha-maleta" className="gestao-subtitulo">
          {E.tituloDaFicha}
        </h2>
        <dl className="gestao-resumo">
          <div>
            <dt>{E.ficha.vendedor}</dt>
            <dd>{maleta.vendedorNome}</dd>
          </div>
          <div>
            <dt>{E.ficha.semana}</dt>
            <dd>{maleta.semanaNumero}</dd>
          </div>
          <div>
            <dt>{E.ficha.praca}</dt>
            <dd>{maleta.pracaNome}</dd>
          </div>
          <div>
            <dt>{E.ficha.tipo}</dt>
            <dd>{TEXTOS_DE_MALETAS.nova.tipos[maleta.tipo] ?? maleta.tipo}</dd>
          </div>
          <div>
            <dt>{E.ficha.situacao}</dt>
            <dd>{E.ficha.situacoes[maleta.status] ?? maleta.status}</dd>
          </div>
          {maleta.enviadaEm ? (
            <div>
              <dt>{E.ficha.enviadaEm}</dt>
              <dd>{dataNaTela(maleta.enviadaEm.slice(0, 10))}</dd>
            </div>
          ) : null}
        </dl>
        <Tabela
          legenda={T.itens.legenda}
          textoVazio={T.itens.vazia}
          colunas={[
            { chave: 'foto', titulo: T.itens.colunas.foto },
            { chave: 'nome', titulo: T.itens.colunas.nome },
            { chave: 'codigo', titulo: T.itens.colunas.codigo },
            { chave: 'quantidade', titulo: T.itens.colunas.quantidade, numerica: true },
            { chave: 'preco', titulo: T.itens.colunas.preco, numerica: true },
            { chave: 'subtotal', titulo: T.itens.colunas.subtotal, numerica: true },
          ]}
          linhas={maleta.itens.map((i) => ({
            foto: (
              <ImagemDoProduto ponte={ponte} hash={i.fotoHash} tamanho="miniatura" nome={i.nome} />
            ),
            nome: i.nome,
            codigo: i.codigo,
            quantidade: i.quantidade,
            preco: <Dinheiro valor={i.precoUnitario} />,
            subtotal: <Dinheiro valor={i.total} />,
          }))}
        />
        <p className="gestao-total-da-compra">
          {T.totais.pecas}: {maleta.totalPecas} · {T.totais.valor}:{' '}
          <Dinheiro valor={maleta.totalValor} />
        </p>
        <div className="gestao-acoes-da-linha">
          <BotaoImprimirNota
            ponte={ponte}
            maletaId={maleta.id}
            vendedorNome={maleta.vendedorNome}
          />
          <Botao variante="primario" onClick={aoMontarOutra}>
            {E.montarOutra}
          </Botao>
        </div>
      </section>
    </div>
  );
}

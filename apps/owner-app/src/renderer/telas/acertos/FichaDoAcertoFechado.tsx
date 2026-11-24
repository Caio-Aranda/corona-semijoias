import { Aviso, Botao, Tabela } from '@corona/ui';
import { useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { entregarArquivo } from '../../nucleo/arquivos';
import { gerarPdfDoAcerto } from '../../nucleo/exportacao/pdf-acerto';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { dataEHora } from '../sincronizacao/dados';
import { PecaComFoto } from './BlocosDaPrevia';
import { ProvedorDaCotacaoDoAcerto, ValorDoAcerto, percentualDoAcerto } from './Valor';
import type { FichaDoAcerto } from './dados';
import { lerFechamento, recebidoEm, type FechamentoGravado } from './fechada';
import { acertoParaPdf, fraseDaCotacao, frasesDaMeta, nomeDoArquivo } from './pdf-da-ficha';

const T = TEXTOS_DE_ACERTOS;
const R = T.fechada;
const P = T.previa;

function BotaoImprimirAcerto({
  ficha,
  fechamento,
}: {
  ficha: FichaDoAcerto;
  fechamento: FechamentoGravado;
}) {
  const [fazendo, setFazendo] = useState(false);
  const [resultado, setResultado] = useState<{ tipo: 'sucesso' | 'erro'; texto: string } | null>(
    null,
  );
  function imprimir() {
    if (fazendo) return;
    setFazendo(true);
    setResultado(null);
    try {
      const nome = nomeDoArquivo(ficha);
      entregarArquivo(nome, 'application/pdf', gerarPdfDoAcerto(acertoParaPdf(ficha, fechamento)));
      setResultado({ tipo: 'sucesso', texto: R.pronta(nome) });
    } catch {
      setResultado({ tipo: 'erro', texto: R.erroDoPdf });
    }
    setFazendo(false);
  }
  return (
    <>
      <Botao disabled={fazendo} onClick={imprimir}>
        {fazendo ? R.gerando : R.imprimir}
      </Botao>
      {resultado ? <Aviso tipo={resultado.tipo}>{resultado.texto}</Aviso> : null}
    </>
  );
}

/** O acerto fechado, só para ler: tudo o que o sistema gravou, com a cotação gravada no acerto, e o botão de imprimir. */
export function FichaDoAcertoFechado({
  ponte,
  ficha,
  aoTentarDeNovo,
}: {
  ponte: PonteDoCorona;
  ficha: FichaDoAcerto;
  aoTentarDeNovo: () => void;
}) {
  const f = lerFechamento(ficha.fechamento);
  if (!f) {
    return (
      <div className="gestao-erro">
        <Aviso tipo="erro">{R.naoLeu}</Aviso>
        <Botao onClick={aoTentarDeNovo}>{P.tentarDeNovo}</Botao>
      </div>
    );
  }
  const [soma, resultado] = frasesDaMeta(f);
  const totais: [string, string][] = [
    [P.totais.vendido, f.vendidoBruto],
    [P.totais.descontos, f.descontos],
    [P.totais.devolvido, f.devolvido],
    [P.totais.vendidoLiquido, f.vendidoLiquido],
    [P.totais.comissao, f.comissao],
    [P.totais.faltasCobradas, f.faltasCobradas],
    [P.totais.liquido, f.liquido],
    [P.totais.creditoUsado, f.creditoUsado],
    [R.totais.recebido, f.recebidoNoAcerto],
    [R.totais.ficouDevendo, f.ficouDevendo],
    [R.totais.pagoAoVendedor, f.pagoAoVendedor],
  ];
  let n = 0;
  return (
    <ProvedorDaCotacaoDoAcerto cotacao={f.cotacao}>
      <section className="gestao-grupo" aria-labelledby="titulo-fechamento">
        <h2 id="titulo-fechamento" className="gestao-subtitulo">
          {R.titulo}
        </h2>
        <p className="gestao-texto">{R.fechadoEm(dataEHora(f.fechadoEm))}</p>
        <div className="gestao-acoes-da-linha">
          <BotaoImprimirAcerto ficha={ficha} fechamento={f} />
        </div>

        <h3 className="gestao-subtitulo">{P.pecas.titulo}</h3>
        <Tabela
          legenda={P.pecas.legenda}
          textoVazio={P.pecas.vazia}
          colunas={[
            { chave: 'peca', titulo: P.pecas.colunas.peca },
            { chave: 'saiu', titulo: P.pecas.colunas.saiu, numerica: true },
            { chave: 'boa', titulo: P.pecas.colunas.boa, numerica: true },
            { chave: 'defeito', titulo: P.pecas.colunas.defeito, numerica: true },
            { chave: 'faltou', titulo: P.pecas.colunas.faltou, numerica: true },
            { chave: 'aprovado', titulo: R.pecas.defeitoAprovado, numerica: true },
          ]}
          linhas={f.itens.map((i) => ({
            peca: <PecaComFoto ponte={ponte} produto={i.produto} />,
            saiu: i.esperado,
            boa: i.contadoBoa,
            defeito: i.contadoDefeito,
            faltou: i.falta,
            aprovado: i.defeitoAprovado,
          }))}
        />

        <h3 className="gestao-subtitulo">{R.pendencias.legenda}</h3>
        <Tabela
          legenda={R.pendencias.legenda}
          textoVazio={R.pendencias.vazia}
          colunas={[
            { chave: 'peca', titulo: R.pendencias.colunas.peca },
            { chave: 'tipo', titulo: R.pendencias.colunas.tipo },
            { chave: 'quantidade', titulo: R.pendencias.colunas.quantidade, numerica: true },
            { chave: 'valor', titulo: R.pendencias.colunas.valor, numerica: true },
            { chave: 'situacao', titulo: R.pendencias.colunas.situacao },
          ]}
          linhas={f.pendencias.map((p) => ({
            peca: p.produto ? (
              <PecaComFoto ponte={ponte} produto={p.produto} />
            ) : (
              R.pendencias.semPeca
            ),
            tipo: R.pendencias.tipos[p.tipo] ?? p.tipo,
            quantidade: p.quantidade,
            valor: <ValorDoAcerto valor={p.valor} />,
            situacao: R.pendencias.situacoes[p.status] ?? p.status,
          }))}
        />

        <h3 className="gestao-subtitulo">{P.comissao.titulo}</h3>
        <p className="gestao-texto">{soma}</p>
        <p className="gestao-texto">{resultado}</p>
        <Tabela
          legenda={P.comissao.legenda}
          textoVazio={P.comissao.vazia}
          colunas={[
            { chave: 'venda', titulo: P.comissao.colunas.venda },
            { chave: 'base', titulo: P.comissao.colunas.base, numerica: true },
            { chave: 'percentual', titulo: P.comissao.colunas.percentual, numerica: true },
            { chave: 'valor', titulo: P.comissao.colunas.valor, numerica: true },
          ]}
          linhas={f.comissoes.map((l) => {
            n += 1;
            return {
              venda: l.tipo === 'ESTORNO' ? P.comissao.devolucao(n) : P.comissao.venda(n),
              base: <ValorDoAcerto valor={l.base} />,
              percentual: percentualDoAcerto(l.percentual),
              valor: <ValorDoAcerto valor={l.valor} />,
            };
          })}
        />

        <h3 className="gestao-subtitulo">{P.totais.titulo}</h3>
        <dl className="gestao-totais">
          {totais.map(([rotulo, valor]) => (
            <div key={rotulo} className="gestao-totais__linha">
              <dt>{rotulo}</dt>
              <dd>
                <ValorDoAcerto valor={valor} />
              </dd>
            </div>
          ))}
        </dl>

        <h3 className="gestao-subtitulo">{R.recebimentos.legenda}</h3>
        <Tabela
          legenda={R.recebimentos.legenda}
          textoVazio={R.recebimentos.vazia}
          colunas={[
            { chave: 'forma', titulo: R.recebimentos.colunas.forma },
            { chave: 'valor', titulo: R.recebimentos.colunas.valor, numerica: true },
            { chave: 'quando', titulo: R.recebimentos.colunas.quando },
            { chave: 'situacao', titulo: R.recebimentos.colunas.situacao },
          ]}
          linhas={f.recebimentos.map((r) => ({
            forma: R.recebimentos.formas[r.forma] ?? r.forma,
            valor: (
              <>
                <ValorDoAcerto valor={r.valor} />
                {recebidoEm(r) ? <span className="cs-valor__outras">{recebidoEm(r)}</span> : null}
              </>
            ),
            quando: dataEHora(r.recebidoEm),
            situacao: r.estornado ? R.recebimentos.desfeito : '',
          }))}
        />

        <h3 className="gestao-subtitulo">{R.contas.legenda}</h3>
        <Tabela
          legenda={R.contas.legenda}
          textoVazio={R.contas.vazia}
          colunas={[
            { chave: 'parcela', titulo: R.contas.colunas.parcela },
            { chave: 'valor', titulo: R.contas.colunas.valor, numerica: true },
            { chave: 'vence', titulo: R.contas.colunas.vence },
            { chave: 'forma', titulo: R.contas.colunas.forma },
            { chave: 'situacao', titulo: R.contas.colunas.situacao },
          ]}
          linhas={f.contas.map((c) => ({
            parcela: R.contas.parcela(c.numero, c.totalDeParcelas),
            valor: <ValorDoAcerto valor={c.valor} />,
            vence: dataParaTela(c.vencimento),
            forma: R.recebimentos.formas[c.formaPrevista] ?? c.formaPrevista,
            situacao: R.contas.situacoes[c.status] ?? c.status,
          }))}
        />

        <p className="gestao-texto">{fraseDaCotacao(f)}</p>
      </section>
    </ProvedorDaCotacaoDoAcerto>
  );
}

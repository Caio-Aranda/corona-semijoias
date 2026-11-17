import { Aviso, Botao } from '@corona/ui';
import { JanelaDeConfirmacao, Tabela } from '../componentes-do-vendedor';
import { useEffect, useRef, useState } from 'react';
import type {
  FichaDoAcerto,
  FichaDoAcertoAgora,
  FechamentoDoAcerto,
  PreviaDaComissao,
  ResumoDoAcerto,
} from '../../shared/dados';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, Dinheiro, novoId } from '../chamadas';
import { centavosDeTexto, dataParaTela, formatarGuarani, formatarReais } from '../moedas';
import { TEXTOS } from '../textos';
import { PaginaDoVendedor } from './PaginaDoVendedor';
import { TelaContarSobras } from './TelaContarSobras';

export interface TelaMeuAcertoProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
}

type Previa = { tipo: 'pronta'; dados: PreviaDaComissao } | { tipo: 'recusada'; texto: string };

/**
 * Meu acerto e minha comissão (tarefa 7.11; ADR-0012, itens 5 e 13): a prévia da comissão da semana, com quanto falta para
 * a meta em Guarani e em Real (o servidor calcula; a tela só mostra, rotulada como estimativa), e a ficha dos acertos do
 * próprio vendedor, lida do computador. **A tela nunca calcula comissão, meta, total nem pendência.** Dólar e Guarani só na
 * tela, pela cotação gravada no acerto.
 */
export function TelaMeuAcerto({ ponte, aoVoltar }: TelaMeuAcertoProps) {
  const T = TEXTOS.acerto;
  const [previa, setPrevia] = useState<Previa | null>(null);
  const [acertos, setAcertos] = useState<ResumoDoAcerto[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [ficha, setFicha] = useState<FichaDoAcerto | null>(null);
  // Pedir o acerto e contar as sobras (tarefa 8.10): só com internet, direto no servidor.
  const [contando, setContando] = useState<string | null>(null);
  const [recemPedido, setRecemPedido] = useState<string | null>(null);
  const [confirmandoPedido, setConfirmandoPedido] = useState(false);
  const [pedindo, setPedindo] = useState(false);
  // O mesmo pedido reenviado (queda de internet) tem o mesmo id: o servidor não abre dois acertos.
  const idDoPedido = useRef(novoId());

  function buscarPrevia() {
    setPrevia(null);
    void chamar<PreviaDaComissao>(ponte, { operacao: 'comissao.previa' }).then((r) => {
      if (r.ok) setPrevia({ tipo: 'pronta', dados: r.dados });
      else
        setPrevia({
          tipo: 'recusada',
          texto: r.mensagem === TEXTOS.semInternet.curto ? T.semInternet : r.mensagem,
        });
    });
  }

  useEffect(() => {
    buscarPrevia();
    void chamar<ResumoDoAcerto[]>(ponte, { operacao: 'acertos.listar' }).then((r) => {
      if (r.ok) setAcertos(r.dados);
      else setErro(r.mensagem);
    });
    // Só ao abrir a tela.
  }, [ponte]);

  async function abrir(id: string) {
    const r = await chamar<FichaDoAcerto>(ponte, { operacao: 'acertos.ver', id });
    if (r.ok) {
      setErro(null);
      setFicha(r.dados);
    } else setErro(r.mensagem);
  }

  async function pedirAcerto() {
    setPedindo(true);
    setErro(null);
    const r = await chamar<FichaDoAcertoAgora>(ponte, {
      operacao: 'acertos.pedir',
      id: idDoPedido.current,
    });
    setPedindo(false);
    setConfirmandoPedido(false);
    if (!r.ok) {
      setErro(r.mensagem === TEXTOS.semInternet.curto ? T.pedirSemInternet : r.mensagem);
      return;
    }
    idDoPedido.current = novoId();
    setRecemPedido(r.dados.id);
    setContando(r.dados.id);
  }

  if (contando) {
    return (
      <TelaContarSobras
        ponte={ponte}
        acertoId={contando}
        aoVoltar={() => {
          setContando(null);
          buscarPrevia();
        }}
      />
    );
  }

  const acertoEmAndamento = acertos?.find((a) => a.situacao === 'EM_ACERTO')?.id ?? recemPedido;

  if (ficha) {
    return (
      <PaginaDoVendedor
        ponte={ponte}
        titulo={`${T.titulo}: ${T.semana(ficha.semanaNumero)}`}
        aoVoltar={() => setFicha(null)}
      >
        {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
        <FichaDoFechamento ficha={ficha} />
      </PaginaDoVendedor>
    );
  }

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}

      <section className="vendas-cartao vendas-cartao--larga" aria-label={T.comissaoTitulo}>
        <h2 className="vendas-subtitulo">{T.comissaoTitulo}</h2>
        {previa === null ? (
          <p className="vendas-texto" aria-busy="true">
            {T.textoCarregando}
          </p>
        ) : previa.tipo === 'recusada' ? (
          <Aviso tipo="informacao">{previa.texto}</Aviso>
        ) : (
          <PreviaDaSemana previa={previa.dados} />
        )}
        <Botao onClick={buscarPrevia}>{T.atualizar}</Botao>
      </section>

      <section className="vendas-cartao vendas-cartao--larga" aria-label={T.pedirTitulo}>
        <h2 className="vendas-subtitulo">{T.pedirTitulo}</h2>
        {acertoEmAndamento ? (
          <>
            <Aviso tipo="informacao">{T.acertoEmAndamentoTitulo}</Aviso>
            <Botao variante="primario" onClick={() => setContando(acertoEmAndamento)}>
              {T.contar}
            </Botao>
          </>
        ) : acertos === null ? null : (
          <>
            <p className="vendas-texto">{T.pedirAjuda}</p>
            <Botao variante="primario" onClick={() => setConfirmandoPedido(true)}>
              {T.pedir}
            </Botao>
          </>
        )}
      </section>
      {confirmandoPedido ? (
        <JanelaDeConfirmacao
          titulo={T.pedirJanelaTitulo}
          rotuloConfirmar={T.pedirConfirmar}
          ocupado={pedindo}
          aoConfirmar={() => void pedirAcerto()}
          aoCancelar={() => setConfirmandoPedido(false)}
        >
          <p>{T.pedirJanelaTexto}</p>
        </JanelaDeConfirmacao>
      ) : null}

      <section aria-label={T.acertosTitulo}>
        <h2 className="vendas-subtitulo">{T.acertosTitulo}</h2>
        {acertos === null ? (
          <p className="vendas-texto" aria-busy="true">
            {T.textoCarregando}
          </p>
        ) : acertos.length === 0 ? (
          <p className="vendas-texto">{T.semAcertos}</p>
        ) : (
          <ul className="vendas-lista">
            {acertos.map((a) => (
              <li key={a.id}>
                <span>
                  <strong>{T.semana(a.semanaNumero)}</strong>
                  {' · '}
                  <span>{T.situacoes[a.situacao] ?? a.situacao}</span>
                  {a.pedidoPeloVendedor ? <span>{` · ${T.pedidoPorVoce}`}</span> : null}
                  {a.fechadoEm ? (
                    <span>{` · ${T.fechadoEm} ${dataParaTela(a.fechadoEm.slice(0, 10))}`}</span>
                  ) : null}
                </span>
                <Botao aria-label={T.verDaSemana(a.semanaNumero)} onClick={() => void abrir(a.id)}>
                  {T.ver}
                </Botao>
              </li>
            ))}
          </ul>
        )}
      </section>
    </PaginaDoVendedor>
  );
}

/** O que o servidor calculou para a semana: a tela só mostra. */
function PreviaDaSemana({ previa }: { previa: PreviaDaComissao }) {
  const T = TEXTOS.acerto;
  const faltaReais = centavosDeTexto(previa.faltaParaMetaReais);
  return (
    <>
      <p className="vendas-texto vendas-texto--pequeno">{T.comissaoPrevia}</p>
      <p className="vendas-texto">
        {T.vendidoNaSemana}: {formatarGuarani(BigInt(previa.vendidoParaMetaGuarani))}
      </p>
      <p className="vendas-texto">
        {T.metaDaSemana}: {formatarGuarani(BigInt(previa.metaGuarani))}
      </p>
      <p className="vendas-texto">
        {T.percentualHoje}: {semZerosSobrando(previa.percentualQueValeriaHoje)}%
      </p>
      <p className="vendas-texto">
        {T.comissaoPrevista}: <Dinheiro reais={previa.comissaoPrevista} cotacao={null} />
      </p>
      {previa.faltaParaMetaGuarani > 0 ? (
        <p className="vendas-total">
          {T.faltaParaAMeta}: {formatarGuarani(BigInt(previa.faltaParaMetaGuarani))}
          {faltaReais !== null ? ` · ${formatarReais(faltaReais)}` : ''}
        </p>
      ) : (
        <Aviso tipo="sucesso">{T.metaBatida}</Aviso>
      )}
    </>
  );
}

/** "30.00" → "30" (só para o texto; o valor é o do servidor). */
function semZerosSobrando(percentual: string): string {
  return percentual.replace(/\.00$/, '');
}

function FichaDoFechamento({ ficha }: { ficha: FichaDoAcerto }) {
  const T = TEXTOS.acerto;
  const f = ficha.fechamento;
  if (ficha.situacao === 'DESISTIDO') return <Aviso tipo="informacao">{T.desistido}</Aviso>;
  if (!f) return <Aviso tipo="informacao">{T.emAndamento}</Aviso>;
  return <FechamentoDoAcertoTela fechamento={f} />;
}

function FechamentoDoAcertoTela({ fechamento: f }: { fechamento: FechamentoDoAcerto }) {
  const T = TEXTOS.acerto;
  const cotacao = f.cotacao ? { dolar: f.cotacao.dolar, guarani: f.cotacao.guarani } : null;
  const dinheiro = (reais: string) => <Dinheiro reais={reais} cotacao={cotacao} />;
  const liquido = centavosDeTexto(f.liquido) ?? 0n;
  const aFavor = liquido < 0n;
  return (
    <>
      <p className="vendas-texto vendas-texto--pequeno">
        {f.cotacao ? T.cotacao(dataParaTela(f.cotacao.data)) : T.semCotacao}
      </p>

      <section className="vendas-cartao vendas-cartao--larga" aria-label={T.resumoTitulo}>
        <h2 className="vendas-subtitulo">{T.resumoTitulo}</h2>
        <p className="vendas-texto">
          {T.vendidoBruto}: {dinheiro(f.vendidoBruto)}
        </p>
        <p className="vendas-texto">
          {T.descontos}: {dinheiro(f.descontos)}
        </p>
        <p className="vendas-texto">
          {T.devolvido}: {dinheiro(f.devolvido)}
        </p>
        <p className="vendas-texto">
          {T.vendidoLiquido}: {dinheiro(f.vendidoLiquido)}
        </p>
        <p className="vendas-texto">
          {T.comissao}: {dinheiro(f.comissao)}
        </p>
        {f.percentualComissao !== null ? (
          <p className="vendas-texto vendas-texto--pequeno">
            {T.comissaoPercentual(f.percentualComissao)}
          </p>
        ) : null}
        {f.metaGuarani !== null && f.vendidoParaMetaGuarani !== null ? (
          <p className="vendas-texto vendas-texto--pequeno">
            {T.metaDoAcerto(
              formatarGuarani(BigInt(f.metaGuarani)).replace('Gs. ', ''),
              formatarGuarani(BigInt(f.vendidoParaMetaGuarani)).replace('Gs. ', ''),
            )}
          </p>
        ) : null}
        <p className="vendas-texto">
          {T.faltasCobradas}: {dinheiro(f.faltasCobradas)}
        </p>
        {aFavor ? (
          <Aviso tipo="informacao">{T.lojaDeve(formatarReais(-liquido))}</Aviso>
        ) : (
          <>
            <p className="vendas-total">
              {T.liquido}: {dinheiro(f.liquido)}
            </p>
            <p className="vendas-texto">
              {T.recebidoNoAcerto}: {dinheiro(f.recebidoNoAcerto)}
            </p>
            <p className="vendas-texto">
              {T.ficouDevendo}: {dinheiro(f.ficouDevendo)}
            </p>
          </>
        )}
      </section>

      <section aria-label={T.pecasTitulo}>
        <h2 className="vendas-subtitulo">{T.pecasTitulo}</h2>
        <Tabela
          legenda={T.pecasTitulo}
          textoVazio=""
          colunas={[
            { chave: 'peca', titulo: T.pecasColunas.peca },
            { chave: 'esperado', titulo: T.pecasColunas.esperado, numerica: true },
            { chave: 'boas', titulo: T.pecasColunas.boas, numerica: true },
            { chave: 'defeito', titulo: T.pecasColunas.defeito, numerica: true },
            { chave: 'falta', titulo: T.pecasColunas.falta, numerica: true },
          ]}
          linhas={f.itens.map((i) => ({
            peca: i.produto.nome,
            esperado: i.esperado,
            boas: i.contadoBoa,
            defeito: i.contadoDefeito,
            falta: i.falta,
          }))}
        />
      </section>

      <section aria-label={T.comissoesTitulo}>
        <h2 className="vendas-subtitulo">{T.comissoesTitulo}</h2>
        <Tabela
          legenda={T.comissoesTitulo}
          textoVazio=""
          colunas={[
            { chave: 'cliente', titulo: T.comissoesColunas.cliente },
            { chave: 'base', titulo: T.comissoesColunas.base, numerica: true },
            { chave: 'percentual', titulo: T.comissoesColunas.percentual, numerica: true },
            { chave: 'valor', titulo: T.comissoesColunas.valor, numerica: true },
          ]}
          linhas={f.comissoes.map((c) => ({
            cliente:
              c.tipo === 'ESTORNO' ? `${c.clienteNome} (${T.estornoDeComissao})` : c.clienteNome,
            base: dinheiro(c.base),
            percentual: semZerosSobrando(c.percentual),
            valor: dinheiro(c.valor),
          }))}
        />
      </section>

      {f.pendencias.length > 0 ? (
        <section aria-label={T.pendenciasTitulo}>
          <h2 className="vendas-subtitulo">{T.pendenciasTitulo}</h2>
          <ul className="vendas-lista">
            {f.pendencias.map((p) => (
              <li key={p.id}>
                <span>
                  <strong>{T.tiposDePendencia[p.tipo] ?? p.tipo}</strong>
                  {p.produto ? <span>{` · ${p.produto.nome}`}</span> : null}
                  {p.quantidade ? <span>{` · ${p.quantidade}`}</span> : null}
                  {' · '}
                  {dinheiro(p.valor)}
                  {' · '}
                  <span>{T.situacoesDaPendencia[p.status] ?? p.status}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {f.recebimentos.length > 0 ? (
        <section aria-label={T.pagamentosTitulo}>
          <h2 className="vendas-subtitulo">{T.pagamentosTitulo}</h2>
          <ul className="vendas-lista">
            {f.recebimentos.map((r) => (
              <li key={r.id}>
                <span>
                  <strong>{r.aplicadoEm === 'CONTAS' ? T.pagouConta : T.pagouAoAcerto}</strong>
                  {' · '}
                  <span>{TEXTOS.aReceber.formasDaLoja[r.forma] ?? r.forma}</span>
                  {' · '}
                  {dinheiro(r.valor)}
                  {r.estornado ? <span>{` · ${T.estornadoNoAcerto}`}</span> : null}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {f.contas.length > 0 ? (
        <section aria-label={T.contasTitulo}>
          <h2 className="vendas-subtitulo">{T.contasTitulo}</h2>
          <ul className="vendas-lista">
            {f.contas.map((c) => (
              <li key={c.id}>
                <span>
                  <strong>{TEXTOS.aReceber.parcelaDe(c.numero, c.total)}</strong>
                  {c.vencimento ? (
                    <span>{` · ${TEXTOS.aReceber.vencimento} ${dataParaTela(c.vencimento)}`}</span>
                  ) : null}
                  {' · '}
                  {dinheiro(c.valor)}
                  {' · '}
                  <span>{TEXTOS.aReceber.situacoes[c.situacao] ?? c.situacao}</span>
                  {c.formaPrevista ? (
                    <span>
                      {` · ${TEXTOS.aReceber.formasDaLoja[c.formaPrevista] ?? c.formaPrevista}`}
                    </span>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}

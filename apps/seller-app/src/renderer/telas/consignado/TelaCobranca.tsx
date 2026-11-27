import { Aviso, Botao, RodapeDeAcoes } from '@corona/ui';
import { useEffect, useMemo, useState } from 'react';
import type {
  DetalheDaNota,
  LinhaDaNota,
  PrevisaoDoAcerto,
} from '../../../shared/dados-do-consignado';
import { diaEmBrasilia } from '../../../shared/garantia';
import type { FormaPaga, PecaDevolvida } from '../../../shared/operacoes-do-consignado';
import {
  FORMAS_DO_CONSIGNADO,
  type FormaDoConsignado,
} from '../../../shared/operacoes-do-consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao } from '../../chamadas';
import { Campo, JanelaDeConfirmacao } from '../../componentes-do-vendedor';
import { centavosDeTexto, formatarGuarani } from '../../moedas';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import {
  classeDaSituacao,
  dia,
  dinheiroDigitado,
  FotoDoProduto,
  porCento,
  reais,
  situacaoNaTela,
  useConsulta,
} from './apoio';
import { EscolherPedido, LeitorDeCodigo } from './componentes';
import { BotaoDeRelatorio, useRelatorioDaTela, ValorDoRelatorio } from './Relatorio';
import type {
  RelatorioDoAcerto,
  ValorNasTresMoedas,
} from '../../../shared/relatorios-do-consignado';

export interface TelaCobrancaProps {
  ponte: PonteDoCorona;
  /** O pedido já escolhido (dois cliques na tela principal ou a busca); sem ele, a pessoa procura. */
  notaId: string | null;
  aoVoltar: () => void;
}

const chaveDaLinha = (l: { produtoId: string; remessaId: string }): string =>
  `${l.produtoId}|${l.remessaId}`;

/**
 * Cobrança (§23.7): acha o pedido (nome, número da nota ou código da maleta) e faz o acerto por nota: aba Itens (as peças
 * devolvidas, lidas uma a uma) e aba Forma de Pagamento (comissão, pendências anteriores, dinheiro, Pix, débito e crédito à
 * vista). As contas vêm do computador com as mesmas fórmulas do servidor, que recalcula tudo ao receber.
 */
export function TelaCobranca({ ponte, notaId: inicial, aoVoltar }: TelaCobrancaProps) {
  const T = TEXTOS.consignado.cobranca;
  const [notaId, setNotaId] = useState<string | null>(inicial);
  const [escolhido, setEscolhido] = useState<{
    id: string;
    numero: string;
    cliente: string;
  } | null>(null);
  const {
    dados: nota,
    erro,
    recarregar,
  } = useConsulta<DetalheDaNota>(
    ponte,
    notaId ? { operacao: 'consignado.nota', id: notaId } : null,
    [notaId],
  );

  if (!notaId || !nota || nota.id !== notaId) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        {erro ? <Aviso tipo="erro">{erro}</Aviso> : null}
        {!notaId ? (
          <>
            <p className="vendas-texto">{T.escolher}</p>
            <EscolherPedido
              ponte={ponte}
              rotuloBusca={T.buscar}
              escolhido={escolhido}
              aoEscolher={(n) => {
                setEscolhido({ id: n.notaId, numero: n.numero, cliente: n.cliente });
                setNotaId(n.notaId);
              }}
              aoTrocar={() => setEscolhido(null)}
            />
          </>
        ) : null}
      </PaginaDoVendedor>
    );
  }
  return (
    <AcertoDoPedido
      key={nota.id}
      ponte={ponte}
      nota={nota}
      aoVoltar={aoVoltar}
      aoMudar={recarregar}
      aoTrocarPedido={() => {
        setNotaId(null);
        setEscolhido(null);
      }}
    />
  );
}

/** "30.00" → "30"; "7.50" → "7,5" (a comissão na tela, sem zeros que sobram). */
function porcentagemNaTela(texto: string): string {
  return texto
    .replace(/\.00$/, '')
    .replace(/(\.\d)0$/, '$1')
    .replace('.', ',');
}

/** A linha da meta embaixo da Comissão % (textos do plano, seção 2); só a prévia, o servidor decide. */
function linhaDaMeta(previa: PrevisaoDoAcerto): string {
  const T = TEXTOS.consignado.cobranca;
  const { meta } = previa;
  const pct = porcentagemNaTela(previa.comissaoPercentual);
  if (meta.vdEmGuarani === null) return T.metaSemCotacao(pct);
  const vendido = formatarGuarani(BigInt(meta.vdEmGuarani));
  const alvo = formatarGuarani(BigInt(meta.metaEmGuarani));
  return meta.bateuAMeta
    ? T.metaBateu(vendido, alvo, pct)
    : T.metaNaoBateu(vendido, alvo, pct, formatarGuarani(BigInt(meta.faltaEmGuarani ?? '0')));
}

function AcertoDoPedido({
  ponte,
  nota,
  aoVoltar,
  aoMudar,
  aoTrocarPedido,
}: {
  ponte: PonteDoCorona;
  nota: DetalheDaNota;
  aoVoltar: () => void;
  aoMudar: () => Promise<void>;
  aoTrocarPedido: () => void;
}) {
  const T = TEXTOS.consignado.cobranca;
  const cotacao = useCotacao(ponte);
  const [aba, setAba] = useState<'itens' | 'pagamento'>('itens');
  const [devolvidos, setDevolvidos] = useState<Record<string, number>>({});
  const [concluida, setConcluida] = useState(false);
  const [valores, setValores] = useState<Record<FormaDoConsignado, string>>({
    DINHEIRO: '',
    PIX: '',
    DEBITO: '',
    CREDITO: '',
  });
  const [mensagem, setMensagem] = useState<{
    tipo: 'sucesso' | 'atencao' | 'erro';
    texto: string;
  } | null>(null);
  const [escolhaDeMaleta, setEscolhaDeMaleta] = useState<LinhaDaNota[] | null>(null);
  const [confirmando, setConfirmando] = useState<
    'concluir' | 'salvar' | 'estornar' | 'remarcar' | null
  >(null);
  const [motivo, setMotivo] = useState('');
  const [novaData, setNovaData] = useState(nota.dataCobranca ?? '');
  const hoje = diaEmBrasilia(new Date());
  const [dataDoAcerto, setDataDoAcerto] = useState(hoje);
  const diaDaSaida = nota.saidaEm ? diaEmBrasilia(new Date(nota.saidaEm)) : null;
  // Com a diferença de relógio guardada do último contato, "hoje" e o dia da saída são os que o servidor vai ver (ADR-0013,
  // item 11). O acerto "de hoje" (sem data escolhida) nunca é conferido aqui: o servidor usa a própria hora.
  const correcao = nota.correcaoDoRelogio;
  const hojeMaximo = correcao && correcao.diaDeHoje < hoje ? correcao.diaDeHoje : hoje;
  const saidaMinima = correcao?.diaDaSaida ?? diaDaSaida;
  const formatoCerto = /^\d{4}-\d{2}-\d{2}$/.test(dataDoAcerto);
  const escolheuOutroDia = dataDoAcerto !== hoje;
  const antesDaSaida =
    formatoCerto && escolheuOutroDia && saidaMinima !== null && dataDoAcerto < saidaMinima;
  const dataInvalida =
    !formatoCerto || (escolheuOutroDia && dataDoAcerto > hojeMaximo) || antesDaSaida;
  // Só vai a data quando não é hoje: o acerto de hoje segue como sempre foi.
  const dataParaEnviar = dataDoAcerto !== hoje && !dataInvalida ? { data: dataDoAcerto } : {};
  const [previa, setPrevia] = useState<PrevisaoDoAcerto | null>(null);
  const [ocupado, setOcupado] = useState(false);

  const acertada = nota.acerto !== null;
  const formasLidas = useMemo(() => {
    const lista: FormaPaga[] = [];
    let invalida = false;
    for (const f of FORMAS_DO_CONSIGNADO) {
      const t = valores[f].trim();
      if (t === '') continue;
      const v = dinheiroDigitado(t);
      if (v === null || Number(v) <= 0) invalida = true;
      else lista.push({ forma: f, valor: v });
    }
    return { lista, invalida };
  }, [valores]);
  const devolvidosParaEnviar = useMemo<PecaDevolvida[]>(
    () =>
      nota.linhas
        .map((l) => ({
          produtoId: l.produtoId,
          remessaId: l.remessaId,
          quantidade: devolvidos[chaveDaLinha(l)] ?? 0,
        }))
        .filter((l) => l.quantidade > 0),
    [nota.linhas, devolvidos],
  );

  useEffect(() => {
    if (acertada || formasLidas.invalida || dataInvalida) {
      setPrevia(null);
      return;
    }
    let ativo = true;
    void chamar<PrevisaoDoAcerto>(ponte, {
      operacao: 'consignado.acertoPrevia',
      acerto: {
        notaId: nota.id,
        devolvidos: devolvidosParaEnviar,
        formas: formasLidas.lista,
        ...dataParaEnviar,
      },
    }).then((r) => {
      if (!ativo) return;
      if (r.ok) {
        setPrevia(r.dados);
        setMensagem((m) => (m?.tipo === 'erro' ? null : m));
      } else setMensagem({ tipo: 'erro', texto: r.mensagem });
    });
    return () => {
      ativo = false;
    };
  }, [ponte, nota.id, acertada, formasLidas, devolvidosParaEnviar, dataInvalida, dataDoAcerto]);

  const pagoAcima =
    previa !== null &&
    (centavosDeTexto(previa.totalPago) ?? 0n) > (centavosDeTexto(previa.totalAReceber) ?? 0n);
  const podeSalvar =
    !acertada &&
    concluida &&
    previa !== null &&
    !pagoAcima &&
    !formasLidas.invalida &&
    !dataInvalida &&
    !ocupado;
  // A cotação do dia da Dt. Acerto (a prévia traz); sem ela, só o Real e o aviso "Sem cotação".
  const cotacaoDoAcerto =
    previa && previa.cotacao !== undefined
      ? previa.cotacao
        ? { dolar: previa.cotacao.dolar, guarani: previa.cotacao.guarani }
        : null
      : cotacao.cotacao;

  function ler(codigo: string): void {
    const lido = codigo.trim().toLowerCase();
    const achadas = nota.linhas.filter((l) => l.codigos.includes(lido));
    if (achadas.length === 0) {
      setMensagem({ tipo: 'atencao', texto: T.foraDaNota });
      return;
    }
    const comFolga = achadas.filter((l) => (devolvidos[chaveDaLinha(l)] ?? 0) < l.dx);
    if (comFolga.length === 0) {
      setMensagem({ tipo: 'atencao', texto: T.devolvidaDemais });
      return;
    }
    if (comFolga.length > 1) {
      setEscolhaDeMaleta(comFolga);
      return;
    }
    somar(comFolga[0] as LinhaDaNota, 1);
  }

  function somar(linha: LinhaDaNota, delta: number): void {
    setMensagem(null);
    setConcluida(false);
    setDevolvidos((d) => {
      const novo = Math.max(0, Math.min(linha.dx, (d[chaveDaLinha(linha)] ?? 0) + delta));
      return { ...d, [chaveDaLinha(linha)]: novo };
    });
  }

  async function salvar(): Promise<void> {
    setOcupado(true);
    const r = await chamar<{ reciboId: string | null }>(ponte, {
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: novoId(),
        reciboId: novoId(),
        notaId: nota.id,
        devolvidos: devolvidosParaEnviar,
        formas: formasLidas.lista,
        ...dataParaEnviar,
      },
    });
    setOcupado(false);
    setConfirmando(null);
    if (r.ok) {
      setMensagem({ tipo: 'sucesso', texto: T.salvo });
      await aoMudar();
    } else setMensagem({ tipo: 'erro', texto: r.mensagem });
  }

  async function estornar(): Promise<void> {
    if (!nota.acerto) return;
    setOcupado(true);
    const r = await chamar(ponte, {
      operacao: 'consignado.estornarAcerto',
      id: novoId(),
      acertoId: nota.acerto.id,
      motivo: motivo.trim(),
    });
    setOcupado(false);
    setConfirmando(null);
    setMotivo('');
    if (r.ok) {
      setDevolvidos({});
      setConcluida(false);
      setMensagem({ tipo: 'sucesso', texto: T.estornado });
      await aoMudar();
    } else setMensagem({ tipo: 'erro', texto: r.mensagem });
  }

  async function remarcar(): Promise<void> {
    setOcupado(true);
    const r = await chamar(ponte, {
      operacao: 'consignado.remarcar',
      id: novoId(),
      notaId: nota.id,
      dataCobranca: novaData,
    });
    setOcupado(false);
    setConfirmando(null);
    if (r.ok) {
      setMensagem({ tipo: 'sucesso', texto: T.remarcado });
      await aoMudar();
    } else setMensagem({ tipo: 'erro', texto: r.mensagem });
  }

  const naoLidas = nota.linhas.filter((l) => l.dx - (devolvidos[chaveDaLinha(l)] ?? 0) > 0);
  const totalNaoLidas = naoLidas.reduce(
    (s, l) => s + (l.dx - (devolvidos[chaveDaLinha(l)] ?? 0)),
    0,
  );
  const a = nota.acerto;

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      <section
        className={`consignado-cabecalho ${classeDaSituacao(nota.situacao)}`}
        aria-label={T.cabecalho(nota.numero, nota.cliente)}
      >
        <p>
          <strong>{T.cabecalho(nota.numero, nota.cliente)}</strong>
        </p>
        <p>
          {TEXTOS.consignado.semanal.colunas.cobranca}: {dia(nota.dataCobranca)} ·{' '}
          {T.saida(dia(nota.saidaEm))} · {situacaoNaTela(nota.situacao)}
        </p>
        <Botao onClick={aoTrocarPedido}>{TEXTOS.consignado.escolherPedido.trocar}</Botao>
        <BotaoDeRelatorio
          ponte={ponte}
          pedido={{ tipo: 'NOTA_DE_SAIDA', notaId: nota.id }}
          rotulo={TEXTOS.consignado.relatorios.abrir.notaDeSaida}
        />
        {nota.acerto ? (
          <BotaoDeRelatorio
            ponte={ponte}
            pedido={{ tipo: 'ACERTO', notaId: nota.id }}
            rotulo={TEXTOS.consignado.relatorios.abrir.acerto}
          />
        ) : null}
      </section>
      {mensagem ? <Aviso tipo={mensagem.tipo}>{mensagem.texto}</Aviso> : null}

      <div className="consignado-abas" role="tablist">
        {(['itens', 'pagamento'] as const).map((id) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={aba === id}
            className={`consignado-aba${aba === id ? ' consignado-aba--ativa' : ''}`}
            onClick={() => setAba(id)}
          >
            {T.abas[id]}
          </button>
        ))}
      </div>

      {aba === 'itens' ? (
        <>
          {!acertada ? (
            <LeitorDeCodigo rotulo={T.lerDevolucao} botao={T.lerAgora} aoLer={ler} />
          ) : (
            <Aviso tipo="informacao">{T.jaAcertado}</Aviso>
          )}
          {escolhaDeMaleta ? (
            <div className="consignado-escolha" role="group" aria-label={T.qualMaleta}>
              <p className="vendas-texto">{T.qualMaleta}</p>
              {escolhaDeMaleta.map((l) => (
                <Botao
                  key={chaveDaLinha(l)}
                  variante="primario"
                  onClick={() => {
                    somar(l, 1);
                    setEscolhaDeMaleta(null);
                  }}
                >
                  {l.maleta}
                </Botao>
              ))}
              <Botao onClick={() => setEscolhaDeMaleta(null)}>{TEXTOS.consignado.cancelar}</Botao>
            </div>
          ) : null}
          <table className="cs-tabela consignado-tabela">
            <caption>{T.itensLegenda}</caption>
            <thead>
              <tr>
                <th scope="col">{T.colunasItens.ref}</th>
                <th scope="col">{T.colunasItens.produto}</th>
                <th scope="col">{T.colunasItens.tamTon}</th>
                <th scope="col">{T.colunasItens.maleta}</th>
                <th scope="col" className="cs-numero">
                  {T.colunasItens.dx}
                </th>
                <th scope="col" className="cs-numero">
                  {T.colunasItens.dv}
                </th>
                <th scope="col" className="cs-numero">
                  {T.colunasItens.preco}
                </th>
                <th scope="col" className="cs-numero">
                  {T.colunasItens.subTotal}
                </th>
                {!acertada ? <th scope="col" /> : null}
              </tr>
            </thead>
            <tbody>
              {nota.linhas.map((l) => {
                const dv = acertada ? l.dv : (devolvidos[chaveDaLinha(l)] ?? 0);
                return (
                  <tr key={chaveDaLinha(l)}>
                    <td>{l.codigo ?? ''}</td>
                    <td>
                      <span className="consignado-peca">
                        <FotoDoProduto ponte={ponte} hash={l.fotoHash} nome={l.nome} />
                        {l.nome}
                      </span>
                    </td>
                    <td>{l.tamTon ?? ''}</td>
                    <td>{l.maleta}</td>
                    <td className="cs-numero">{l.dx}</td>
                    <td className="cs-numero">{dv}</td>
                    <td className="cs-numero">{reais(l.precoUnitario)}</td>
                    <td className="cs-numero">{reais(l.subTotal)}</td>
                    {!acertada ? (
                      <td>
                        <Botao
                          aria-label={T.devolucaoDe(l.nome)}
                          disabled={dv === 0}
                          onClick={() => somar(l, -1)}
                        >
                          {T.desfazerUma}
                        </Botao>
                      </td>
                    ) : null}
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="vendas-total">
            {T.rodape(
              previa?.itensDx ?? a?.itensDx ?? nota.itensDx,
              previa?.itensDv ?? a?.itensDv ?? 0,
              previa?.itensVd ?? a?.itensVd ?? nota.itensDx,
            )}
          </p>
          {!acertada ? (
            <RodapeDeAcoes
              cancelar={<Botao onClick={() => setConfirmando('remarcar')}>{T.remarcar}</Botao>}
              principal={
                <Botao variante="primario" grande onClick={() => setConfirmando('concluir')}>
                  {T.concluir}
                </Botao>
              }
            />
          ) : null}
        </>
      ) : (
        <section aria-label={T.acertoLegenda}>
          {acertada && a ? (
            <ResumoDoAcerto
              ponte={ponte}
              notaId={nota.id}
              acerto={a}
              saldoAtual={nota.saldoDaNota}
              aoEstornar={() => setConfirmando('estornar')}
            />
          ) : (
            <>
              <h2 className="vendas-subtitulo">{T.acertoTitulo}</h2>
              <dl className="consignado-conta">
                <dt>{T.vendaPercentual}</dt>
                <dd>{previa ? porCento(previa.vendaPercentual) : ''}</dd>
                <dt>{T.comissaoRs}</dt>
                <dd>
                  {previa ? <Dinheiro reais={previa.comissao} cotacao={cotacaoDoAcerto} /> : ''}
                </dd>
                <dt>{T.bruto}</dt>
                <dd>{previa ? <Dinheiro reais={previa.bruto} cotacao={cotacaoDoAcerto} /> : ''}</dd>
              </dl>
              <Campo
                rotulo={T.comissaoPercentual}
                ajuda={T.comissaoDaMetaAjuda}
                readOnly
                value={previa ? porcentagemNaTela(previa.comissaoPercentual) : ''}
                onChange={() => undefined}
              />
              {previa ? <p className="vendas-texto">{linhaDaMeta(previa)}</p> : null}
              <Campo
                rotulo={T.dataDoAcerto}
                ajuda={T.dataDoAcertoAjuda}
                type="date"
                max={hoje}
                {...(saidaMinima ? { min: saidaMinima } : {})}
                value={dataDoAcerto}
                onChange={(e) => setDataDoAcerto(e.target.value)}
                {...(dataInvalida
                  ? {
                      erro:
                        antesDaSaida && correcao && saidaMinima
                          ? T.dataDoAcertoAntesDaSaida(dia(saidaMinima))
                          : T.dataDoAcertoInvalida,
                    }
                  : {})}
              />
              {previa && previa.cotacao === null ? (
                <p className="vendas-texto vendas-texto--pequeno">{T.semCotacaoDoDia}</p>
              ) : null}

              <h2 className="vendas-subtitulo">{T.pendenciasTitulo}</h2>
              <p className="vendas-texto vendas-texto--pequeno">{T.pendenciasAjuda}</p>
              {nota.pendenciasAnteriores.length === 0 ? (
                <p className="vendas-texto">{T.nenhumaPendencia}</p>
              ) : (
                <table className="cs-tabela">
                  <caption className="consignado-so-leitor">{T.pendenciasTitulo}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{T.pendenciasColunas.nota}</th>
                      <th scope="col">{T.pendenciasColunas.data}</th>
                      <th scope="col" className="cs-numero">
                        {T.pendenciasColunas.valor}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {nota.pendenciasAnteriores.map((d) => (
                      <tr key={d.acertoId}>
                        <td className="consignado-nota">{d.numeroDaNota}</td>
                        <td>{dia(d.acertadoEm)}</td>
                        <td className="cs-numero">{reais(d.saldo)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <dl className="consignado-conta">
                <dt>{T.totalDoDebito}</dt>
                <dd>
                  {previa ? (
                    <Dinheiro reais={previa.pendenciasAnteriores} cotacao={cotacaoDoAcerto} />
                  ) : (
                    ''
                  )}
                </dd>
                <dt>{T.totalAReceber}</dt>
                <dd>
                  {previa ? (
                    <Dinheiro reais={previa.totalAReceber} cotacao={cotacaoDoAcerto} />
                  ) : (
                    ''
                  )}
                </dd>
              </dl>

              <h2 className="vendas-subtitulo">{T.pagamentoTitulo}</h2>
              {FORMAS_DO_CONSIGNADO.map((f) => (
                <Campo
                  key={f}
                  rotulo={TEXTOS.consignado.formas[f] ?? f}
                  inputMode="decimal"
                  placeholder="0,00"
                  value={valores[f]}
                  {...(valores[f].trim() !== '' && dinheiroDigitado(valores[f]) === null
                    ? { erro: T.valorInvalido }
                    : {})}
                  onChange={(e) => setValores((v) => ({ ...v, [f]: e.target.value }))}
                />
              ))}
              <dl className="consignado-conta">
                <dt>{T.totalPago}</dt>
                <dd>
                  {previa ? <Dinheiro reais={previa.totalPago} cotacao={cotacaoDoAcerto} /> : ''}
                </dd>
                <dt>{T.ficouDevendo}</dt>
                <dd>
                  {previa ? <Dinheiro reais={previa.ficouDevendo} cotacao={cotacaoDoAcerto} /> : ''}
                </dd>
              </dl>
              {pagoAcima ? <Aviso tipo="erro">{T.pagoAcima}</Aviso> : null}
              <p className="vendas-total">
                {T.rodape(previa?.itensDx ?? 0, previa?.itensDv ?? 0, previa?.itensVd ?? 0)} ·{' '}
                {T.totalDx}: {reais(previa?.valorDx)} · {T.totalDv}: {reais(previa?.valorDv)} ·{' '}
                {T.totalVd}: {reais(previa?.valorVd)}
              </p>
              {!concluida ? <Aviso tipo="informacao">{T.concluirPrimeiro}</Aviso> : null}
              <RodapeDeAcoes
                principal={
                  <Botao
                    variante="primario"
                    grande
                    disabled={!podeSalvar}
                    onClick={() => setConfirmando('salvar')}
                  >
                    {T.salvar}
                  </Botao>
                }
              />
            </>
          )}
        </section>
      )}
      <p className="vendas-texto vendas-texto--pequeno">{T.semInternetNaoPrecisa}</p>

      {confirmando === 'concluir' ? (
        <JanelaDeConfirmacao
          titulo={T.concluirTitulo}
          rotuloConfirmar={T.concluirBotao}
          aoConfirmar={() => {
            setConcluida(true);
            setConfirmando(null);
            setAba('pagamento');
          }}
          aoCancelar={() => setConfirmando(null)}
        >
          <p>{T.concluirTexto(totalNaoLidas)}</p>
          {naoLidas.length > 0 ? (
            <>
              <p>
                <strong>{T.naoLidas}</strong>
              </p>
              <ul>
                {naoLidas.map((l) => (
                  <li key={chaveDaLinha(l)}>
                    {l.nome} ({l.maleta}): {l.dx - (devolvidos[chaveDaLinha(l)] ?? 0)}
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </JanelaDeConfirmacao>
      ) : null}
      {confirmando === 'salvar' && previa ? (
        <JanelaDeConfirmacao
          titulo={T.salvarTitulo}
          rotuloConfirmar={T.salvarBotao}
          ocupado={ocupado}
          aoConfirmar={() => void salvar()}
          aoCancelar={() => setConfirmando(null)}
        >
          {T.salvarTexto(reais(previa.ficouDevendo), previa.ficouDevendo === '0.00')}
        </JanelaDeConfirmacao>
      ) : null}
      {confirmando === 'estornar' ? (
        <JanelaDeConfirmacao
          titulo={T.estornarTitulo}
          rotuloConfirmar={T.estornarBotao}
          perigosa
          ocupado={ocupado || motivo.trim() === ''}
          aoConfirmar={() => void estornar()}
          aoCancelar={() => setConfirmando(null)}
        >
          <p>{T.estornarTexto}</p>
          <Campo
            rotulo={T.estornarMotivo}
            value={motivo}
            onChange={(e) => setMotivo(e.target.value)}
          />
        </JanelaDeConfirmacao>
      ) : null}
      {confirmando === 'remarcar' ? (
        <JanelaDeConfirmacao
          titulo={T.remarcarTitulo}
          rotuloConfirmar={T.remarcarBotao}
          ocupado={ocupado || !/^\d{4}-\d{2}-\d{2}$/.test(novaData)}
          aoConfirmar={() => void remarcar()}
          aoCancelar={() => setConfirmando(null)}
        >
          <p>{T.remarcarTexto}</p>
          <Campo
            rotulo={T.remarcarData}
            type="date"
            value={novaData}
            onChange={(e) => setNovaData(e.target.value)}
          />
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}

/** O acerto já salvo: números, recibo e o botão Estornar (corrigir é estornar e fazer de novo). */
function ResumoDoAcerto({
  ponte,
  notaId,
  acerto,
  saldoAtual,
  aoEstornar,
}: {
  ponte: PonteDoCorona;
  notaId: string;
  acerto: NonNullable<DetalheDaNota['acerto']>;
  /** O que ainda deve desta nota hoje (já com pagamentos e sobras aplicados depois do acerto). */
  saldoAtual: string;
  aoEstornar: () => void;
}) {
  const T = TEXTOS.consignado.cobranca;
  const cotacao = useCotacao(ponte);
  // O acerto já salvo mostra a cotação do dia em que foi feito (ADR-0006), não a de hoje.
  const relatorio = useRelatorioDaTela<RelatorioDoAcerto>(ponte, { tipo: 'ACERTO', notaId }, [
    acerto,
  ]);
  const d = (v: string, doRelatorio?: ValorNasTresMoedas) =>
    doRelatorio ? (
      <ValorDoRelatorio v={doRelatorio} />
    ) : (
      <Dinheiro reais={v} cotacao={cotacao.cotacao} />
    );
  // Compara por centavos: "294.7" e "294.70" são o mesmo valor.
  const saldoMudou =
    (centavosDeTexto(saldoAtual) ?? 0n) !== (centavosDeTexto(acerto.ficouDevendo) ?? 0n);
  return (
    <>
      <p className="vendas-texto">{T.acertoDe(dia(acerto.acertadoEm))}</p>
      <dl className="consignado-conta">
        <dt>{T.totalDx}</dt>
        <dd>{d(acerto.valorDx, relatorio?.valorDx)}</dd>
        <dt>{T.totalDv}</dt>
        <dd>{d(acerto.valorDv, relatorio?.valorDv)}</dd>
        <dt>{T.totalVd}</dt>
        <dd>{d(acerto.valorVd, relatorio?.valorVd)}</dd>
        <dt>{T.vendaPercentual}</dt>
        <dd>{porCento(acerto.vendaPercentual)}</dd>
        <dt>{T.comissaoPercentual}</dt>
        <dd>{porCento(acerto.comissaoPercentual)}</dd>
        <dt>{T.comissaoRs}</dt>
        <dd>{d(acerto.comissao, relatorio?.comissao)}</dd>
        <dt>{T.bruto}</dt>
        <dd>{d(acerto.bruto, relatorio?.valorLiquido)}</dd>
        <dt>{T.pendenciasTitulo}</dt>
        <dd>{d(acerto.pendenciasAnteriores, relatorio?.totalPendenciasAnteriores)}</dd>
        <dt>{T.totalAReceber}</dt>
        <dd>{d(acerto.totalAReceber, relatorio?.totalAReceber)}</dd>
        <dt>{T.totalPago}</dt>
        <dd>{d(acerto.totalPago, relatorio?.totalPago)}</dd>
        <dt>{T.ficouDevendo}</dt>
        <dd>{d(acerto.ficouDevendo, relatorio?.ficouDevendo)}</dd>
        {saldoMudou ? (
          <>
            <dt>{T.devendoAgora}</dt>
            <dd>
              <Dinheiro reais={saldoAtual} cotacao={cotacao.cotacao} />
            </dd>
          </>
        ) : null}
      </dl>
      {saldoMudou ? (
        <p className="vendas-texto vendas-texto--pequeno">
          {T.devendoAgoraAjuda(reais(acerto.ficouDevendo))}
        </p>
      ) : null}
      {acerto.recibo ? (
        <>
          <p className="vendas-texto">
            <strong>{T.recibo(acerto.recibo.numero)}</strong>
          </p>
          <ul className="vendas-lista">
            {acerto.recibo.formas.map((f) => (
              <li key={f.forma}>
                {TEXTOS.consignado.formas[f.forma] ?? f.forma}: {reais(f.valor)}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      <RodapeDeAcoes
        principal={
          <Botao variante="perigoso" onClick={aoEstornar}>
            {T.estornar}
          </Botao>
        }
      />
    </>
  );
}

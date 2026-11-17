import { Aviso, Botao } from '@corona/ui';
import { Campo } from '../componentes-do-vendedor';
import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  ContaDaLoja,
  ParcelaAReceber,
  RecebiveisDoVendedor,
  RecebimentoDoCliente,
  VendaAPrazo,
} from '../../shared/dados';
import { FORMAS_DE_RECEBIMENTO, type FormaDeRecebimento } from '../../shared/operacoes';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao } from '../chamadas';
import { centavosDeTexto, dataParaTela, formatarReais, lerReaisDigitados } from '../moedas';
import { TEXTOS } from '../textos';
import { PaginaDoVendedor } from './PaginaDoVendedor';

export interface TelaAReceberProps {
  ponte: PonteDoCorona;
  aoVoltar: () => void;
}

type Aberto =
  | { tipo: 'receber'; vendaId: string }
  | { tipo: 'estornar'; vendaId: string; recebimentoId: string };

/**
 * A receber (tarefa 7.11; ADR-0012, itens 7, 8 e 11): o que os clientes devem ao vendedor (vendas a prazo, com as parcelas
 * que o servidor gerou), receber de um cliente e estornar no mesmo dia, e o que o vendedor deve à loja (contas do
 * acerto, o boleto entre elas). **A tela não calcula parcela, abatimento nem total**: manda quanto o cliente pagou e
 * mostra o que a loja e o computador devolvem. Só Dinheiro e Transferência; nunca cheque, cartão nem entrada.
 */
export function TelaAReceber({ ponte, aoVoltar }: TelaAReceberProps) {
  const T = TEXTOS.aReceber;
  const { cotacao } = useCotacao(ponte);
  const [dados, setDados] = useState<RecebiveisDoVendedor | null>(null);
  const [erroInicial, setErroInicial] = useState<string | null>(null);
  const [aberto, setAberto] = useState<Aberto | null>(null);
  const [valor, setValor] = useState('');
  const [forma, setForma] = useState<FormaDeRecebimento>('DINHEIRO');
  const [motivo, setMotivo] = useState('');
  const [problema, setProblema] = useState<string | null>(null);
  const [sucesso, setSucesso] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  // Tentar de novo depois de um erro usa os mesmos ids: o servidor nunca registra duas vezes.
  const idDoRecebimento = useRef<string | null>(null);
  const idDoEstorno = useRef<string | null>(null);

  const carregar = useCallback(async () => {
    const r = await chamar<RecebiveisDoVendedor>(ponte, { operacao: 'recebiveis.ver' });
    if (r.ok) setDados(r.dados);
    else setErroInicial(r.mensagem);
  }, [ponte]);

  useEffect(() => {
    void carregar();
  }, [carregar]);

  function abrirRecebimento(vendaId: string) {
    idDoRecebimento.current = novoId();
    setAberto({ tipo: 'receber', vendaId });
    setValor('');
    setForma('DINHEIRO');
    setProblema(null);
    setSucesso(null);
  }

  function abrirEstorno(vendaId: string, recebimentoId: string) {
    idDoEstorno.current = novoId();
    setAberto({ tipo: 'estornar', vendaId, recebimentoId });
    setMotivo('');
    setProblema(null);
    setSucesso(null);
  }

  function fechar() {
    setAberto(null);
    setProblema(null);
  }

  async function registrar(vendaId: string) {
    const lido = lerReaisDigitados(valor);
    if (lido === null) return setProblema(T.valorInvalido);
    if ((centavosDeTexto(lido) ?? 0n) <= 0n) return setProblema(T.valorZerado);
    idDoRecebimento.current ??= novoId();
    setEnviando(true);
    const r = await chamar(ponte, {
      operacao: 'recebimentos.receber',
      recebimento: { id: idDoRecebimento.current, vendaId, valor: lido, forma },
    });
    setEnviando(false);
    if (!r.ok) return setProblema(r.mensagem);
    idDoRecebimento.current = null;
    setAberto(null);
    setProblema(null);
    setSucesso(T.registrado);
    await carregar();
  }

  async function estornar(recebimentoId: string) {
    if (!motivo.trim()) return setProblema(T.motivoObrigatorio);
    idDoEstorno.current ??= novoId();
    setEnviando(true);
    const r = await chamar(ponte, {
      operacao: 'recebimentos.estornar',
      id: recebimentoId,
      estornoId: idDoEstorno.current,
      motivo: motivo.trim(),
    });
    setEnviando(false);
    if (!r.ok) return setProblema(r.mensagem);
    idDoEstorno.current = null;
    setAberto(null);
    setProblema(null);
    setSucesso(T.estornoRegistrado);
    await carregar();
  }

  const situacao = (s: string) => T.situacoes[s] ?? s;

  const parcela = (p: ParcelaAReceber) => (
    <li key={p.id}>
      <strong>{T.parcelaDe(p.numero, p.total)}</strong>
      {' · '}
      <span>
        {T.vencimento} {dataParaTela(p.vencimento)}
      </span>
      {' · '}
      <Dinheiro reais={p.valor} cotacao={cotacao} />
      {' · '}
      <span>{situacao(p.situacao)}</span>
      {p.valorPago !== '0.00' ? (
        <span>
          {' · '}
          {T.jaPago}: <Dinheiro reais={p.valorPago} cotacao={cotacao} />
        </span>
      ) : null}
      {p.vencida ? (
        <>
          {' '}
          <strong className="vendas-aviso-de-parcela">{T.vencida}</strong>
        </>
      ) : null}
    </li>
  );

  const recebimento = (v: VendaAPrazo, r: RecebimentoDoCliente) => (
    <li key={r.id}>
      <span>{T.recebimentoDe(formatarValor(r.valor), T.formas[r.forma] ?? r.forma)}</span>
      {' · '}
      <span>{dataParaTela(r.em.slice(0, 10))}</span>
      {r.aguardandoEnvio ? (
        <>
          {' · '}
          <span>{T.aguardandoEnvio}</span>
        </>
      ) : null}
      {r.estornado ? (
        <>
          {' · '}
          <strong>{T.estornado}</strong>
        </>
      ) : null}
      {r.podeEstornar ? (
        <>
          {' '}
          <Botao onClick={() => abrirEstorno(v.vendaId, r.id)}>{T.estornar}</Botao>
        </>
      ) : null}
    </li>
  );

  const venda = (v: VendaAPrazo) => {
    const aqui = aberto?.vendaId === v.vendaId ? aberto : null;
    const temEstimativa = v.aguardandoEnvio || v.recebimentos.some((r) => r.aguardandoEnvio);
    const temForaDoPrazo = v.recebimentos.some((r) => !r.podeEstornar && !r.estornado);
    return (
      <section
        key={v.vendaId}
        className="vendas-cartao vendas-cartao--larga"
        aria-label={`${v.clienteNome}: ${T.vendaDe(dataParaTela(v.vendidaEm.slice(0, 10)))}`}
      >
        <h3 className="vendas-subtitulo">{v.clienteNome}</h3>
        <p className="vendas-texto">{T.vendaDe(dataParaTela(v.vendidaEm.slice(0, 10)))}</p>
        <p className="vendas-texto">
          {T.total}: <Dinheiro reais={v.total} cotacao={cotacao} />
          {' · '}
          {T.pago}: <Dinheiro reais={v.pago} cotacao={cotacao} />
        </p>
        <p className="vendas-total">
          {T.falta}: <Dinheiro reais={v.aReceber} cotacao={cotacao} />
        </p>
        {v.aguardandoEnvio ? <p className="vendas-texto">{T.aguardandoEnvio}</p> : null}
        {temEstimativa ? (
          <p className="vendas-texto vendas-texto--pequeno">{T.estimativa}</p>
        ) : null}
        {v.parcelas.length > 0 ? (
          <ul className="vendas-lista">{v.parcelas.map(parcela)}</ul>
        ) : v.aReceber !== '0.00' ? (
          <p className="vendas-texto">{T.semParcelasAinda}</p>
        ) : null}
        {v.recebimentos.length > 0 ? (
          <>
            <h4 className="vendas-subtitulo">{T.recebimentosTitulo}</h4>
            <ul className="vendas-lista">{v.recebimentos.map((r) => recebimento(v, r))}</ul>
            {temForaDoPrazo ? (
              <p className="vendas-texto vendas-texto--pequeno">{T.soNoMesmoDia}</p>
            ) : null}
          </>
        ) : null}

        {aqui?.tipo === 'receber' ? (
          <div className="vendas-leitor">
            <p className="vendas-texto vendas-texto--pequeno">{T.explicacao}</p>
            <Campo
              rotulo={T.valorRecebido}
              value={valor}
              onChange={(e) => setValor(e.target.value)}
              inputMode="decimal"
              autoComplete="off"
            />
            <fieldset className="vendas-pagamento">
              <legend>{T.formaDoRecebimento}</legend>
              {FORMAS_DE_RECEBIMENTO.map((f) => (
                <label key={f}>
                  <input
                    type="radio"
                    name={`forma-do-recebimento-${v.vendaId}`}
                    checked={forma === f}
                    onChange={() => setForma(f)}
                  />{' '}
                  {T.formas[f]}
                </label>
              ))}
            </fieldset>
            <Botao
              variante="primario"
              disabled={enviando}
              onClick={() => void registrar(v.vendaId)}
            >
              {T.confirmarRecebimento}
            </Botao>
            <Botao onClick={fechar}>{T.cancelar}</Botao>
          </div>
        ) : aqui?.tipo === 'estornar' ? (
          <div className="vendas-leitor">
            <Campo
              rotulo={T.motivoDoEstorno}
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
              autoComplete="off"
              maxLength={400}
            />
            <Botao
              variante="primario"
              disabled={enviando}
              onClick={() => void estornar(aqui.recebimentoId)}
            >
              {T.confirmarEstorno}
            </Botao>
            <Botao onClick={fechar}>{T.cancelar}</Botao>
          </div>
        ) : v.aReceber !== '0.00' ? (
          <Botao
            aria-label={T.receberDe(v.clienteNome)}
            onClick={() => abrirRecebimento(v.vendaId)}
          >
            {T.receber}
          </Botao>
        ) : null}
      </section>
    );
  };

  const conta = (c: ContaDaLoja) => (
    <li key={c.id}>
      <strong>{T.parcelaDe(c.numero, c.total)}</strong>
      {c.vencimento ? <span>{` · ${T.vencimento} ${dataParaTela(c.vencimento)}`}</span> : null}
      {' · '}
      <Dinheiro reais={c.valor} cotacao={cotacao} />
      {' · '}
      <span>{situacao(c.situacao)}</span>
      {c.valorPago !== '0.00' ? (
        <span>
          {' · '}
          {T.jaPago}: <Dinheiro reais={c.valorPago} cotacao={cotacao} />
        </span>
      ) : null}
      {c.formaPrevista ? (
        <span>
          {' · '}
          {T.formaPrevista}: <span>{T.formasDaLoja[c.formaPrevista] ?? c.formaPrevista}</span>
        </span>
      ) : null}
      {c.vencida ? (
        <>
          {' '}
          <strong className="vendas-aviso-de-parcela">{T.vencida}</strong>
        </>
      ) : null}
    </li>
  );

  if (erroInicial) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <Aviso tipo="erro">{erroInicial}</Aviso>
      </PaginaDoVendedor>
    );
  }
  if (!dados) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        <p className="vendas-texto" aria-busy="true">
          {T.textoCarregando}
        </p>
      </PaginaDoVendedor>
    );
  }

  return (
    <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
      {problema ? <Aviso tipo="erro">{problema}</Aviso> : null}
      {sucesso ? <Aviso tipo="sucesso">{sucesso}</Aviso> : null}

      <section aria-label={T.clientesTitulo}>
        <h2 className="vendas-subtitulo">{T.clientesTitulo}</h2>
        {dados.dosClientes.length === 0 ? (
          <p className="vendas-texto">{T.semClientes}</p>
        ) : (
          dados.dosClientes.map(venda)
        )}
      </section>

      <section aria-label={T.lojaTitulo}>
        <h2 className="vendas-subtitulo">{T.lojaTitulo}</h2>
        {dados.daLoja.length === 0 ? (
          <p className="vendas-texto">{T.semLoja}</p>
        ) : (
          <>
            <ul className="vendas-lista">{dados.daLoja.map(conta)}</ul>
            {dados.daLoja.some((c) => c.formaPrevista === 'BOLETO') ? (
              <p className="vendas-texto vendas-texto--pequeno">{T.boletoAjuda}</p>
            ) : null}
          </>
        )}
      </section>
    </PaginaDoVendedor>
  );
}

/** "50.00" → "R$ 50,00" para a frase do recebimento (só formata, não calcula). */
function formatarValor(reais: string): string {
  const c = centavosDeTexto(reais);
  return c === null ? reais : formatarReais(c);
}

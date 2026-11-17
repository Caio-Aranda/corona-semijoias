import { Aviso, Botao } from '@corona/ui';
import { Campo, JanelaDeConfirmacao, Tabela } from '../../componentes-do-vendedor';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import type { LinhaDaMaleta, PreviaDaDevolucao, PreviaDaTroca } from '../../../shared/dados';
import {
  FORMAS_DE_PAGAMENTO_NA_TELA,
  MOTIVOS_DE_DEVOLUCAO,
  type FormaDePagamento,
  type LinhaDaVendaParaEnviar,
  type MotivoDeDevolucao,
  type PecaQueVolta,
} from '../../../shared/operacoes';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao } from '../../chamadas';
import { centavosDeTexto, lerReaisDigitados } from '../../moedas';
import { TEXTOS } from '../../textos';
import { BuscaPorNome } from '../BuscaPorNome';
import { EscolhaDeParcelas, conferirParcelas, parcelasIniciais } from '../EscolhaDeParcelas';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { type Marcacao, PecasQueVoltam, pecasMarcadas, reais, textoDeCentavos } from './apoio';

export interface FormularioDeTrocaProps {
  ponte: PonteDoCorona;
  vendaId: string;
  aoVoltar: () => void;
  aoVerLista: () => void;
}

interface PecaNova {
  linha: LinhaDaMaleta;
  quantidade: number;
  desconto: string;
}

type Forma = FormaDePagamento | 'PRAZO';

/** Desconto digitado → centavos; vazio é zero; formato errado é `null`. */
function centavosDoDigitado(digitado: string): bigint | null {
  if (!digitado.trim()) return 0n;
  const texto = lerReaisDigitados(digitado);
  return texto === null ? null : centavosDeTexto(texto);
}

/**
 * Troca (tarefa 6.8): as peças que voltam (como na devolução, sem a forma do dinheiro) e as que o cliente leva, da
 * maleta conferida. A diferença é prévia; a diferença a pagar vai à vista ou a prazo (dúvida V8). Uma operação só.
 */
export function FormularioDeTroca({
  ponte,
  vendaId,
  aoVoltar,
  aoVerLista,
}: FormularioDeTrocaProps) {
  const T = TEXTOS.devolucao;
  const { cotacao } = useCotacao(ponte);
  const [marcadas, setMarcadas] = useState<Record<string, Marcacao>>({});
  const [motivo, setMotivo] = useState<MotivoDeDevolucao>('TAMANHO');
  const [observacao, setObservacao] = useState('');
  const [novas, setNovas] = useState<PecaNova[]>([]);
  const [codigo, setCodigo] = useState('');
  const [lido, setLido] = useState<string | null>(null);
  const [forma, setForma] = useState<Forma>('DINHEIRO');
  const [valorPago, setValorPago] = useState('');
  // Só quando sobra da diferença a prazo (tarefa 7.11).
  const [parcelas, setParcelas] = useState(parcelasIniciais);
  const [previaA, setPreviaA] = useState<PreviaDaDevolucao | null>(null);
  const [previa, setPrevia] = useState<PreviaDaTroca | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [problema, setProblema] = useState<string | null>(null);
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [feito, setFeito] = useState<string | null>(null);
  // Todos os ids da troca nascem aqui e não mudam ao tentar de novo (nada é gravado duas vezes).
  const ids = useRef({
    troca: novoId(),
    credito: novoId(),
    pagamentoDoCredito: novoId(),
    devolucao: novoId(),
    venda: novoId(),
    pagamento: novoId(),
  });

  const defeito = motivo === 'DEFEITO';
  const voltam = pecasMarcadas(marcadas).map((p) =>
    defeito ? { ...p, estado: 'DEFEITO' as const } : p,
  );
  const descontos = novas.map((n) => centavosDoDigitado(n.desconto));
  const levam: LinhaDaVendaParaEnviar[] = novas.map((n, i) => ({
    produtoId: n.linha.produto.id,
    quantidade: n.quantidade,
    ...((descontos[i] ?? 0n) > 0n ? { desconto: textoDeCentavos(descontos[i] ?? 0n) } : {}),
  }));
  const chave = JSON.stringify({ voltam, levam });

  useEffect(() => {
    let ativo = true;
    const { voltam: v, levam: l } = JSON.parse(chave) as {
      voltam: PecaQueVolta[];
      levam: LinhaDaVendaParaEnviar[];
    };
    const devolucao = {
      vendaId,
      motivo,
      ...(observacao.trim() ? { observacao } : {}),
      itens: v,
    };
    void Promise.all([
      chamar<PreviaDaDevolucao>(ponte, { operacao: 'devolucoes.previa', ...devolucao }),
      chamar<PreviaDaTroca>(ponte, { operacao: 'trocas.previa', devolucao, itens: l }),
    ]).then(([a, t]) => {
      if (!ativo) return;
      if (a.ok) setPreviaA(a.dados);
      else setErro(a.mensagem);
      if (t.ok) setPrevia(t.dados);
    });
    return () => {
      ativo = false;
    };
  }, [ponte, vendaId, motivo, observacao, chave]);

  const diferenca = previa ? (centavosDeTexto(previa.diferenca) ?? 0n) : 0n;
  let pago: bigint | null = 0n;
  if (diferenca > 0n && forma !== 'PRAZO')
    pago = valorPago.trim() === '' ? diferenca : centavosDoDigitado(valorPago);

  // O que fica a prazo da diferença: só então se escolhem as parcelas (a tela não calcula parcela).
  const sobraAPrazo = diferenca > 0n && pago !== null && pago < diferenca;

  function aceitar(linha: LinhaDaMaleta) {
    setLido(linha.produto.nome);
    setNovas((atual) =>
      atual.some((n) => n.linha.produto.id === linha.produto.id)
        ? atual.map((n) =>
            n.linha.produto.id === linha.produto.id && n.quantidade < linha.saldo
              ? { ...n, quantidade: n.quantidade + 1 }
              : n,
          )
        : [...atual, { linha, quantidade: 1, desconto: '' }],
    );
  }

  async function ler(e: FormEvent) {
    e.preventDefault();
    const texto = codigo.trim();
    if (!texto || !previa?.remessaId) return;
    const r = await chamar<LinhaDaMaleta>(ponte, {
      operacao: 'maletas.peca',
      id: previa.remessaId,
      codigo: texto,
    });
    setCodigo('');
    if (!r.ok) {
      setProblema(r.mensagem);
      return;
    }
    setProblema(null);
    aceitar(r.dados);
  }

  function pedirConfirmacao() {
    let falta: string | null = null;
    if (voltam.length === 0) falta = T.faltaPeca;
    else if (novas.length === 0) falta = T.faltaPecaNova;
    else if (motivo === 'OUTRO' && !observacao.trim()) falta = T.faltaObservacao;
    else if (descontos.some((d) => d === null)) falta = TEXTOS.venda.descontoInvalido;
    else if (pago === null) falta = TEXTOS.venda.valorPagoInvalido;
    else if (diferenca > 0n && pago > diferenca) falta = TEXTOS.venda.pagoMaiorQueTotal;
    else if (sobraAPrazo) {
      const p = conferirParcelas(parcelas);
      if (!p.ok) falta = p.texto;
    }
    setProblema(falta);
    if (!falta) setConfirmando(true);
  }

  async function registrar() {
    if (!previa?.remessaId || pago === null) return;
    setEnviando(true);
    const pagamentos =
      diferenca > 0n && forma !== 'PRAZO' && pago > 0n
        ? [{ id: ids.current.pagamento, valor: textoDeCentavos(pago), forma }]
        : [];
    const escolha = sobraAPrazo ? conferirParcelas(parcelas) : null;
    const r = await chamar(ponte, {
      operacao: 'trocas.registrar',
      troca: {
        id: ids.current.troca,
        creditoId: ids.current.credito,
        pagamentoDoCreditoId: ids.current.pagamentoDoCredito,
        devolucao: {
          id: ids.current.devolucao,
          vendaId,
          motivo,
          ...(observacao.trim() ? { observacao: observacao.trim() } : {}),
          itens: voltam,
        },
        venda: {
          id: ids.current.venda,
          remessaId: previa.remessaId,
          itens: levam,
          pagamentos,
          ...(escolha?.ok ? { parcelamento: escolha.parcelamento } : {}),
        },
      },
    });
    setEnviando(false);
    setConfirmando(false);
    if (!r.ok) {
      setProblema(r.mensagem);
      return;
    }
    const cliente = previaA?.clienteNome ?? '';
    setFeito(
      diferenca > 0n && pagamentos.length > 0
        ? T.receba(reais(textoDeCentavos(pago)))
        : diferenca < 0n
          ? T.ficouDeCredito(cliente, reais(textoDeCentavos(-diferenca)))
          : T.trocaRegistrada,
    );
  }

  const titulo = T.tituloDaTroca(previaA?.clienteNome ?? '');
  if (feito) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={titulo} aoVoltar={aoVoltar}>
        <Aviso tipo="sucesso">{feito}</Aviso>
        <Botao variante="primario" grande onClick={aoVerLista}>
          {T.minhas}
        </Botao>
      </PaginaDoVendedor>
    );
  }
  if (!previaA || !previa) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={T.titulo} aoVoltar={aoVoltar}>
        {erro ? (
          <Aviso tipo="erro">{erro}</Aviso>
        ) : (
          <p className="vendas-texto" aria-busy="true">
            {TEXTOS.carregando}
          </p>
        )}
      </PaginaDoVendedor>
    );
  }

  const podeRegistrar =
    (previaA.dentroDoPrazo || previaA.autorizacao !== null) && previa.remessaId !== null;
  const textoDaDiferenca =
    diferenca > 0n
      ? T.diferencaAPagar(reais(previa.diferenca))
      : diferenca < 0n
        ? T.ficaDeCredito(reais(textoDeCentavos(-diferenca)))
        : T.semDiferenca;

  return (
    <PaginaDoVendedor ponte={ponte} titulo={titulo} aoVoltar={aoVoltar}>
      {problema ? <Aviso tipo="erro">{problema}</Aviso> : null}
      {previaA.problema ? <Aviso tipo="atencao">{previaA.problema}</Aviso> : null}

      <h2 className="vendas-subtitulo">{T.voltam}</h2>
      <PecasQueVoltam
        ponte={ponte}
        pecas={previaA.pecas}
        marcadas={marcadas}
        defeitoObrigatorio={defeito}
        aoMudar={(id, m) => setMarcadas((atual) => ({ ...atual, [id]: m }))}
      />
      <div className="cs-campo">
        <label htmlFor="motivo-da-troca">{T.motivo}</label>
        <select
          id="motivo-da-troca"
          value={motivo}
          onChange={(e) => setMotivo(e.target.value as MotivoDeDevolucao)}
        >
          {MOTIVOS_DE_DEVOLUCAO.map((m) => (
            <option key={m} value={m}>
              {T.motivos[m]}
            </option>
          ))}
        </select>
      </div>
      <Campo
        rotulo={T.observacao}
        value={observacao}
        maxLength={400}
        onChange={(e) => setObservacao(e.target.value)}
        autoComplete="off"
      />

      <h2 className="vendas-subtitulo">{T.levam}</h2>
      {previa.remessaId === null ? (
        <Aviso tipo="informacao">{T.semMaletaConferida}</Aviso>
      ) : (
        <>
          <form className="vendas-leitor" onSubmit={(e) => void ler(e)}>
            <Campo
              rotulo={T.lerNova}
              value={codigo}
              onChange={(e) => setCodigo(e.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
            <Botao type="submit" variante="primario" disabled={!codigo.trim()}>
              {T.lerAgora}
            </Botao>
          </form>
          {lido ? <p className="vendas-texto">{lido}</p> : null}
          <BuscaPorNome ponte={ponte} maletaId={previa.remessaId} aoEscolher={aceitar} />
          {novas.length > 0 ? (
            <Tabela
              legenda={T.levam}
              colunas={[
                { chave: 'peca', titulo: T.colunas.peca },
                { chave: 'qtd', titulo: T.quantidade, numerica: true },
                { chave: 'preco', titulo: TEXTOS.venda.preco, numerica: true },
                { chave: 'desconto', titulo: T.desconto },
                { chave: 'acoes', titulo: '' },
              ]}
              linhas={novas.map((n, i) => ({
                peca: n.linha.produto.nome,
                qtd: n.quantidade,
                preco: <Dinheiro reais={n.linha.precoUnitario} cotacao={cotacao} />,
                desconto: (
                  <input
                    className="vendas-desconto"
                    aria-label={T.descontoDe(n.linha.produto.nome)}
                    inputMode="decimal"
                    value={n.desconto}
                    aria-invalid={descontos[i] === null ? true : undefined}
                    onChange={(e) =>
                      setNovas((atual) =>
                        atual.map((x) =>
                          x.linha.produto.id === n.linha.produto.id
                            ? { ...x, desconto: e.target.value }
                            : x,
                        ),
                      )
                    }
                  />
                ),
                acoes: (
                  <Botao
                    aria-label={T.tirar(n.linha.produto.nome)}
                    onClick={() =>
                      setNovas((atual) =>
                        atual.filter((x) => x.linha.produto.id !== n.linha.produto.id),
                      )
                    }
                  >
                    {TEXTOS.venda.tirar}
                  </Botao>
                ),
              }))}
            />
          ) : null}
        </>
      )}

      <section className="vendas-cartao vendas-cartao--larga" aria-label={T.valorNovo}>
        <p className="vendas-texto">
          {T.valorQueVolta}: <Dinheiro reais={previa.valorQueVolta} cotacao={cotacao} />
        </p>
        {previa.abatido !== '0.00' ? (
          <p className="vendas-texto">
            {T.abatido}: <Dinheiro reais={previa.abatido} cotacao={cotacao} />
          </p>
        ) : null}
        <p className="vendas-texto">
          {T.valorNovo}: <Dinheiro reais={previa.valorNovo} cotacao={cotacao} />
        </p>
        <p className="vendas-total">{textoDaDiferenca}</p>
        <p className="vendas-texto vendas-texto--pequeno">{T.previa}</p>
      </section>

      {diferenca > 0n ? (
        <fieldset className="vendas-pagamento">
          <legend>{T.comoPagouADiferenca}</legend>
          {FORMAS_DE_PAGAMENTO_NA_TELA.map((f) => (
            <label key={f}>
              <input
                type="radio"
                name="forma-da-diferenca"
                checked={forma === f}
                onChange={() => setForma(f)}
              />{' '}
              {TEXTOS.venda.formas[f]}
            </label>
          ))}
          <label>
            <input
              type="radio"
              name="forma-da-diferenca"
              checked={forma === 'PRAZO'}
              onChange={() => setForma('PRAZO')}
            />{' '}
            {TEXTOS.venda.aPrazo}
          </label>
          {forma !== 'PRAZO' ? (
            <Campo
              rotulo={T.valorPago}
              value={valorPago}
              placeholder={reais(previa.diferenca).replace('R$ ', '')}
              onChange={(e) => setValorPago(e.target.value)}
              inputMode="decimal"
              autoComplete="off"
            />
          ) : null}
        </fieldset>
      ) : null}

      {sobraAPrazo ? <EscolhaDeParcelas valor={parcelas} aoMudar={setParcelas} /> : null}

      <div className="vendas-rodape">
        <Botao onClick={aoVoltar}>{T.cancelar}</Botao>
        {podeRegistrar ? (
          <Botao variante="primario" grande onClick={pedirConfirmacao}>
            {T.registrarTroca}
          </Botao>
        ) : null}
      </div>

      {confirmando ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarTrocaTitulo}
          rotuloConfirmar={T.registrarTroca}
          rotuloCancelar={TEXTOS.voltar}
          ocupado={enviando}
          aoConfirmar={() => void registrar()}
          aoCancelar={() => setConfirmando(false)}
        >
          <p>
            {T.confirmarTrocaTexto(
              voltam.reduce((t, v) => t + v.quantidade, 0),
              novas.reduce((t, n) => t + n.quantidade, 0),
            )}
          </p>
          <p>{textoDaDiferenca}</p>
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}

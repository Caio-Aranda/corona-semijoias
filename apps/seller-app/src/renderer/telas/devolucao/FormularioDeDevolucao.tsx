import { Aviso, Botao, Campo, JanelaDeConfirmacao } from '@corona/ui';
import { useEffect, useRef, useState } from 'react';
import type { PreviaDaDevolucao } from '../../../shared/dados';
import {
  FORMAS_DE_PAGAMENTO_NA_TELA,
  MOTIVOS_DE_DEVOLUCAO,
  type FormaDePagamento,
  type MotivoDeDevolucao,
  type PecaQueVolta,
} from '../../../shared/operacoes';
import type { PonteDoCorona } from '../../../shared/ponte';
import { chamar, Dinheiro, novoId, useCotacao } from '../../chamadas';
import { TEXTOS } from '../../textos';
import { PaginaDoVendedor } from '../PaginaDoVendedor';
import { diaEMes, type Marcacao, PecasQueVoltam, pecasMarcadas, reais } from './apoio';

export interface FormularioDeDevolucaoProps {
  ponte: PonteDoCorona;
  vendaId: string;
  aoVoltar: () => void;
  aoVerLista: () => void;
}

/**
 * Devolução (tarefa 6.8): por peça, quantas voltam e o estado; o motivo, a observação e como o dinheiro foi devolvido.
 * O valor é sempre prévia (o computador nunca manda valor). Fora do prazo, o botão some e aparece "Pedir autorização à
 * dona"; com a autorização aprovada, a devolução a usa sozinha.
 */
export function FormularioDeDevolucao({
  ponte,
  vendaId,
  aoVoltar,
  aoVerLista,
}: FormularioDeDevolucaoProps) {
  const T = TEXTOS.devolucao;
  const { cotacao } = useCotacao(ponte);
  const [marcadas, setMarcadas] = useState<Record<string, Marcacao>>({});
  const [motivo, setMotivo] = useState<MotivoDeDevolucao>('ARREPENDIMENTO');
  const [observacao, setObservacao] = useState('');
  const [forma, setForma] = useState<FormaDePagamento>('DINHEIRO');
  const [previa, setPrevia] = useState<PreviaDaDevolucao | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const [problema, setProblema] = useState<string | null>(null);
  const [confirmando, setConfirmando] = useState<'devolucao' | 'pedido' | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [feito, setFeito] = useState<string | null>(null);
  // O mesmo pedido reenviado tem o mesmo id: nada é gravado duas vezes.
  const idDaDevolucao = useRef(novoId());
  const idDoPedido = useRef(novoId());

  const defeito = motivo === 'DEFEITO';
  const itens = pecasMarcadas(marcadas).map((p) =>
    defeito ? { ...p, estado: 'DEFEITO' as const } : p,
  );
  const chaveDosItens = JSON.stringify(itens);

  useEffect(() => {
    let ativo = true;
    void chamar<PreviaDaDevolucao>(ponte, {
      operacao: 'devolucoes.previa',
      vendaId,
      motivo,
      ...(observacao.trim() ? { observacao } : {}),
      itens: JSON.parse(chaveDosItens) as PecaQueVolta[],
    }).then((r) => {
      if (!ativo) return;
      if (r.ok) {
        setPrevia(r.dados);
        setErro(null);
      } else setErro(r.mensagem);
    });
    return () => {
      ativo = false;
    };
    // A chave em texto das peças evita pedir a prévia de novo sem mudança.
  }, [ponte, vendaId, motivo, observacao, chaveDosItens]);

  function conferir(): string | null {
    if (itens.length === 0) return T.faltaPeca;
    if (motivo === 'OUTRO' && !observacao.trim()) return T.faltaObservacao;
    return null;
  }

  function pedirConfirmacao(qual: 'devolucao' | 'pedido') {
    const falta = conferir();
    setProblema(falta);
    if (!falta) setConfirmando(qual);
  }

  async function registrar() {
    setEnviando(true);
    const base = {
      vendaId,
      motivo,
      ...(observacao.trim() ? { observacao: observacao.trim() } : {}),
      itens,
    };
    const r =
      confirmando === 'pedido'
        ? await chamar(ponte, {
            operacao: 'autorizacoes.pedir',
            pedido: { id: idDoPedido.current, ...base },
          })
        : await chamar(ponte, {
            operacao: 'devolucoes.registrar',
            devolucao: { id: idDaDevolucao.current, ...base, formaReembolso: forma },
          });
    setEnviando(false);
    const qual = confirmando;
    setConfirmando(null);
    if (!r.ok) {
      setProblema(r.mensagem);
      return;
    }
    setFeito(qual === 'pedido' ? T.pedido : T.registrada);
  }

  const titulo = T.tituloDaDevolucao(previa?.clienteNome ?? '');
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
  if (!previa) {
    return (
      <PaginaDoVendedor ponte={ponte} titulo={TEXTOS.devolucao.titulo} aoVoltar={aoVoltar}>
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

  const podeRegistrar = previa.dentroDoPrazo || previa.autorizacao !== null;
  const temCredito = previa.emCredito !== '0.00';
  const temAbatido = previa.abatido !== '0.00';
  return (
    <PaginaDoVendedor ponte={ponte} titulo={titulo} aoVoltar={aoVoltar}>
      {problema ? <Aviso tipo="erro">{problema}</Aviso> : null}
      {previa.problema ? <Aviso tipo="atencao">{previa.problema}</Aviso> : null}
      {previa.autorizacao ? (
        <Aviso tipo="sucesso">{T.aprovada(diaEMes(previa.autorizacao.validaAte))}</Aviso>
      ) : null}
      <p className="vendas-texto vendas-texto--pequeno">
        {T.garantia(diaEMes(previa.desistenciaAte), diaEMes(previa.defeitoAte))}
      </p>

      <PecasQueVoltam
        ponte={ponte}
        pecas={previa.pecas}
        marcadas={marcadas}
        destinos={previa.destinos}
        defeitoObrigatorio={defeito}
        aoMudar={(id, m) => setMarcadas((atual) => ({ ...atual, [id]: m }))}
      />

      <div className="cs-campo">
        <label htmlFor="motivo-da-devolucao">{T.motivo}</label>
        <select
          id="motivo-da-devolucao"
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

      <fieldset className="vendas-pagamento">
        <legend>{T.comoDevolveu}</legend>
        {FORMAS_DE_PAGAMENTO_NA_TELA.map((f) => (
          <label key={f}>
            <input
              type="radio"
              name="forma-do-reembolso"
              checked={forma === f}
              onChange={() => setForma(f)}
            />{' '}
            {TEXTOS.venda.formas[f]}
          </label>
        ))}
      </fieldset>

      <section className="vendas-cartao vendas-cartao--larga" aria-label={T.valor}>
        <p className="vendas-total">
          {T.valor}: <Dinheiro reais={previa.valor} cotacao={cotacao} />
        </p>
        {temAbatido ? (
          <p className="vendas-texto">
            {T.abatido}: <Dinheiro reais={previa.abatido} cotacao={cotacao} />
          </p>
        ) : null}
        <p className="vendas-texto">
          {T.emDinheiro}: <Dinheiro reais={previa.emDinheiro} cotacao={cotacao} />
        </p>
        {temCredito ? (
          <p className="vendas-texto">
            {T.emCredito}: <Dinheiro reais={previa.emCredito} cotacao={cotacao} /> {T.creditoDepois}
          </p>
        ) : null}
        <p className="vendas-texto vendas-texto--pequeno">{T.previa}</p>
      </section>

      <div className="vendas-rodape">
        <Botao onClick={aoVoltar}>{T.cancelar}</Botao>
        {podeRegistrar ? (
          <Botao variante="primario" grande onClick={() => pedirConfirmacao('devolucao')}>
            {T.registrar}
          </Botao>
        ) : previa.podePedirAutorizacao ? (
          <Botao variante="primario" grande onClick={() => pedirConfirmacao('pedido')}>
            {T.pedir}
          </Botao>
        ) : null}
      </div>

      {confirmando === 'devolucao' ? (
        <JanelaDeConfirmacao
          titulo={T.confirmarTitulo}
          rotuloConfirmar={T.registrar}
          rotuloCancelar={TEXTOS.voltar}
          ocupado={enviando}
          aoConfirmar={() => void registrar()}
          aoCancelar={() => setConfirmando(null)}
        >
          <p>
            {T.confirmarTexto(
              itens.reduce((t, i) => t + i.quantidade, 0),
              reais(previa.valor),
            )}
          </p>
        </JanelaDeConfirmacao>
      ) : null}
      {confirmando === 'pedido' ? (
        <JanelaDeConfirmacao
          titulo={T.pedirTitulo}
          rotuloConfirmar={T.pedir}
          rotuloCancelar={TEXTOS.voltar}
          ocupado={enviando}
          aoConfirmar={() => void registrar()}
          aoCancelar={() => setConfirmando(null)}
        >
          <p>{T.pedirTexto}</p>
        </JanelaDeConfirmacao>
      ) : null}
    </PaginaDoVendedor>
  );
}

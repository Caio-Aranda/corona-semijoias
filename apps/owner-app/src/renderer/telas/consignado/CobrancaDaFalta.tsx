import { Botao, Campo, JanelaDeConfirmacao } from '@corona/ui';
import { useRef, useState } from 'react';
import { FORMAS_DA_COBRANCA, type FormaDaCobranca } from '../../../shared/consignado';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, type ErroDaTela } from '../../nucleo/chamadas';
import { useCotacao } from '../../nucleo/cotacao';
import { formatarNaMoeda, type MoedaDoRecebimento } from '../../nucleo/moedas';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { dataNaTela } from '../cadastros/definicoes';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { CampoDeValorRecebido, valorDoRecebimento } from '../financeiro/CampoDeValorRecebido';
import { Dinheiro } from '../maletas/Dinheiro';
import type { LeituraDoFechamentoLida } from './dados';

const T = TEXTOS_DE_CONSIGNADO.fechamento.receber;
type Pendencia = LeituraDoFechamentoLida['pendencias'][number];

/**
 * Uma falta cobrada do representante (§23.10, item 1a): quanto foi pago e quanto falta, o botão "Registrar pagamento" (valor,
 * moeda e forma; o sistema grava em Real) e os pagamentos, cada um com "Desfazer pagamento" (pede o motivo).
 */
export function CobrancaDaFalta({
  ponte,
  pendencia,
  aoMudar,
  aoAvisar,
}: {
  ponte: PonteDoCorona;
  pendencia: Pendencia;
  aoMudar: () => void;
  /** O aviso fica na tela do fechamento: ele continua lá quando o relatório é buscado de novo. */
  aoAvisar: (texto: string) => void;
}) {
  const cotacao = useCotacao();
  const [abrindo, setAbrindo] = useState(false);
  const [digitado, setDigitado] = useState('');
  const [moeda, setMoeda] = useState<MoedaDoRecebimento>('REAL');
  const [forma, setForma] = useState<FormaDaCobranca>('DINHEIRO');
  const [tentou, setTentou] = useState(false);
  const [ocupado, setOcupado] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [desfazendo, setDesfazendo] = useState<string | null>(null);
  const [motivo, setMotivo] = useState('');
  const [tentouDesfazer, setTentouDesfazer] = useState(false);
  // Um id por pagamento e por estorno: reenviar depois de uma queda da internet não duplica.
  const idDoPagamento = useRef(crypto.randomUUID());
  const idDoEstorno = useRef(crypto.randomUUID());

  const emMoeda: MoedaDoRecebimento = cotacao.taxas ? moeda : 'REAL';
  const valor = valorDoRecebimento(digitado, emMoeda);
  const faltaPagar = Number(pendencia.faltaPagar) > 0;

  async function registrar() {
    setTentou(true);
    if (!valor || ocupado) return;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'cobranca.pagar', {
      id: pendencia.id,
      pagamentoId: idDoPagamento.current,
      forma,
      ...valor,
      ...('moeda' in valor && cotacao.id ? { cotacaoId: cotacao.id } : {}),
    });
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      if (r.erro.codigo === 'COTACAO_DO_RECEBIMENTO_MUDOU') cotacao.recarregar();
      return;
    }
    idDoPagamento.current = crypto.randomUUID();
    setAbrindo(false);
    setDigitado('');
    setTentou(false);
    aoAvisar(T.registrado);
    aoMudar();
  }

  async function desfazer() {
    setTentouDesfazer(true);
    if (!desfazendo || motivo.trim() === '' || ocupado) return;
    setOcupado(true);
    setErro(null);
    const r = await chamar(ponte, 'cobranca.desfazer', {
      id: desfazendo,
      estornoId: idDoEstorno.current,
      motivo: motivo.trim(),
    });
    setOcupado(false);
    if (!r.ok) {
      setErro(r.erro);
      setDesfazendo(null);
      return;
    }
    idDoEstorno.current = crypto.randomUUID();
    setDesfazendo(null);
    setMotivo('');
    setTentouDesfazer(false);
    aoAvisar(T.desfeitoAgora);
    aoMudar();
  }

  return (
    <div className="gestao-cobranca-da-falta">
      <dl className="gestao-resumo">
        <div>
          <dt>{T.pagoRotulo}</dt>
          <dd>
            <Dinheiro valor={pendencia.pago} />
          </dd>
        </div>
        <div>
          <dt>{T.faltaRotulo}</dt>
          <dd>
            <Dinheiro valor={pendencia.faltaPagar} />
          </dd>
        </div>
      </dl>
      {pendencia.pagamentos.length > 0 ? (
        <ul>
          {pendencia.pagamentos.map((g) => {
            const naMoeda =
              g.moeda !== 'REAL' && g.valorNaMoeda
                ? formatarNaMoeda(g.valorNaMoeda, g.moeda)
                : null;
            return (
              <li key={g.id}>
                {T.pagamento(dataNaTela(g.data), T.formas[g.forma] ?? g.forma)}{' '}
                {naMoeda ? `${naMoeda} = ` : null}
                <Dinheiro valor={g.valor} />
                {g.desfeito ? (
                  <span> · {T.desfeito(g.motivoDoEstorno)}</span>
                ) : (
                  <>
                    {' '}
                    <Botao
                      aria-label={`${T.desfazer}: ${dataNaTela(g.data)}`}
                      disabled={ocupado}
                      onClick={() => {
                        setDesfazendo(g.id);
                        setMotivo('');
                        setTentouDesfazer(false);
                      }}
                    >
                      {T.desfazer}
                    </Botao>
                  </>
                )}
              </li>
            );
          })}
        </ul>
      ) : null}
      {erro ? <AvisoDeErro erro={erro} /> : null}
      {faltaPagar && !abrindo ? (
        <Botao
          aria-label={T.registrarDe(pendencia.nome)}
          onClick={() => {
            setAbrindo(true);
            setDigitado(pendencia.faltaPagar.replace('.', ','));
            setMoeda('REAL');
          }}
        >
          {T.registrar}
        </Botao>
      ) : null}
      {abrindo ? (
        <div className="gestao-formulario">
          <CampoDeValorRecebido
            id={`pagamento-${pendencia.id}`}
            rotulo={T.valor}
            moeda={emMoeda}
            digitado={digitado}
            aoMudar={(d, m) => {
              setDigitado(d);
              setMoeda(m);
            }}
            cotacao={cotacao.taxas}
            {...(tentou && !valor ? { erro: T.valorInvalido } : {})}
          />
          <CampoDeEscolha
            id={`forma-${pendencia.id}`}
            rotulo={T.forma}
            valor={forma}
            aoMudar={(f) => setForma(f as FormaDaCobranca)}
            opcoes={FORMAS_DA_COBRANCA.map((f) => ({ valor: f, texto: T.formas[f] ?? f }))}
          />
          <div className="gestao-acoes-da-linha">
            <Botao onClick={() => setAbrindo(false)} disabled={ocupado}>
              {T.cancelar}
            </Botao>
            <Botao variante="primario" onClick={() => void registrar()} disabled={ocupado}>
              {ocupado ? T.registrando : T.confirmar}
            </Botao>
          </div>
        </div>
      ) : null}
      {desfazendo ? (
        <JanelaDeConfirmacao
          titulo={T.desfazerTitulo}
          rotuloConfirmar={T.desfazerSim}
          rotuloCancelar={T.voltar}
          perigosa
          ocupado={ocupado}
          aoCancelar={() => setDesfazendo(null)}
          aoConfirmar={() => void desfazer()}
        >
          <p>{T.desfazerTexto}</p>
          <Campo
            id={`motivo-desfazer-${pendencia.id}`}
            rotulo={T.motivo}
            value={motivo}
            maxLength={500}
            onChange={(e) => setMotivo(e.target.value)}
            {...(tentouDesfazer && motivo.trim() === '' ? { erro: T.motivoFalta } : {})}
          />
        </JanelaDeConfirmacao>
      ) : null}
    </div>
  );
}

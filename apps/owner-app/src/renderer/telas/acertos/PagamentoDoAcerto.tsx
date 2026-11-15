import { Aviso, Campo } from '@corona/ui';
import {
  DESTINOS_A_FAVOR_DO_VENDEDOR,
  FORMAS_DO_PAGAMENTO,
  MAXIMO_DE_PARCELAS,
  type FormaDoPagamento,
} from '../../../shared/acertos';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { CampoDeEscolha } from '../devolucoes/Apoio';
import { ValorDoAcerto, realDoAcerto } from './Valor';
import { ehNegativo, ehPositivo, valorDigitado, type PagamentoEmEdicao } from './pagamento';
import type { PreviaDoFechamento } from './previa';

const T = TEXTOS_DE_ACERTOS.pagamento;

const FORMAS = FORMAS_DO_PAGAMENTO.map((f) => ({
  valor: f,
  texto: f === 'PIX' ? T.transferencia : T.dinheiro,
}));

export interface PagamentoDoAcertoProps {
  previa: PreviaDoFechamento;
  pagamento: PagamentoEmEdicao;
  /** `digitando`: a mudança veio de texto digitado (o sistema espera a pessoa parar de digitar). */
  aoMudar: (novo: PagamentoEmEdicao, digitando?: boolean) => void;
}

/** Quanto o vendedor pagou agora, boleto, o que ficou devendo, contas antigas e o acerto a favor do vendedor. */
export function PagamentoDoAcerto({ previa, pagamento, aoMudar }: PagamentoDoAcertoProps) {
  const temRestante = ehPositivo(previa.ficouDevendo) || previa.restanteSemData;
  const aFavor = ehNegativo(previa.aPagarNoAcerto);
  const boletoSemData =
    valorDigitado(pagamento.boleto.digitado) !== null && pagamento.boleto.vencimento === '';
  return (
    <section className="gestao-grupo" aria-labelledby="previa-pagamento">
      <h3 id="previa-pagamento" className="gestao-subtitulo">
        {T.titulo}
      </h3>
      <p className="gestao-texto">{T.ajuda}</p>

      <div className="gestao-formulario gestao-formulario--larga">
        <h4 className="gestao-subtitulo">{T.pagouAgora}</h4>
        {pagamento.recebimentos.map((r, indice) => (
          <CampoDeDinheiro
            key={r.id}
            id={`recebimento-${r.forma}`}
            rotulo={r.forma === 'PIX' ? T.transferencia : T.dinheiro}
            ajuda={T.ajudaDoValor}
            valor={r.digitado}
            aoMudar={(digitado) =>
              aoMudar(
                {
                  ...pagamento,
                  recebimentos: pagamento.recebimentos.map((x, k) =>
                    k === indice ? { ...x, digitado } : x,
                  ),
                },
                true,
              )
            }
          />
        ))}

        <h4 className="gestao-subtitulo">{T.boleto.titulo}</h4>
        <p className="gestao-texto">{T.boleto.ajuda}</p>
        <CampoDeDinheiro
          id="boleto-valor"
          rotulo={T.boleto.valor}
          ajuda={T.ajudaDoValor}
          valor={pagamento.boleto.digitado}
          aoMudar={(digitado) =>
            aoMudar({ ...pagamento, boleto: { ...pagamento.boleto, digitado } }, true)
          }
        />
        <Campo
          type="date"
          rotulo={T.boleto.vencimento}
          value={pagamento.boleto.vencimento}
          {...(boletoSemData ? { erro: T.boleto.semVencimento } : {})}
          onChange={(e) =>
            aoMudar({ ...pagamento, boleto: { ...pagamento.boleto, vencimento: e.target.value } })
          }
        />
      </div>

      {temRestante ? (
        <div className="gestao-formulario gestao-formulario--larga">
          <h4 className="gestao-subtitulo">{T.devendo.titulo}</h4>
          <p className="gestao-texto">{T.devendo.ajuda}</p>
          <CampoDeEscolha
            id="restante-parcelas"
            rotulo={T.devendo.parcelas}
            valor={String(pagamento.parcelas)}
            aoMudar={(v) => aoMudar({ ...pagamento, parcelas: Number(v) })}
            opcoes={Array.from({ length: MAXIMO_DE_PARCELAS }, (_, i) => ({
              valor: String(i + 1),
              texto: i === 0 ? T.devendo.umaParcela : T.devendo.parcelasDe(i + 1),
            }))}
          />
          <Campo
            type="date"
            rotulo={T.devendo.primeira}
            value={pagamento.primeiroVencimento}
            onChange={(e) => aoMudar({ ...pagamento, primeiroVencimento: e.target.value })}
          />
          {previa.restanteSemData ? <Aviso tipo="atencao">{T.devendo.semData}</Aviso> : null}
          {previa.parcelasDoRestante.length > 0 ? (
            <ul>
              {previa.parcelasDoRestante.map((p) => (
                <li key={p.numero}>
                  {T.devendo.parcela(p.numero, realDoAcerto(p.valor), dataParaTela(p.vencimento))}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {previa.contasAbertasDoVendedor.length > 0 ? (
        <div className="gestao-formulario gestao-formulario--larga">
          <h4 className="gestao-subtitulo">{T.antigas.titulo}</h4>
          <p className="gestao-texto">{T.antigas.ajuda}</p>
          {previa.contasAbertasDoVendedor.map((c) => {
            const atual = pagamento.contasAntigas[c.id] ?? {
              id: crypto.randomUUID(),
              forma: 'DINHEIRO' as FormaDoPagamento,
              digitado: '',
            };
            const trocar = (parcial: Partial<typeof atual>, digitando = false) =>
              aoMudar(
                {
                  ...pagamento,
                  contasAntigas: { ...pagamento.contasAntigas, [c.id]: { ...atual, ...parcial } },
                },
                digitando,
              );
            return (
              <div key={c.id} className="gestao-grupo">
                <p className="gestao-texto">
                  {T.antigas.conta(
                    c.numero,
                    c.totalDeParcelas,
                    dataParaTela(c.vencimento),
                    realDoAcerto(c.emAberto),
                  )}
                </p>
                <CampoDeDinheiro
                  id={`conta-${c.id}`}
                  rotulo={T.antigas.pagar(c.numero, c.totalDeParcelas)}
                  ajuda={T.ajudaDoValor}
                  valor={atual.digitado}
                  aoMudar={(digitado) => trocar({ digitado }, true)}
                />
                <CampoDeEscolha
                  id={`conta-forma-${c.id}`}
                  rotulo={T.antigas.como(c.numero)}
                  valor={atual.forma}
                  aoMudar={(v) => trocar({ forma: v as FormaDoPagamento })}
                  opcoes={FORMAS}
                />
              </div>
            );
          })}
        </div>
      ) : null}

      {aFavor ? (
        <div className="gestao-formulario gestao-formulario--larga">
          <h4 className="gestao-subtitulo">{T.aFavor.titulo}</h4>
          <p className="gestao-texto">
            {T.aFavor.deve(realDoAcerto(previa.aPagarNoAcerto.replace(/^-/, '')))}
          </p>
          {DESTINOS_A_FAVOR_DO_VENDEDOR.map((destino) => (
            <label key={destino} className="gestao-opcao">
              <input
                type="radio"
                name="destino-a-favor-do-vendedor"
                checked={pagamento.aFavor === destino}
                onChange={() => aoMudar({ ...pagamento, aFavor: destino })}
              />{' '}
              {destino === 'CREDITO' ? T.aFavor.credito : T.aFavor.pagarAgora}
            </label>
          ))}
          {pagamento.aFavor === 'PAGAR_NA_HORA' ? (
            <CampoDeEscolha
              id="a-favor-forma"
              rotulo={T.aFavor.como}
              valor={pagamento.formaDoPagamento}
              aoMudar={(v) => aoMudar({ ...pagamento, formaDoPagamento: v as FormaDoPagamento })}
              opcoes={FORMAS}
            />
          ) : null}
        </div>
      ) : null}

      <dl className="gestao-totais">
        {(
          [
            [T.resumo.recebido, previa.recebidoNoAcerto],
            [T.resumo.boleto, previa.boleto],
            [T.resumo.ficouDevendo, previa.ficouDevendo],
            [T.resumo.creditoDisponivel, previa.creditoDisponivel],
            [T.resumo.pagoAoVendedor, previa.pagoAoVendedor],
          ] as const
        ).map(([rotulo, valor]) => (
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

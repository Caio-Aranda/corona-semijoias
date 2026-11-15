import { Aviso, Botao } from '@corona/ui';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { PonteDoCorona } from '../../../shared/ponte';
import { AvisoDeErro } from '../../componentes/AvisoDeErro';
import { chamar, useBusca, type ErroDaTela } from '../../nucleo/chamadas';
import { useCotacao } from '../../nucleo/cotacao';
import { dataParaTela } from '../../nucleo/moedas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import {
  ComissaoDaPrevia,
  ImpedimentosDaPrevia,
  PecasDaPrevia,
  TotaisDaPrevia,
} from './BlocosDaPrevia';
import { DecisoesDaPrevia } from './DecisoesDaPrevia';
import { FecharAcerto } from './FecharAcerto';
import { PagamentoDoAcerto } from './PagamentoDoAcerto';
import { ProvedorDaCotacaoDoAcerto } from './Valor';
import { erroDoAcerto, type FichaDoAcerto } from './dados';
import {
  pagamentoIncompleto,
  pagamentoInicial,
  pagamentoParaPedido,
  type PagamentoEmEdicao,
} from './pagamento';
import {
  decisoesCompletas,
  decisoesIniciais,
  lerPrevia,
  pedidoDeDecisoes,
  type Decisoes,
  type PreviaDoFechamento,
} from './previa';

const T = TEXTOS_DE_ACERTOS.previa;

export interface PreviaDoAcertoProps {
  ponte: PonteDoCorona;
  acerto: FichaDoAcerto;
  aoVoltar: () => void;
  /** O acerto foi fechado (ou já estava): a tela mostra a ficha dele. */
  aoFechado: (ficha: FichaDoAcerto, jaEstavaFechado: boolean) => void;
}

/** As decisões e o pagamento como o sistema os recebe; sem pagamento algum, a chave nem vai. */
function pedidoDe(
  decisoes: Decisoes,
  pagamento: PagamentoEmEdicao,
  previa: PreviaDoFechamento | null,
): Record<string, unknown> {
  const pagamentoPronto = pagamentoParaPedido(pagamento, previa);
  return {
    ...pedidoDeDecisoes(decisoes),
    ...(Object.keys(pagamentoPronto).length > 0 ? { pagamento: pagamentoPronto } : {}),
  };
}

/**
 * Conferência do acerto: o esperado contra o contado, as decisões da dona, o pagamento e os números. Os números são sempre os
 * da última prévia que o sistema devolveu; a tela não soma, não multiplica e não arredonda nada.
 */
export function PreviaDoAcerto({ ponte, acerto, aoVoltar, aoFechado }: PreviaDoAcertoProps) {
  const busca = useBusca(
    () => chamar(ponte, 'acertos.previa', { id: acerto.id }),
    `previa-${acerto.id}`,
  );
  const hoje = useCotacao();
  const [previa, setPrevia] = useState<PreviaDoFechamento | null>(null);
  const [decisoes, setDecisoes] = useState<Decisoes | null>(null);
  const [pagamento, setPagamento] = useState<PagamentoEmEdicao>(pagamentoInicial);
  const [calculando, setCalculando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [naoLeu, setNaoLeu] = useState(false);
  const [confirmando, setConfirmando] = useState(false);
  // Só a resposta da última decisão vale: as mais antigas são descartadas.
  const ultimaChamada = useRef(0);
  const espera = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  // O id do fechamento vale enquanto nada mudar: tentar de novo depois de uma falha de rede nunca fecha duas vezes.
  const idDoFechamento = useRef(crypto.randomUUID());
  useEffect(() => () => clearTimeout(espera.current), []);

  useEffect(() => {
    if (!busca.dados) return;
    const lida = lerPrevia(busca.dados);
    if (!lida) {
      setNaoLeu(true);
      return;
    }
    setNaoLeu(false);
    setPrevia(lida);
    // As decisões nascem da prévia sem decisões: cobrem exatamente o que o servidor vai exigir ao fechar.
    setDecisoes((atual) => atual ?? decisoesIniciais(lida));
  }, [busca.dados]);

  const nomes = useMemo(() => {
    const mapa = new Map<string, string>();
    for (const i of previa?.itens ?? []) mapa.set(i.produto.id, i.produto.nome);
    for (const f of previa?.faltas ?? []) mapa.set(f.produto.id, f.produto.nome);
    for (const d of previa?.defeitos ?? []) mapa.set(d.produto.id, d.produto.nome);
    for (const f of decisoes?.faltas ?? []) mapa.set(f.produto.id, f.produto.nome);
    return mapa;
  }, [previa, decisoes]);

  async function pedirPrevia(d: Decisoes, p: PagamentoEmEdicao) {
    const chamada = ++ultimaChamada.current;
    setCalculando(true);
    setErro(null);
    const r = await chamar(ponte, 'acertos.previaComDecisoes', {
      id: acerto.id,
      pedido: pedidoDe(d, p, previa),
    });
    if (chamada !== ultimaChamada.current) return;
    setCalculando(false);
    if (!r.ok) {
      setErro(erroDoAcerto(r.erro));
      return;
    }
    const lida = lerPrevia(r.dados);
    if (!lida) {
      setNaoLeu(true);
      return;
    }
    setNaoLeu(false);
    setPrevia(lida);
  }

  /** Escolha na lista refaz a prévia na hora; texto digitado espera a pessoa parar de digitar. */
  function mudar(d: Decisoes, p: PagamentoEmEdicao, digitando: boolean) {
    setDecisoes(d);
    setPagamento(p);
    idDoFechamento.current = crypto.randomUUID();
    clearTimeout(espera.current);
    ultimaChamada.current += 1;
    if (!decisoesCompletas(d) || pagamentoIncompleto(p)) {
      // Falta um motivo, um vencimento ou o valor está estranho: o sistema ainda não recebe; Fechar fica desligado.
      setCalculando(false);
      return;
    }
    if (digitando) {
      setCalculando(true);
      espera.current = setTimeout(() => void pedirPrevia(d, p), 400);
      return;
    }
    void pedirPrevia(d, p);
  }

  /** Os números mudaram ou apareceu algo que impede: lê a prévia de novo, com as decisões de novo, e outro id. */
  function conferirDeNovo(motivo: ErroDaTela) {
    setConfirmando(false);
    setErro(motivo);
    idDoFechamento.current = crypto.randomUUID();
    setDecisoes(null);
    busca.recarregar();
  }

  const cotacaoDaPrevia =
    previa && hoje.taxas && hoje.data === previa.cotacaoData ? hoje.taxas : null;
  const aguardando =
    decisoes !== null && (!decisoesCompletas(decisoes) || pagamentoIncompleto(pagamento));
  const podeFechar =
    previa !== null &&
    decisoes !== null &&
    !calculando &&
    !aguardando &&
    !naoLeu &&
    previa.podeFechar &&
    !previa.restanteSemData;

  return (
    <section className="gestao-grupo" aria-labelledby="titulo-previa-do-acerto">
      <h2 id="titulo-previa-do-acerto" className="gestao-subtitulo">
        {T.titulo}
      </h2>
      <p className="gestao-texto">{T.ajuda}</p>
      <div className="gestao-acoes-da-linha">
        <Botao onClick={aoVoltar}>{T.voltar}</Botao>
      </div>
      {busca.erro && !previa ? (
        <AvisoDeErro erro={busca.erro} aoTentarDeNovo={busca.recarregar} />
      ) : null}
      {naoLeu ? (
        <div className="gestao-erro">
          <Aviso tipo="erro">{T.naoLeu}</Aviso>
          <Botao onClick={busca.recarregar}>{T.tentarDeNovo}</Botao>
        </div>
      ) : null}
      {erro ? (
        <AvisoDeErro
          erro={erro}
          aoTentarDeNovo={() => decisoes && void pedirPrevia(decisoes, pagamento)}
        />
      ) : null}
      {calculando ? (
        <p role="status" className="gestao-texto">
          {T.calculando}
        </p>
      ) : null}
      {aguardando ? <Aviso tipo="atencao">{T.motivoPendente}</Aviso> : null}
      {previa && decisoes && !naoLeu ? (
        <ProvedorDaCotacaoDoAcerto cotacao={cotacaoDaPrevia}>
          <ImpedimentosDaPrevia previa={previa} nomeDaPeca={(id) => nomes.get(id) ?? ''} />
          <PecasDaPrevia ponte={ponte} previa={previa} />
          <DecisoesDaPrevia
            ponte={ponte}
            previa={previa}
            decisoes={decisoes}
            aoMudar={(novas, digitando = false) => mudar(novas, pagamento, digitando)}
            nomeDaPeca={(id) => nomes.get(id) ?? ''}
          />
          <ComissaoDaPrevia previa={previa} />
          <TotaisDaPrevia previa={previa} calculando={calculando} />
          <PagamentoDoAcerto
            previa={previa}
            pagamento={pagamento}
            aoMudar={(novo, digitando = false) => mudar(decisoes, novo, digitando)}
          />
          <p className="gestao-texto">
            {cotacaoDaPrevia ? T.cotacao.frase(dataParaTela(previa.cotacaoData)) : T.cotacao.sem}
          </p>
          <div className="gestao-acoes-da-linha">
            <Botao
              variante="primario"
              grande
              disabled={!podeFechar}
              onClick={() => setConfirmando(true)}
            >
              {TEXTOS_DE_ACERTOS.fechar.botao}
            </Botao>
          </div>
          {confirmando ? (
            <FecharAcerto
              ponte={ponte}
              acerto={acerto}
              previa={previa}
              idDoFechamento={idDoFechamento.current}
              pedido={pedidoDe(decisoes, pagamento, previa)}
              aoFechar={aoFechado}
              aoPrecisarConferir={conferirDeNovo}
              aoVoltar={() => setConfirmando(false)}
            />
          ) : null}
        </ProvedorDaCotacaoDoAcerto>
      ) : null}
    </section>
  );
}

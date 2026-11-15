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
import { ProvedorDaCotacaoDoAcerto } from './Valor';
import { erroDoAcerto, type FichaDoAcerto } from './dados';
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
}

/**
 * Conferência do acerto: o esperado contra o contado, as decisões da dona e os números. Os números são sempre os da última
 * prévia que o sistema devolveu; a tela não soma, não multiplica e não arredonda nada.
 */
export function PreviaDoAcerto({ ponte, acerto, aoVoltar }: PreviaDoAcertoProps) {
  const busca = useBusca(
    () => chamar(ponte, 'acertos.previa', { id: acerto.id }),
    `previa-${acerto.id}`,
  );
  const hoje = useCotacao();
  const [previa, setPrevia] = useState<PreviaDoFechamento | null>(null);
  const [decisoes, setDecisoes] = useState<Decisoes | null>(null);
  const [calculando, setCalculando] = useState(false);
  const [erro, setErro] = useState<ErroDaTela | null>(null);
  const [naoLeu, setNaoLeu] = useState(false);
  // Só a resposta da última decisão vale: as mais antigas são descartadas.
  const ultimaChamada = useRef(0);
  const espera = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
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

  async function recalcular(novas: Decisoes) {
    const chamada = ++ultimaChamada.current;
    if (!decisoesCompletas(novas)) {
      // Falta o motivo de "A loja assume": o sistema ainda não recebe; os números mostrados ficam como estão.
      setCalculando(false);
      return;
    }
    setCalculando(true);
    setErro(null);
    const r = await chamar(ponte, 'acertos.previaComDecisoes', {
      id: acerto.id,
      pedido: pedidoDeDecisoes(novas),
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
  function mudar(novas: Decisoes, digitando = false) {
    setDecisoes(novas);
    clearTimeout(espera.current);
    if (digitando && decisoesCompletas(novas)) {
      ultimaChamada.current += 1;
      setCalculando(true);
      espera.current = setTimeout(() => void recalcular(novas), 400);
      return;
    }
    void recalcular(novas);
  }

  const cotacaoDaPrevia =
    previa && hoje.taxas && hoje.data === previa.cotacaoData ? hoje.taxas : null;
  const aguardandoMotivo = decisoes !== null && !decisoesCompletas(decisoes);

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
        <AvisoDeErro erro={erro} aoTentarDeNovo={() => decisoes && void recalcular(decisoes)} />
      ) : null}
      {calculando ? (
        <p role="status" className="gestao-texto">
          {T.calculando}
        </p>
      ) : null}
      {aguardandoMotivo ? <Aviso tipo="atencao">{T.motivoPendente}</Aviso> : null}
      {previa && decisoes && !naoLeu ? (
        <ProvedorDaCotacaoDoAcerto cotacao={cotacaoDaPrevia}>
          <ImpedimentosDaPrevia previa={previa} nomeDaPeca={(id) => nomes.get(id) ?? ''} />
          <PecasDaPrevia ponte={ponte} previa={previa} />
          <DecisoesDaPrevia
            ponte={ponte}
            previa={previa}
            decisoes={decisoes}
            aoMudar={mudar}
            nomeDaPeca={(id) => nomes.get(id) ?? ''}
          />
          <ComissaoDaPrevia previa={previa} />
          <TotaisDaPrevia previa={previa} calculando={calculando} />
          <p className="gestao-texto">
            {cotacaoDaPrevia ? T.cotacao.frase(dataParaTela(previa.cotacaoData)) : T.cotacao.sem}
          </p>
        </ProvedorDaCotacaoDoAcerto>
      ) : null}
    </section>
  );
}

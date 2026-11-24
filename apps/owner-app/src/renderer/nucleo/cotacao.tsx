import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { PonteDoCorona } from '../../shared/ponte';
import { chamar, useBusca } from './chamadas';
import { dataParaTela, type CotacaoParaTela } from './moedas';
import { horaEmBrasilia } from './cotacao-da-loja';
import { TEXTOS } from '../textos';

/** O que a API devolve em /api/dona/cotacoes/hoje (campos que a tela usa). */
interface RespostaDaCotacao {
  disponivel?: boolean;
  cotacao?: {
    id?: string;
    dataCotacao?: string;
    dolar?: string;
    guarani?: string;
    deDiaAnterior?: boolean;
    origem?: string;
    vigenteDesde?: string;
    guaranisPorReal?: string | null;
  };
}

export interface CotacaoDoDia {
  /** Taxas para converter. Nulo enquanto não houver cotação. */
  taxas: CotacaoParaTela | null;
  /** O id da cotação em uso (o recebimento o manda de volta: se o sistema usar outra, ele recusa e nada é gravado). */
  id: string | null;
  /** A cotação em uso foi digitada pela dona (e não buscada do Banco Central). */
  daLoja: boolean;
  /** Busca a cotação de novo (depois de a dona mudar, ou de o sistema avisar que ela mudou). */
  recarregar: () => void;
  /** "Cotação do Banco Central de 06/11/2026." (ou a versão "a última publicada"). */
  linha: string;
  /** Data da cotação em uso (AAAA-MM-DD), para os arquivos dos relatórios. */
  data: string | null;
  /** A de hoje não chegou e vale a de um dia anterior. */
  deDiaAnterior: boolean;
  carregando: boolean;
}

const SEM_COTACAO: CotacaoDoDia = {
  taxas: null,
  id: null,
  daLoja: false,
  recarregar: () => undefined,
  linha: TEXTOS.dinheiro.semCotacao,
  data: null,
  deDiaAnterior: false,
  carregando: false,
};

const Contexto = createContext<CotacaoDoDia>(SEM_COTACAO);

/** Lê a resposta da rota "cotação do dia". Qualquer coisa fora do formato vira "sem cotação". */
export function lerCotacao(bruto: unknown): CotacaoDoDia {
  const r = (typeof bruto === 'object' && bruto !== null ? bruto : {}) as RespostaDaCotacao;
  const c = r.cotacao;
  if (r.disponivel === false || !c?.dolar || !c.guarani || !c.dataCotacao) return SEM_COTACAO;
  const data = dataParaTela(c.dataCotacao);
  const deDiaAnterior = c.deDiaAnterior === true;
  const daLoja = c.origem === 'DONA';
  return {
    taxas: {
      dolar: c.dolar,
      guarani: c.guarani,
      ...(daLoja && c.guaranisPorReal ? { guaranisPorReal: c.guaranisPorReal } : {}),
    },
    id: c.id ?? null,
    daLoja,
    recarregar: () => undefined,
    linha: daLoja
      ? TEXTOS.dinheiro.cotacaoDaLoja(data, horaEmBrasilia(c.vigenteDesde ?? ''))
      : deDiaAnterior
        ? TEXTOS.dinheiro.cotacaoDeDiaAnterior(data)
        : TEXTOS.dinheiro.cotacaoDoDia(data),
    data: c.dataCotacao,
    deDiaAnterior,
    carregando: false,
  };
}

/** Busca a cotação do dia uma vez e a oferece a toda a tela (campos de dinheiro e rodapé). */
export function ProvedorDaCotacao({
  ponte,
  children,
}: {
  ponte: PonteDoCorona;
  children: ReactNode;
}) {
  const busca = useBusca(() => chamar(ponte, 'cotacao.hoje'), 'cotacao');
  const { recarregar } = busca;
  const valor = useMemo<CotacaoDoDia>(
    () =>
      busca.dados === null
        ? { ...SEM_COTACAO, recarregar, carregando: busca.carregando }
        : { ...lerCotacao(busca.dados), recarregar },
    [busca.dados, busca.carregando, recarregar],
  );
  return <Contexto.Provider value={valor}>{children}</Contexto.Provider>;
}

export function useCotacao(): CotacaoDoDia {
  return useContext(Contexto);
}

/** Linha da cotação embaixo das telas de valores (glossário, parte 11). */
export function RodapeDaCotacao() {
  const { linha, carregando } = useCotacao();
  if (carregando) return null;
  return <p className="gestao-cotacao">{linha}</p>;
}

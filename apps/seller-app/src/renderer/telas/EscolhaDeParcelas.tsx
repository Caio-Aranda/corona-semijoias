import { Campo } from '../componentes-do-vendedor';
import { maisMeses, MESES_ATE_O_PRIMEIRO_VENCIMENTO } from '../../shared/datas-das-parcelas';
import { diaEmBrasilia } from '../../shared/garantia';
import { MAXIMO_DE_PARCELAS, type ParcelamentoParaEnviar } from '../../shared/operacoes';
import { TEXTOS } from '../textos';

/**
 * Parcelas da venda a prazo (tarefa 7.11; ADR-0012, item 7). A pessoa escolhe só **em quantas** e **quando vence a
 * primeira**. A tela NUNCA calcula o valor da parcela, a diferença do centavo nem o total: o servidor divide o que ficou
 * em aberto. Sem entrada e sem cheque.
 */
export interface ParcelasDigitadas {
  quantidade: string;
  primeiroVencimento: string;
}

/** Uma parcela, vencendo daqui a um mês (como o servidor faz se nada for escolhido). */
export function parcelasIniciais(): ParcelasDigitadas {
  return { quantidade: '1', primeiroVencimento: maisMeses(diaEmBrasilia(new Date()), 1) };
}

export type ResultadoDasParcelas =
  { ok: true; parcelamento: ParcelamentoParaEnviar } | { ok: false; texto: string };

/** Confere o que a pessoa escolheu e monta o que vai na venda; o texto de erro diz o que fazer. */
export function conferirParcelas(p: ParcelasDigitadas): ResultadoDasParcelas {
  const T = TEXTOS.parcelas;
  const quantidade = /^\d{1,3}$/.test(p.quantidade.trim()) ? Number(p.quantidade.trim()) : 0;
  if (quantidade < 1 || quantidade > MAXIMO_DE_PARCELAS) {
    return { ok: false, texto: T.quantidadeInvalida };
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(p.primeiroVencimento)) {
    return { ok: false, texto: T.vencimentoInvalido };
  }
  const hoje = diaEmBrasilia(new Date());
  if (p.primeiroVencimento < hoje) return { ok: false, texto: T.vencimentoNoPassado };
  if (p.primeiroVencimento > maisMeses(hoje, MESES_ATE_O_PRIMEIRO_VENCIMENTO)) {
    return { ok: false, texto: T.vencimentoLonge };
  }
  return {
    ok: true,
    parcelamento: { quantidade, primeiroVencimento: p.primeiroVencimento },
  };
}

export interface EscolhaDeParcelasProps {
  valor: ParcelasDigitadas;
  aoMudar: (valor: ParcelasDigitadas) => void;
}

export function EscolhaDeParcelas({ valor, aoMudar }: EscolhaDeParcelasProps) {
  const T = TEXTOS.parcelas;
  return (
    <section className="vendas-cartao vendas-cartao--larga" aria-label={T.titulo}>
      <h2 className="vendas-subtitulo">{T.titulo}</h2>
      <p className="vendas-texto vendas-texto--pequeno">{T.ajuda}</p>
      <Campo
        rotulo={T.quantidade}
        type="number"
        min={1}
        max={MAXIMO_DE_PARCELAS}
        step={1}
        value={valor.quantidade}
        onChange={(e) => aoMudar({ ...valor, quantidade: e.target.value })}
        autoComplete="off"
      />
      <Campo
        rotulo={T.primeiroVencimento}
        type="date"
        value={valor.primeiroVencimento}
        onChange={(e) => aoMudar({ ...valor, primeiroVencimento: e.target.value })}
      />
    </section>
  );
}

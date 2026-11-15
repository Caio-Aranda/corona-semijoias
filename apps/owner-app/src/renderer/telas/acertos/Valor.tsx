import { ValorTresMoedas } from '@corona/ui';
import { createContext, useContext, type ReactNode } from 'react';
import {
  centavosDeTexto,
  formatarReais,
  tresMoedas,
  type CotacaoParaTela,
} from '../../nucleo/moedas';

/**
 * A cotação com que o acerto mostra Dólar e Guarani: a que a API devolveu junto do acerto (nunca a do dia, para o acerto
 * fechado). Sem ela, só Real. Tudo é gravado em Real; Dólar e Guarani só aparecem na tela.
 */
export const CotacaoDoAcerto = createContext<CotacaoParaTela | null>(null);

export function ProvedorDaCotacaoDoAcerto({
  cotacao,
  children,
}: {
  cotacao: CotacaoParaTela | null;
  children: ReactNode;
}) {
  return <CotacaoDoAcerto.Provider value={cotacao}>{children}</CotacaoDoAcerto.Provider>;
}

/** "12.50" → "R$ 12,50" (só formata, nunca calcula). Negativo mostra o sinal. */
export function realDoAcerto(valor: string): string {
  const c = centavosDeTexto(valor);
  return c === null ? valor : formatarReais(c);
}

/** O valor que a API mandou, em Real e (se positivo e houver cotação) em Dólar e Guarani. Negativo: só Real, com o sinal. */
export function ValorDoAcerto({ valor }: { valor: string }) {
  const cotacao = useContext(CotacaoDoAcerto);
  const centavos = centavosDeTexto(valor);
  if (centavos === null) return <>{valor}</>;
  if (centavos < 0n) return <>{formatarReais(centavos)}</>;
  const t = tresMoedas(valor, cotacao);
  if (!t) return <>{formatarReais(centavos)}</>;
  return (
    <ValorTresMoedas
      real={t.real}
      {...(t.dolar ? { dolar: t.dolar } : {})}
      {...(t.guarani ? { guarani: t.guarani } : {})}
    />
  );
}

/** "40.00" → "40%"; "37.50" → "37,5%". Só formata o percentual que a API mandou. */
export function percentualDoAcerto(texto: string): string {
  const limpo = texto.includes('.') ? texto.replace(/0+$/, '').replace(/\.$/, '') : texto;
  return `${limpo.replace('.', ',')}%`;
}

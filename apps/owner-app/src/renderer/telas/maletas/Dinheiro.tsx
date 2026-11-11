import { ValorTresMoedas } from '@corona/ui';
import { useCotacao } from '../../nucleo/cotacao';
import { centavosDeTexto, formatarReais, tresMoedas } from '../../nucleo/moedas';

/** Centavos como o texto da API ("12.50"). Só para valores de zero em diante. */
function centavosEmTexto(c: bigint): string {
  return `${c / 100n}.${String(c % 100n).padStart(2, '0')}`;
}

/**
 * Valor em Real (texto "12.50" ou centavos) e, quando há cotação, em Dólar e Guarani (ADR-0006).
 * Valor negativo (ex.: passou do limite) aparece só em Real, com o sinal.
 */
export function Dinheiro({ valor }: { valor: string | bigint }) {
  const { taxas } = useCotacao();
  const centavos = typeof valor === 'bigint' ? valor : centavosDeTexto(valor);
  if (centavos === null) return <>{String(valor)}</>;
  if (centavos < 0n) return <>{formatarReais(centavos)}</>;
  const t = tresMoedas(centavosEmTexto(centavos), taxas);
  if (!t) return <>{formatarReais(centavos)}</>;
  return (
    <ValorTresMoedas
      real={t.real}
      {...(t.dolar ? { dolar: t.dolar } : {})}
      {...(t.guarani ? { guarani: t.guarani } : {})}
    />
  );
}

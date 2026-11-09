export interface ValorTresMoedasProps {
  /** Valores já formatados (glossário, parte 8), ex.: "R$ 100,00". Este componente não converte nada. */
  real: string;
  dolar?: string;
  guarani?: string;
}

/** Real em destaque; Dólar e Guarani logo abaixo, menores, em texto escuro (glossário, parte 11). */
export function ValorTresMoedas({ real, dolar, guarani }: ValorTresMoedasProps) {
  const outras = [dolar, guarani].filter((v): v is string => Boolean(v));
  return (
    <span className="cs-valor">
      <strong className="cs-valor__real">{real}</strong>
      {outras.length > 0 ? <span className="cs-valor__outras">{outras.join(' · ')}</span> : null}
    </span>
  );
}

import { Campo, ValorTresMoedas } from '@corona/ui';
import { useCotacao } from '../nucleo/cotacao';
import { lerReaisDigitados, tresMoedas } from '../nucleo/moedas';
import { TEXTOS } from '../textos';

export interface CampoDeDinheiroProps {
  rotulo?: string;
  /** O que a pessoa digitou, do jeito que digitou (ex.: "1.234,56"). */
  valor: string;
  /** Devolve o texto digitado e o valor para a API ("1234.56"), ou `null` se ainda não é um valor. */
  aoMudar: (digitado: string, valorEmReais: string | null) => void;
  erro?: string;
  ajuda?: string;
  autoFocus?: boolean;
  id?: string;
}

/**
 * Campo de dinheiro: a pessoa digita em reais, com vírgula, e vê logo abaixo o mesmo valor em Real,
 * Dólar e Guarani (cotação do dia). Ninguém digita Dólar nem Guarani (glossário, parte 11).
 */
export function CampoDeDinheiro({
  rotulo = TEXTOS.dinheiro.rotulo,
  valor,
  aoMudar,
  erro,
  ajuda = TEXTOS.dinheiro.ajuda,
  autoFocus,
  id,
}: CampoDeDinheiroProps) {
  const { taxas } = useCotacao();
  const decimal = valor.trim() === '' ? null : lerReaisDigitados(valor);
  const invalido = valor.trim() !== '' && decimal === null;
  const mostrado = decimal === null ? null : tresMoedas(decimal, taxas);
  const mensagem = erro ?? (invalido ? TEXTOS.dinheiro.invalido : undefined);
  return (
    <div>
      <Campo
        {...(id ? { id } : {})}
        rotulo={rotulo}
        value={valor}
        inputMode="decimal"
        autoComplete="off"
        {...(autoFocus ? { autoFocus } : {})}
        {...(mensagem ? { erro: mensagem } : {})}
        {...(ajuda ? { ajuda } : {})}
        onChange={(e) => {
          const digitado = e.target.value;
          aoMudar(digitado, digitado.trim() === '' ? null : lerReaisDigitados(digitado));
        }}
      />
      {mostrado ? (
        <p className="gestao-valor-convertido" aria-live="polite">
          <ValorTresMoedas
            real={mostrado.real}
            {...(mostrado.dolar ? { dolar: mostrado.dolar } : {})}
            {...(mostrado.guarani ? { guarani: mostrado.guarani } : {})}
          />
        </p>
      ) : null}
    </div>
  );
}

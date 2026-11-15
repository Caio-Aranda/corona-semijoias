import { useCotacao } from '../../nucleo/cotacao';
import { ProvedorDaCotacaoDoAcerto, ValorDoAcerto } from '../acertos/Valor';

/**
 * O valor que a API mandou, em Real e, havendo cotação do dia, em Dólar e Guarani (só na tela; tudo é gravado em Real).
 * Nunca calcula: só formata. Negativo mostra só o Real, com o sinal.
 */
export function ValorDoDia({ valor }: { valor: string }) {
  const { taxas } = useCotacao();
  return (
    <ProvedorDaCotacaoDoAcerto cotacao={taxas}>
      <ValorDoAcerto valor={valor} />
    </ProvedorDaCotacaoDoAcerto>
  );
}

/** Só o Real, com o sinal (para o que já aconteceu e não tem a cotação do dia do lançamento na resposta). */
export { realDoAcerto as soReal } from '../acertos/Valor';

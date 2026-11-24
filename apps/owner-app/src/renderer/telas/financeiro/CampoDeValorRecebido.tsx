import { Campo, ValorTresMoedas } from '@corona/ui';
import { CampoDeDinheiro } from '../../componentes/CampoDeDinheiro';
import {
  lerValorNaMoeda,
  reaisDoValorNaMoeda,
  tresMoedasDoRecebimento,
  type CotacaoParaTela,
  type MoedaDoRecebimento,
} from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import type { ValorDoRecebimento } from '../../../shared/valor-do-recebimento';
import { CampoDeEscolha } from '../devolucoes/Apoio';

const T = TEXTOS_DE_FINANCEIRO.moedaDoRecebimento;
const MOEDAS: readonly MoedaDoRecebimento[] = ['REAL', 'DOLAR', 'GUARANI'];

/**
 * O que vai para o sistema: em Real, o valor; em Dólar ou Guarani, só a moeda e o valor digitado (o sistema converte e grava em
 * Real; a tela nunca manda o valor convertido). `null` enquanto o digitado não é um valor maior que zero.
 */
export function valorDoRecebimento(
  digitado: string,
  moeda: MoedaDoRecebimento,
): ValorDoRecebimento | null {
  const v = digitado.trim() === '' ? null : lerValorNaMoeda(digitado, moeda);
  if (v === null) return null;
  return moeda === 'REAL' ? { valor: v } : { moeda, valorNaMoeda: v };
}

export interface CampoDeValorRecebidoProps {
  id: string;
  /** O rótulo do valor em Real (em Dólar ou Guarani, ganha "em Dólar" ou "em Guarani"). */
  rotulo: string;
  rotuloDaMoeda?: string;
  moeda: MoedaDoRecebimento;
  /** O que a pessoa digitou, do jeito que digitou. */
  digitado: string;
  aoMudar: (digitado: string, moeda: MoedaDoRecebimento) => void;
  /** Com que cotação mostrar a prévia; sem ela, só Real (e o campo da moeda some). */
  cotacao: CotacaoParaTela | null;
  erro?: string;
  ajuda?: string;
}

/**
 * Valor recebido do representante na moeda que a dona escolheu (Real, Dólar ou Guarani; 23/11/2026). Antes de confirmar, mostra
 * "Fica assim:" com R$, US$ e Gs., na mesma conta que o sistema faz (a dele é a que vale).
 */
export function CampoDeValorRecebido({
  id,
  rotulo,
  rotuloDaMoeda = T.rotulo,
  moeda,
  digitado,
  aoMudar,
  cotacao,
  erro,
  ajuda,
}: CampoDeValorRecebidoProps) {
  const emMoeda = cotacao ? moeda : 'REAL';
  const escolha = cotacao ? (
    <CampoDeEscolha
      id={`${id}-moeda`}
      rotulo={rotuloDaMoeda}
      valor={emMoeda}
      aoMudar={(m) => aoMudar('', m as MoedaDoRecebimento)}
      opcoes={MOEDAS.map((m) => ({ valor: m, texto: T.nomes[m] ?? m }))}
    />
  ) : null;

  if (emMoeda === 'REAL' || !cotacao) {
    return (
      <>
        {escolha}
        <CampoDeDinheiro
          id={id}
          rotulo={rotulo}
          valor={digitado}
          {...(erro ? { erro } : {})}
          {...(ajuda ? { ajuda } : {})}
          aoMudar={(d) => aoMudar(d, 'REAL')}
        />
      </>
    );
  }

  const naMoeda = digitado.trim() === '' ? null : lerValorNaMoeda(digitado, emMoeda);
  const reais = naMoeda === null ? null : reaisDoValorNaMoeda(naMoeda, emMoeda, cotacao);
  const pequeno = reais !== null && !/[1-9]/.test(reais);
  const mostrado =
    reais === null || pequeno ? null : tresMoedasDoRecebimento(reais, emMoeda, naMoeda, cotacao);
  const mensagem =
    erro ??
    (digitado.trim() !== '' && naMoeda === null
      ? T.invalido[emMoeda]
      : pequeno
        ? T.pequeno
        : undefined);
  return (
    <>
      {escolha}
      <div>
        <Campo
          id={id}
          rotulo={`${rotulo} ${T.valorEm[emMoeda] ?? ''}`.trim()}
          value={digitado}
          inputMode={emMoeda === 'GUARANI' ? 'numeric' : 'decimal'}
          autoComplete="off"
          ajuda={ajuda ?? T.ajuda}
          {...(mensagem ? { erro: mensagem } : {})}
          onChange={(e) => aoMudar(e.target.value, emMoeda)}
        />
        {mostrado ? (
          <p className="gestao-valor-convertido" aria-live="polite">
            {T.ficaAssim}{' '}
            <ValorTresMoedas
              real={mostrado.real}
              {...(mostrado.dolar ? { dolar: mostrado.dolar } : {})}
              {...(mostrado.guarani ? { guarani: mostrado.guarani } : {})}
            />
          </p>
        ) : null}
      </div>
    </>
  );
}

import {
  dataParaTela,
  dividirArredondando,
  formatarGuarani,
  formatarReais,
  taxaDeTexto,
} from './moedas';

/**
 * A dona muda a cotação do Dólar e do Guarani durante o dia (ADR-0006, item 15). A tela só digita e mostra: o servidor valida os
 * limites, compara com o Banco Central (aviso a partir de 15%) e grava. Aqui ficam a leitura das respostas e a passagem do que ela
 * digita ("5,80" e "1.350") para o que a API recebe ("5.80" e "1350").
 */

/** "5,80" → "5.80". Só números, com vírgula (ou ponto) nos centavos; até 2 casas no Dólar não é regra da tela (o servidor valida). */
export function dolarDigitado(texto: string): string | null {
  const t = texto.trim().replace(',', '.');
  return /^\d{1,3}(\.\d{1,4})?$/.test(t) ? t : null;
}

/** "1.350" → "1350"; "1.350,5" → "1350.5". O ponto de milhar sai; a vírgula vira o ponto dos centavos. */
export function guaraniDigitado(texto: string): string | null {
  const t = texto.trim().replace(/\./g, '').replace(',', '.');
  return /^\d{1,5}(\.\d{1,2})?$/.test(t) ? t : null;
}

/** "5.80000000" → "5,80" (o que se mostra e se digita; sem zeros sobrando além dos centavos). */
export function dolarParaCampo(taxa: string): string {
  const m = /^(\d+)\.?(\d*)$/.exec(taxa.trim());
  if (!m) return '';
  const centavos = (m[2] ?? '').replace(/0+$/, '').padEnd(2, '0');
  return `${m[1] ?? '0'},${centavos}`;
}

/** "1350.00000000" → "1.350"; "1117.32" → "1.117,32" (milhar com ponto, centavos só se houver). */
export function guaraniParaCampo(guaranisPorReal: string): string {
  const m = /^(\d+)\.?(\d*)$/.exec(guaranisPorReal.trim());
  if (!m) return '';
  const inteiro = (m[1] ?? '0').replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  const centavos = (m[2] ?? '').replace(/0+$/, '');
  return centavos ? `${inteiro},${centavos.slice(0, 2)}` : inteiro;
}

/** Guaranis por real de uma taxa de Guarani em reais por Guarani ("0.00089500" → "1117"): só para preencher a tela. */
export function guaranisPorRealDaTaxa(taxaDoGuarani: string): string | null {
  const t = taxaDeTexto(taxaDoGuarani);
  return t === null ? null : String(dividirArredondando(10n ** 8n, t));
}

export interface BancoCentralDaPrevia {
  data: string;
  dolar: string;
  guaranisPorReal: string;
}

export interface PreviaDaCotacao {
  bancoCentral: BancoCentralDaPrevia | null;
  avisoDeDiferenca: boolean;
  /** Qual moeda passou dos 15% (para a frase do aviso); o servidor decide, a tela não calcula. */
  moedaDoAviso: 'DOLAR' | 'GUARANI' | null;
}

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');

export function lerPrevia(bruto: unknown): PreviaDaCotacao {
  const o = objeto(bruto);
  const b = objeto(o['bancoCentral']);
  const bancoCentral =
    texto(b['dolar']) && texto(b['guaranisPorReal'])
      ? {
          data: texto(b['dataCotacao']),
          dolar: texto(b['dolar']),
          guaranisPorReal: texto(b['guaranisPorReal']),
        }
      : null;
  const moeda = o['moedaDoAviso'];
  return {
    bancoCentral,
    avisoDeDiferenca: o['avisoDeDiferenca'] === true,
    moedaDoAviso: moeda === 'DOLAR' || moeda === 'GUARANI' ? moeda : null,
  };
}

export interface LinhaDoHistorico {
  id: string;
  vigenteDesde: string;
  origem: 'BANCO_CENTRAL' | 'DONA';
  dolar: string;
  guaranisPorReal: string | null;
  guarani: string;
  lancadaPor: string | null;
  valeAgora: boolean;
}

export function lerHistorico(bruto: unknown): LinhaDoHistorico[] {
  const lista = objeto(bruto)['cotacoes'];
  if (!Array.isArray(lista)) return [];
  return lista.map((item) => {
    const o = objeto(item);
    return {
      id: texto(o['id']),
      vigenteDesde: texto(o['vigenteDesde']),
      origem: o['origem'] === 'DONA' ? 'DONA' : 'BANCO_CENTRAL',
      dolar: texto(o['dolar']),
      guaranisPorReal: texto(o['guaranisPorReal']) || null,
      guarani: texto(o['guarani']),
      lancadaPor: texto(o['lancadaPor']) || null,
      valeAgora: o['valeAgora'] === true,
    };
  });
}

/** "10:15", a hora de Brasília de um instante ISO. */
export function horaEmBrasilia(instante: string): string {
  const d = new Date(instante);
  if (Number.isNaN(d.getTime())) return '';
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone: 'America/Sao_Paulo',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(d);
}

/** 1 real = Gs. de uma linha do histórico (a que a dona digitou, ou a conta da taxa do Banco Central). */
export function guaranisDaLinha(l: LinhaDoHistorico): string {
  if (l.guaranisPorReal) return guaraniParaCampo(l.guaranisPorReal);
  const g = guaranisPorRealDaTaxa(l.guarani);
  return g === null ? '' : formatarGuarani(BigInt(g)).replace('Gs. ', '');
}

/** "R$ 5,80" para o aviso do Banco Central. */
export function reaisDeTaxa(taxa: string): string {
  const t = taxaDeTexto(taxa);
  return t === null ? '' : formatarReais(dividirArredondando(t, 10n ** 6n)).replace('R$ ', '');
}

/** A data da cotação como o servidor manda: "2026-11-06" (Banco Central) ou "2026-11-23T10:15" (da dona, com a hora em Brasília). */
export function dataEHoraDaCotacao(iso: string): { dia: string; hora: string | null } {
  const hora = /T(\d{2}:\d{2})$/.exec(iso)?.[1] ?? null;
  return { dia: dataParaTela(iso.slice(0, 10)), hora };
}

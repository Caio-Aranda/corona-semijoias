/**
 * Monta a grade que os três arquivos (CSV, Excel e PDF) usam (§12): cabeçalho com a loja, os filtros, a
 * data e quem gerou; cada valor em dinheiro em Real, Dólar e Guarani; a data da cotação em cada linha;
 * e os totais no fim. Os totais em Dólar e em Guarani são a SOMA das linhas já convertidas, igual à
 * tela, para o detalhamento sempre fechar. Tudo em inteiros (BigInt): dinheiro nunca passa por float.
 */
import {
  centavosDeTexto,
  dataParaTela,
  dolarEmCentavos,
  guaraniInteiro,
  taxaDeTexto,
  type CotacaoParaTela,
} from '../moedas';

export type TipoDeColuna =
  'texto' | 'inteiro' | 'dinheiro' | 'percentual' | 'data' | 'dataHora' | 'simNao';

export interface ColunaDoRelatorio {
  chave: string;
  titulo: string;
  tipo: TipoDeColuna;
}

export interface TotalDoRelatorio {
  chave: string;
  rotulo: string;
  quantidade: number;
  valorEmReais?: string | null;
}

export interface PedidoDeExportacao {
  loja: string;
  titulo: string;
  /** Os filtros usados, já em palavras ("Situação: Ativos"). */
  filtros: string[];
  /** "10/11/2026 às 09:30" */
  geradoEm: string;
  geradoPor: string;
  /** "Cotação do Banco Central de 06/11/2026." ou nada se não houver cotação. */
  linhaDaCotacao: string | null;
  /** Data da cotação em uso (AAAA-MM-DD), repetida em cada linha. */
  dataDaCotacao: string | null;
  cotacao: CotacaoParaTela | null;
  colunas: ColunaDoRelatorio[];
  linhas: Record<string, unknown>[];
  totais: TotalDoRelatorio[];
  /** Qual campo das linhas forma cada total de valor ({ 'preco-venda': 'precoVenda' }). */
  totaisDeValor: Record<string, string>;
}

/** Uma célula: o texto e se é número (decimal com ponto), para o Excel guardar como número. */
export interface Celula {
  v: string;
  numero?: boolean;
}

export interface Grade {
  cabecalho: string[];
  titulos: string[];
  /** Quais colunas são numéricas (alinham à direita no PDF). */
  numericas: boolean[];
  linhas: Celula[][];
  rodape: Celula[][];
  /** Aviso de rodapé do PDF (a cotação), se houver. */
  cotacao: string | null;
}

const texto = (v: string): Celula => ({ v });
const numero = (v: string): Celula => ({ v, numero: true });

function centavosParaDecimal(c: bigint): string {
  const negativo = c < 0n;
  const abs = negativo ? -c : c;
  return `${negativo ? '-' : ''}${abs / 100n}.${(abs % 100n).toString().padStart(2, '0')}`;
}

const FORMATO_DE_HORA = new Intl.DateTimeFormat('pt-BR', {
  timeZone: 'America/Sao_Paulo',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** "dd/mm/aaaa às hh:mm" no horário de Brasília (nunca "UTC"). */
export function dataEHoraParaTela(instante: Date | string): string {
  const d = typeof instante === 'string' ? new Date(instante) : instante;
  if (Number.isNaN(d.getTime())) return '';
  const p = Object.fromEntries(FORMATO_DE_HORA.formatToParts(d).map((x) => [x.type, x.value]));
  return `${p.day}/${p.month}/${p.year} às ${p.hour}:${p.minute}`;
}

interface Taxas {
  dolar: bigint;
  guarani: bigint;
}

function taxas(c: CotacaoParaTela | null): Taxas | null {
  if (!c) return null;
  const dolar = taxaDeTexto(c.dolar);
  const guarani = taxaDeTexto(c.guarani);
  return dolar === null || guarani === null ? null : { dolar, guarani };
}

function celulasDeDinheiro(valor: unknown, t: Taxas | null): Celula[] {
  const centavos = typeof valor === 'string' ? centavosDeTexto(valor) : null;
  if (centavos === null) return [texto(''), texto(''), texto('')];
  if (!t) return [numero(centavosParaDecimal(centavos)), texto(''), texto('')];
  return [
    numero(centavosParaDecimal(centavos)),
    numero(centavosParaDecimal(dolarEmCentavos(centavos, t.dolar))),
    numero(guaraniInteiro(centavos, t.guarani).toString()),
  ];
}

/** O valor de uma linha como texto; objeto ou lista nunca viram "[object Object]". */
export function comoTexto(valor: unknown): string {
  if (typeof valor === 'string') return valor;
  if (typeof valor === 'number' || typeof valor === 'boolean' || typeof valor === 'bigint') {
    return String(valor);
  }
  return '';
}

function celula(tipo: TipoDeColuna, valor: unknown): Celula[] {
  const v = comoTexto(valor);
  if (v === '') return [texto('')];
  switch (tipo) {
    case 'inteiro':
    case 'percentual':
      return [numero(v)];
    case 'data':
      return [texto(dataParaTela(v))];
    case 'dataHora':
      return [texto(dataEHoraParaTela(v))];
    default:
      return [texto(v)];
  }
}

/** "Ativo/Inativo" só serve para a coluna de situação; "Sim/Não" para as outras. */
function celulaSimNao(c: ColunaDoRelatorio, valor: unknown): Celula {
  if (c.titulo === 'Situação') return texto(valor === true ? 'Ativo' : 'Inativo');
  return texto(valor === true ? 'Sim' : 'Não');
}

/** Soma, linha a linha, os valores já convertidos (o total em Dólar e em Guarani é essa soma, §12). */
export function somarConvertido(
  linhas: Record<string, unknown>[],
  campo: string,
  cotacao: CotacaoParaTela | null,
): { dolarEmCentavos: bigint; guarani: bigint } | null {
  const t = taxas(cotacao);
  if (!t) return null;
  let dolarEmCentavos_ = 0n;
  let guarani = 0n;
  for (const l of linhas) {
    const c = typeof l[campo] === 'string' ? centavosDeTexto(l[campo]) : null;
    if (c === null) continue;
    dolarEmCentavos_ += dolarEmCentavos(c, t.dolar);
    guarani += guaraniInteiro(c, t.guarani);
  }
  return { dolarEmCentavos: dolarEmCentavos_, guarani };
}

export function montarGrade(p: PedidoDeExportacao): Grade {
  const t = taxas(p.cotacao);
  const temDinheiro = p.colunas.some((c) => c.tipo === 'dinheiro');
  const titulos: string[] = [];
  const numericas: boolean[] = [];
  for (const c of p.colunas) {
    if (c.tipo === 'dinheiro') {
      titulos.push(`${c.titulo} (R$)`, `${c.titulo} (US$)`, `${c.titulo} (Gs.)`);
      numericas.push(true, true, true);
    } else {
      titulos.push(c.titulo);
      numericas.push(c.tipo === 'inteiro' || c.tipo === 'percentual');
    }
  }
  if (temDinheiro) {
    titulos.push('Data da cotação');
    numericas.push(false);
  }
  const dataDaCotacao = t && p.dataDaCotacao ? dataParaTela(p.dataDaCotacao) : '';

  const linhas = p.linhas.map((linha) => {
    const celulas: Celula[] = [];
    for (const c of p.colunas) {
      const valor = linha[c.chave];
      if (c.tipo === 'dinheiro') celulas.push(...celulasDeDinheiro(valor, t));
      else if (c.tipo === 'simNao') celulas.push(celulaSimNao(c, valor));
      else celulas.push(...celula(c.tipo, valor));
    }
    if (temDinheiro) celulas.push(texto(dataDaCotacao));
    return celulas;
  });

  // Rodapé: o rótulo na primeira coluna; a quantidade na segunda; o valor nas três colunas do campo.
  const rodape = p.totais.map((total) => {
    const linha: Celula[] = titulos.map(() => texto(''));
    linha[0] = texto(total.rotulo);
    const campo = p.totaisDeValor[total.chave];
    const colunaDoCampo = campo ? p.colunas.findIndex((c) => c.chave === campo) : -1;
    if (campo && colunaDoCampo >= 0 && total.valorEmReais != null) {
      let inicio = 0;
      for (let i = 0; i < colunaDoCampo; i++) inicio += p.colunas[i]!.tipo === 'dinheiro' ? 3 : 1;
      const real = centavosDeTexto(total.valorEmReais);
      linha[inicio] = real === null ? texto('') : numero(centavosParaDecimal(real));
      const soma = somarConvertido(p.linhas, campo, p.cotacao);
      if (soma) {
        linha[inicio + 1] = numero(centavosParaDecimal(soma.dolarEmCentavos));
        linha[inicio + 2] = numero(soma.guarani.toString());
      }
    } else if (titulos.length > 1) {
      linha[1] = texto(String(total.quantidade));
    }
    return linha;
  });

  return {
    cabecalho: [
      p.loja,
      p.titulo,
      `Filtros: ${p.filtros.length > 0 ? p.filtros.join('; ') : 'nenhum'}`,
      `Gerado em ${p.geradoEm} por ${p.geradoPor}`,
      ...(p.linhaDaCotacao ? [p.linhaDaCotacao] : []),
    ],
    titulos,
    numericas,
    linhas,
    rodape,
    cotacao: p.linhaDaCotacao,
  };
}

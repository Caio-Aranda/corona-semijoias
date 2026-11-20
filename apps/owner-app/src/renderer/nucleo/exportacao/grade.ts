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
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';
import { localeAtual } from '../../idioma/idioma';

export type TipoDeColuna =
  | 'texto'
  | 'inteiro'
  /** Quantidade com sinal: na tela +3 e -2; no arquivo, o número. */
  | 'sinal'
  | 'dinheiro'
  | 'percentual'
  | 'data'
  | 'dataHora'
  | 'simNao'
  /** Foto do produto: só na tela; os arquivos não levam imagem. */
  | 'foto';

export interface ColunaDoRelatorio {
  chave: string;
  titulo: string;
  tipo: TipoDeColuna;
  /**
   * Maletas (tarefa 3.7): o servidor já manda o Dólar e o Guarani de cada linha, pela cotação do dia em
   * que a maleta foi enviada (não a de hoje). Os campos da linha que os trazem, e o da data da cotação.
   */
  convertido?: { dolar: string; guarani: string; data: string };
  /** Dinheiro que não depende de cotação (o limite do vendedor): sai só em Real. */
  soReal?: boolean;
}

export interface TotalDoRelatorio {
  chave: string;
  rotulo: string;
  quantidade: number;
  valorEmReais?: string | null;
  /** Soma de peças, quando o total é de peças. */
  pecas?: number | null;
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
  /** Total já convertido pelo servidor (Dólar e Guarani), pela chave do total. Vale no lugar da soma local. */
  totaisConvertidos?: Record<string, { dolar: string; guarani: string }>;
  /** Linhas que entram na soma de cada total de valor (ex.: compras desfeitas ficam fora do total). */
  linhasDoTotal?: Record<string, (linha: Record<string, unknown>) => boolean>;
  /**
   * Relatório em partes (Fechamento da maleta): cada parte vira uma seção do arquivo, com o título e os totais dela, separadas
   * por uma linha em branco. O que não está em nenhuma parte fica na primeira.
   */
  secoes?: { titulo: string; totais: string[] }[];
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

const FORMATO_DE_HORA = new Intl.DateTimeFormat(localeAtual(), {
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
  return `${p.day}/${p.month}/${p.year} ${SOLTOS.arquivo.as} ${p.hour}:${p.minute}`;
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

function celulasJaConvertidas(real: unknown, dolar: unknown, guarani: unknown): Celula[] {
  const r = typeof real === 'string' ? centavosDeTexto(real) : null;
  if (r === null) return [texto(''), texto(''), texto('')];
  const d = typeof dolar === 'string' ? centavosDeTexto(dolar) : null;
  const g = typeof guarani === 'string' ? centavosDeTexto(guarani) : null;
  return [
    numero(centavosParaDecimal(r)),
    d === null ? texto('') : numero(centavosParaDecimal(d)),
    // O Guarani é inteiro: o servidor manda "1234567.00"; tira os centavos.
    g === null ? texto('') : numero((g / 100n).toString()),
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
    case 'sinal':
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
  if (c.titulo === SOLTOS.arquivo.situacao)
    return texto(valor === true ? SOLTOS.arquivo.ativo : SOLTOS.arquivo.inativo);
  return texto(valor === true ? SOLTOS.arquivo.sim : SOLTOS.arquivo.nao);
}

/** Soma, linha a linha, os valores já convertidos (o total em Dólar e em Guarani é essa soma, §12). */
export function somarConvertido(
  linhas: Record<string, unknown>[],
  campo: string,
  cotacao: CotacaoParaTela | null,
  entra: (linha: Record<string, unknown>) => boolean = () => true,
): { dolarEmCentavos: bigint; guarani: bigint } | null {
  const t = taxas(cotacao);
  if (!t) return null;
  let dolarEmCentavos_ = 0n;
  let guarani = 0n;
  for (const l of linhas) {
    if (!entra(l)) continue;
    const c = typeof l[campo] === 'string' ? centavosDeTexto(l[campo]) : null;
    if (c === null) continue;
    dolarEmCentavos_ += dolarEmCentavos(c, t.dolar);
    guarani += guaraniInteiro(c, t.guarani);
  }
  return { dolarEmCentavos: dolarEmCentavos_, guarani };
}

/** Sem partes, o rodapé é a lista de totais; com partes, cada uma ganha um título e uma linha em branco antes (a primeira só o título). */
function juntarSecoes(p: PedidoDeExportacao, colunas: number, linhas: Celula[][]): Celula[][] {
  if (!p.secoes || p.secoes.length === 0) return linhas;
  const vazia = (): Celula[] => Array.from({ length: colunas }, () => texto(''));
  const chaves = p.secoes.flatMap((s) => s.totais);
  const resultado: Celula[][] = [];
  p.secoes.forEach((secao, i) => {
    if (i > 0) resultado.push(vazia());
    const titulo = vazia();
    titulo[0] = texto(secao.titulo);
    resultado.push(titulo);
    p.totais.forEach((total, k) => {
      const nela = secao.totais.includes(total.chave) || (i === 0 && !chaves.includes(total.chave));
      if (nela) resultado.push(linhas[k]!);
    });
  });
  return resultado;
}

export function montarGrade(pedido: PedidoDeExportacao): Grade {
  // A data da cotação das maletas já sai na última coluna do arquivo; não repete.
  const dataPropria = pedido.colunas.find((c) => c.convertido)?.convertido?.data;
  const p = {
    ...pedido,
    colunas: pedido.colunas.filter((c) => c.tipo !== 'foto' && c.chave !== dataPropria),
  };
  const t = taxas(p.cotacao);
  const temDinheiro = p.colunas.some((c) => c.tipo === 'dinheiro' && !c.soReal);
  const titulos: string[] = [];
  const numericas: boolean[] = [];
  for (const c of p.colunas) {
    if (c.tipo === 'dinheiro' && c.soReal) {
      titulos.push(`${c.titulo} (R$)`);
      numericas.push(true);
    } else if (c.tipo === 'dinheiro') {
      titulos.push(`${c.titulo} (R$)`, `${c.titulo} (US$)`, `${c.titulo} (Gs.)`);
      numericas.push(true, true, true);
    } else {
      titulos.push(c.titulo);
      numericas.push(c.tipo === 'inteiro' || c.tipo === 'sinal' || c.tipo === 'percentual');
    }
  }
  if (temDinheiro) {
    titulos.push(SOLTOS.arquivo.dataDaCotacao);
    numericas.push(false);
  }
  const dataDaCotacao = t && p.dataDaCotacao ? dataParaTela(p.dataDaCotacao) : '';

  const linhas = p.linhas.map((linha) => {
    const celulas: Celula[] = [];
    for (const c of p.colunas) {
      const valor = linha[c.chave];
      if (c.tipo === 'dinheiro' && c.soReal) {
        celulas.push(celulasDeDinheiro(valor, null)[0]!);
      } else if (c.tipo === 'dinheiro' && c.convertido) {
        celulas.push(
          ...celulasJaConvertidas(valor, linha[c.convertido.dolar], linha[c.convertido.guarani]),
        );
      } else if (c.tipo === 'dinheiro') celulas.push(...celulasDeDinheiro(valor, t));
      else if (c.tipo === 'simNao') celulas.push(celulaSimNao(c, valor));
      else celulas.push(...celula(c.tipo, valor));
    }
    if (temDinheiro) {
      const propria = p.colunas.find((c) => c.convertido)?.convertido?.data;
      celulas.push(texto(propria ? dataParaTela(comoTexto(linha[propria])) : dataDaCotacao));
    }
    return celulas;
  });

  // Rodapé: o rótulo na primeira coluna; a quantidade na segunda; o valor nas três colunas do campo.
  const rodapeDe = p.totais.map((total) => {
    const linha: Celula[] = titulos.map(() => texto(''));
    linha[0] = texto(total.rotulo);
    const campo = p.totaisDeValor[total.chave];
    const colunaDoCampo = campo ? p.colunas.findIndex((c) => c.chave === campo) : -1;
    if (campo && colunaDoCampo >= 0 && total.valorEmReais != null) {
      let inicio = 0;
      for (let i = 0; i < colunaDoCampo; i++) {
        const ci = p.colunas[i]!;
        inicio += ci.tipo === 'dinheiro' && !ci.soReal ? 3 : 1;
      }
      const real = centavosDeTexto(total.valorEmReais);
      linha[inicio] = real === null ? texto('') : numero(centavosParaDecimal(real));
      const pronto = p.totaisConvertidos?.[total.chave];
      const soma = pronto
        ? null
        : somarConvertido(p.linhas, campo, p.cotacao, p.linhasDoTotal?.[total.chave]);
      if (pronto) {
        const d = centavosDeTexto(pronto.dolar);
        const g = centavosDeTexto(pronto.guarani);
        if (d !== null) linha[inicio + 1] = numero(centavosParaDecimal(d));
        if (g !== null) linha[inicio + 2] = numero((g / 100n).toString());
      } else if (soma) {
        linha[inicio + 1] = numero(centavosParaDecimal(soma.dolarEmCentavos));
        linha[inicio + 2] = numero(soma.guarani.toString());
      }
    } else if (titulos.length > 1) {
      // Total em dinheiro que não tem coluna de valor (ex.: o Fechamento da maleta): o valor em Real vai na segunda coluna.
      const real = total.valorEmReais != null ? centavosDeTexto(total.valorEmReais) : null;
      linha[1] =
        real !== null
          ? numero(centavosParaDecimal(real))
          : texto(String(total.pecas ?? total.quantidade));
    }
    return linha;
  });
  const rodape = juntarSecoes(p, titulos.length, rodapeDe);

  return {
    cabecalho: [
      p.loja,
      p.titulo,
      `Filtros: ${p.filtros.length > 0 ? p.filtros.join('; ') : 'nenhum'}`,
      SOLTOS.arquivo.geradoEm(p.geradoEm, p.geradoPor),
      ...(p.linhaDaCotacao ? [p.linhaDaCotacao] : []),
    ],
    titulos,
    numericas,
    linhas,
    rodape,
    cotacao: p.linhaDaCotacao,
  };
}

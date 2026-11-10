/**
 * Regras da compra na tela: quantidades, custos e totais. Tudo em centavos inteiros (`BigInt`):
 * dinheiro nunca passa por número com vírgula. O cálculo é o mesmo do servidor (custo com 2 casas,
 * arredondado para cima na metade, e só então vezes a quantidade); o servidor recalcula e a tela
 * mostra o que ele devolveu.
 */
import { MAXIMO_DA_QUANTIDADE } from '../../shared/compras';
import { centavosDeTexto, dividirArredondando, lerReaisDigitados } from './moedas';

/** Uma linha da compra em andamento. Quantidade e custo ficam como a pessoa digitou. */
export interface LinhaDaCompra {
  produtoId: string;
  nome: string;
  codigo: string;
  fotoHash: string | null;
  /** O que está no campo: "3". */
  quantidade: string;
  /** O que está no campo: "12,50". */
  custo: string;
}

/** Quantidade inteira de 1 até o limite do servidor; qualquer outra coisa → `null`. */
export function lerQuantidade(digitada: string): number | null {
  const t = digitada.trim();
  if (!/^\d{1,7}$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= MAXIMO_DA_QUANTIDADE ? n : null;
}

/** "12,50" (ou "1.234,5") → centavos; vazio ou fora do formato → `null`. */
export function custoEmCentavos(digitado: string): bigint | null {
  const decimal = lerReaisDigitados(digitado);
  return decimal === null ? null : centavosDeTexto(decimal);
}

/**
 * Custo vindo de planilha, com até 4 casas depois da vírgula ("12,345"), arredondado para centavos
 * como o servidor faz (metade para cima). Mais casas, sinal ou letras → `null`.
 */
export function custoDaPlanilhaEmCentavos(bruto: string): bigint | null {
  const t = bruto.trim().replace(/^R\$\s*/, '');
  let inteiro: string;
  let fracao = '';
  let m: RegExpExecArray | null;
  if ((m = /^(\d{1,3}(?:\.\d{3})+|\d+),(\d{1,4})$/.exec(t))) {
    inteiro = m[1]!.replace(/\./g, '');
    fracao = m[2]!;
  } else if ((m = /^(\d+)\.(\d{1,4})$/.exec(t))) {
    inteiro = m[1]!;
    fracao = m[2]!;
  } else if ((m = /^(\d{1,3}(?:\.\d{3})+|\d+)$/.exec(t))) {
    inteiro = m[1]!.replace(/\./g, '');
  } else return null;
  if (inteiro.length > 12) return null;
  const emDecimosDeMilesimo = BigInt(inteiro) * 10_000n + BigInt(fracao.padEnd(4, '0') || '0');
  return dividirArredondando(emDecimosDeMilesimo, 100n);
}

/** Centavos → texto que a API entende ("1250" → "12.50"). */
export function centavosParaApi(centavos: bigint): string {
  const reais = centavos / 100n;
  return `${reais}.${(centavos % 100n).toString().padStart(2, '0')}`;
}

/** Centavos → texto para o campo, com vírgula ("1250" → "12,50"). */
export function centavosParaCampo(centavos: bigint): string {
  return centavosParaApi(centavos).replace('.', ',');
}

/** Total da linha: custo (2 casas) vezes a quantidade. `null` enquanto a linha não está boa. */
export function totalDaLinha(linha: Pick<LinhaDaCompra, 'quantidade' | 'custo'>): bigint | null {
  const q = lerQuantidade(linha.quantidade);
  const c = custoEmCentavos(linha.custo);
  return q === null || c === null ? null : BigInt(q) * c;
}

/** Soma só as linhas que já estão boas. */
export function totalDaCompra(linhas: readonly LinhaDaCompra[]): bigint {
  let soma = 0n;
  for (const l of linhas) soma += totalDaLinha(l) ?? 0n;
  return soma;
}

export function totalDePecas(linhas: readonly LinhaDaCompra[]): number {
  let soma = 0;
  for (const l of linhas) soma += lerQuantidade(l.quantidade) ?? 0;
  return soma;
}

/** Peça lida de novo soma 1 na linha que já existe; peça nova entra no fim da lista. */
export function adicionarOuSomar(
  linhas: readonly LinhaDaCompra[],
  nova: LinhaDaCompra,
): LinhaDaCompra[] {
  const i = linhas.findIndex((l) => l.produtoId === nova.produtoId);
  if (i < 0) return [...linhas, nova];
  return linhas.map((l, j) => {
    if (j !== i) return l;
    const atual = lerQuantidade(l.quantidade);
    // Quantidade que ainda não é número (campo vazio ou errado): a leitura conta como a primeira.
    const proxima = Math.min((atual ?? 0) + 1, MAXIMO_DA_QUANTIDADE);
    return { ...l, quantidade: String(proxima) };
  });
}

export interface ProblemaDaLinha {
  quantidade?: string;
  custo?: string;
}

/** O que falta acertar em cada linha antes de confirmar (mensagens simples, sem termo técnico). */
export function problemasDasLinhas(
  linhas: readonly LinhaDaCompra[],
  textos: { quantidade: string; custo: string },
): ProblemaDaLinha[] {
  return linhas.map((l) => {
    const p: ProblemaDaLinha = {};
    if (lerQuantidade(l.quantidade) === null) p.quantidade = textos.quantidade;
    if (custoEmCentavos(l.custo) === null) p.custo = textos.custo;
    return p;
  });
}

export interface ItemDoPedido {
  produtoId: string;
  quantidade: number;
  custoUnitario: string;
}

/** Itens no formato da API. Devolve `null` se alguma linha ainda não está boa. */
export function itensDoPedido(linhas: readonly LinhaDaCompra[]): ItemDoPedido[] | null {
  const itens: ItemDoPedido[] = [];
  for (const l of linhas) {
    const q = lerQuantidade(l.quantidade);
    const c = custoEmCentavos(l.custo);
    if (q === null || c === null) return null;
    itens.push({ produtoId: l.produtoId, quantidade: q, custoUnitario: centavosParaApi(c) });
  }
  return itens;
}

export interface ErrosDoServidor {
  /** Erros de cada linha, pelo índice do item enviado. */
  porItem: Map<number, string[]>;
  /** Erros do cabeçalho (fornecedor, data, número da nota...) e outros, pelo nome do campo. */
  gerais: Record<string, string>;
}

/** Separa os erros do servidor: `itens[2].codigo` vai para a linha 2; o resto fica no cabeçalho. */
export function separarErrosDoServidor(campos: Record<string, string>): ErrosDoServidor {
  const porItem = new Map<number, string[]>();
  const gerais: Record<string, string> = {};
  for (const [campo, mensagem] of Object.entries(campos)) {
    const m = /^itens\[(\d+)\]/.exec(campo);
    if (!m) {
      gerais[campo] = mensagem;
      continue;
    }
    const i = Number(m[1]);
    const lista = porItem.get(i) ?? [];
    if (!lista.includes(mensagem)) lista.push(mensagem);
    porItem.set(i, lista);
  }
  return { porItem, gerais };
}

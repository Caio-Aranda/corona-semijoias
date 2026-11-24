/** As contas a pagar como a API devolve, lidas com cuidado (nada aqui calcula dinheiro). */
import type { ValorDoLancamento } from '../devolucoes/dados';
import { inteiro, lista, objeto, texto, valorEmReais } from './dados';

export interface BaixaDaConta {
  id: string;
  tipo: string;
  valor: string;
  forma: string;
  dataPagamento: string;
  motivo: string;
  estornaId: string;
}

export interface ContaAPagar {
  id: string;
  fornecedorNome: string;
  numeroDaCompra: string;
  parcela: number;
  totalParcelas: number;
  vencimento: string;
  valor: string;
  valorPago: string;
  /** O valor e o já pago com Dólar e Guarani pela cotação do dia de cada lançamento (o servidor calcula; ADR-0006). */
  valorNaTela: ValorDoLancamento;
  pagoNaTela: ValorDoLancamento;
  saldo: string;
  situacao: string;
  baixas: BaixaDaConta[];
}

export function lerConta(bruto: unknown): ContaAPagar | null {
  const o = objeto(bruto);
  const valor = valorEmReais(o.valor);
  const valorPago = valorEmReais(o.valorPago);
  const saldo = valorEmReais(o.saldo);
  if (typeof o.id !== 'string' || valor === null || valorPago === null || saldo === null) {
    return null;
  }
  const baixas = lista(o.baixas).flatMap((b): BaixaDaConta[] => {
    const x = objeto(b);
    const v = valorEmReais(x.valor);
    if (typeof x.id !== 'string' || v === null) return [];
    return [
      {
        id: x.id,
        tipo: texto(x.tipo),
        valor: v,
        forma: texto(x.forma),
        dataPagamento: texto(x.dataPagamento),
        motivo: texto(x.motivo),
        estornaId: texto(x.estornaId),
      },
    ];
  });
  return {
    id: o.id,
    fornecedorNome: texto(o.fornecedorNome),
    numeroDaCompra: texto(o.numeroDaCompra),
    parcela: inteiro(o.parcela),
    totalParcelas: inteiro(o.totalParcelas),
    vencimento: texto(o.vencimento),
    valor,
    valorPago,
    valorNaTela: emTresMoedas(valor, o.valorEmDolar, o.valorEmGuarani),
    pagoNaTela: emTresMoedas(valorPago, o.valorPagoEmDolar, o.valorPagoEmGuarani),
    saldo,
    situacao: texto(o.situacao),
    baixas,
  };
}

export interface PaginaDeContas {
  itens: ContaAPagar[];
  pagina: number;
  tamanho: number;
  total: number;
}

export function lerPaginaDeContas(bruto: unknown): PaginaDeContas {
  const o = objeto(bruto);
  return {
    itens: lista(o.itens).flatMap((b) => {
      const c = lerConta(b);
      return c ? [c] : [];
    }),
    pagina: inteiro(o.pagina),
    tamanho: inteiro(o.tamanho) || 20,
    total: inteiro(o.total),
  };
}

/** Só a conta aberta, parcial ou vencida ainda tem o que pagar (a situação vem da API). */
export const podePagar = (c: ContaAPagar): boolean =>
  c.situacao === 'ABERTA' || c.situacao === 'PARCIAL' || c.situacao === 'VENCIDA';

/** O pagamento que ainda vale: é pagamento, não é o estorno de outro e nenhum estorno aponta para ele. */
export const baixaPodeSerEstornada = (b: BaixaDaConta, todas: readonly BaixaDaConta[]): boolean =>
  b.tipo === 'PAGAMENTO' && b.estornaId === '' && !todas.some((o) => o.estornaId === b.id);

/** O Real e, quando o servidor calculou, o Dólar e o Guarani (só os dois juntos). */
export function emTresMoedas(real: string, dolar: unknown, guarani: unknown): ValorDoLancamento {
  const d = valorEmReais(dolar);
  const g = valorEmReais(guarani);
  return d === null || g === null
    ? { real, dolar: null, guarani: null }
    : { real, dolar: d, guarani: g };
}

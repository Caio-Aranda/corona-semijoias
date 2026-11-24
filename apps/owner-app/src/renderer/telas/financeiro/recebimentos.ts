/** Contas a receber e recebimentos do vendedor como a API devolve, lidos com cuidado (nada aqui calcula dinheiro). */
import type { MoedaDoRecebimento } from '../../nucleo/moedas';
import type { ValorDoLancamento } from '../devolucoes/dados';
import { emTresMoedas } from './contas-a-pagar';
import { inteiro, lista, objeto, texto, valorEmReais } from './dados';

export interface ContaAReceber {
  id: string;
  nivel: string;
  origem: string;
  vendedorNome: string;
  clienteNome: string;
  numero: number;
  totalDeParcelas: number;
  vencimento: string;
  valor: string;
  valorPago: string;
  /** O valor e o já pago com Dólar e Guarani pela cotação do dia de cada lançamento (o servidor calcula; ADR-0006). */
  valorNaTela: ValorDoLancamento;
  pagoNaTela: ValorDoLancamento;
  emAberto: string;
  status: string;
  vencida: boolean;
}

export function lerContasAReceber(bruto: unknown): ContaAReceber[] {
  return lista(bruto).flatMap((b): ContaAReceber[] => {
    const o = objeto(b);
    const valor = valorEmReais(o.valor);
    const valorPago = valorEmReais(o.valorPago);
    const emAberto = valorEmReais(o.emAberto);
    if (typeof o.id !== 'string' || valor === null || valorPago === null || emAberto === null) {
      return [];
    }
    return [
      {
        id: o.id,
        nivel: texto(o.nivel),
        origem: texto(o.origem),
        vendedorNome: texto(o.vendedorNome),
        clienteNome: texto(o.clienteNome),
        numero: inteiro(o.numero),
        totalDeParcelas: inteiro(o.totalDeParcelas),
        vencimento: texto(o.vencimento),
        valor,
        valorPago,
        valorNaTela: emTresMoedas(valor, o.valorEmDolar, o.valorEmGuarani),
        pagoNaTela: emTresMoedas(valorPago, o.valorPagoEmDolar, o.valorPagoEmGuarani),
        emAberto,
        status: texto(o.status),
        vencida: o.vencida === true,
      },
    ];
  });
}

export interface RecebimentoDoVendedor {
  id: string;
  /** Em que moeda a dona digitou; o que vale é sempre o `valor` em Real. */
  moeda: MoedaDoRecebimento;
  /** O que ela digitou em Dólar ou Guarani (vazio em Real). */
  valorNaMoeda: string | null;
  /** Dólar e Guarani que o sistema calculou para mostrar (vazios sem cotação). */
  dolar: string | null;
  guarani: string | null;
  vendedorId: string;
  vendedorNome: string;
  momento: string;
  aplicadoEm: string;
  forma: string;
  valor: string;
  recebidoEm: string;
  estornaId: string;
  motivo: string;
  estornado: boolean;
}

export function lerRecebimentos(bruto: unknown): RecebimentoDoVendedor[] {
  return lista(bruto).flatMap((b): RecebimentoDoVendedor[] => {
    const o = objeto(b);
    const valor = valorEmReais(o.valor);
    if (typeof o.id !== 'string' || valor === null) return [];
    return [
      {
        id: o.id,
        moeda: o.moeda === 'DOLAR' || o.moeda === 'GUARANI' ? o.moeda : 'REAL',
        valorNaMoeda: typeof o.valorNaMoeda === 'string' ? o.valorNaMoeda : null,
        dolar: typeof o.dolar === 'string' ? o.dolar : null,
        guarani: typeof o.guarani === 'string' ? o.guarani : null,
        vendedorId: texto(o.vendedorId),
        vendedorNome: texto(o.vendedorNome),
        momento: texto(o.momento),
        aplicadoEm: texto(o.aplicadoEm),
        forma: texto(o.forma),
        valor,
        recebidoEm: texto(o.recebidoEm),
        estornaId: texto(o.estornaId),
        motivo: texto(o.motivo),
        estornado: o.estornado === true,
      },
    ];
  });
}

/** Só o recebimento que ainda vale (nem já estornado, nem ele mesmo um estorno) pode ser estornado. */
export const recebimentoPodeSerEstornado = (r: RecebimentoDoVendedor): boolean =>
  !r.estornado && r.estornaId === '';

/** Contas a receber e recebimentos do vendedor como a API devolve, lidos com cuidado (nada aqui calcula dinheiro). */
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
        emAberto,
        status: texto(o.status),
        vencida: o.vencida === true,
      },
    ];
  });
}

export interface RecebimentoDoVendedor {
  id: string;
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

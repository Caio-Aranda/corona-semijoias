/** As pendências do acerto como a API devolve, lidas com cuidado. */
import { dinheiro } from './previa';
import { inteiro, lista, objeto, texto } from './dados';

export interface PendenciaDoAcerto {
  id: string;
  vendedorId: string;
  vendedorNome: string;
  produtoNome: string;
  quantidade: number;
  valor: string;
  tipo: string;
  status: string;
}

export function lerPendencias(bruto: unknown): PendenciaDoAcerto[] {
  return lista(bruto).flatMap((b) => {
    const o = objeto(b);
    const valor = dinheiro(o.valor);
    if (typeof o.id !== 'string' || valor === null) return [];
    return [
      {
        id: o.id,
        vendedorId: texto(o.vendedorId),
        vendedorNome: texto(o.vendedorNome),
        produtoNome: texto(o.produtoNome),
        quantidade: inteiro(o.quantidade),
        valor,
        tipo: texto(o.tipo),
        status: texto(o.status),
      },
    ];
  });
}

import type { PonteDoCorona } from '../../shared/ponte';

/**
 * A nota da maleta (PDF "Nota de remessa") é da tarefa 3.7: ela busca os dados da nota na API e
 * gera o PDF no gerador da tarefa 2.8. Enquanto isso, a ficha da maleta enviada não mostra o
 * botão "Imprimir nota" (ver `NOTA_DA_MALETA_PRONTA`). Para ligar: a 3.7 troca esta função pela
 * impressão de verdade e liga a constante; a ficha já chama esta função com o código da maleta.
 */
export const NOTA_DA_MALETA_PRONTA = false;

export function imprimirNotaDaMaleta(ponte: PonteDoCorona, maletaId: string): Promise<void> {
  // Completado na tarefa 3.7: busca a nota pelo código da maleta e gera o PDF.
  return Promise.resolve(void [ponte, maletaId]);
}

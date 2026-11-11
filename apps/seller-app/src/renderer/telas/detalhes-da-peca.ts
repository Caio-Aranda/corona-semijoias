import type { PecaDaMaleta } from '../../shared/dados';
import { TEXTOS } from '../textos';

/**
 * As linhas a mais do painel da peça lida: quantas há na maleta, o COD Fábrica (código do fornecedor) e a categoria.
 * Quem não tem o dado não mostra a linha. Nunca custo nem estoque da loja.
 */
export function detalhesDaPeca(
  peca: PecaDaMaleta,
  naMaleta?: number,
): { rotulo: string; valor: string | number }[] {
  const T = TEXTOS.painelDaPeca;
  const linhas: { rotulo: string; valor: string | number }[] = [];
  if (naMaleta !== undefined) linhas.push({ rotulo: T.naMaleta, valor: naMaleta });
  if (peca.codigoFornecedor) linhas.push({ rotulo: T.codFabrica, valor: peca.codigoFornecedor });
  if (peca.categoria) linhas.push({ rotulo: T.categoria, valor: peca.categoria });
  return linhas;
}

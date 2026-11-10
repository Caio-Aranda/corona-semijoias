/**
 * Listas de escolha dos formulários (categoria, fornecedor, praça, restrição): só quem está ativo, mas o
 * que já estava escolhido continua aparecendo, com a marca de inativo, para a ficha não "perder" o vínculo.
 */
export function soAtivosMaisAtual<T extends { id: string; nome: string; ativo: boolean }>(
  itens: readonly T[],
  atual: string,
  marcaInativo: string,
): T[] {
  return itens
    .filter((i) => i.ativo || i.id === atual)
    .map((i) => (i.ativo ? i : { ...i, nome: `${i.nome} ${marcaInativo}` }));
}

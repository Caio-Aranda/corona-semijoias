/**
 * Textos dos relatórios de vendas e da curva ABC da dona (tarefa 8.5), do glossário da interface ("Relatórios de vendas").
 * A dona é tratada por "você". Na tela nunca aparecem os nomes técnicos das situações.
 */
export const TEXTOS_DE_RELATORIOS_DE_VENDAS = {
  grupoDoMenu: 'Relatórios de vendas',
  cotacao:
    'Dólar e Guarani pela cotação do Banco Central do dia de cada venda. Tudo é gravado em Real.',
  liquido:
    'Vendido líquido é o que foi vendido menos o que o cliente devolveu ou trocou. Vendas canceladas ficam fora dos totais.',
  curva:
    'Classe A: as peças que somam até 80% do vendido líquido. Classe B: até 95%. Classe C: o resto. Peça sem venda no período não aparece. Dólar e Guarani pela cotação do dia de cada venda.',
  situacaoDaVenda: {
    CONCLUIDA: 'Feita',
    ESTORNADA: 'Cancelada',
  } as Record<string, string>,
  classes: {
    A: 'A',
    B: 'B',
    C: 'C',
  } as Record<string, string>,
  semPraca: 'Sem praça',
} as const;

import type { Grade } from '../../nucleo/exportacao/grade';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import type { LeituraDoFechamentoLida } from './dados';

/**
 * As faltas do Fechamento da maleta como uma tabela a mais, só para o PDF do relatório (§23.10, item 1): peça, quantidade,
 * preço da maleta, valor, decisão e motivo, com o total a cobrar e o da perda da loja. Sem falta, nada (o PDF fica como era).
 */
export function gradeDasFaltas(leitura: LeituraDoFechamentoLida, maleta: string): Grade | null {
  if (leitura.pendencias.length === 0) return null;
  const T = TEXTOS_DE_CONSIGNADO.fechamento.pdfDasFaltas;
  const soma = (decisao: string) =>
    (
      leitura.pendencias
        .filter((p) => p.decisao === decisao)
        .reduce((total, p) => total + Math.round(Number(p.valor) * 100), 0) / 100
    ).toFixed(2);
  const vazio = { v: '' };
  return {
    cabecalho: [T.titulo, maleta],
    titulos: [...T.colunas],
    numericas: [false, true, true, true, false, false],
    linhas: leitura.pendencias.map((p) => [
      { v: p.nome },
      { v: String(p.quantidade), numero: true },
      { v: p.precoUnitario, numero: true },
      { v: p.valor, numero: true },
      { v: T.decisoes[p.decisao] ?? p.decisao },
      { v: p.motivo },
    ]),
    rodape: [
      [
        { v: T.totalACobrar },
        vazio,
        vazio,
        { v: soma('COBRAR_DO_REPRESENTANTE'), numero: true },
        vazio,
        vazio,
      ],
      [
        { v: T.totalDaPerda },
        vazio,
        vazio,
        { v: soma('PERDA_DA_LOJA'), numero: true },
        vazio,
        vazio,
      ],
      [
        { v: T.totalPago },
        vazio,
        vazio,
        { v: leitura.cobranca.pagoNestaMaleta, numero: true },
        vazio,
        vazio,
      ],
      [
        { v: T.totalFaltaPagar },
        vazio,
        vazio,
        { v: leitura.cobranca.faltaPagarNestaMaleta, numero: true },
        vazio,
        vazio,
      ],
    ],
    cotacao: null,
  };
}

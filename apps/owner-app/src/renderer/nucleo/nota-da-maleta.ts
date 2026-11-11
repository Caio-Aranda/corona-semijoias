import type { PonteDoCorona } from '../../shared/ponte';
import { TEXTOS_DE_MALETAS } from '../textos-maletas';
import { entregarArquivo } from './arquivos';
import { chamar } from './chamadas';
import { gerarPdfDaNota, lerNota } from './exportacao/pdf-nota';

/**
 * A nota da maleta (PDF "Nota de remessa", tarefa 3.7): busca os dados congelados no envio (rota da
 * nota, só da dona), gera o PDF no gerador da tarefa 2.8 e entrega pelo mesmo caminho dos relatórios
 * (o Windows pergunta onde guardar; a tela nunca escolhe o caminho). Maleta em montagem não tem nota,
 * por isso os botões só aparecem nas maletas enviadas.
 */
export const NOTA_DA_MALETA_PRONTA = true;

export type ResultadoDaNota = { ok: true; arquivo: string } | { ok: false; mensagem: string };

export async function imprimirNotaDaMaleta(
  ponte: PonteDoCorona,
  maletaId: string,
): Promise<ResultadoDaNota> {
  const T = TEXTOS_DE_MALETAS.nota;
  const r = await chamar<unknown>(ponte, 'maletas.nota', { id: maletaId });
  if (!r.ok) return { ok: false, mensagem: r.erro.mensagem };
  const nota = lerNota(r.dados);
  if (!nota) return { ok: false, mensagem: T.erro };
  try {
    const arquivo = `nota-da-maleta-${nota.numero.replace(/[^A-Za-z0-9-]/g, '') || 'sem-numero'}.pdf`;
    entregarArquivo(arquivo, 'application/pdf', gerarPdfDaNota(nota));
    return { ok: true, arquivo };
  } catch {
    return { ok: false, mensagem: T.erro };
  }
}

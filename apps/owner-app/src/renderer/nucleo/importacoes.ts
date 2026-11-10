/**
 * Guarda no servidor o resultado de uma importação em massa (tarefa 1.13), para o relatório "Resultado das
 * importações". As linhas vão em partes de até 500; o mesmo id de lote junta as partes.
 */
import type { PonteDoCorona } from '../../shared/ponte';
import {
  LINHAS_POR_ENVIO_DE_IMPORTACAO,
  type LinhaDeImportacao,
} from '../../shared/validacao-produtos';
import { chamar } from './chamadas';

/** Um identificador novo para cada importação (a tela gera; o servidor não repete o lote). */
export function novoLoteDeImportacao(): string {
  return crypto.randomUUID();
}

/** Devolve `true` se o servidor guardou tudo. Falhar aqui nunca desfaz a importação, que já foi feita. */
export async function guardarResultadoDaImportacao(
  ponte: PonteDoCorona,
  loteId: string,
  tipo: 'PLANILHA' | 'FOTOS',
  arquivoNome: string | undefined,
  linhas: LinhaDeImportacao[],
): Promise<boolean> {
  for (let i = 0; i < linhas.length; i += LINHAS_POR_ENVIO_DE_IMPORTACAO) {
    const r = await chamar(ponte, 'importacoes.registrar', {
      importacaoId: loteId,
      importacao: {
        tipo,
        ...(arquivoNome ? { arquivoNome } : {}),
        linhas: linhas.slice(i, i + LINHAS_POR_ENVIO_DE_IMPORTACAO),
      },
    });
    if (!r.ok) return false;
  }
  return true;
}

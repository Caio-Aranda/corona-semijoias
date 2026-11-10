import { randomUUID } from 'node:crypto';
import type { ArquivoDeTexto } from './arquivo-de-texto';

/** UUID v4: é o formato que a API aceita em `dispositivoId`. */
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/**
 * Identificador deste aparelho (ADR-0004, item 4): um UUID aleatório criado na primeira abertura
 * e guardado num arquivo da pasta de dados do aplicativo. Não vem de hardware, nome do computador
 * nem usuário do Windows. Só o processo principal o conhece: vai no corpo do login e da renovação,
 * nunca é mostrado nem entregue à tela, e não serve como segredo.
 *
 * Se o arquivo não existir ou estiver estragado, nasce um novo. Se não der para gravar, vale o novo
 * só nesta abertura (o aplicativo não deixa de funcionar por causa disso).
 */
export async function identificadorDoAparelho(
  arquivo: ArquivoDeTexto,
  gerar: () => string = randomUUID,
): Promise<string> {
  const guardado = (await arquivo.ler().catch(() => null))?.trim() ?? '';
  if (UUID_V4.test(guardado)) return guardado.toLowerCase();

  const novo = gerar();
  await arquivo.gravar(novo).catch(() => undefined);
  return novo;
}

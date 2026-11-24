/**
 * Prepara a conversa do WhatsApp com o cliente (tarefa 9.3; §23.11). O sistema só ABRE a conversa com o número e uma mensagem
 * curta pronta; quem anexa o PDF e envia é o representante. Nada é enviado sozinho.
 */

/** Tamanho que o WhatsApp aceita bem num endereço; a mensagem é curta de propósito. */
export const MAXIMO_DA_MENSAGEM = 600;

/**
 * O número do cliente como o WhatsApp quer (só dígitos, com o código do país), ou `null` se não parece um telefone.
 * A loja fica na fronteira (Brasil e Paraguai): com 10 ou 11 dígitos sem código, vale o Brasil (55); número paraguaio
 * escrito com o zero na frente ("0981 123 456") vira 595 e os nove dígitos seguintes.
 */
export function numeroParaWhatsapp(telefone: string | null | undefined): string | null {
  if (!telefone) return null;
  let d = telefone.replace(/\D/g, '');
  if (d.startsWith('00')) d = d.slice(2);
  if (d.length < 8) return null;
  if (d.startsWith('595') && d.length === 12) return d;
  if (d.startsWith('55') && (d.length === 12 || d.length === 13)) return d;
  // Paraguai sem código do país: 0 + 9 dígitos (celular 09xx) ou 0 + 8.
  if (d.startsWith('09') && d.length === 10) return `595${d.slice(1)}`;
  if (d.length === 10 || d.length === 11) {
    // Brasil: DDD + número (o DDD nunca começa com zero).
    if (d.startsWith('0')) d = d.slice(1);
    return d.length === 10 || d.length === 11 ? `55${d}` : null;
  }
  return d.length <= 15 ? d : null;
}

/**
 * O endereço `https://wa.me/<número>?text=...` ou `null` se o telefone não serve. Com `semNumero` (relatório para o próprio
 * representante, que não tem cliente), abre o WhatsApp com a mensagem pronta e a pessoa escolhe a conversa.
 */
export function enderecoDoWhatsapp(
  telefone: string | null | undefined,
  mensagem: string,
  semNumero = false,
): string | null {
  const texto = mensagem.trim().slice(0, MAXIMO_DA_MENSAGEM);
  if (semNumero) return `https://wa.me/?text=${encodeURIComponent(texto)}`;
  const numero = numeroParaWhatsapp(telefone);
  if (!numero) return null;
  return `https://wa.me/${numero}?text=${encodeURIComponent(texto)}`;
}

/** Confere que um endereço é mesmo uma conversa do WhatsApp (o processo principal só abre este tipo de endereço). */
export function ehEnderecoDoWhatsapp(endereco: string): boolean {
  try {
    const u = new URL(endereco);
    return (
      u.protocol === 'https:' &&
      u.hostname === 'wa.me' &&
      u.port === '' &&
      u.username === '' &&
      u.password === '' &&
      /^\/(\d{8,15})?$/.test(u.pathname) &&
      u.hash === ''
    );
  } catch {
    return false;
  }
}

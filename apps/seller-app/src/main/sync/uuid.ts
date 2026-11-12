import { randomBytes } from 'node:crypto';

/**
 * UUID v7 (RFC 9562): 48 bits de milissegundos e o resto sorteado. É o id que o computador dá às operações e às linhas
 * que ele cria (ADR-0013, item 2): ordenável pela hora e sem colisão entre computadores.
 */
export function uuidV7(
  agora: Date = new Date(),
  sorteio: (n: number) => Buffer = randomBytes,
): string {
  const b = Buffer.alloc(16);
  const ms = BigInt(Math.max(0, Math.floor(agora.getTime())));
  for (let i = 0; i < 6; i++) b[i] = Number((ms >> BigInt(8 * (5 - i))) & 0xffn);
  sorteio(10).copy(b, 6);
  b[6] = ((b[6] ?? 0) & 0x0f) | 0x70;
  b[8] = ((b[8] ?? 0) & 0x3f) | 0x80;
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

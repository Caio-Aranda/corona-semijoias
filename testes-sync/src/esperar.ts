/** Espera por condição, nunca por tempo fixo: tenta até dar certo ou acabar o limite, e então diz o que faltou. */
export async function esperarAte(
  condicao: () => boolean | Promise<boolean>,
  o: { limiteMs?: number; intervaloMs?: number; descricao: string | (() => string) },
): Promise<void> {
  const limite = Date.now() + (o.limiteMs ?? 30_000);
  let ultimoErro: unknown;
  for (;;) {
    try {
      if (await condicao()) return;
      ultimoErro = undefined;
    } catch (erro) {
      ultimoErro = erro;
    }
    if (Date.now() > limite) {
      const texto = typeof o.descricao === 'function' ? o.descricao() : o.descricao;
      throw new Error(
        `Não aconteceu a tempo: ${texto}` +
          (ultimoErro instanceof Error ? ` (último erro: ${ultimoErro.message})` : ''),
      );
    }
    await new Promise((ok) => setTimeout(ok, o.intervaloMs ?? 100));
  }
}

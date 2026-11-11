// Peças puras da publicação do Corona Gestão (ADR-0009, item 3). Testadas em publicacao.test.mjs.

/** Lê "version", "path" e "sha512" do latest.yml (formato simples do electron-builder). */
export function lerLatest(texto) {
  const campo = (nome) => {
    const m = new RegExp(String.raw`^${nome}:\s*(.+?)\s*$`, 'm').exec(texto);
    return m ? m[1].replace(/^['"]|['"]$/g, '') : null;
  };
  const versao = campo('version');
  const arquivo = campo('path');
  const sha512 = campo('sha512');
  if (!versao || !arquivo || !sha512) {
    throw new Error('O latest.yml não tem version, path e sha512.');
  }
  return { versao, arquivo, sha512 };
}

/** Compara duas versões de três números. Negativo se a < b, zero se iguais, positivo se a > b. */
export function compararVersoes(a, b) {
  const numeros = (v) => {
    const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(v);
    if (!m) throw new Error(`Versão inválida: "${v}". Use três números, como 1.0.1.`);
    return m.slice(1).map(Number);
  };
  const [pa, pb] = [numeros(a), numeros(b)];
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pa[i] - pb[i];
  return 0;
}

/** Só publica versão MAIOR que a que está no ar (o app nunca volta para uma versão antiga). */
export function conferirVersaoNova(publicada, nova) {
  if (publicada === null) return;
  if (compararVersoes(nova, publicada) <= 0) {
    throw new Error(
      `A versão no servidor é ${publicada} e esta é ${nova}. Aumente a "version" no package.json do aplicativo (por exemplo para o próximo número) e tente de novo.`,
    );
  }
}

/**
 * Instaladores a apagar da pasta de atualizações: todos menos os {@code manter} mais novos (por versão, não por
 * ordem alfabética: 1.0.10 é mais novo que 1.0.9).
 */
export function instaladoresAntigos(nomes, prefixo = 'Corona-Gestao', manter = 3) {
  const versaoDe = (n) => /-Setup-(\d+\.\d+\.\d+)\.exe$/.exec(n)?.[1];
  return nomes
    .filter((n) => nomeDoInstaladorValido(n, prefixo))
    .sort((a, b) => compararVersoes(versaoDe(a), versaoDe(b)))
    .slice(0, Math.max(0, nomes.filter((n) => nomeDoInstaladorValido(n, prefixo)).length - manter));
}

/** Nome do instalador: só o que o electron-builder gera, sem caminho. */
export function nomeDoInstaladorValido(nome, prefixo = 'Corona-Gestao') {
  return new RegExp(String.raw`^${prefixo}-Setup-\d+\.\d+\.\d+\.exe$`).test(nome);
}

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
      `A versão no servidor é ${publicada} e esta é ${nova}. Aumente a "version" em apps/owner-app/package.json (por exemplo para o próximo número) e tente de novo.`,
    );
  }
}

/** Nome do instalador: só o que o electron-builder gera, sem caminho. */
export function nomeDoInstaladorValido(nome) {
  return /^Corona-Gestao-Setup-\d+\.\d+\.\d+\.exe$/.test(nome);
}

// Monta o manual em espanhol da dona (Markdown e HTML para o PDF) e o arquivo de tempos do vídeo a partir da MESMA fonte:
// testes-e2e/manual-da-dona/capitulos.json. O manual e o vídeo seguem a mesma ordem e os mesmos nomes de capítulo porque
// os dois nascem deste arquivo (o teste que grava o vídeo lê os mesmos textos).

export const AJUDA = {
  titulo: 'Si necesita ayuda',
  texto:
    'Si algo no funciona o la pantalla muestra un mensaje que no entiende, hable con Caio. Cuéntele qué estaba haciendo y qué mensaje apareció (una foto de la pantalla ayuda mucho).',
};

export const ANTES_DE_EMPEZAR = {
  titulo: 'Antes de empezar',
  parrafos: [
    'Este manual es para usar Corona Gestión todos los días. Cada capítulo tiene los pasos numerados, con una foto de la pantalla, y sigue el mismo orden que el video.',
    'En el programa hay un botón ES / PT arriba a la derecha: ES es español y PT es portugués. Este manual usa los nombres de los botones y de los menús en español.',
    'Todos los nombres, productos y valores de las fotos son de ejemplo (inventados). No son datos reales de la tienda.',
    'Todo el dinero se guarda en Reales. El Dólar y el Guaraní solo se muestran en la pantalla, con la cotización del día.',
  ],
};

/** "mm:ss" (ou "h:mm:ss" depois de uma hora). */
export function formatarTempo(segundos) {
  const total = Math.max(0, Math.round(segundos));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const dois = (n) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${dois(m)}:${dois(s)}` : `${dois(m)}:${dois(s)}`;
}

const numero = (c) => Number(c.id);

/** O índice do manual: o mesmo que a lista de capítulos do vídeo. */
export function indice(dados) {
  return dados.capitulos.map((c) => `${numero(c)}. ${c.titulo}`);
}

export function manualEmMarkdown(dados, { imagens = 'capturas' } = {}) {
  const l = [];
  l.push(`# ${dados.titulo}`, '');
  l.push(`Versión ${dados.versao} · Corona Semijoias`, '');
  l.push(`## ${ANTES_DE_EMPEZAR.titulo}`, '');
  for (const p of ANTES_DE_EMPEZAR.parrafos) l.push(p, '');
  l.push('## Capítulos', '');
  for (const i of indice(dados)) l.push(`- ${i}`);
  l.push('');
  for (const c of dados.capitulos) {
    l.push(`## ${numero(c)}. ${c.titulo}`, '', c.intro, '');
    c.pasos.forEach((p, i) => {
      l.push(`${i + 1}. ${p.texto}`, `   ![${p.texto}](${imagens}/${p.id}.png)`, '');
    });
    if (c.avisos.length > 0) {
      l.push('**Bueno saber**', '');
      for (const a of c.avisos) l.push(`- ${a}`);
      l.push('');
    }
    if (c.id === dados.capitulos.at(-1).id && dados.tablaDeAvisos.length > 0) {
      l.push('| Lo que dice la pantalla | Qué hacer |', '| --- | --- |');
      for (const t of dados.tablaDeAvisos) {
        l.push(`| ${t.aviso.replaceAll('|', '/')} | ${t.queHacer.replaceAll('|', '/')} |`);
      }
      l.push('');
    }
  }
  l.push(`## ${AJUDA.titulo}`, '', AJUDA.texto, '');
  return l.join('\n');
}

const esc = (t) =>
  t
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');

/** O mesmo manual em HTML, com as imagens por caminho de arquivo (o PDF sai do Chromium do Playwright). */
export function manualEmHtml(dados, { imagens = 'capturas' } = {}) {
  const partes = [];
  partes.push(`<h1>${esc(dados.titulo)}</h1>`);
  partes.push(`<p class="sub">Versión ${esc(dados.versao)} · Corona Semijoias</p>`);
  partes.push(`<h2>${esc(ANTES_DE_EMPEZAR.titulo)}</h2>`);
  for (const p of ANTES_DE_EMPEZAR.parrafos) partes.push(`<p>${esc(p)}</p>`);
  partes.push('<h2>Capítulos</h2><ul>');
  for (const i of indice(dados)) partes.push(`<li>${esc(i)}</li>`);
  partes.push('</ul>');
  for (const c of dados.capitulos) {
    partes.push(
      `<section class="capitulo"><h2>${numero(c)}. ${esc(c.titulo)}</h2><p>${esc(c.intro)}</p><ol>`,
    );
    for (const p of c.pasos) {
      partes.push(
        `<li><p class="paso">${esc(p.texto)}</p><img src="${esc(imagens)}/${p.id}.png" alt="${esc(p.texto)}"></li>`,
      );
    }
    partes.push('</ol>');
    if (c.avisos.length > 0) {
      partes.push('<div class="bueno"><strong>Bueno saber</strong><ul>');
      for (const a of c.avisos) partes.push(`<li>${esc(a)}</li>`);
      partes.push('</ul></div>');
    }
    if (c.id === dados.capitulos.at(-1).id && dados.tablaDeAvisos.length > 0) {
      partes.push(
        '<table><thead><tr><th>Lo que dice la pantalla</th><th>Qué hacer</th></tr></thead><tbody>',
      );
      for (const t of dados.tablaDeAvisos) {
        partes.push(`<tr><td>${esc(t.aviso)}</td><td>${esc(t.queHacer)}</td></tr>`);
      }
      partes.push('</tbody></table>');
    }
    partes.push('</section>');
  }
  partes.push(
    `<section class="capitulo"><h2>${esc(AJUDA.titulo)}</h2><p>${esc(AJUDA.texto)}</p></section>`,
  );
  const estilo = `
    body { font-family: Arial, Helvetica, sans-serif; color: #231812; font-size: 12.5pt; line-height: 1.45; }
    h1 { font-family: Georgia, serif; color: #8a6420; font-size: 30pt; margin: 0 0 4pt; }
    h2 { font-family: Georgia, serif; color: #8a6420; font-size: 20pt; border-bottom: 2px solid #c9a24d; padding-bottom: 4pt; margin-top: 0; }
    .sub { color: #5c4a3a; margin-top: 0; }
    .capitulo { break-before: page; }
    ol { padding-left: 20pt; }
    li { margin-bottom: 14pt; break-inside: avoid; }
    .paso { font-weight: 700; margin: 0 0 6pt; }
    img { width: 100%; border: 1px solid #cdbfae; border-radius: 6px; }
    .bueno { background: #faf4e6; border-left: 5px solid #c9a24d; padding: 6pt 12pt; margin: 8pt 0; }
    table { border-collapse: collapse; width: 100%; font-size: 11pt; }
    th, td { border: 1px solid #cdbfae; padding: 6pt 8pt; text-align: left; vertical-align: top; }
    th { background: #f1e6cf; }
  `;
  return `<!doctype html><html lang="es"><head><meta charset="utf-8"><title>${esc(dados.titulo)}</title><style>${estilo}</style></head><body>${partes.join('\n')}</body></html>`;
}

/** O arquivo de tempos do vídeo: um capítulo por linha, "mm:ss Nome", na ordem do manual. */
export function temposEmMarkdown(dados, tiempos) {
  const porId = new Map(tiempos.map((t) => [t.id, t.segundo]));
  const l = [
    `# Tiempos del video: ${dados.titulo}`,
    '',
    'Un capítulo por pantalla, en el mismo orden y con los mismos nombres que el manual (`manual-corona-gestao-es.md`).',
    '',
    '```',
  ];
  for (const c of dados.capitulos) {
    const s = porId.get(c.id);
    if (s === undefined) throw new Error(`O capítulo ${c.id} não tem tempo gravado.`);
    l.push(`${formatarTempo(s)} ${c.titulo}`);
  }
  l.push('```', '');
  return l.join('\n');
}

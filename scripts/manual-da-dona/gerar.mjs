// Gera o manual da dona a partir de testes-e2e/manual-da-dona/capitulos.json e das capturas gravadas pelo teste
// testes-e2e/testes/zz-manual-da-dona.test.ts.
//   node scripts/manual-da-dona/gerar.mjs --gravacao C:/Projetos/manual-lucia/gravacao [--sem-pdf]
// Copia as capturas para docs/manuais/capturas, escreve docs/manuais/manual-corona-gestao-es.md, o PDF e
// docs/manuais/video-tempos.md (os tempos vêm de <gravacao>/tiempos.json).
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { manualEmHtml, manualEmMarkdown, temposEmMarkdown } from './montar.mjs';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const argumentos = process.argv.slice(2);
const valor = (nome) => {
  const i = argumentos.indexOf(nome);
  return i >= 0 ? argumentos[i + 1] : undefined;
};
const gravacao = valor('--gravacao');
if (!gravacao) {
  console.error('Uso: node scripts/manual-da-dona/gerar.mjs --gravacao <pasta> [--sem-pdf]');
  process.exit(1);
}
const dados = JSON.parse(
  readFileSync(join(raiz, 'testes-e2e', 'manual-da-dona', 'capitulos.json'), 'utf8'),
);
const saida = join(raiz, 'docs', 'manuais');
const capturas = join(saida, 'capturas');
mkdirSync(capturas, { recursive: true });

for (const c of dados.capitulos) {
  for (const p of c.pasos) {
    const origem = join(gravacao, 'capturas', `${p.id}.png`);
    if (!existsSync(origem)) throw new Error(`Falta a captura do passo ${p.id}: ${origem}`);
    copyFileSync(origem, join(capturas, `${p.id}.png`));
  }
}
writeFileSync(join(saida, 'manual-corona-gestao-es.md'), manualEmMarkdown(dados));
const tiempos = JSON.parse(readFileSync(join(gravacao, 'tiempos.json'), 'utf8'));
writeFileSync(join(saida, 'video-tempos.md'), temposEmMarkdown(dados, tiempos));
console.log(`Manual e tempos escritos em ${saida} (${readdirSync(capturas).length} capturas).`);

if (!argumentos.includes('--sem-pdf')) {
  const html = join(saida, '.manual-es.tmp.html');
  writeFileSync(html, manualEmHtml(dados));
  const { chromium } = await import('@playwright/test');
  const navegador = await chromium.launch();
  try {
    const pagina = await navegador.newPage();
    await pagina.goto(pathToFileURL(html).href);
    await pagina.pdf({
      path: join(saida, 'manual-corona-gestao-es.pdf'),
      format: 'A4',
      printBackground: true,
      margin: { top: '16mm', bottom: '16mm', left: '14mm', right: '14mm' },
      displayHeaderFooter: true,
      headerTemplate: '<span></span>',
      footerTemplate:
        '<div style="font-size:8pt;width:100%;text-align:center;color:#5c4a3a">Corona Gestión · Manual · página <span class="pageNumber"></span> de <span class="totalPages"></span></div>',
    });
  } finally {
    await navegador.close();
    rmSync(html, { force: true });
  }
  console.log('PDF escrito em docs/manuais/manual-corona-gestao-es.pdf');
}

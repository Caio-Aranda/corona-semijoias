import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  formatarTempo,
  indice,
  manualEmHtml,
  manualEmMarkdown,
  temposEmMarkdown,
} from './montar.mjs';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const dados = JSON.parse(
  readFileSync(join(raiz, 'testes-e2e', 'manual-da-dona', 'capitulos.json'), 'utf8'),
);

test('os tempos saem como mm:ss (e h:mm:ss depois de uma hora)', () => {
  assert.equal(formatarTempo(0), '00:00');
  assert.equal(formatarTempo(70.4), '01:10');
  assert.equal(formatarTempo(599.6), '10:00');
  assert.equal(formatarTempo(3725), '1:02:05');
  assert.equal(formatarTempo(-3), '00:00');
});

test('os capítulos e os passos têm ids únicos e seguem a ordem 01, 02, 03...', () => {
  const ids = new Set();
  dados.capitulos.forEach((c, i) => {
    assert.equal(c.id, String(i + 1).padStart(2, '0'));
    assert.ok(c.pasos.length > 0, `capítulo ${c.id} sem passos`);
    c.pasos.forEach((p, j) => {
      assert.equal(p.id, `${c.id}-${j + 1}`);
      assert.ok(!ids.has(p.id), `passo repetido ${p.id}`);
      ids.add(p.id);
      assert.ok(p.texto.length > 10 && p.texto.length < 260, `texto do passo ${p.id}`);
    });
  });
});

test('o teste que grava o vídeo usa todos os passos e capítulos do manual (e só eles)', () => {
  const teste = readFileSync(
    join(raiz, 'testes-e2e', 'testes', 'zz-manual-da-dona.test.ts'),
    'utf8',
  );
  const usados = new Set([...teste.matchAll(/paso\(janela, '(\d\d-\d+)'/g)].map((m) => m[1]));
  const capitulosUsados = new Set(
    [...teste.matchAll(/capitulo\(janela, '(\d\d)'\)/g)].map((m) => m[1]),
  );
  const esperados = dados.capitulos.flatMap((c) => c.pasos.map((p) => p.id));
  assert.deepEqual([...usados].sort(), [...esperados].sort());
  assert.deepEqual(
    [...capitulosUsados].sort(),
    dados.capitulos.map((c) => c.id),
  );
});

test('o manual em Markdown tem um passo numerado e uma captura por passo, na ordem do índice', () => {
  const md = manualEmMarkdown(dados);
  let ultimo = -1;
  for (const c of dados.capitulos) {
    const posicao = md.indexOf(`## ${Number(c.id)}. ${c.titulo}`);
    assert.ok(posicao > ultimo, `capítulo ${c.id} fora de ordem`);
    ultimo = posicao;
    c.pasos.forEach((p, i) => {
      assert.ok(md.includes(`${i + 1}. ${p.texto}`), `texto do passo ${p.id}`);
      assert.ok(md.includes(`](capturas/${p.id}.png)`), `captura do passo ${p.id}`);
    });
  }
  for (const linha of indice(dados)) assert.ok(md.includes(`- ${linha}`));
  assert.ok(md.includes('hable con Caio'));
});

test('a tabela de avisos entra no último capítulo e o HTML escapa o texto', () => {
  const md = manualEmMarkdown(dados);
  assert.ok(md.includes('| Lo que dice la pantalla | Qué hacer |'));
  assert.ok(md.includes('Registrar semana'));
  const html = manualEmHtml({
    ...dados,
    capitulos: [
      {
        id: '01',
        titulo: 'A <b>&</b>',
        intro: 'x',
        pasos: [{ id: '01-1', texto: 'a "b"' }],
        avisos: [],
      },
    ],
    tablaDeAvisos: [],
  });
  assert.ok(html.includes('A &lt;b&gt;&amp;&lt;/b&gt;'));
  assert.ok(html.includes('alt="a &quot;b&quot;"'));
});

test('o arquivo de tempos tem um capítulo por linha, na ordem do manual, e falha se falta um tempo', () => {
  const tiempos = dados.capitulos.map((c, i) => ({ id: c.id, titulo: c.titulo, segundo: i * 70 }));
  const texto = temposEmMarkdown(dados, tiempos);
  const linhas = texto.split('\n').filter((l) => /^\d\d:\d\d /.test(l));
  assert.equal(linhas.length, dados.capitulos.length);
  assert.equal(linhas[0], `00:00 ${dados.capitulos[0].titulo}`);
  assert.equal(linhas[1], `01:10 ${dados.capitulos[1].titulo}`);
  assert.throws(() => temposEmMarkdown(dados, tiempos.slice(1)), /não tem tempo/);
});

test('o manual e o arquivo de tempos que estão em docs/manuais seguem o mesmo índice (se já gerados)', () => {
  const manual = join(raiz, 'docs', 'manuais', 'manual-corona-gestao-es.md');
  const tempos = join(raiz, 'docs', 'manuais', 'video-tempos.md');
  if (!existsSync(manual) || !existsSync(tempos)) return;
  const md = readFileSync(manual, 'utf8');
  const t = readFileSync(tempos, 'utf8');
  for (const c of dados.capitulos) {
    assert.ok(md.includes(`## ${Number(c.id)}. ${c.titulo}`), `manual sem o capítulo ${c.id}`);
    assert.ok(t.includes(` ${c.titulo}\n`), `tempos sem o capítulo ${c.id}`);
  }
});

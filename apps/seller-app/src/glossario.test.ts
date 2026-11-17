import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Auditoria M6: as palavras proibidas na tela (docs/glossario-interface.md, parte 2) não podem aparecer
 * nos textos do Corona Vendas. Se falhar, corrija o TEXTO (e o glossário primeiro, se o texto aprovado mudar);
 * nunca a lista de palavras.
 */
const aqui = dirname(fileURLToPath(import.meta.url));
const glossario = readFileSync(
  join(aqui, '..', '..', '..', 'docs', 'glossario-interface.md'),
  'utf8',
);

function bloco(nome: string): string[] {
  const achou = new RegExp(`<!-- ${nome}:INICIO -->([\\s\\S]*?)<!-- ${nome}:FIM -->`).exec(
    glossario,
  );
  expect(achou, `bloco ${nome} do glossário`).not.toBeNull();
  return (achou as RegExpExecArray)[1]!
    .split(/\r?\n/)
    .map((linha) => linha.trim())
    .filter((linha) => linha !== '' && !linha.startsWith('```'));
}

const blocoA = bloco('PROIBIDAS-A');
const blocoB = bloco('PROIBIDAS-B');
const blocoEs = bloco('PROIBIDAS-ES');

const escapar = (t: string) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const FORMAS_EXTRAS: Record<string, string> = {
  sync: 'sincroniz\\p{L}*',
  sincronização: 'sincroniz\\p{L}*',
  'fila de sincronização': 'sincroniz\\p{L}*',
  bipar: 'bip\\p{L}*',
  bipe: 'bip\\p{L}*',
  movimentação: 'movimenta\\p{L}*',
};
const LETRA = '\\p{L}\\p{N}_';

function padraoA(palavra: string): RegExp {
  const baixa = palavra.toLowerCase();
  const plural = `(?:s|es|${baixa.endsWith('ção') ? 'ções' : 's'})?`;
  const extra = FORMAS_EXTRAS[baixa] ? `|${FORMAS_EXTRAS[baixa]}` : '';
  return new RegExp(`(?<![${LETRA}])(?:${escapar(baixa)}${plural}${extra})(?![${LETRA}])`, 'iu');
}
const padraoB = (palavra: string) =>
  new RegExp(`(?<![A-Za-z0-9_])${escapar(palavra)}(?![A-Za-z0-9_])`);

/** Os textos entre aspas dos arquivos de textos, em português e em espanhol (fora dos testes). */
function textosDaTela(arquivos: string[]): { texto: string; arquivo: string }[] {
  const pasta = join(aqui, 'renderer');
  const achados: { texto: string; arquivo: string }[] = [];
  for (const arquivo of arquivos) {
    const codigo = readFileSync(join(pasta, arquivo), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '');
    for (const m of codigo.matchAll(
      /'((?:[^'\\\n]|\\.)*)'|"((?:[^"\\\n]|\\.)*)"|`((?:[^`\\]|\\.)*)`/g,
    )) {
      achados.push({ texto: m[1] ?? m[2] ?? m[3] ?? '', arquivo });
    }
  }
  return achados;
}

describe('os textos do Corona Vendas respeitam as palavras proibidas do glossário (M6)', () => {
  const textos = textosDaTela(['textos-pt.ts']);

  it('há textos para conferir', () => {
    expect(textos.length).toBeGreaterThan(100);
  });

  it('nenhum texto traz palavra do bloco A nem identificador do bloco B', () => {
    const problemas: string[] = [];
    for (const { texto, arquivo } of textos) {
      // "remessa" só aparece no PDF "Nota de remessa" (glossário, §19.1).
      if (texto !== 'Nota de remessa') {
        for (const p of blocoA)
          if (padraoA(p).test(texto)) problemas.push(`"${p}" → "${texto}" (${arquivo})`);
      }
      // O texto que é SÓ o identificador ('CANCELADA') é valor comparado com a API ou carimbo do PDF, não frase de tela.
      for (const p of blocoB)
        if (texto !== p && padraoB(p).test(texto))
          problemas.push(`"${p}" → "${texto}" (${arquivo})`);
    }
    expect(problemas).toEqual([]);
  });
});

describe('os textos em espanhol do Corona Vendas respeitam as palavras proibidas (issue #212)', () => {
  const textos = textosDaTela(['textos-es.ts', 'erros-es.ts']);

  it('há textos para conferir', () => {
    expect(textos.length).toBeGreaterThan(100);
  });

  it('nenhum texto traz palavra proibida em espanhol nem identificador do bloco B', () => {
    const problemas: string[] = [];
    for (const { texto, arquivo } of textos) {
      for (const p of blocoEs)
        if (padraoA(p).test(texto)) problemas.push(`"${p}" → "${texto}" (${arquivo})`);
      for (const p of blocoB)
        if (texto !== p && padraoB(p).test(texto))
          problemas.push(`"${p}" → "${texto}" (${arquivo})`);
    }
    expect(problemas).toEqual([]);
  });
});

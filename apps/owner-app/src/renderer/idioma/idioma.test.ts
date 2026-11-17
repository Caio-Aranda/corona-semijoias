import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { ERROS_EM_ESPANHOL } from './erros-es';
import { definirIdioma, dicionariosRegistrados, idiomaAtual, textosVivos } from './idioma';

// Carrega todos os arquivos de textos para que os dicionários se registrem.
import.meta.glob('../textos*.ts', { eager: true });

type Achado = { caminho: string; texto: string };

/** Anda pelos dois dicionários juntos e diz o que falta, sobra ou tem outro formato. */
function comparar(
  pt: unknown,
  es: unknown,
  caminho: string,
  falhas: string[],
  vistos: Set<unknown>,
) {
  if (pt === es) return; // o mesmo objeto nos dois (um dicionário aninhado)
  if (typeof pt === 'string') {
    if (typeof es !== 'string') falhas.push(`${caminho}: no espanhol deveria ser texto`);
    return;
  }
  if (typeof pt === 'function') {
    if (typeof es !== 'function') falhas.push(`${caminho}: no espanhol deveria ser função`);
    else if (es.length !== pt.length) falhas.push(`${caminho}: número de argumentos diferente`);
    return;
  }
  if (typeof pt !== 'object' || pt === null) return;
  if (vistos.has(pt)) return;
  vistos.add(pt);
  if (typeof es !== 'object' || es === null) {
    falhas.push(`${caminho}: falta no espanhol`);
    return;
  }
  if (Array.isArray(pt)) {
    if (!Array.isArray(es) || es.length !== pt.length)
      falhas.push(`${caminho}: lista de tamanho diferente`);
    else pt.forEach((v, i) => comparar(v, es[i], `${caminho}[${i}]`, falhas, vistos));
    return;
  }
  const chavesPt = Object.keys(pt);
  const chavesEs = Object.keys(es);
  for (const k of chavesPt) {
    if (!chavesEs.includes(k)) falhas.push(`${caminho}.${k}: falta no espanhol`);
    else
      comparar(
        (pt as Record<string, unknown>)[k],
        (es as Record<string, unknown>)[k],
        `${caminho}.${k}`,
        falhas,
        vistos,
      );
  }
  for (const k of chavesEs)
    if (!chavesPt.includes(k)) falhas.push(`${caminho}.${k}: só existe no espanhol`);
}

/** Todos os textos do dicionário (chama as funções com argumentos de teste). */
function coletar(v: unknown, caminho: string, saida: Achado[], vistos = new Set<unknown>()) {
  if (typeof v === 'string') saida.push({ caminho, texto: v });
  else if (typeof v === 'function') {
    try {
      const r = (v as (...a: unknown[]) => unknown)(...Array.from({ length: v.length }, () => 'x'));
      coletar(r, `${caminho}()`, saida, vistos);
    } catch {
      // função que espera outro tipo de argumento: os textos fixos dela são conferidos pelo formato
    }
  } else if (typeof v === 'object' && v !== null && !vistos.has(v)) {
    vistos.add(v);
    for (const [k, x] of Object.entries(v)) coletar(x, `${caminho}.${k}`, saida, vistos);
  }
}

describe('dicionários es e pt', () => {
  it('há dicionários registrados', () => {
    expect(dicionariosRegistrados().length).toBeGreaterThan(0);
  });

  it.each(dicionariosRegistrados().map((d) => [d.nome, d] as const))(
    '%s: o espanhol tem exatamente as mesmas chaves do português',
    (_nome, d) => {
      const falhas: string[] = [];
      comparar(d.pt, d.es, d.nome, falhas, new Set());
      expect(falhas).toEqual([]);
    },
  );

  it.each(dicionariosRegistrados().map((d) => [d.nome, d] as const))(
    '%s: nenhum texto do espanhol ficou em português',
    (_nome, d) => {
      const achados: Achado[] = [];
      const antes = idiomaAtual();
      definirIdioma('es'); // os dicionários aninhados são vivos: leem o idioma de agora
      coletar(d.es, d.nome, achados);
      definirIdioma(antes);
      // ã, õ e ç só existem em português; ñ, ¿ e ¡ só em espanhol.
      const ruins = achados
        .filter((a) => /[ãõç]/i.test(a.texto))
        .map((a) => `${a.caminho}: ${a.texto}`);
      expect(ruins).toEqual([]);
    },
  );
});

describe('troca de idioma', () => {
  it('o dicionário vivo devolve o texto do idioma de agora', () => {
    const t = textosVivos(
      'teste',
      { a: 'Olá', f: (n: string) => `Oi ${n}` },
      { a: 'Hola', f: (n: string) => `Hola ${n}` },
    );
    const antes = idiomaAtual();
    definirIdioma('es');
    expect(t.a).toBe('Hola');
    expect(t.f('Ana')).toBe('Hola Ana');
    definirIdioma('pt');
    expect(t.a).toBe('Olá');
    definirIdioma(antes);
  });
});

describe('erros da API em espanhol', () => {
  it('há texto em espanhol para todos os códigos de erro do servidor', () => {
    const java = readFileSync(
      join(
        __dirname,
        '..',
        '..',
        '..',
        '..',
        '..',
        'backend',
        'src',
        'main',
        'java',
        'br',
        'com',
        'coronasemijoias',
        'api',
        'comum',
        'erros',
        'CodigoErro.java',
      ),
      'utf8',
    );
    const codigos = [...java.matchAll(/^\s{4}([A-Z][A-Z0-9_]+)\(/gm)].map((m) => m[1] as string);
    expect(codigos.length).toBeGreaterThan(150);
    // ACESSO_BLOQUEADO traz os minutos no texto e tem função própria.
    const faltam = codigos.filter((c) => c !== 'ACESSO_BLOQUEADO' && !(c in ERROS_EM_ESPANHOL));
    expect(faltam).toEqual([]);
  });
});

describe('informes en español', () => {
  it('ninguna definición de informe queda con texto en portugués', async () => {
    const antes = idiomaAtual();
    definirIdioma('es');
    vi.resetModules();
    const { RELATORIOS } = await import('../nucleo/relatorios');
    const restos: string[] = [];
    const andar = (v: unknown, caminho: string) => {
      if (typeof v === 'string') {
        if (/[ãõç]|ção|ções|\b(não|você|peças?|vendedor es)\b/i.test(v))
          restos.push(`${caminho}: ${v}`);
      } else if (Array.isArray(v)) v.forEach((x, i) => andar(x, `${caminho}[${i}]`));
      else if (typeof v === 'object' && v !== null) {
        for (const [k, x] of Object.entries(v)) {
          if (['valor', 'chave', 'nome', 'campo', 'tipo', 'filtro', 'abre', 'grupo'].includes(k))
            continue;
          andar(x, `${caminho}.${k}`);
        }
      }
    };
    andar(RELATORIOS, 'RELATORIOS');
    definirIdioma(antes);
    expect(restos).toEqual([]);
  });
});

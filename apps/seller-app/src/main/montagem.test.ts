import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { montarCoronaVendas, type DependenciasDaMontagem } from './montagem';
import type { Cofre } from './tipos';

/** Todas as importações relativas, a partir de um arquivo, seguindo a árvore (só arquivos do próprio app). */
function arvoreDeImportacoes(inicio: string): Map<string, string[]> {
  const vistos = new Map<string, string[]>();
  const pendentes = [inicio];
  while (pendentes.length > 0) {
    const arquivo = pendentes.pop()!;
    if (vistos.has(arquivo)) continue;
    const texto = readFileSync(arquivo, 'utf8');
    const especificadores = [
      ...texto.matchAll(/(?:import|export)[^'"]*?from\s+['"]([^'"]+)['"]/g),
      ...texto.matchAll(/import\(\s*['"]([^'"]+)['"]\s*\)/g),
      ...texto.matchAll(/require\(\s*['"]([^'"]+)['"]\s*\)/g),
    ].map((m) => m[1]!);
    vistos.set(arquivo, especificadores);
    for (const e of especificadores) {
      if (!e.startsWith('.')) continue;
      const base = resolve(dirname(arquivo), e);
      const candidato = [`${base}.ts`, `${base}.tsx`, join(base, 'index.ts')].find((c) =>
        existsSync(c),
      );
      if (candidato) pendentes.push(candidato);
    }
  }
  return vistos;
}

const cofreDeTeste: Cofre = {
  disponivel: () => Promise.resolve(true),
  cifrar: (texto) => Promise.resolve(new TextEncoder().encode(`teste:${texto}`)),
  decifrar: (dados) => Promise.resolve(new TextDecoder().decode(dados).replace(/^teste:/, '')),
};

const pastas: string[] = [];
afterEach(() => {
  for (const p of pastas.splice(0)) rmSync(p, { recursive: true, force: true });
});

function dependencias(
  sobrepor: Partial<DependenciasDaMontagem> = {},
): DependenciasDaMontagem & { pedidos: string[] } {
  const pasta = mkdtempSync(join(tmpdir(), 'corona-montagem-'));
  pastas.push(pasta);
  const pedidos: string[] = [];
  return {
    pastaDeDados: pasta,
    enderecoDoServidor: 'http://127.0.0.1:9',
    versaoDoApp: '1.0.0',
    cofre: cofreDeTeste,
    // Sem servidor: toda chamada falha como "sem internet".
    fetch: (entrada) => {
      pedidos.push(String(entrada instanceof Request ? entrada.url : entrada));
      return Promise.reject(new TypeError('fetch failed'));
    },
    relogio: {
      agora: () => Date.now(),
      agendar: (ms, fn) => {
        const id = setTimeout(fn, ms);
        return () => clearTimeout(id);
      },
    },
    avisarATela: () => {},
    avisarSincronizacao: () => {},
    registrar: () => {},
    pedidos,
    ...sobrepor,
  };
}

describe('montagem do Corona Vendas (tarefa 5.12)', () => {
  it('não importa o Electron, nem direto nem pelos arquivos que usa', () => {
    const arvore = arvoreDeImportacoes(join(import.meta.dirname, 'montagem.ts'));
    expect(arvore.size).toBeGreaterThan(10);
    const comElectron = [...arvore.entries()]
      .filter(([, especificadores]) =>
        especificadores.some((e) => e === 'electron' || e.startsWith('electron/')),
      )
      .map(([arquivo]) => arquivo);
    expect(comElectron).toEqual([]);
    // A montagem é a do aplicativo: o index.ts chama esta função, e não monta nada por conta própria.
    const index = readFileSync(join(import.meta.dirname, 'index.ts'), 'utf8');
    expect(index).toContain('montarCoronaVendas(');
    expect(index).not.toMatch(/new (MotorDeSincronizacao|GuardaDeTokens|ServicoDoBanco)\(/);
  });

  it('monta em Node: sem sessão guardada está "fora", e o motor não liga sem o banco aberto', async () => {
    const d = dependencias();
    const montado = await montarCoronaVendas(d);

    expect(await montado.servico.estouLogado()).toEqual({ estado: 'fora' });
    // O identificador do aparelho nasce na primeira abertura, dentro da pasta de dados.
    expect(existsSync(join(d.pastaDeDados, 'aparelho', 'identificador.txt'))).toBe(true);
    montado.motor.ligar();
    expect(montado.motor.ligado).toBe(false);
    expect(montado.bancoLocal.banco).toBeNull();
    montado.batida();
    montado.fechar();
  });

  it('sem internet, entrar diz "sem conexão" e a chamada saiu pelo fetch recebido', async () => {
    const d = dependencias();
    const montado = await montarCoronaVendas(d);

    expect(await montado.servico.entrar('ana', 'senha-qualquer-1')).toEqual({
      tipo: 'sem-conexao',
    });
    expect(d.pedidos.length).toBeGreaterThan(0);
    expect(d.pedidos.every((u) => u.startsWith('http://127.0.0.1:9/'))).toBe(true);
    montado.fechar();
  });

  it('o mesmo identificador do aparelho ao montar de novo na mesma pasta', async () => {
    const d = dependencias();
    const primeiro = await montarCoronaVendas(d);
    primeiro.fechar();
    const id = readFileSync(join(d.pastaDeDados, 'aparelho', 'identificador.txt'), 'utf8');
    const segundo = await montarCoronaVendas(d);
    segundo.fechar();
    expect(readFileSync(join(d.pastaDeDados, 'aparelho', 'identificador.txt'), 'utf8')).toBe(id);
  });
});

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// Barreira do ADR-0003: o cliente do vendedor não pode ter rota nem tipo que só existe na dona.
const raiz = join(import.meta.dirname, '..');
// A lista mora num arquivo só, lido também pelo lint e pela varredura do pacote no CI.
const MARCADORES = (
  JSON.parse(
    readFileSync(join(raiz, '..', '..', 'scripts', 'marcadores-proibidos.json'), 'utf8'),
  ) as { marcadores: string[] }
).marcadores;

function arquivos(pasta: string): string[] {
  return readdirSync(pasta).flatMap((nome) => {
    if (nome === 'node_modules' || nome === 'test') return [];
    const caminho = join(pasta, nome);
    return statSync(caminho).isDirectory() ? arquivos(caminho) : [caminho];
  });
}

describe('barreira do cliente do vendedor', () => {
  it('não contém nenhum marcador da dona (OpenAPI, código gerado e escrito à mão)', () => {
    const achados = arquivos(raiz).flatMap((arquivo) => {
      const conteudo = readFileSync(arquivo, 'utf8');
      return MARCADORES.filter((m) => conteudo.includes(m)).map((m) => `${arquivo}: ${m}`);
    });
    expect(achados).toEqual([]);
  });
});

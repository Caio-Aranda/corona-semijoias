import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// ADR-0003 item 3: nada deste pacote conhece a dona nem o preço de custo.
// A lista mora num arquivo só (scripts/marcadores-proibidos.json), lido também pelo lint e pelo CI.
const proibidos = (
  JSON.parse(
    readFileSync(join(process.cwd(), '..', '..', 'scripts', 'marcadores-proibidos.json'), 'utf8'),
  ) as { marcadores: string[] }
).marcadores;

function arquivos(pasta: string): string[] {
  return readdirSync(pasta).flatMap((nome) => {
    const caminho = join(pasta, nome);
    return statSync(caminho).isDirectory() ? arquivos(caminho) : [caminho];
  });
}

describe('varredura de termos proibidos', () => {
  const raiz = process.cwd();
  const lista = [...arquivos(join(raiz, 'src')), join(raiz, 'package.json')].filter(
    (f) => !f.endsWith('varredura.test.ts'),
  );

  it.each(proibidos)('nenhum arquivo contém %s', (termo) => {
    const achados = lista.filter((f) => readFileSync(f, 'utf8').includes(termo));
    expect(achados).toEqual([]);
  });
});

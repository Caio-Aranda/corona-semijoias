import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

/** Um arquivo pequeno de texto na pasta de dados do aplicativo (processo principal). */
export interface ArquivoDeTexto {
  ler(): Promise<string | null>;
  gravar(texto: string): Promise<void>;
}

/** Arquivo de verdade no disco. Quem decide o caminho é o chamador (sempre dentro da pasta de dados). */
export function arquivoDeTexto(caminho: string): ArquivoDeTexto {
  return {
    async ler() {
      try {
        return await readFile(caminho, 'utf8');
      } catch {
        return null;
      }
    },
    async gravar(texto) {
      await mkdir(dirname(caminho), { recursive: true });
      await writeFile(caminho, texto, { encoding: 'utf8', mode: 0o600 });
    },
  };
}

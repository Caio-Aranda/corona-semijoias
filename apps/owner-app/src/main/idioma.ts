import { ipcMain, type IpcMainEvent } from 'electron';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { CANAIS } from '../shared/ponte';

let arquivoDoIdioma: string | null = null;

/** O idioma guardado neste computador, para os textos que nascem no processo principal. Sem escolha: espanhol. */
export function idiomaDoComputador(): 'es' | 'pt' {
  if (!arquivoDoIdioma) return 'es';
  try {
    return readFileSync(arquivoDoIdioma, 'utf8').trim() === 'pt' ? 'pt' : 'es';
  } catch {
    return 'es';
  }
}

/** O idioma escolhido fica num arquivinho da pasta do aplicativo, neste computador. A tela só lê e pede para guardar. */
export function registrarIdioma(
  arquivo: string,
  remetenteConfiavel: (e: IpcMainEvent) => boolean,
): void {
  arquivoDoIdioma = arquivo;
  const ler = (): string => {
    try {
      const v = readFileSync(arquivo, 'utf8').trim();
      return v === 'es' || v === 'pt' ? v : '';
    } catch {
      return '';
    }
  };
  ipcMain.on(CANAIS.idiomaLer, (e) => {
    e.returnValue = remetenteConfiavel(e) ? ler() : '';
  });
  ipcMain.on(CANAIS.idiomaGuardar, (e, idioma: unknown) => {
    if (!remetenteConfiavel(e) || (idioma !== 'es' && idioma !== 'pt')) return;
    try {
      mkdirSync(dirname(arquivo), { recursive: true });
      writeFileSync(arquivo, idioma, 'utf8');
    } catch {
      // Sem como guardar: vale só até fechar o programa.
    }
  });
}

import { PARTICAO_DO_ATUALIZADOR } from '@corona/conexao-segura';
import { NET_SESSION_NAME } from 'electron-updater/out/electronHttpExecutor';
import { expect, it } from 'vitest';

it('a partição do certificado fixado é a mesma que o electron-updater usa para baixar', () => {
  expect(PARTICAO_DO_ATUALIZADOR).toBe(NET_SESSION_NAME);
});

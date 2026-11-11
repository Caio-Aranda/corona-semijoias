import { _electron as electron, type ElectronApplication, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DONA, URL_DA_API } from './ambiente';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export interface AppAberto {
  app: ElectronApplication;
  janela: Page;
  /** Pasta onde o Windows "guardaria" os arquivos baixados neste teste. */
  pastaDeDownloads: string;
}

/**
 * Abre o Corona Gestão de verdade (Electron) com uma pasta de dados NOVA (sem sessão guardada), e faz o
 * Windows "guardar" os downloads numa pasta de teste, sem a janela de salvar.
 */
export async function abrirOApp(): Promise<AppAberto> {
  const dados = mkdtempSync(join(tmpdir(), 'corona-e2e-'));
  const pastaDeDownloads = mkdtempSync(join(tmpdir(), 'corona-e2e-baixados-'));
  const app = await electron.launch({
    args: [join(raiz, 'apps', 'owner-app'), `--user-data-dir=${dados}`],
    env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' },
  });
  await app.evaluate(({ session }, pasta) => {
    session.defaultSession.on('will-download', (_evento, item) => {
      item.setSavePath(`${pasta}/${item.getFilename()}`);
    });
  }, pastaDeDownloads);
  const janela = await app.firstWindow();
  await janela.waitForLoadState('domcontentloaded');
  return { app, janela, pastaDeDownloads };
}

export async function entrar(janela: Page, senha: string): Promise<void> {
  await janela.getByLabel('Usuário').fill(DONA.login);
  await janela.getByLabel('Senha', { exact: true }).fill(senha);
  await janela.getByRole('button', { name: 'Entrar' }).click();
}

export async function irPara(janela: Page, rota: string): Promise<void> {
  await janela.evaluate((r) => {
    window.location.hash = `/${r}`;
  }, rota);
}

/** Cliente mínimo da API, só para o teste preparar dados (a tela é o que se testa). */
export async function chamarApi<T = unknown>(
  metodo: 'GET' | 'POST',
  caminho: string,
  token: string | null,
  corpo?: unknown,
): Promise<T> {
  const r = await fetch(`${URL_DA_API}${caminho}`, {
    method: metodo,
    headers: {
      'Content-Type': 'application/json',
      'X-App-Tipo': 'gestao',
      'X-App-Versao': '1.0.0',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(corpo === undefined ? {} : { body: JSON.stringify(corpo) }),
  });
  if (!r.ok) throw new Error(`${metodo} ${caminho} -> ${r.status}: ${await r.text()}`);
  return (await r.json()) as T;
}

export async function tokenDaDona(): Promise<string> {
  const r = await chamarApi<{ tokenDeAcesso: string }>('POST', '/api/auth/login', null, {
    login: DONA.login,
    senha: DONA.senhaNova,
  });
  return r.tokenDeAcesso;
}

export function novoId(): string {
  return randomUUID();
}

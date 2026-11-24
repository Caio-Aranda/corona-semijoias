import {
  _electron as electron,
  expect,
  type ElectronApplication,
  type Page,
} from '@playwright/test';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export interface VendasAberto {
  app: ElectronApplication;
  janela: Page;
  pastaDeDados: string;
}

/**
 * Abre o Corona Vendas de verdade (Electron) com uma pasta de dados NOVA: é "um computador novo" (sem banco, sem sessão e
 * com um identificador de computador que o app cria na hora). O endereço da API já vem embutido no pacote compilado
 * (`preparar.ts`), então não há senha nem segredo aqui: o banco local é cifrado com uma chave que o próprio app guarda no
 * cofre do Windows. Passando `pastaDeDados` de um computador que já existe, abre o MESMO computador de novo.
 */
export async function abrirOVendas(
  idioma: 'es' | 'pt' = 'pt',
  pastaDeDados?: string,
): Promise<VendasAberto> {
  const dados = pastaDeDados ?? mkdtempSync(join(tmpdir(), 'corona-e2e-vendas-'));
  // O programa abre em espanhol; os testes procuram o texto em português, então o idioma é escolhido antes de abrir.
  writeFileSync(join(dados, 'idioma.txt'), idioma, 'utf8');
  const app = await electron.launch({
    args: [join(raiz, 'apps', 'seller-app'), `--user-data-dir=${dados}`],
    env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' },
  });
  const janela = await app.firstWindow();
  await janela.waitForLoadState('domcontentloaded');
  return { app, janela, pastaDeDados: dados };
}

export async function entrarNoVendas(janela: Page, login: string, senha: string): Promise<void> {
  await janela.getByLabel('Usuário').fill(login);
  await janela.getByLabel('Senha', { exact: true }).fill(senha);
  await janela.getByRole('button', { name: 'Entrar' }).click();
}

/** Troca a senha temporária (obrigatória no primeiro acesso). */
export async function trocarSenhaNoVendas(janela: Page, senhaNova: string): Promise<void> {
  await janela.getByLabel('Senha nova', { exact: true }).fill(senhaNova);
  await janela.getByLabel('Repita a senha nova').fill(senhaNova);
  await janela.getByRole('button', { name: 'Salvar senha nova' }).click();
}

/** Primeiro acesso inteiro: senha temporária, troca obrigatória e chegada na tela principal. */
export async function entrarComoRepresentante(
  janela: Page,
  login: string,
  senhaTemporaria: string,
  senhaNova: string,
): Promise<void> {
  await entrarNoVendas(janela, login, senhaTemporaria);
  await trocarSenhaNoVendas(janela, senhaNova);
  await expect(janela.getByRole('heading', { name: 'Gerenciamento Semanal' })).toBeVisible();
}

/** A primeira semana da representante (a tela pede o número da semana em que ela está). */
export async function comecarASemana(janela: Page, numero: number): Promise<void> {
  await janela.getByLabel('Número da semana').fill(String(numero));
  await janela.getByRole('button', { name: 'Começar a semana' }).click();
  await expect(
    janela.getByText(String(numero).padStart(7, '0'), { exact: false }).first(),
  ).toBeVisible();
}

/** Lê um código como o leitor faz: digita e dá Enter. */
export async function lerCodigo(janela: Page, rotulo: string, codigo: string): Promise<void> {
  const campo = janela.getByLabel(rotulo, { exact: true });
  await campo.fill(codigo);
  await campo.press('Enter');
}

/** O indicador do alto da tela ("Com internet. Tudo enviado." etc.). */
export function indicadorDeEnvio(janela: Page) {
  return janela.getByRole('region', { name: 'Situação dos envios' });
}

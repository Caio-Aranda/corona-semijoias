import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { DONA } from '../ambiente';
import { abrirOApp, irPara, type AppAberto } from '../apoio';

// O seletor ES / PT do Corona Gestão: o programa abre em espanhol, troca na hora e lembra a escolha.
// Se CORONA_FOTOS apontar para uma pasta, tira as fotos das telas principais nos dois idiomas.
// Roda depois dos outros (ordem alfabética): usa a senha nova da dona e o acerto já fechado.
test.describe.configure({ mode: 'serial' });

let sessao: AppAberto | undefined;
test.afterEach(async () => {
  await sessao?.app.close();
  sessao = undefined;
});

const PASTA = process.env['CORONA_FOTOS'];

async function foto(janela: Page, idioma: string, nome: string): Promise<void> {
  if (!PASTA) return;
  mkdirSync(PASTA, { recursive: true });
  await janela.waitForTimeout(900);
  await janela.screenshot({ path: join(PASTA, `${nome}-${idioma}.png`) });
}

async function escolher(janela: Page, idioma: 'ES' | 'PT'): Promise<void> {
  await janela.getByRole('button', { name: idioma === 'ES' ? 'Español' : 'Português' }).click();
  await janela.waitForLoadState('domcontentloaded');
  await expect(
    janela.getByRole('button', { name: idioma === 'ES' ? 'Español' : 'Português' }),
  ).toHaveAttribute('aria-pressed', 'true');
}

test('o programa abre em espanhol, troca para português na hora e lembra a escolha', async () => {
  sessao = await abrirOApp('es');
  const { janela } = sessao;
  await expect(janela.getByLabel('Usuario')).toBeVisible();
  await foto(janela, 'es', '1-entrada');
  await escolher(janela, 'PT');
  await expect(janela.getByLabel('Usuário')).toBeVisible();
  await foto(janela, 'pt', '1-entrada');
  await sessao.app.close();
  sessao = undefined;
});

test('telas principais nos dois idiomas', async () => {
  sessao = await abrirOApp('es');
  const { janela } = sessao;
  await janela.getByLabel('Usuario').fill(DONA.login);
  await janela.getByLabel('Contraseña', { exact: true }).fill(DONA.senhaNova);
  await janela.getByRole('button', { name: 'Entrar' }).click();
  await expect(janela.getByRole('button', { name: 'Salir' })).toBeVisible();

  const telas = async (idioma: 'es' | 'pt') => {
    await irPara(janela, 'inicio');
    await foto(janela, idioma, '2-inicio');
    await irPara(janela, 'cadastros/produtos');
    await janela
      .getByRole('button', { name: idioma === 'es' ? 'Registrar producto' : 'Cadastrar produto' })
      .first()
      .click();
    await foto(janela, idioma, '3-cadastro-de-produto');
    await irPara(janela, 'relatorios/vendas-por-vendedor');
    await foto(janela, idioma, '4-relatorio');
    await irPara(janela, 'acertos/lista');
    await foto(janela, idioma, '5-acerto-lista');
    const abrir = janela
      .getByRole('button', { name: /^(Abrir|Abrir acerto|Abrir acerto de)/ })
      .first();
    if (await abrir.count()) {
      await abrir.click();
      await foto(janela, idioma, '6-acerto-ficha');
    }
  };
  await telas('es');
  await escolher(janela, 'PT');
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();
  await telas('pt');
});

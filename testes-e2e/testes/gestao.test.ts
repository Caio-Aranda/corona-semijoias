import { expect, test } from '@playwright/test';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { DONA } from '../ambiente';
import {
  abrirOApp,
  chamarApi,
  entrar,
  irPara,
  novoId,
  tokenDaDona,
  type AppAberto,
} from '../apoio';

// Um app, uma API e um banco de teste; os testes seguem a vida real da dona, na ordem.
test.describe.configure({ mode: 'serial' });

let sessao: AppAberto | undefined;
test.afterEach(async () => {
  await sessao?.app.close();
  sessao = undefined;
});

test('a dona entra com a senha temporária e é obrigada a criar a senha nova', async () => {
  sessao = await abrirOApp();
  const { janela } = sessao;

  await entrar(janela, DONA.senhaTemporaria);
  await expect(janela.getByRole('heading', { name: 'Crie sua senha nova.' })).toBeVisible();

  // Senha fraca é recusada na própria tela, em português.
  await janela.getByLabel('Senha nova', { exact: true }).fill('curta');
  await janela.getByLabel('Repita a senha nova').fill('curta');
  await janela.getByRole('button', { name: 'Salvar senha nova' }).click();
  await expect(janela.getByText(/pelo menos 8 caracteres/).first()).toBeVisible();

  await janela.getByLabel('Senha nova', { exact: true }).fill(DONA.senhaNova);
  await janela.getByLabel('Repita a senha nova').fill(DONA.senhaNova);
  await janela.getByRole('button', { name: 'Salvar senha nova' }).click();
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();

  // A senha nova vale: sair e entrar de novo.
  await janela.getByRole('button', { name: 'Sair' }).click();
  await entrar(janela, DONA.senhaNova);
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();
});

test('a dona cadastra um fornecedor e ele aparece na lista', async () => {
  sessao = await abrirOApp();
  const { janela } = sessao;
  await entrar(janela, DONA.senhaNova);
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();

  await irPara(janela, 'cadastros/fornecedores');
  await janela.getByRole('button', { name: 'Novo fornecedor' }).click();
  await janela.getByLabel('Nome do fornecedor (razão social)').fill('Fornecedor E2E Ltda');
  await janela.getByLabel('Nome do contato').fill('Contato de Teste');
  await janela.getByRole('button', { name: /^(Salvar|Cadastrar)/ }).click();

  await expect(janela.getByText('Fornecedor E2E Ltda foi cadastrado.')).toBeVisible();
  await expect(janela.getByRole('cell', { name: 'Fornecedor E2E Ltda' })).toBeVisible();
});

test('entrada de compra lendo o código (leitor como teclado) soma no estoque', async () => {
  // Dados criados pelo teste: uma categoria e uma peça com código da loja.
  const token = await tokenDaDona();
  const categoria = await chamarApi<{ id: string }>('POST', '/api/dona/categorias', token, {
    id: novoId(),
    nome: 'Categoria E2E',
  });
  const produto = await chamarApi<{ id: string; estoqueAtual: number }>(
    'POST',
    '/api/dona/produtos',
    token,
    {
      id: novoId(),
      nome: 'Brinco E2E',
      categoriaId: categoria.id,
      precoVenda: '49.90',
      precoCusto: '12.50',
      codigos: [{ codigo: 'E2E-0001', tipo: 'LOJA', principal: true }],
    },
  );
  expect(produto.estoqueAtual).toBe(0);

  sessao = await abrirOApp();
  const { janela } = sessao;
  await entrar(janela, DONA.senhaNova);
  await irPara(janela, 'estoque/receber-compra');
  await janela
    .getByLabel('Fornecedor', { exact: true })
    .selectOption({ label: 'Fornecedor E2E Ltda' });

  // O leitor digita o código e aperta Enter; ler de novo a mesma peça soma 1.
  const campo = janela.getByLabel('Código da peça ou nome');
  await campo.fill('E2E-0001');
  await campo.press('Enter');
  await expect(janela.getByText('Brinco E2E entrou na compra.')).toBeVisible();
  await campo.fill('e2e-0001');
  await campo.press('Enter');
  await expect(janela.getByText('Brinco E2E: agora são 2 peças nesta compra.')).toBeVisible();

  await janela.getByRole('button', { name: 'Confirmar compra' }).click();
  await janela.getByRole('dialog').getByRole('button', { name: 'Confirmar compra' }).click();
  await expect(janela.getByText('Compra gravada.')).toBeVisible();

  const depois = await chamarApi<{ estoqueAtual: number }>(
    'GET',
    `/api/dona/produtos/${produto.id}`,
    token,
  );
  expect(depois.estoqueAtual).toBe(2);
});

test('exporta um relatório em CSV e o arquivo gerado tem a peça cadastrada', async () => {
  sessao = await abrirOApp();
  const { janela, pastaDeDownloads } = sessao;
  await entrar(janela, DONA.senhaNova);
  await irPara(janela, 'relatorios/produtos');
  await expect(janela.getByRole('cell', { name: 'Brinco E2E' }).first()).toBeVisible();

  await janela.getByRole('button', { name: 'Baixar em CSV' }).click();
  await expect
    .poll(() => readdirSync(pastaDeDownloads).filter((f) => f.endsWith('.csv')).length, {
      timeout: 20_000,
    })
    .toBe(1);
  const nome = readdirSync(pastaDeDownloads).find((f) => f.endsWith('.csv'));
  const texto = readFileSync(join(pastaDeDownloads, nome ?? ''), 'utf8');
  expect(texto).toContain('Brinco E2E');
  expect(texto).toContain('E2E-0001');
});

import { expect, test } from '@playwright/test';
import { REPRESENTANTE } from '../ambiente';
import { chamarApi } from '../apoio';
import { abrirOVendas, entrarNoVendas, type VendasAberto } from '../apoio-do-vendas';
import { criarMassaDoVendas, ocuparOPrimeiroComputador } from '../massa-do-vendas';
import { RedeDoVendas } from '../rede-do-vendas';

// Primeiro acesso da representante: senha temporária, troca obrigatória e os dados da loja chegando (maleta e cliente).
// E o computador novo: quem já tem um computador ativo espera a dona liberar o novo.
let rede: RedeDoVendas;
let sessao: VendasAberto | undefined;

test.beforeAll(async () => {
  rede = await RedeDoVendas.ligar();
});
test.afterAll(() => rede.desligar());
test.afterEach(async () => {
  await sessao?.app.close();
  sessao = undefined;
});

test('primeiro acesso: senha temporária, troca obrigatória e a maleta aparece', async () => {
  const m = await criarMassaDoVendas('Primeiro acesso');
  sessao = await abrirOVendas();
  const { janela } = sessao;

  // A senha errada não entra; a temporária entra mas só até a troca da senha.
  await entrarNoVendas(janela, m.login, 'SenhaErrada123');
  await expect(
    janela.getByRole('alert').or(janela.getByText(/senha|usuário/i).first()),
  ).toBeVisible();
  await entrarNoVendas(janela, m.login, m.senhaTemporaria);
  await expect(janela.getByRole('heading', { name: 'Crie sua senha nova.' })).toBeVisible();
  await janela.getByLabel('Senha nova', { exact: true }).fill('curta');
  await janela.getByLabel('Repita a senha nova').fill('curta');
  await janela.getByRole('button', { name: 'Salvar senha nova' }).click();
  await expect(janela.getByText(/A senha nova ainda não está boa/)).toBeVisible();
  await janela.getByLabel('Senha nova', { exact: true }).fill(REPRESENTANTE.senhaNova);
  await janela.getByLabel('Repita a senha nova').fill(REPRESENTANTE.senhaNova);
  await janela.getByRole('button', { name: 'Salvar senha nova' }).click();
  await expect(janela.getByRole('heading', { name: 'Gerenciamento Semanal' })).toBeVisible();
  await expect(janela.getByText(m.nomeDaRepresentante)).toBeVisible();

  // Os dados da loja chegam sozinhos: a maleta com as duas peças e a cliente da representante.
  await janela.getByRole('button', { name: 'Maletas', exact: true }).click();
  await expect(
    janela.getByText(/Enviada 10 · Com clientes 0 · Vendida 0 · Disponível 10/),
  ).toBeVisible();
  await expect(janela.getByText(`${m.codigoDoColar} · ${m.nomeDoColar}`)).toBeVisible();
  await expect(janela.getByText(`${m.codigoDaPulseira} · ${m.nomeDaPulseira}`)).toBeVisible();
  await janela.getByRole('button', { name: 'Voltar' }).first().click();
  await janela.getByRole('button', { name: 'Clientes', exact: true }).click();
  await expect(janela.getByText(m.nomeDaCliente)).toBeVisible();
});

test('computador novo: entra, espera a dona liberar e só então a maleta chega', async () => {
  const m = await criarMassaDoVendas('Computador novo');
  // O primeiro computador da representante é o "antigo": já está ativo, com a senha trocada.
  await ocuparOPrimeiroComputador(m);

  sessao = await abrirOVendas();
  const { janela } = sessao;
  await entrarNoVendas(janela, m.login, REPRESENTANTE.senhaNova);
  await expect(janela.getByRole('heading', { name: 'Gerenciamento Semanal' })).toBeVisible();
  await janela.getByRole('button', { name: 'Maletas', exact: true }).click();
  // Sem a liberação da dona, nenhuma maleta chega a este computador.
  await expect(janela.getByText(/Maleta \d{4}/)).toHaveCount(0);

  const aparelhos = await chamarApi<{ id: string; situacao: string }[]>(
    'GET',
    `/api/dona/vendedores/${m.vendedorId}/aparelhos`,
    m.token,
  );
  const novo = aparelhos.find((a) => a.situacao === 'AGUARDANDO_LIBERACAO');
  expect(novo, 'o computador novo devia estar esperando a dona').toBeTruthy();
  await chamarApi('POST', `/api/dona/aparelhos/${novo!.id}/liberar`, m.token);

  // Liberado, o computador novo recebe a maleta (a tela confere sozinha; voltar e abrir de novo ajuda a atualizar).
  await expect(async () => {
    await janela.getByRole('button', { name: 'Voltar' }).first().click();
    await janela.getByRole('button', { name: 'Maletas', exact: true }).click();
    await expect(janela.getByText(/Enviada 10/).first()).toBeVisible({
      timeout: 2_000,
    });
  }).toPass({ timeout: 90_000, intervals: [3_000] });
  await expect(janela.getByText(`${m.codigoDoColar} · ${m.nomeDoColar}`)).toBeVisible();
});

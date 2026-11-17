import { expect, test, type Page } from '@playwright/test';
import { DONA } from '../ambiente';
import { abrirOApp, chamarApi, entrar, irPara, type AppAberto } from '../apoio';
import {
  MASSA,
  criarMassaDoAcerto,
  tokenDaDonaPronta,
  type MassaDoAcerto,
} from '../massa-do-acerto';

// A dona faz um acerto inteiro no Corona Gestão: começa, conta o que voltou, confere os números do servidor, fecha e
// vê a ficha do acerto fechado. A massa (vendedor, maleta, venda) vem da API; veja as contas em massa-do-acerto.ts.
test.describe.configure({ mode: 'serial' });

let sessao: AppAberto | undefined;
let massa: MassaDoAcerto;

test.afterEach(async () => {
  await sessao?.app.close();
  sessao = undefined;
});

/** O leitor lê a peça `vezes` vezes, uma de cada vez: a tela só aceita a próxima depois que o servidor somou a anterior. */
async function lerPeca(janela: Page, codigo: string, id: string, vezes: number): Promise<void> {
  const campo = janela.getByLabel('Código da peça ou nome');
  for (let n = 1; n <= vezes; n++) {
    await campo.fill(codigo);
    await campo.press('Enter');
    await expect(janela.getByTestId(`contagem-BOA-${id}`)).toHaveText(String(n));
  }
}

/** Confere uma linha dos totais ("Vendido: R$ 200,00"): o rótulo é o da tela e o valor é o que o servidor devolveu. */
async function valorDe(janela: Page, rotulo: string, valor: string): Promise<void> {
  await expect(
    janela
      .locator('.gestao-totais__linha')
      .filter({ has: janela.getByText(rotulo, { exact: true }) })
      .first(),
  ).toContainText(valor);
}

test('a dona começa, conta, confere e fecha o acerto da vendedora', async () => {
  const token = await tokenDaDonaPronta();
  massa = await criarMassaDoAcerto(token);

  sessao = await abrirOApp();
  const { janela } = sessao;
  await entrar(janela, DONA.senhaNova);
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();

  // Começa o acerto.
  await irPara(janela, 'acertos/lista');
  await janela.getByRole('button', { name: 'Começar acerto' }).click();
  await janela
    .getByLabel('Vendedor', { exact: true })
    .selectOption({ label: MASSA.nomeDoVendedor });
  await janela.getByRole('button', { name: 'Começar', exact: true }).click();
  await expect(
    janela.getByRole('heading', { name: `Acerto de ${MASSA.nomeDoVendedor}, semana 2700` }),
  ).toBeVisible();

  // Conta o que voltou: 3 colares e 3 pulseiras (uma pulseira não voltou).
  await lerPeca(janela, massa.colar.codigo, massa.colar.id, 3);
  await lerPeca(janela, massa.pulseira.codigo, massa.pulseira.id, 3);

  // Confere: os números são do servidor (contas à mão em massa-do-acerto.ts).
  await janela.getByRole('button', { name: 'Conferir o acerto' }).click();
  await expect(janela.getByRole('heading', { name: 'Conferência do acerto' })).toBeVisible();
  await expect(janela.getByText('Totais do acerto')).toBeVisible();
  // Faltou 1 pulseira (R$ 60,00) e a decisão já vem como "Cobrar agora".
  await expect(janela.getByText('Faltou 1 peça (R$ 60,00). Vai ser cobrada agora.')).toBeVisible();
  await janela
    .getByLabel('O que fazer com a falta de Pulseira Lua')
    .selectOption({ label: 'Cobrar agora' });
  // Vendido R$ 200,00 = 200.000 Guarani: faltam 500.000 Guarani (R$ 500,00) para a meta; comissão de 30%.
  await expect(
    janela.getByText(/Vendido na semana: Gs\. 200\.000 de Gs\. 700\.000 \(meta\)/),
  ).toBeVisible();
  await expect(janela.getByText(/Falta para a meta: Gs\. 500\.000 \(R\$ 500,00\)\./)).toBeVisible();
  await expect(janela.getByText('Ainda não bateu a meta: comissão de 30%.')).toBeVisible();
  await valorDe(janela, 'Vendido', 'R$ 200,00');
  await valorDe(janela, 'Comissão do vendedor', 'R$ 60,00');
  await valorDe(janela, 'Faltas cobradas', 'R$ 60,00');
  // Valor do acerto = 200,00 - 60,00 + 60,00.
  await valorDe(janela, 'Valor do acerto', 'R$ 200,00');
  await valorDe(janela, 'A pagar agora', 'R$ 200,00');

  // O vendedor pagou R$ 150,00 em dinheiro; o resto (R$ 50,00) vira conta a receber, em 1 parcela.
  await janela.getByLabel('Dinheiro', { exact: true }).fill('150,00');
  await valorDe(janela, 'Recebido agora', 'R$ 150,00');
  await valorDe(janela, 'Ficou devendo', 'R$ 50,00');
  const vence = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);
  await janela.getByLabel('Data da primeira parcela').fill(vence);
  await expect(janela.getByText(/^Parcela 1: R\$ 50,00, vence em /)).toBeVisible();

  // Fecha: pergunta antes, mostra o valor, e só então fecha.
  await janela.getByRole('button', { name: 'Fechar acerto', exact: true }).click();
  const pergunta = janela.getByRole('dialog');
  await expect(
    pergunta.getByText(
      `Fechar o acerto de ${MASSA.nomeDoVendedor}? Depois de fechado, o acerto não muda mais.`,
    ),
  ).toBeVisible();
  await pergunta.getByRole('button', { name: 'Fechar acerto', exact: true }).click();

  // A ficha do acerto fechado.
  await expect(janela.getByText('Acerto fechado.')).toBeVisible();
  await expect(janela.getByRole('heading', { name: 'Fechamento do acerto' })).toBeVisible();
  await valorDe(janela, 'Vendido depois dos descontos', 'R$ 200,00');
  await valorDe(janela, 'Comissão do vendedor', 'R$ 60,00');
  await valorDe(janela, 'Valor do acerto', 'R$ 200,00');
  await valorDe(janela, 'Recebido no acerto', 'R$ 150,00');
  await valorDe(janela, 'Ficou devendo', 'R$ 50,00');
  await expect(janela.getByRole('cell', { name: 'Peça que faltou' })).toBeVisible();
  await expect(janela.getByRole('cell', { name: 'Cobrada' })).toBeVisible();
  await expect(janela.getByRole('cell', { name: 'Em aberto' }).first()).toBeVisible();

  // O estoque continua batendo com as maletas depois de tudo (conciliação do servidor).
  const conciliacao = await chamarApi<{ diferencasComMaletas: unknown[] }>(
    'GET',
    '/api/dona/estoque/conciliacao',
    token,
  );
  expect(conciliacao.diferencasComMaletas).toEqual([]);

  // Fechado, o acerto aparece na lista como "Acertado".
  await janela.getByRole('button', { name: 'Voltar para a lista' }).click();
  await expect(janela.getByRole('cell', { name: 'Acertado' })).toBeVisible();
});

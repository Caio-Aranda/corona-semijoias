import { expect, test } from '@playwright/test';
import { REPRESENTANTE } from '../ambiente';
import {
  abrirOVendas,
  comecarASemana,
  entrarComoRepresentante,
  indicadorDeEnvio,
  lerCodigo,
  type VendasAberto,
} from '../apoio-do-vendas';
import {
  criarMassaDoVendas,
  notasNaDona,
  situacaoDoClienteNaDona,
  type MassaDoVendas,
} from '../massa-do-vendas';
import { RedeDoVendas } from '../rede-do-vendas';

// O que a representante faz no dia a dia, do pedido ao dinheiro: Novo Pedido lendo o código, o acerto com pagamento parcial
// na Cobrança e o pagamento do que ficou devendo (Lançar pagamento). Um teste depois do outro, no mesmo computador.
//
// Contas (à mão): Colar R$ 100,00 e Pulseira R$ 60,00. Pedido: 2 colares + 1 pulseira = 3 peças, DX R$ 260,00.
// Acerto: volta 1 colar (DV R$ 100,00), vendido VD R$ 160,00; comissão 30% = R$ 48,00; bruto R$ 112,00. O cliente paga
// R$ 100,00 em dinheiro e ficou devendo R$ 12,00; depois paga os R$ 12,00 e o pedido fecha.
test.describe.configure({ mode: 'serial' });

const SEMANA = 65;
let m: MassaDoVendas;
let sessao: VendasAberto;
let rede: RedeDoVendas;

test.beforeAll(async () => {
  rede = await RedeDoVendas.ligar();
  m = await criarMassaDoVendas('Pedido');
  sessao = await abrirOVendas();
  await entrarComoRepresentante(sessao.janela, m.login, m.senhaTemporaria, REPRESENTANTE.senhaNova);
  await comecarASemana(sessao.janela, SEMANA);
});

test.afterAll(async () => {
  await sessao?.app.close();
  await rede?.desligar();
});

test('venda lendo o código como o leitor: o pedido sai da maleta e chega ao servidor', async () => {
  const { janela } = sessao;
  await janela.getByRole('button', { name: 'Novo Pedido' }).click();
  await janela.getByRole('button', { name: 'Escolher' }).click();
  await expect(janela.getByText(m.nomeDaCliente, { exact: true })).toBeVisible();
  await janela.getByLabel('Maleta', { exact: true }).selectOption({ index: 1 });

  await lerCodigo(janela, 'Ler o código da peça', m.codigoDoColar);
  await expect(janela.getByText(`Peça lida: ${m.nomeDoColar}.`)).toBeVisible();
  await lerCodigo(janela, 'Ler o código da peça', m.codigoDoColar);
  await lerCodigo(janela, 'Ler o código da peça', m.codigoDaPulseira);
  await expect(janela.getByText(`Peça lida: ${m.nomeDaPulseira}.`)).toBeVisible();
  // A mesma peça lida duas vezes soma na mesma linha: duas linhas no pedido, três peças, R$ 260,00.
  await expect(janela.getByText('Total de itens: 3', { exact: false })).toBeVisible();
  await expect(janela.getByText('R$ 260,00').first()).toBeVisible();

  await janela.getByRole('button', { name: 'Finalizar pedido' }).click();
  const dialogo = janela.getByRole('dialog');
  await expect(dialogo.getByText(/3 peça\(s\), R\$ 260,00/)).toBeVisible();
  await dialogo.getByRole('button', { name: 'Finalizar pedido' }).click();
  await expect(janela.getByText('Pedido 0000065-01 finalizado.')).toBeVisible();

  // Chegou ao servidor sozinho (o computador envia depois de gravar).
  await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 30_000 });
  await expect
    .poll(async () =>
      (await notasNaDona(m)).map((n) => [n.numero, n.situacao, n.itensDx, n.valorDx]),
    )
    .toEqual([['0000065-01', 'EM_ABERTO', 3, '260.00']]);
});

test('acerto do pedido: devolve uma peça, paga uma parte e fica devendo; o servidor confere', async () => {
  const { janela } = sessao;
  // Volta à tela principal e abre a Cobrança.
  await janela.getByRole('button', { name: 'Voltar' }).first().click();
  await janela.getByRole('button', { name: 'Cobrança', exact: true }).click();
  await janela.getByRole('button', { name: 'Escolher' }).click();
  await expect(janela.getByText(`Pedido 0000065-01 · ${m.nomeDaCliente}`).first()).toBeVisible();

  await lerCodigo(janela, 'Ler o código da peça devolvida', m.codigoDoColar);
  await janela.getByRole('button', { name: 'Concluir devolução' }).first().click();
  const dialogo = janela.getByRole('dialog');
  await expect(
    dialogo.getByText(/Estas 2 peça\(s\) não foram lidas e serão consideradas vendidas/),
  ).toBeVisible();
  await dialogo.getByRole('button', { name: 'Concluir devolução' }).click();

  await janela.getByRole('tab', { name: 'Forma de Pagamento' }).click();
  await janela.getByLabel('Comissão %', { exact: false }).fill('30');
  await janela.getByLabel('Dinheiro', { exact: true }).fill('100,00');
  await expect(janela.getByText('Total VD: R$ 160,00')).toBeVisible();
  await janela.getByRole('button', { name: 'Salvar acerto' }).click();
  await janela.getByRole('dialog').getByRole('button', { name: 'Salvar acerto' }).click();
  await expect(janela.getByText('Acerto salvo.')).toBeVisible();

  await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 30_000 });
  await expect
    .poll(async () => (await notasNaDona(m)).map((n) => n.situacao))
    .toEqual(['PENDENTE']);
  expect((await situacaoDoClienteNaDona(m)).pendencia).toBe('12.00');
});

test('recebimento: a cliente paga o que ficou devendo em Clientes e o pedido fecha', async () => {
  const { janela } = sessao;
  await janela.getByRole('button', { name: 'Voltar' }).first().click();
  await janela.getByRole('button', { name: 'Clientes', exact: true }).click();
  await janela.getByRole('button', { name: 'Abrir' }).first().click();
  await janela.getByRole('button', { name: 'Lançar pagamento' }).first().click();
  const dialogo = janela.getByRole('dialog');
  await expect(dialogo.getByText('R$ 12,00').first()).toBeVisible();
  await dialogo.getByLabel('Dinheiro', { exact: true }).fill('12,00');
  await dialogo.getByRole('button', { name: 'Lançar pagamento' }).click();
  await expect(janela.getByText(/Pagamento lançado\./)).toBeVisible();

  await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 30_000 });
  await expect.poll(async () => (await situacaoDoClienteNaDona(m)).pendencia).toBe('0.00');
  expect((await notasNaDona(m)).map((n) => n.situacao)).toEqual(['FECHADO']);
});

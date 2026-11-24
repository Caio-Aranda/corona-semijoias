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
import { criarMassaDoVendas, notasNaDona } from '../massa-do-vendas';
import { RedeDoVendas } from '../rede-do-vendas';

// Sem internet a representante vende do mesmo jeito: o pedido fica gravado no computador e sobe sozinho quando a internet
// volta. A "internet" é a rede do teste (`rede-do-vendas.ts`), que derruba as conexões do app ao ser cortada.
let rede: RedeDoVendas;
let sessao: VendasAberto | undefined;

test.beforeAll(async () => {
  rede = await RedeDoVendas.ligar();
});
test.afterAll(() => rede.desligar());
test.afterEach(async () => {
  rede.religar();
  await sessao?.app.close();
  sessao = undefined;
});

test('sem internet o pedido fica gravado no computador e sobe quando a internet volta', async () => {
  const m = await criarMassaDoVendas('Sem internet');
  sessao = await abrirOVendas();
  const { janela } = sessao;
  await entrarComoRepresentante(janela, m.login, m.senhaTemporaria, REPRESENTANTE.senhaNova);
  await comecarASemana(janela, 66);
  await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 30_000 });

  rede.cortar();
  await janela.getByRole('button', { name: 'Novo Pedido' }).click();
  await janela.getByRole('button', { name: 'Escolher' }).click();
  await janela.getByLabel('Maleta', { exact: true }).selectOption({ index: 1 });
  await lerCodigo(janela, 'Ler o código da peça', m.codigoDoColar);
  await expect(janela.getByText(`Peça lida: ${m.nomeDoColar}.`)).toBeVisible();
  await janela.getByRole('button', { name: 'Finalizar pedido' }).click();
  await janela.getByRole('dialog').getByRole('button', { name: 'Finalizar pedido' }).click();
  await expect(janela.getByText('Pedido 0000066-01 finalizado.')).toBeVisible();

  // O computador avisa, em palavras comuns, que a venda está guardada e ainda não foi enviada.
  await expect(indicadorDeEnvio(janela)).toContainText(
    'Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar.',
    { timeout: 30_000 },
  );
  await expect(indicadorDeEnvio(janela)).toContainText(/aguardando envio/);
  // Nada chegou ao servidor enquanto estava sem internet.
  expect(await notasNaDona(m)).toEqual([]);

  // A internet volta: o pedido sobe sozinho, sem a representante fazer nada.
  rede.religar();
  await expect(indicadorDeEnvio(janela)).toContainText('Tudo enviado', { timeout: 90_000 });
  await expect
    .poll(async () => (await notasNaDona(m)).map((n) => [n.numero, n.itensDx, n.valorDx]), {
      timeout: 30_000,
    })
    .toEqual([['0000066-01', 1, '100.00']]);
});

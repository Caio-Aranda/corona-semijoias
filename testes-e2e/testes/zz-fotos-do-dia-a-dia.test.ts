import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { DONA } from '../ambiente';
import { abrirOApp, chamarApi, entrar, irPara, novoId, type AppAberto } from '../apoio';
import { gravarCotacaoDeHoje, tokenDaDonaPronta } from '../massa-do-acerto';

// Fotos das telas do dia a dia da dona (montar maleta e fechamento), para revisar a organização das telas (item 7 da revisão
// de 27/11/2026). Só roda com CORONA_FOTOS apontando para uma pasta; sem ela, o teste é pulado.
test.describe.configure({ mode: 'serial' });

const PASTA = process.env['CORONA_FOTOS'];
const PECAS = [
  { nome: 'Brinco Gota Fotos', codigo: 'FT-BRINCO', preco: '89.90' },
  { nome: 'Colar Fotos', codigo: 'FT-COLAR', preco: '150.00' },
];
const REPRESENTANTE = 'Representante das Fotos';
const SEMANA = 2801;

let sessao: AppAberto | undefined;
test.afterEach(async () => {
  await sessao?.app.close();
  sessao = undefined;
});

async function foto(janela: Page, nome: string): Promise<void> {
  if (!PASTA) return;
  mkdirSync(PASTA, { recursive: true });
  await janela.waitForTimeout(900);
  await janela.screenshot({ path: join(PASTA, `${nome}.png`), fullPage: true });
}

async function prepararMassa(): Promise<void> {
  const token = await tokenDaDonaPronta();
  gravarCotacaoDeHoje();
  const categoria = await chamarApi<{ id: string }>('POST', '/api/dona/categorias', token, {
    id: novoId(),
    nome: 'Categoria das Fotos',
  });
  for (const p of PECAS) {
    const produto = await chamarApi<{ id: string }>('POST', '/api/dona/produtos', token, {
      id: novoId(),
      nome: p.nome,
      categoriaId: categoria.id,
      precoVenda: p.preco,
      precoCusto: '20.00',
      codigos: [{ codigo: p.codigo, tipo: 'LOJA', principal: true }],
    });
    await chamarApi('POST', '/api/dona/estoque/movimentacoes', token, {
      id: novoId(),
      produtoId: produto.id,
      tipo: 'ENTRADA',
      quantidade: 5,
      motivo: 'Entrada das fotos',
    });
  }
  await chamarApi('POST', '/api/dona/vendedores', token, { nome: REPRESENTANTE, semLimite: true });
  const inicio = new Date(Date.UTC(SEMANA, 0, 4));
  await chamarApi('POST', '/api/dona/semanas', token, {
    id: novoId(),
    numero: SEMANA,
    inicio: inicio.toISOString().slice(0, 10),
    fim: new Date(inicio.getTime() + 6 * 86_400_000).toISOString().slice(0, 10),
  });
}

async function ler(janela: Page, rotulo: string | RegExp, codigo: string): Promise<void> {
  const campo = janela.getByLabel(rotulo).first();
  await campo.fill(codigo);
  await campo.press('Enter');
}

test('fotos da montagem da maleta e do fechamento', async () => {
  test.skip(!PASTA, 'Defina CORONA_FOTOS para tirar as fotos.');
  await prepararMassa();
  sessao = await abrirOApp('pt');
  const { janela } = sessao;
  await janela.setViewportSize({ width: 1366, height: 768 });
  await entrar(janela, DONA.senhaNova);
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();

  // Montar maleta: a tela vazia, depois com duas peças lidas.
  await irPara(janela, 'maletas/montar');
  await foto(janela, '1-montar-inicio');
  await janela.getByLabel('Representante').selectOption({ label: REPRESENTANTE });
  await janela.getByLabel('Semana').selectOption({ label: `Semana ${SEMANA}` });
  await janela.getByRole('button', { name: 'Começar a montar' }).click();
  await ler(janela, 'Código da peça ou nome', PECAS[0]!.codigo);
  await expect(janela.getByText(`${PECAS[0]!.nome} entrou na maleta.`)).toBeVisible();
  await ler(janela, 'Código da peça ou nome', PECAS[1]!.codigo);
  await ler(janela, 'Código da peça ou nome', PECAS[1]!.codigo);
  await foto(janela, '2-montar-com-pecas');
  await janela.getByRole('button', { name: 'Enviar maleta' }).click();
  await foto(janela, '3-montar-confirmar-envio');
  await janela.getByRole('dialog').getByRole('button', { name: 'Enviar maleta' }).click();
  await expect(janela.getByText('Maleta enviada', { exact: true }).first()).toBeVisible();
  await foto(janela, '4-montar-enviada');

  // Fechamento: escolher a maleta, começar, ler uma peça só (as outras faltam) e tentar concluir.
  await irPara(janela, 'maletas/fechamento');
  await foto(janela, '5-fechamento-escolher');
  const escolha = janela.getByLabel('Maleta que voltou');
  await escolha.selectOption({ index: 1 });
  await janela.getByRole('button', { name: 'Abrir o fechamento' }).click();
  await janela.getByRole('button', { name: 'Começar o fechamento' }).click();
  await ler(janela, /código/i, PECAS[0]!.codigo);
  await foto(janela, '6-fechamento-em-andamento');
  await janela.getByRole('button', { name: 'Concluir o fechamento' }).click();
  await foto(janela, '7-fechamento-decidir-falta');

  // Cobrar do representante, concluir e registrar uma parte do pagamento (§23.10, item 1a).
  await janela.getByRole('radio', { name: 'Cobrar do representante' }).first().check();
  await janela.getByRole('button', { name: 'Concluir o fechamento' }).click();
  await janela.getByRole('dialog').getByRole('button', { name: 'Concluir' }).click();
  await expect(janela.getByText('Faltas registradas no fechamento')).toBeVisible();
  await foto(janela, '8-fechamento-com-cobranca');
  await janela
    .getByRole('button', { name: /^Registrar pagamento de / })
    .first()
    .click();
  const valor = janela.getByLabel('Valor pago');
  await valor.fill('50,00');
  await foto(janela, '9-registrar-pagamento');
  await janela.getByRole('button', { name: 'Registrar', exact: true }).click();
  await expect(janela.getByText('Pagamento registrado. Ele entrou no caixa.')).toBeVisible();
  await foto(janela, '10-pagamento-registrado');
});

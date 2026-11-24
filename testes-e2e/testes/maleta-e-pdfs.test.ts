import { expect, test, type Page } from '@playwright/test';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { DONA } from '../ambiente';
import { abrirOApp, chamarApi, entrar, irPara, novoId, type AppAberto } from '../apoio';
import { gravarCotacaoDeHoje, tokenDaDonaPronta } from '../massa-do-acerto';

// Revisão de 23/11/2026: a dona monta a maleta lendo o código, envia, baixa a nota em PDF e baixa um relatório
// em PDF nos dois idiomas. A massa (peça com estoque, representante, semana e cotação) vem da API.
test.describe.configure({ mode: 'serial' });

const PECA = { nome: 'Colar Revisão', codigo: 'RV-COLAR', preco: '150.00' };
const REPRESENTANTE = 'Representante da Revisão';
const SEMANA = 2701;

let sessao: AppAberto | undefined;

test.afterEach(async () => {
  await sessao?.app.close();
  sessao = undefined;
});

/** O PDF é escrito à mão em Windows-1252 e sem compressão: o texto de cada célula aparece inteiro no arquivo. */
function textoDoPdf(caminho: string): string {
  return new TextDecoder('windows-1252').decode(readFileSync(caminho));
}

/**
 * O arquivo aparece na pasta quando o download começa, antes de terminar: só conta o PDF já fechado (termina em %%EOF). Ler
 * ou apagar um arquivo pela metade fazia o teste dos dois idiomas falhar de vez em quando.
 */
function pdfsProntos(pasta: string, ignorar: ReadonlySet<string> = new Set()): string[] {
  return readdirSync(pasta).filter((f) => {
    if (!f.endsWith('.pdf') || ignorar.has(f)) return false;
    try {
      return readFileSync(join(pasta, f), 'latin1').trimEnd().endsWith('%%EOF');
    } catch {
      return false;
    }
  });
}

async function esperarArquivo(pasta: string, final: string): Promise<string> {
  await expect
    .poll(
      () =>
        (final === '.pdf'
          ? pdfsProntos(pasta)
          : readdirSync(pasta).filter((f) => f.endsWith(final))
        ).length,
      {
        timeout: 20_000,
      },
    )
    .toBe(1);
  const nome =
    final === '.pdf' ? pdfsProntos(pasta)[0] : readdirSync(pasta).find((f) => f.endsWith(final));
  return join(pasta, nome ?? '');
}

async function prepararMassa(): Promise<void> {
  const token = await tokenDaDonaPronta();
  gravarCotacaoDeHoje();
  const categoria = await chamarApi<{ id: string }>('POST', '/api/dona/categorias', token, {
    id: novoId(),
    nome: 'Categoria da Revisão',
  });
  const produto = await chamarApi<{ id: string }>('POST', '/api/dona/produtos', token, {
    id: novoId(),
    nome: PECA.nome,
    categoriaId: categoria.id,
    precoVenda: PECA.preco,
    precoCusto: '20.00',
    codigos: [{ codigo: PECA.codigo, tipo: 'LOJA', principal: true }],
  });
  await chamarApi('POST', '/api/dona/estoque/movimentacoes', token, {
    id: novoId(),
    produtoId: produto.id,
    tipo: 'ENTRADA',
    quantidade: 5,
    motivo: 'Entrada da revisão',
  });
  await chamarApi('POST', '/api/dona/vendedores', token, { nome: REPRESENTANTE, semLimite: true });
  const inicio = new Date(Date.UTC(SEMANA, 0, 4));
  await chamarApi('POST', '/api/dona/semanas', token, {
    id: novoId(),
    numero: SEMANA,
    inicio: inicio.toISOString().slice(0, 10),
    fim: new Date(inicio.getTime() + 6 * 86_400_000).toISOString().slice(0, 10),
  });
}

async function lerPeca(janela: Page, codigo: string): Promise<void> {
  const campo = janela.getByLabel('Código da peça ou nome');
  await campo.fill(codigo);
  await campo.press('Enter');
}

test('a dona monta a maleta lendo o código, envia e baixa a nota em PDF', async () => {
  await prepararMassa();
  sessao = await abrirOApp();
  const { janela, pastaDeDownloads } = sessao;
  await entrar(janela, DONA.senhaNova);
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();

  await irPara(janela, 'maletas/montar');
  await janela.getByLabel('Representante').selectOption({ label: REPRESENTANTE });
  await janela.getByLabel('Semana').selectOption({ label: `Semana ${SEMANA}` });
  await janela.getByRole('button', { name: 'Começar a montar' }).click();

  await lerPeca(janela, PECA.codigo);
  await expect(janela.getByText(`${PECA.nome} entrou na maleta.`)).toBeVisible();
  await lerPeca(janela, PECA.codigo);
  await expect(janela.getByLabel(`Quantidade de ${PECA.nome}`)).toHaveValue('2');

  await janela.getByRole('button', { name: 'Enviar maleta' }).click();
  const dialogo = janela.getByRole('dialog');
  await expect(dialogo.getByText(/2 peças para Representante da Revisão/)).toBeVisible();
  await dialogo.getByRole('button', { name: 'Enviar maleta' }).click();
  await expect(janela.getByText('Maleta enviada', { exact: true }).first()).toBeVisible();

  await janela.getByRole('button', { name: `Imprimir nota: ${REPRESENTANTE}` }).click();
  await expect(janela.getByText(/A nota .* foi preparada/)).toBeVisible();
  const pdf = textoDoPdf(await esperarArquivo(pastaDeDownloads, '.pdf'));
  expect(pdf.startsWith('%PDF-1.4')).toBe(true);
  expect(pdf.trimEnd().endsWith('%%EOF')).toBe(true);
  for (const trecho of [
    'Nota de remessa',
    REPRESENTANTE,
    PECA.nome,
    PECA.codigo,
    'R$ 150,00',
    'R$ 300,00',
    // Cotação do teste: 5,00 reais por dólar e 0,001 real por guarani.
    'US$ 60,00',
    'Gs. 300.000',
  ]) {
    expect(pdf, `a nota devia ter "${trecho}"`).toContain(trecho);
  }
});

test('relatório de produtos em PDF sai em português e, trocando o idioma, em espanhol', async () => {
  sessao = await abrirOApp('pt');
  const { janela, pastaDeDownloads } = sessao;
  await entrar(janela, DONA.senhaNova);
  await expect(janela.getByRole('button', { name: 'Sair' })).toBeVisible();

  const baixar = async (botao: string): Promise<string> => {
    await irPara(janela, 'relatorios/produtos');
    await expect(janela.getByRole('cell', { name: PECA.nome }).first()).toBeVisible();
    const antes = new Set(readdirSync(pastaDeDownloads));
    await janela.getByRole('button', { name: botao }).click();
    await expect
      .poll(() => pdfsProntos(pastaDeDownloads, antes).length, { timeout: 20_000 })
      .toBe(1);
    const texto = textoDoPdf(join(pastaDeDownloads, pdfsProntos(pastaDeDownloads, antes)[0] ?? ''));
    return texto;
  };

  const emPortugues = await baixar('Baixar em PDF');
  for (const trecho of [
    'Corona Semijoias',
    'Produtos e custos',
    'Gerado em',
    PECA.nome,
    'Página 1 de',
  ]) {
    expect(emPortugues, `o PDF em português devia ter "${trecho}"`).toContain(trecho);
  }
  expect(emPortugues).not.toMatch(/undefined|NaN/);

  await janela.getByRole('button', { name: 'Español' }).click();
  await janela.waitForLoadState('domcontentloaded');
  await expect(janela.getByRole('button', { name: 'Salir' })).toBeVisible();
  const emEspanhol = await baixar('Descargar en PDF');
  for (const trecho of ['Productos y costos', 'Generado el', PECA.nome, 'Página 1 de']) {
    expect(emEspanhol, `el PDF en español debía tener "${trecho}"`).toContain(trecho);
  }
  expect(emEspanhol).not.toContain('Produtos e custos');
  expect(emEspanhol).not.toMatch(/undefined|NaN/);
});

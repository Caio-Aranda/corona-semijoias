// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PedidoDeChamada, PonteDoCorona, ResultadoDeChamada } from '../shared/ponte';
import type {
  NotaDeSaida,
  RelatorioDoAcerto,
  RelatorioDoConsignado,
  ResultadoDeSaidaDoRelatorio,
  SituacaoDaMaleta,
  TotaisDaSemanaNoRelatorio,
  ValorNasTresMoedas,
} from '../shared/relatorios-do-consignado';
import { definirIdioma } from './idioma';
import { BotaoDeRelatorio } from './telas/consignado/Relatorio';

/**
 * A folha dos relatórios do consignado (tarefa 9.3; §23.11): o que cada um mostra, DX/DV/VD, Real com Dólar e Guarani
 * abaixo (ou "Sem cotação"), e os três botões: Imprimir, Salvar em PDF e Preparar para o WhatsApp.
 */
const v = (
  real: string,
  dolar: string | null = null,
  guarani: string | null = null,
): ValorNasTresMoedas => ({
  real,
  dolar,
  guarani,
});

const cabecalho = {
  representante: 'Rosa',
  praca: 'PONTA PORÃ - MS',
  geradoEm: '2026-11-19T12:00:00.000Z',
};
const cliente = {
  nome: 'Vendedora Ana',
  documento: '123.456.789-00',
  telefone: '(67) 99999-1234',
  endereco: 'Rua das Flores, 10 · Centro',
};

const nota: NotaDeSaida = {
  tipo: 'NOTA_DE_SAIDA',
  cabecalho,
  numero: '0000064-21',
  semana: 64,
  saidaEm: '2026-11-10T12:00:00.000Z',
  dataCobranca: '2026-12-25',
  cliente,
  maletas: ['0007'],
  linhas: [
    {
      codigo: 'A-1',
      produto: 'Brinco A',
      maleta: '0007',
      quantidade: 10,
      preco: '200.00',
      subTotal: '2000.00',
    },
  ],
  itensDx: 10,
  totalDx: v('2000.00', '400.00', '2666667'),
  pendenciaAnterior: v('498.10', '99.62', '664133'),
  pendencias: [{ numeroDaNota: '0000063-32', data: '2026-11-03', saldo: '498.10' }],
};

const acerto: RelatorioDoAcerto = {
  tipo: 'ACERTO',
  cabecalho,
  numero: '0000064-21',
  cliente,
  saidaEm: '2026-11-10T12:00:00.000Z',
  acertadoEm: '2026-12-20T12:00:00.000Z',
  vendidas: [
    {
      codigo: 'A-1',
      produto: 'Brinco A',
      maleta: '0007',
      quantidade: 5,
      preco: '200.00',
      valor: '1000.00',
    },
  ],
  itensDx: 26,
  itensDv: 15,
  itensVd: 11,
  valorDx: v('5370.00'),
  valorDv: v('3060.00'),
  valorVd: v('2310.00'),
  vendaPercentual: '43.02',
  comissaoPercentual: '30.00',
  comissao: v('693.00'),
  valorLiquido: v('1617.00'),
  totalPendenciasAnteriores: v('498.10'),
  totalAReceber: v('2115.10'),
  recibo: '0000064-02',
  pago: [
    { forma: 'DINHEIRO', valor: v('1470.00') },
    { forma: 'PIX', valor: v('300.00') },
  ],
  totalPago: v('1770.00'),
  ficouDevendo: v('345.10'),
};

const totais: TotaisDaSemanaNoRelatorio = {
  tipo: 'TOTAIS_DA_SEMANA',
  cabecalho,
  semana: 64,
  situacaoDaSemana: 'EM_ANDAMENTO',
  pedidos: 3,
  itensDx: 26,
  itensDv: 15,
  itensVd: 11,
  valorDx: v('5370.00', '1074.00', '7160000'),
  valorDv: v('3060.00', '612.00', '4080000'),
  valorVd: v('2310.00', '462.00', '3080000'),
  comissao: v('693.00'),
  valorLiquido: v('1617.00'),
  recebido: v('1770.00'),
  recebidoPorForma: [
    { forma: 'DINHEIRO', valor: v('1470.00') },
    { forma: 'PIX', valor: v('300.00') },
    { forma: 'DEBITO', valor: v('0.00') },
    { forma: 'CREDITO', valor: v('0.00') },
  ],
  pendente: v('345.10'),
};

const maleta: SituacaoDaMaleta = {
  tipo: 'SITUACAO_DA_MALETA',
  cabecalho,
  maleta: '0007',
  status: 'ENVIADA',
  enviadaEm: '2026-11-01T12:00:00.000Z',
  pecas: [
    {
      codigo: 'A-1',
      produto: 'Brinco A',
      enviada: 12,
      naMaleta: 5,
      comClientes: 2,
      vendida: 5,
      devolvidaALoja: 0,
      comQuem: ['Vendedora Ana (0000065-01): 2'],
    },
  ],
  totalEnviada: 12,
  totalNaMaleta: 5,
  totalComClientes: 2,
  totalVendida: 5,
  itensDx: 7,
  itensDv: 0,
  itensVd: 5,
  valorDx: v('1400.00'),
  valorDv: v('0.00'),
  valorVd: v('1000.00'),
  valorNaMaleta: v('1000.00'),
  valorComClientes: v('400.00'),
};

function criarPonte(
  dados: RelatorioDoConsignado,
  saida: Partial<Record<'imprimir' | 'pdf' | 'zap', ResultadoDeSaidaDoRelatorio>> = {},
) {
  const imprimirRelatorio = vi.fn(() => Promise.resolve(saida.imprimir ?? { ok: true as const }));
  const salvarRelatorioEmPdf = vi.fn<
    (dados: { nome: string }) => Promise<ResultadoDeSaidaDoRelatorio>
  >(() => Promise.resolve(saida.pdf ?? { ok: true as const, arquivo: 'C:/Docs/nota.pdf' }));
  const prepararRelatorioNoWhatsapp = vi.fn<
    (dados: {
      telefone: string | null;
      mensagem: string;
      nome: string;
      semNumero?: boolean;
    }) => Promise<ResultadoDeSaidaDoRelatorio>
  >(() =>
    Promise.resolve(saida.zap ?? { ok: true as const, pasta: 'C:/Docs/Corona Vendas/Relatórios' }),
  );
  const chamadas: PedidoDeChamada[] = [];
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(),
    chamarApi: vi.fn((p: PedidoDeChamada): Promise<ResultadoDeChamada> => {
      chamadas.push(p);
      return Promise.resolve({ ok: true, dados });
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
    aoMudarASincronizacao: () => () => undefined,
    lerIdioma: () => Promise.resolve(null),
    gravarIdioma: () => Promise.resolve(),
    imprimirRelatorio,
    salvarRelatorioEmPdf,
    prepararRelatorioNoWhatsapp,
  };
  return { ponte, chamadas, imprimirRelatorio, salvarRelatorioEmPdf, prepararRelatorioNoWhatsapp };
}

async function abrir(dados: RelatorioDoConsignado, rotulo: string, saida = {}) {
  const p = criarPonte(dados, saida);
  render(
    <BotaoDeRelatorio ponte={p.ponte} pedido={{ tipo: dados.tipo } as never} rotulo={rotulo} />,
  );
  const u = userEvent.setup();
  await u.click(screen.getByRole('button', { name: rotulo }));
  await screen.findByRole('article');
  return { ...p, u };
}

beforeEach(() => definirIdioma('pt'));

describe('Nota de saída', () => {
  it('mostra Representante, Praça, o cliente, as peças (sem Tam./Ton.), Itens DX, Total DX nas três moedas, a pendência e o espaço de assinatura', async () => {
    await abrir(nota, 'Nota de saída');
    const folha = screen.getByRole('article');
    expect(within(folha).getByText('Corona Semijoias')).toBeInTheDocument();
    expect(within(folha).getByText('PONTA PORÃ - MS')).toBeInTheDocument();
    expect(within(folha).getByText('Rosa')).toBeInTheDocument();
    expect(within(folha).getByText('0000064-21')).toBeInTheDocument();
    expect(within(folha).getByText('Vendedora Ana')).toBeInTheDocument();
    expect(within(folha).getByText('25/12/2026')).toBeInTheDocument();
    const cabecas = within(folha)
      .getAllByRole('columnheader')
      .map((c) => c.textContent);
    expect(cabecas).toEqual(['Ref.', 'Produto', 'Maleta', 'Qtd.', 'Preço', 'Sub-total']);
    expect(cabecas.join(' ')).not.toMatch(/Tam|Ton/);
    expect(within(folha).getByText('Itens DX: 10')).toBeInTheDocument();
    expect(within(folha).getAllByText('R$ 2.000,00').length).toBeGreaterThan(1);
    expect(within(folha).getByText('US$ 400,00 · Gs. 2.666.667')).toBeInTheDocument();
    expect(within(folha).getByText('R$ 498,10')).toBeInTheDocument();
    expect(within(folha).getByText(/Assinatura do cliente/)).toBeInTheDocument();
    expect(within(folha).getByText(/sem nota fiscal/)).toBeInTheDocument();
  });

  it('pede ao computador o relatório da nota (os dados são locais, sem internet)', async () => {
    const { chamadas } = await abrir(nota, 'Nota de saída');
    expect(chamadas[0]).toMatchObject({ operacao: 'consignado.relatorio' });
  });
});

describe('Relatório do acerto', () => {
  it('mostra DX, DV e VD em valores e itens, comissão, pendências, total a receber, pago por forma e quanto ficou devendo; sem cotação, só o Real e "Sem cotação"', async () => {
    await abrir(acerto, 'Relatório do acerto');
    const folha = screen.getByRole('article');
    for (const texto of [
      'R$ 5.370,00',
      'R$ 3.060,00',
      'R$ 2.310,00',
      'R$ 693,00',
      'R$ 1.617,00',
      'R$ 2.115,10',
      'R$ 1.770,00',
      'R$ 345,10',
    ])
      expect(within(folha).getAllByText(texto).length).toBeGreaterThan(0);
    expect(within(folha).getByText('Itens DX 26 · DV 15 · VD 11')).toBeInTheDocument();
    expect(within(folha).getByText('43,02%')).toBeInTheDocument();
    expect(within(folha).getByText('Pago: Dinheiro')).toBeInTheDocument();
    expect(within(folha).getByText('Pago: Pix')).toBeInTheDocument();
    expect(within(folha).getByText('Recibo nº 0000064-02')).toBeInTheDocument();
    expect(within(folha).getAllByText('Sem cotação').length).toBeGreaterThan(0);
  });
});

describe('Totais da semana e Situação da maleta', () => {
  it('Totais da semana: DX, DV, VD com Dólar e Guarani, recebido por forma e pendente', async () => {
    await abrir(totais, 'Relatório da semana');
    const folha = screen.getByRole('article');
    expect(within(folha).getByText('US$ 462,00 · Gs. 3.080.000')).toBeInTheDocument();
    expect(within(folha).getByText('Itens DX 26 · DV 15 · VD 11')).toBeInTheDocument();
    expect(within(folha).getByText('Pix')).toBeInTheDocument();
    expect(within(folha).getByText('Pedidos da semana: 3')).toBeInTheDocument();
  });

  it('Situação da maleta: peças na maleta, com clientes (com quem) e vendidas', async () => {
    await abrir(maleta, 'Situação da maleta');
    const folha = screen.getByRole('article');
    expect(within(folha).getByText('Maleta 0007')).toBeInTheDocument();
    expect(within(folha).getByText('Vendedora Ana (0000065-01): 2')).toBeInTheDocument();
    expect(
      within(folha).getByText('Enviadas 12 · Na maleta 5 · Com clientes 2 · Vendidas 5'),
    ).toBeInTheDocument();
    expect(within(folha).getAllByText('Sem cotação').length).toBeGreaterThan(0);
  });
});

describe('Imprimir, Salvar em PDF e WhatsApp', () => {
  it('imprimir chama a impressão do programa', async () => {
    const { u, imprimirRelatorio } = await abrir(nota, 'Nota de saída');
    await u.click(screen.getByRole('button', { name: 'Imprimir' }));
    expect(imprimirRelatorio).toHaveBeenCalledTimes(1);
  });

  it('salvar em PDF manda o nome do arquivo e conta onde ficou', async () => {
    const { u, salvarRelatorioEmPdf } = await abrir(nota, 'Nota de saída');
    await u.click(screen.getByRole('button', { name: 'Salvar em PDF' }));
    expect(salvarRelatorioEmPdf).toHaveBeenCalledWith({ nome: 'Nota de saida 0000064-21' });
    expect(await screen.findByText('PDF salvo em C:/Docs/nota.pdf.')).toBeInTheDocument();
  });

  it('WhatsApp: manda o telefone do cliente e uma mensagem curta pronta; avisa que o representante anexa e envia', async () => {
    const { u, prepararRelatorioNoWhatsapp } = await abrir(nota, 'Nota de saída');
    await u.click(screen.getByRole('button', { name: 'Preparar para o WhatsApp' }));
    const dados = prepararRelatorioNoWhatsapp.mock.calls[0]?.[0];
    expect(dados).toMatchObject({
      telefone: '(67) 99999-1234',
      semNumero: false,
      nome: 'Nota de saida 0000064-21',
    });
    expect(dados?.mensagem).toBe(
      'Olá, Vendedora Ana! Segue a nota de saída 0000064-21 da Corona Semijoias. Qualquer dúvida, é só chamar.',
    );
    expect(
      await screen.findByText(/anexe o PDF e envie\. Nada é enviado sozinho/),
    ).toBeInTheDocument();
  });

  it('WhatsApp sem internet: o PDF fica guardado e a tela avisa em linguagem simples', async () => {
    const { u } = await abrir(nota, 'Nota de saída', {
      zap: { ok: false, motivo: 'sem-internet', pasta: 'C:/Docs/Corona Vendas/Relatórios' },
    });
    await u.click(screen.getByRole('button', { name: 'Preparar para o WhatsApp' }));
    expect(await screen.findByText(/Sem internet: o PDF já está guardado/)).toBeInTheDocument();
    expect(screen.getByText(/o WhatsApp só abre com internet/)).toBeInTheDocument();
  });

  it('WhatsApp de cliente sem telefone diz o que fazer', async () => {
    const { u } = await abrir(nota, 'Nota de saída', {
      zap: { ok: false, motivo: 'sem-telefone' },
    });
    await u.click(screen.getByRole('button', { name: 'Preparar para o WhatsApp' }));
    expect(await screen.findByText(/não tem telefone cadastrado/)).toBeInTheDocument();
  });

  it('relatório do representante (Totais) abre o WhatsApp sem número', async () => {
    const { u, prepararRelatorioNoWhatsapp } = await abrir(totais, 'Relatório da semana');
    await u.click(screen.getByRole('button', { name: 'Preparar para o WhatsApp' }));
    await waitFor(() =>
      expect(prepararRelatorioNoWhatsapp).toHaveBeenCalledWith(
        expect.objectContaining({ telefone: null, semNumero: true }),
      ),
    );
  });

  it('cancelar a impressão não mostra erro, só diz que nada foi feito', async () => {
    const { u } = await abrir(nota, 'Nota de saída', {
      imprimir: { ok: false, motivo: 'cancelado' },
    });
    await u.click(screen.getByRole('button', { name: 'Imprimir' }));
    expect(await screen.findByText('Nada foi salvo nem impresso.')).toBeInTheDocument();
  });
});

describe('em espanhol (o padrão)', () => {
  it('a Nota de saída fala espanhol, com a mensagem do WhatsApp em espanhol', async () => {
    definirIdioma('es');
    const { u, prepararRelatorioNoWhatsapp } = await abrir(nota, 'Nota de salida');
    const folha = screen.getByRole('article');
    expect(within(folha).getByText(/Firma del cliente/)).toBeInTheDocument();
    expect(within(folha).getByText('Plaza:')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Preparar para WhatsApp' }));
    expect(prepararRelatorioNoWhatsapp.mock.calls[0]?.[0].mensagem).toContain(
      'nota de salida 0000064-21',
    );
  });
});

// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { esquecerImagens } from '../../componentes/ImagemDoProduto';
import { entregarArquivo } from '../../nucleo/arquivos';
import {
  HASH,
  ID_ACERTO,
  ID_ANEL,
  ID_BRINCO,
  ID_MALETA,
  ID_VENDA,
  abrir,
  ficha,
  montar,
  produtoDaFicha,
  resumoDeAcerto,
  type Manipulador,
} from './apoio-de-teste';

vi.mock('../../nucleo/arquivos', async (original) => ({
  ...(await original<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: vi.fn(),
}));

beforeEach(() => {
  esquecerImagens();
  vi.mocked(entregarArquivo).mockClear();
});

const resposta = (dados: unknown) => ({ ok: true as const, dados });

/** O fechamento gravado, como a ficha o devolve. Os totais são de propósito incoerentes entre si. */
function fechamentoGravado(extra: Record<string, unknown> = {}) {
  const anel = produtoDaFicha(ID_ANEL, 'Anel Solitário', '0012', HASH);
  const brinco = produtoDaFicha(ID_BRINCO, 'Brinco de Argola', 'B-7');
  return {
    fechadoEm: '2026-11-12T15:00:00Z',
    cotacaoId: ID_VENDA,
    cotacaoData: '2026-11-12',
    usdVenda: '5.00000000',
    pygVenda: '0.00080000',
    metaGuarani: 700000,
    percentualComissao: '40.00',
    vendidoParaMetaGuarani: 750000,
    vendidoBruto: '1000.00',
    descontos: '10.00',
    devolvido: '20.00',
    vendidoLiquido: '970.00',
    comissao: '388.00',
    faltasCobradas: '70.00',
    liquido: '777.77',
    recebidoNoAcerto: '110.00',
    ficouDevendo: '17.83',
    pagoAoVendedor: '0.00',
    creditoUsado: '5.00',
    itens: [
      {
        produto: anel,
        remessaId: ID_MALETA,
        precoRemessa: '70.00',
        esperado: 5,
        contadoBoa: 3,
        contadoDefeito: 1,
        falta: 1,
        defeitoAprovado: 0,
      },
      {
        produto: brinco,
        remessaId: ID_MALETA,
        precoRemessa: '30.00',
        esperado: 2,
        contadoBoa: 2,
        contadoDefeito: 0,
        falta: 0,
        defeitoAprovado: 0,
      },
    ],
    eventos: [],
    comissoes: [
      { vendaId: ID_VENDA, tipo: 'COMISSAO', base: '100.00', percentual: '40.00', valor: '40.00' },
    ],
    pendencias: [
      {
        id: ID_VENDA,
        tipo: 'FALTA',
        produto: anel,
        quantidade: 1,
        valor: '70.00',
        status: 'COBRADA',
      },
    ],
    recebimentos: [
      {
        id: ID_VENDA,
        forma: 'DINHEIRO',
        valor: '60.00',
        estornado: false,
        recebidoEm: '2026-11-12T15:00:00Z',
      },
      {
        id: ID_MALETA,
        forma: 'PIX',
        valor: '50.00',
        estornado: true,
        recebidoEm: '2026-11-12T15:00:00Z',
      },
    ],
    contas: [
      {
        id: ID_ACERTO,
        numero: 1,
        totalDeParcelas: 2,
        valor: '10.00',
        vencimento: '2026-12-15',
        formaPrevista: 'BOLETO',
        status: 'ABERTA',
        valorPago: '0.00',
      },
    ],
    ...extra,
  };
}

function servidor(
  fechamento: unknown = fechamentoGravado(),
  extra: Record<string, Manipulador> = {},
) {
  return montar({
    'acertos.listar': () => resposta([resumoDeAcerto({ status: 'ACERTADO' })]),
    'acertos.ver': () => resposta(ficha({ status: 'ACERTADO', fechamento })),
    ...extra,
  });
}

async function abrirFicha(m: ReturnType<typeof servidor>) {
  const u = await abrir(m);
  await u.click(await screen.findByRole('button', { name: /Abrir acerto de Ana Vendedora/ }));
  await screen.findByRole('heading', { name: 'Fechamento do acerto', level: 2 });
  return u;
}

const valorDe = (rotulo: string) =>
  screen.getByText(rotulo, { selector: 'dt' }).nextElementSibling?.textContent ?? '';
const textoDoPdf = (bytes: Uint8Array) => new TextDecoder('latin1').decode(bytes);
const noPdf = (s: string) => s.replace(/[()\\]/g, (c) => `\\${c}`);

describe('ficha do acerto fechado', () => {
  it('mostra os totais gravados como vieram, sem calcular', async () => {
    await abrirFicha(servidor());
    expect(valorDe('Vendido')).toContain('R$ 1.000,00');
    expect(valorDe('Vendido depois dos descontos')).toContain('R$ 970,00');
    expect(valorDe('Comissão do vendedor')).toContain('R$ 388,00');
    expect(valorDe('Faltas cobradas')).toContain('R$ 70,00');
    expect(valorDe('Valor do acerto')).toContain('R$ 777,77');
    expect(valorDe('Crédito do vendedor usado')).toContain('R$ 5,00');
    expect(valorDe('Recebido no acerto')).toContain('R$ 110,00');
    expect(valorDe('Ficou devendo')).toContain('R$ 17,83');
    expect(valorDe('Pago ao vendedor')).toContain('R$ 0,00');
  });

  it('mostra a meta usada, o percentual gravado e a soma em Guarani que decidiu a meta', async () => {
    await abrirFicha(servidor());
    expect(
      screen.getByText(/Vendido na semana: Gs\. 750\.000 de Gs\. 700\.000 \(meta\)/),
    ).toBeInTheDocument();
    expect(screen.getByText('Bateu a meta: comissão de 40%.')).toBeInTheDocument();
    const tabela = screen.getByRole('table', { name: 'Comissão por venda' });
    expect(tabela).toHaveTextContent('40%');
    expect(tabela).toHaveTextContent('R$ 40,00');
  });

  it('lista peças, pendências com a decisão, recebimentos (o desfeito marcado) e as contas geradas', async () => {
    await abrirFicha(servidor());
    const pecas = screen.getByRole('table', { name: 'Peças do acerto' });
    expect(pecas).toHaveTextContent('Anel Solitário');
    expect(pecas).toHaveTextContent('Brinco de Argola');
    const pendencias = screen.getByRole('table', { name: 'Pendências deste acerto' });
    expect(pendencias).toHaveTextContent('Cobrada');
    expect(pendencias).toHaveTextContent('Anel Solitário');
    const recebimentos = screen.getByRole('table', { name: 'O que o vendedor pagou' });
    expect(recebimentos).toHaveTextContent('Dinheiro');
    expect(recebimentos).toHaveTextContent('Transferência (PIX)');
    expect(within(recebimentos).getByText('Desfeito')).toBeInTheDocument();
    const contas = screen.getByRole('table', { name: 'Contas que o acerto gerou' });
    expect(contas).toHaveTextContent('1 de 2');
    expect(contas).toHaveTextContent('15/12/2026');
    expect(contas).toHaveTextContent('Boleto');
  });

  it('é só leitura: nenhum botão que muda o acerto', async () => {
    await abrirFicha(servidor());
    expect(
      screen.queryByRole('button', { name: /Fechar acerto|Desistir|Conferir|Desfazer|Contar/ }),
    ).toBeNull();
    expect(screen.getByRole('button', { name: 'Imprimir acerto' })).toBeInTheDocument();
  });

  it('usa a cotação gravada no acerto, nunca a do dia', async () => {
    const m = servidor(fechamentoGravado(), {
      'cotacao.hoje': () =>
        resposta({
          disponivel: true,
          cotacao: { dataCotacao: '2026-11-14', dolar: '4.00000000', guarani: '0.00100000' },
        }),
    });
    await abrirFicha(m);
    // R$ 1.000,00 a 5,00 por Dólar: US$ 200,00 (e não US$ 250,00, da cotação de hoje).
    expect(valorDe('Vendido')).toContain('US$ 200,00');
    expect(valorDe('Vendido')).not.toContain('US$ 250,00');
    expect(
      screen.getByText(
        /Valores em Dólar e Guarani pela cotação de 12\/11\/2026\. Vale o valor em reais\./,
      ),
    ).toBeInTheDocument();
  });

  it('sem cotação gravada, só Real e o aviso', async () => {
    await abrirFicha(servidor(fechamentoGravado({ usdVenda: undefined, pygVenda: undefined })));
    expect(valorDe('Vendido')).toBe('R$ 1.000,00');
    expect(screen.getByText(/Sem cotação: os valores aparecem só em reais/)).toBeInTheDocument();
  });

  it('abaixo da meta: mostra o percentual gravado', async () => {
    await abrirFicha(
      servidor(fechamentoGravado({ vendidoParaMetaGuarani: 650000, percentualComissao: '30.00' })),
    );
    expect(screen.getByText('Ainda não bateu a meta: comissão de 30%.')).toBeInTheDocument();
  });

  it('fechamento fora da forma: aviso simples, nunca tela quebrada', async () => {
    const m = servidor({ liquido: 'abc' });
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: /Abrir acerto de Ana Vendedora/ }));
    expect(
      await screen.findByText('Não deu para ler o fechamento deste acerto. Tente de novo.'),
    ).toBeInTheDocument();
  });

  it('Imprimir acerto gera o PDF com os mesmos textos e valores da ficha, inclusive meta e percentual', async () => {
    const u = await abrirFicha(servidor());
    await u.click(screen.getByRole('button', { name: 'Imprimir acerto' }));
    await waitFor(() => expect(entregarArquivo).toHaveBeenCalledTimes(1));
    const [nome, tipo, bytes] = vi.mocked(entregarArquivo).mock.calls[0]!;
    expect(nome).toMatch(/^acerto-.*\.pdf$/);
    expect(tipo).toBe('application/pdf');
    const pdf = textoDoPdf(bytes);
    for (const t of [
      'Corona Semijoias',
      'Acerto de Ana Vendedora, semana 45',
      'R$ 1.000,00',
      'R$ 970,00',
      'R$ 388,00',
      'R$ 777,77',
      'Vendido na semana: Gs. 750.000 de Gs. 700.000 (meta)',
      'Bateu a meta: comissão de 40%.',
      'Anel Solitário',
      'Desfeito',
      'Valores em Dólar e Guarani pela cotação de 12/11/2026. Vale o valor em reais.',
    ]) {
      expect(pdf, `o PDF deve ter "${t}"`).toContain(noPdf(t));
    }
    expect(await screen.findByText(/foi preparado/)).toBeInTheDocument();
  });
});

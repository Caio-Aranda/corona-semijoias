// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { conferirRelatorio } from '../../../shared/validacao-relatorios';
import { App } from '../../App';

const entregues: { nome: string; tipo: string; bytes: Uint8Array }[] = [];
vi.mock('../../nucleo/arquivos', async (original) => ({
  ...(await original<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: (nome: string, tipo: string, bytes: Uint8Array) => {
    entregues.push({ nome, tipo, bytes });
  },
}));

const ID_ACERTO = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';

type Linha = Record<string, unknown>;
type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const TRES = (campo: string, real: string, dolar: string, guarani: string): Linha => ({
  [campo]: real,
  [`${campo}EmDolar`]: dolar,
  [`${campo}EmGuarani`]: guarani,
});

const ACERTO: Linha = {
  id: ID_ACERTO,
  vendedorId: ID_VEND,
  vendedor: 'Maria Souza',
  semanaId: ID_VEND,
  semana: 12,
  fechadoEm: '2026-11-05T15:00:00Z',
  percentual: '40.00',
  dataDaCotacao: '2026-11-05',
  ...TRES('vendidoBruto', '150.00', '30.00', '60000'),
  ...TRES('descontos', '0.00', '0.00', '0'),
  ...TRES('devolvido', '50.00', '10.00', '20000'),
  ...TRES('comissao', '40.00', '8.00', '16000'),
  ...TRES('faltasCobradas', '50.00', '10.00', '20000'),
  ...TRES('liquido', '110.00', '22.00', '44000'),
  ...TRES('recebidoNoAcerto', '110.00', '22.00', '44000'),
  ...TRES('ficouDevendo', '0.00', '0.00', '0'),
};

const TOTAIS_DO_ACERTO = [
  {
    chave: 'liquido',
    rotulo: 'Valor do acerto',
    quantidade: 1,
    valorEmReais: '110.00',
    valorEmDolar: '22.00',
    valorEmGuarani: '44000',
  },
];

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({
      ok: true,
      dados: {
        disponivel: true,
        cotacao: {
          dataCotacao: '2026-11-06',
          dolar: '5.50130000',
          guarani: '0.00089500',
          deDiaAnterior: false,
        },
      },
    }),
    'vendedores.escolha': () => pagina([{ id: ID_VEND, nome: 'Maria Souza' }]),
    'fornecedores.escolha': () => pagina([]),
    'pracas.listar': () => pagina([]),
    'categorias.listar': () => pagina([]),
    'semanas.listar': () => pagina([]),
    'relatorios.cadastros': () => ({
      ok: true,
      dados: { itens: [ACERTO], total: 1, pagina: 0, tamanho: 20, totais: TOTAIS_DO_ACERTO },
    }),
  };
  const todos = { ...padrao, ...manipuladores };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn(async (pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = todos[pedido.operacao];
      return m ? m(pedido) : { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

const dosRelatorios = (m: ReturnType<typeof montar>) =>
  m.chamadas.filter((c) => c.operacao === 'relatorios.cadastros').map((c) => c.parametros as Linha);

async function abrir(m: ReturnType<typeof montar>, titulo: string) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await screen.findByRole('button', { name: titulo }));
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  entregues.length = 0;
});

describe('Relatórios financeiros (tarefa 7.12)', () => {
  it('o menu tem o grupo e os nove relatórios com os nomes do glossário', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    expect(await screen.findByText('Relatórios financeiros')).toBeInTheDocument();
    for (const t of [
      'Acertos por vendedor e semana',
      'Faltas e pendências',
      'Esperado e contado',
      'Comissões por vendedor e semana',
      'Parcelas a receber',
      'Parcelas a pagar',
      'Quem está devendo',
      'Fluxo do caixa',
      'Recebimentos por forma de pagamento',
    ]) {
      expect(await screen.findByRole('button', { name: t })).toBeInTheDocument();
    }
  });

  it('o acerto mostra Real, Dólar e Guarani que vieram da linha e do rodapé, não os de hoje', async () => {
    const m = montar();
    await abrir(m, 'Acertos por vendedor e semana');
    await screen.findByRole('heading', { name: 'Acertos por vendedor e semana', level: 1 });
    expect((await screen.findAllByText('Maria Souza')).length).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'financeiro-acertos', pagina: 0 });
    expect(screen.getAllByText(/US\$\s*22,00/).length).toBeGreaterThan(0);
    const totais = screen.getByRole('region', { name: 'Totais' });
    expect(within(totais).getAllByText(/US\$\s*22,00/).length).toBeGreaterThan(0);
    expect(screen.getByText('40,00%')).toBeInTheDocument();
  });

  it('quem deve vai ao servidor e a conta vencida aparece escrita "Vencida"', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              id: ID_ACERTO,
              devedorTipo: 'CLIENTE',
              parte: 'Ana Lima',
              vendedorId: ID_VEND,
              vendedor: 'Maria Souza',
              parcela: 2,
              totalParcelas: 3,
              vencimento: '2026-10-01',
              situacao: 'VENCIDA',
              diasDeAtraso: 40,
              dataDaCotacao: '2026-09-01',
              valorEmReais: '33.33',
              valorEmDolar: '6.00',
              valorEmGuarani: '12000',
              pagoEmReais: '0.00',
              pagoEmDolar: '0.00',
              pagoEmGuarani: '0',
              canceladoEmReais: '0.00',
              canceladoEmDolar: '0.00',
              canceladoEmGuarani: '0',
              emAbertoEmReais: '33.33',
              emAbertoEmDolar: '6.00',
              emAbertoEmGuarani: '12000',
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [],
        },
      }),
    });
    const u = await abrir(m, 'Parcelas a receber');
    expect(await screen.findByText('Vencida')).toBeInTheDocument();
    expect(screen.getByText('2/3')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Quem deve'), 'CLIENTE');
    await waitFor(() => expect(dosRelatorios(m).some((p) => p.devedor === 'CLIENTE')).toBe(true));
    expect(screen.queryByText(/cheque|cartão/i)).toBeNull();
  });

  it('o arquivo leva as três moedas, a data da cotação e a frase do dia de cada acerto', async () => {
    const m = montar();
    const u = await abrir(m, 'Acertos por vendedor e semana');
    await screen.findAllByText('Maria Souza');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('05/11/2026');
    expect(csv).toContain('22,00');
    expect(csv).toContain('110,00');
    expect(csv).toContain('cotação do Banco Central do dia em que cada acerto foi fechado');
  });
});

describe('Relatórios financeiros na ponte', () => {
  it('aceita os filtros novos e recusa valor fora da lista', () => {
    const ok = conferirRelatorio({
      relatorio: 'financeiro-a-receber',
      devedor: 'CLIENTE',
      situacao: 'VENCIDA',
    });
    expect(ok).not.toBeNull();
    expect(
      conferirRelatorio({ relatorio: 'financeiro-recebimentos', momento: 'AVULSO', forma: 'PIX' }),
    ).not.toBeNull();
    expect(
      conferirRelatorio({ relatorio: 'financeiro-caixa', origem: 'MANUAL', tipo: 'SAIDA' }),
    ).not.toBeNull();
    expect(
      conferirRelatorio({ relatorio: 'financeiro-a-receber', devedor: 'FORNECEDOR' }),
    ).toBeNull();
    expect(conferirRelatorio({ relatorio: 'financeiro-recebimentos', momento: 'HOJE' })).toBeNull();
    expect(conferirRelatorio({ relatorio: 'financeiro-caixa', tipo: 'FALTA' })).toBeNull();
    expect(conferirRelatorio({ relatorio: 'financeiro-inventado' })).toBeNull();
  });
});

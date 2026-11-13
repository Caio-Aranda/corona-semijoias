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
import { App } from '../../App';

const entregues: { nome: string; tipo: string; bytes: Uint8Array }[] = [];
vi.mock('../../nucleo/arquivos', async (original) => ({
  ...(await original<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: (nome: string, tipo: string, bytes: Uint8Array) => {
    entregues.push({ nome, tipo, bytes });
  },
}));

const ID_DEVOLUCAO = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_CLIENTE = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';

type Linha = Record<string, unknown>;
type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const DEVOLUCAO = (motivo: string, situacao: string, cancelada: boolean): Linha => ({
  id: ID_DEVOLUCAO,
  vendaId: ID_DEVOLUCAO,
  vendedorId: ID_VEND,
  vendedor: 'Maria Souza',
  clienteId: ID_CLIENTE,
  cliente: 'Ana Lima',
  motivo,
  tipo: 'DEVOLUCAO',
  situacao,
  cancelada,
  devolvidaEm: '2026-11-05T15:00:00Z',
  pecas: 1,
  valorDevolvido: '100.00',
  valorDevolvidoEmDolar: '25.00',
  valorDevolvidoEmGuarani: '50000',
  valorReembolsado: '100.00',
  valorReembolsadoEmDolar: '25.00',
  valorReembolsadoEmGuarani: '50000',
  valorEmCredito: '0.00',
  valorEmCreditoEmDolar: '0.00',
  valorEmCreditoEmGuarani: '0',
  dataDaCotacao: '2026-11-05',
});

const TOTAIS = [
  {
    chave: 'geral',
    rotulo: 'Devoluções feitas (valem no total)',
    quantidade: 1,
    pecas: 1,
    valorEmReais: '100.00',
    valorEmDolar: '25.00',
    valorEmGuarani: '50000',
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
    'pracas.listar': () => pagina([]),
    'categorias.listar': () => pagina([]),
    'semanas.listar': () => pagina([]),
    'relatorios.cadastros': () => ({
      ok: true,
      dados: {
        itens: [
          DEVOLUCAO('ARREPENDIMENTO', 'ACEITA', false),
          DEVOLUCAO('DEFEITO', 'CANCELADA', true),
        ],
        total: 2,
        pagina: 0,
        tamanho: 20,
        totais: TOTAIS,
      },
    }),
    'devolucoes.conferencia': () => ({
      ok: true,
      dados: { geradaEm: '2026-11-13T12:00:00Z', tudoCerto: true, conferencias: [] },
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

describe('Relatórios de devolução e troca', () => {
  it('o menu tem o grupo, os sete relatórios e a conferência com os nomes do glossário', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    expect(await screen.findByText('Relatórios de devoluções')).toBeInTheDocument();
    for (const t of [
      'Devoluções por vendedor e motivo',
      'Quanto cada vendedor devolveu do que vendeu',
      'Peças que voltaram com defeito',
      'Trocas feitas',
      'Créditos dos clientes: em aberto e usados',
      'Dinheiro devolvido aos clientes',
      'Pedidos de autorização',
      'Conferência das devoluções e trocas',
    ]) {
      expect(await screen.findByRole('button', { name: t })).toBeInTheDocument();
    }
  });

  it('a devolução cancelada aparece escrita "Cancelada" e o valor sai nas três moedas do dia de cada devolução', async () => {
    const m = montar();
    await abrir(m, 'Devoluções por vendedor e motivo');
    await screen.findByRole('heading', { name: 'Devoluções por vendedor e motivo', level: 1 });
    expect(await screen.findByText('Cancelada')).toBeInTheDocument();
    expect(screen.getAllByText('Desistência').length).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'devolucao-por-motivo', pagina: 0 });
    // O Dólar vem da linha (cotação do dia da devolução), não da cotação de hoje.
    expect(screen.getAllByText(/US\$\s*25,00/).length).toBeGreaterThan(0);
    const totais = screen.getByRole('region', { name: 'Totais' });
    expect(within(totais).getAllByText(/US\$\s*25,00/).length).toBeGreaterThan(0);
  });

  it('o motivo escolhido vai ao servidor', async () => {
    const m = montar();
    const u = await abrir(m, 'Devoluções por vendedor e motivo');
    await screen.findByText('Cancelada');
    await u.selectOptions(screen.getByLabelText('Motivo da devolução'), 'DEFEITO');
    await waitFor(() => expect(dosRelatorios(m).some((p) => p.motivo === 'DEFEITO')).toBe(true));
  });

  it('o arquivo usa o Dólar de cada devolução e a frase da cotação', async () => {
    const m = montar();
    const u = await abrir(m, 'Devoluções por vendedor e motivo');
    await screen.findByText('Cancelada');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('05/11/2026');
    expect(csv).toContain('25,00');
    expect(csv).toContain('Cancelada');
    expect(csv).toContain('cotação do Banco Central do dia de cada devolução');
  });

  it('a taxa mostra "Acima do limite" em palavras', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              vendedorId: ID_VEND,
              vendedor: 'Maria Souza',
              vendas: 5,
              vendido: '740.00',
              vendidoEmDolar: '157.00',
              vendidoEmGuarani: '650000',
              devolucoes: 4,
              trocas: 2,
              devolvido: '360.00',
              devolvidoEmDolar: '90.00',
              devolvidoEmGuarani: '180000',
              porDesistencia: 2,
              porDefeito: 1,
              porTamanho: 1,
              porOutro: 0,
              porcentagemDevolvida: '48.65',
              acimaDoLimite: true,
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [],
        },
      }),
    });
    await abrir(m, 'Quanto cada vendedor devolveu do que vendeu');
    expect(await screen.findByText('Acima do limite')).toBeInTheDocument();
    expect(screen.getByText('48,65%')).toBeInTheDocument();
  });

  it('a conferência diz "Tudo certo" quando nada está fora do lugar', async () => {
    const m = montar({
      'devolucoes.conferencia': () => ({
        ok: true,
        dados: {
          geradaEm: '2026-11-13T12:00:00Z',
          tudoCerto: true,
          conferencias: [
            { chave: 'maleta', conferidos: 7, divergencias: [] },
            { chave: 'creditos', conferidos: 3, divergencias: [] },
          ],
        },
      }),
    });
    await abrir(m, 'Conferência das devoluções e trocas');
    expect(await screen.findByText(/Tudo certo: as devoluções/)).toBeInTheDocument();
    expect(screen.getByText('Maleta com devoluções e trocas')).toBeInTheDocument();
    expect(screen.getByText('Conferidos: 7')).toBeInTheDocument();
  });

  it('a conferência lista o que não bate, sem esconder', async () => {
    const m = montar({
      'devolucoes.conferencia': () => ({
        ok: true,
        dados: {
          geradaEm: '2026-11-13T12:00:00Z',
          tudoCerto: false,
          conferencias: [
            {
              chave: 'creditos',
              conferidos: 3,
              divergencias: [{ referencia: 'Crédito 123', descricao: 'Os usos não batem.' }],
            },
          ],
        },
      }),
    });
    await abrir(m, 'Conferência das devoluções e trocas');
    expect(await screen.findByText(/Há diferenças/)).toBeInTheDocument();
    expect(screen.getByText(/Os usos não batem\./)).toBeInTheDocument();
    expect(screen.queryByText(/Tudo certo/)).not.toBeInTheDocument();
  });
});

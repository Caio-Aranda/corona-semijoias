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
import { abrirAreaDoMenu, abrirRelatorioPeloMenu } from '../../apoio-de-testes';
import { App } from '../../App';

const entregues: { nome: string; tipo: string; bytes: Uint8Array }[] = [];
vi.mock('../../nucleo/arquivos', async (original) => ({
  ...(await original<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: (nome: string, tipo: string, bytes: Uint8Array) => {
    entregues.push({ nome, tipo, bytes });
  },
}));

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

// V1 da massa do servidor: 4 vendas, 6 peças, vendido 660, devolvido 230, líquido 430, valor médio 107,50.
const VENDEDOR: Linha = {
  posicao: 1,
  chave: ID_VEND,
  nome: 'Maria Souza',
  detalhe: 'Ciudad del Este',
  codigo: null,
  fotoPrincipalHash: null,
  vendas: 4,
  pecas: 6,
  ticketMedio: '107.50',
  ...TRES('vendido', '660.00', '141.00', '570000'),
  ...TRES('devolvido', '230.00', '52.50', '165000'),
  ...TRES('liquido', '430.00', '88.50', '405000'),
};

const TOTAIS = [
  {
    chave: 'liquido',
    rotulo: 'Vendido líquido',
    quantidade: 1,
    pecas: 6,
    valorEmReais: '430.00',
    valorEmDolar: '88.50',
    valorEmGuarani: '405000',
  },
  {
    chave: 'vendido',
    rotulo: 'Vendido',
    quantidade: 1,
    pecas: 6,
    valorEmReais: '660.00',
    valorEmDolar: '141.00',
    valorEmGuarani: '570000',
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
      dados: { itens: [VENDEDOR], total: 1, pagina: 0, tamanho: 20, totais: TOTAIS },
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
  await abrirRelatorioPeloMenu(u, titulo);
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  entregues.length = 0;
});

describe('Relatórios de vendas e curva ABC (tarefa 8.5)', () => {
  it('o menu tem o grupo de vendas com os nomes do glossário e a curva ABC no Estoque', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    const cartoes = await abrirAreaDoMenu(u, 'vendas');
    for (const t of [
      'Vendas feitas',
      'Vendas por vendedor',
      'Vendas por praça',
      'Vendas por cliente',
      'Vendas por produto',
      'Vendas por categoria',
      'Vendas por dia',
      'Ranking de vendedores',
      'Ranking de produtos',
      'Valor médio por venda',
      'Vendas canceladas',
    ]) {
      expect(within(cartoes).getByText(t)).toBeInTheDocument();
    }
    const estoque = await abrirAreaDoMenu(u, 'estoque');
    expect(within(estoque).getByText('Curva ABC dos produtos')).toBeInTheDocument();
  });

  it('por vendedor mostra Real, Dólar e Guarani que vieram da linha e do rodapé, não os de hoje', async () => {
    const m = montar();
    await abrir(m, 'Vendas por vendedor');
    await screen.findByRole('heading', { name: 'Vendas por vendedor', level: 1 });
    expect((await screen.findAllByText('Maria Souza')).length).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'vendas-por-vendedor', pagina: 0 });
    expect(screen.getAllByText(/US\$\s*88,50/).length).toBeGreaterThan(0);
    const totais = screen.getByRole('region', { name: 'Totais' });
    expect(within(totais).getAllByText(/US\$\s*88,50/).length).toBeGreaterThan(0);
    expect(within(totais).getAllByText(/US\$\s*141,00/).length).toBeGreaterThan(0);
  });

  it('a lista de vendas manda a situação escolhida e escreve "Cancelada" em palavras da loja', async () => {
    const venda: Linha = {
      vendaId: '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11',
      vendidaEm: '2026-11-05T15:00:00Z',
      vendedor: 'Maria Souza',
      praca: null,
      cliente: 'Ana Lima',
      situacao: 'ESTORNADA',
      cancelada: true,
      pecas: 1,
      dataDaCotacao: '2026-11-05',
      ...TRES('vendido', '100.00', '25.00', '50000'),
      ...TRES('devolvido', '0.00', '0.00', '0'),
      ...TRES('liquido', '0.00', '0.00', '0'),
    };
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: { itens: [venda], total: 1, pagina: 0, tamanho: 20, totais: [] },
      }),
    });
    const u = await abrir(m, 'Vendas feitas');
    expect(await screen.findByText('Cancelada')).toBeInTheDocument();
    expect(screen.getByText('Sem praça')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Situação'), 'TODAS');
    await waitFor(() => expect(dosRelatorios(m).some((p) => p.situacao === 'TODAS')).toBe(true));
    expect(screen.queryByText(/estorn/i)).toBeNull();
  });

  it('a curva ABC mostra a classe e a parte de cada produto', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              posicao: 1,
              produtoId: ID_VEND,
              nome: 'Colar Lua',
              referencia: null,
              codigo: 'VD-1',
              fotoPrincipalHash: null,
              categoria: 'Colares',
              pecas: 4,
              percentual: '59.26',
              percentualAcumulado: '59.26',
              classe: 'A',
              ...TRES('liquido', '400.00', '80.00', '400000'),
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [
            {
              chave: 'classe-a',
              rotulo: 'Classe A',
              quantidade: 1,
              pecas: 4,
              valorEmReais: '400.00',
              valorEmDolar: '80.00',
              valorEmGuarani: '400000',
            },
          ],
        },
      }),
    });
    await abrir(m, 'Curva ABC dos produtos');
    expect(await screen.findByText('Colar Lua')).toBeInTheDocument();
    expect(screen.getAllByText('59,26%').length).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'vendas-curva-abc' });
    expect(screen.queryByRole('columnheader', { name: /custo|lucro/i })).toBeNull();
  });

  it('o arquivo leva as três moedas e a frase da cotação do dia de cada venda', async () => {
    const m = montar();
    const u = await abrir(m, 'Vendas por vendedor');
    await screen.findAllByText('Maria Souza');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('88,50');
    expect(csv).toContain('430,00');
    expect(csv).toContain('107,50');
    expect(csv).toContain('cotação do Banco Central do dia de cada venda');
  });
});

describe('Relatórios de vendas na ponte', () => {
  it('aceita os nomes novos com os filtros e recusa o que não existe', () => {
    expect(
      conferirRelatorio({
        relatorio: 'vendas-por-vendedor',
        vendedorId: ID_VEND,
        de: '2026-11-01',
        ate: '2026-11-30',
      }),
    ).not.toBeNull();
    expect(conferirRelatorio({ relatorio: 'vendas-lista', situacao: 'TODAS' })).not.toBeNull();
    expect(
      conferirRelatorio({ relatorio: 'vendas-curva-abc', recorte: 'classe-a' }),
    ).not.toBeNull();
    expect(conferirRelatorio({ relatorio: 'vendas-lista', situacao: 'QUALQUER' })).toBeNull();
    expect(conferirRelatorio({ relatorio: 'vendas-inventado' })).toBeNull();
  });
});

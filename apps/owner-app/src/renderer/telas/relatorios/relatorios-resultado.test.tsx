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

const linhaDoResultado = (
  chave: string,
  titulo: string,
  operacao: string,
  real: string,
): Linha => ({
  id: chave,
  chave,
  titulo,
  operacao,
  valorEmReais: real,
  valorEmDolar: '1.00',
  valorEmGuarani: '1000',
});

// A massa de abril do servidor: resultado -168,00 (prejuízo).
const RESULTADO = [
  linhaDoResultado('VENDAS', 'Vendas', 'SOMA', '235.00'),
  linhaDoResultado('DEVOLUCOES', 'Devoluções e trocas', 'SUBTRAI', '50.00'),
  linhaDoResultado('CUSTO', 'Custo das peças vendidas', 'SUBTRAI', '44.50'),
  linhaDoResultado('RESULTADO', 'Resultado do período', 'TOTAL', '-168.00'),
  {
    id: 'aviso',
    chave: 'AVISO',
    operacao: 'AVISO',
    titulo: '3 peças estão com o custo do cadastro: esse custo muda se o cadastro mudar.',
  },
];

const PRODUTO: Linha = {
  posicao: 1,
  produtoId: '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33',
  nome: 'Colar Lua',
  codigo: 'VD-1',
  categoria: 'Colares',
  fotoPrincipalHash: null,
  pecas: 3,
  margem: '75.00',
  custoDoCadastro: true,
  ...TRES('receita', '90.00', '18.00', '90000'),
  ...TRES('custo', '22.50', '4.50', '22500'),
  ...TRES('lucro', '67.50', '13.50', '67500'),
};

function montar() {
  const chamadas: PedidoDeChamada[] = [];
  const todos: Record<string, Manipulador> = {
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
    'vendedores.escolha': () => pagina([]),
    'fornecedores.escolha': () => pagina([]),
    'pracas.listar': () => pagina([]),
    'categorias.listar': () => pagina([]),
    'semanas.listar': () => pagina([]),
    'relatorios.cadastros': (p) => {
      const nome = String((p.parametros as Linha).relatorio);
      if (nome === 'financeiro-resultado') {
        return {
          ok: true,
          dados: { itens: RESULTADO, total: 5, pagina: 0, tamanho: 20, totais: [] },
        };
      }
      return {
        ok: true,
        dados: {
          itens: [PRODUTO],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [
            {
              chave: 'lucro',
              rotulo: 'Lucro',
              quantidade: 1,
              valorEmReais: '67.50',
              valorEmDolar: '13.50',
              valorEmGuarani: '67500',
            },
          ],
        },
      };
    },
  };
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

describe('Resultado do período e lucro (tarefa 8.6)', () => {
  it('o menu financeiro tem os quatro relatórios novos, com os nomes do glossário', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    for (const t of [
      'Resultado do período',
      'Registros do resultado',
      'Lucro por produto',
      'Lucro por vendedor',
    ]) {
      expect(await screen.findByRole('button', { name: t })).toBeInTheDocument();
    }
  });

  it('o resultado mostra as linhas na ordem, com (−) e (+), o prejuízo escrito e o aviso do dia da venda', async () => {
    const m = montar();
    await abrir(m, 'Resultado do período');
    await screen.findByRole('heading', { name: 'Resultado do período', level: 1 });
    expect(await screen.findByText('(+) Vendas')).toBeInTheDocument();
    expect(screen.getByText('(−) Devoluções e trocas')).toBeInTheDocument();
    expect(screen.getByText('(−) Custo das peças vendidas')).toBeInTheDocument();
    expect(screen.getByText('= Resultado do período (prejuízo)')).toBeInTheDocument();
    expect(screen.getByText(/3 peças estão com o custo do cadastro/)).toBeInTheDocument();
    expect(
      screen.getAllByText(/A devolução abate o mês em que a venda foi feita/).length,
    ).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'financeiro-resultado' });
  });

  it('o lucro por produto mostra a foto que falta sem travar, o lucro e a marca de custo do cadastro', async () => {
    const m = montar();
    await abrir(m, 'Lucro por produto');
    expect(await screen.findByText('Colar Lua')).toBeInTheDocument();
    expect(screen.getAllByText('75,00%').length).toBeGreaterThan(0);
    expect(screen.getByRole('columnheader', { name: 'Custo do cadastro' })).toBeInTheDocument();
    const totais = screen.getByRole('region', { name: 'Totais' });
    expect(within(totais).getAllByText(/US\$\s*13,50/).length).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'financeiro-lucro-produto' });
  });

  it('o arquivo do lucro leva as três moedas', async () => {
    const m = montar();
    const u = await abrir(m, 'Lucro por produto');
    await screen.findByText('Colar Lua');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('67,50');
    expect(csv).toContain('13,50');
  });
});

describe('Resultado e lucro na ponte', () => {
  it('aceita os nomes novos e a linha do resultado, e recusa o que não existe', () => {
    expect(
      conferirRelatorio({
        relatorio: 'financeiro-resultado-registros',
        de: '2292-04-01',
        ate: '2292-04-30',
        linha: 'PERDAS',
      }),
    ).not.toBeNull();
    expect(conferirRelatorio({ relatorio: 'financeiro-lucro-produto' })).not.toBeNull();
    expect(conferirRelatorio({ relatorio: 'financeiro-lucro-vendedor' })).not.toBeNull();
    expect(
      conferirRelatorio({ relatorio: 'financeiro-resultado-registros', linha: 'OUTRA' }),
    ).toBeNull();
  });
});

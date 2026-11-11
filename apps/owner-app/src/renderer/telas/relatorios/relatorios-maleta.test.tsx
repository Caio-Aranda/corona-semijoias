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

const ID_MALETA = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_SEMANA = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';

type Linha = Record<string, unknown>;
type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const EM_ABERTO = (atrasada: boolean, i: number): Linha => ({
  remessaId: ID_MALETA,
  numero: `S12-V7-${i}`,
  vendedorId: ID_VEND,
  vendedor: 'Maria Souza',
  semana: 12,
  praca: 'Ciudad del Este',
  tipo: 'NOVA',
  status: 'ENVIADA',
  enviadaEm: '2026-11-01T12:00:00Z',
  diasDesdeOEnvio: atrasada ? 10 : 3,
  atrasada,
  pecasEmAberto: 4,
  valorEmReais: '100.00',
  valorEmDolar: '18.00',
  valorEmGuarani: '111732.00',
  dataDaCotacao: '2026-11-01',
});

const TOTAIS_EM_ABERTO = [
  {
    chave: 'emAberto',
    rotulo: 'Maletas em aberto',
    quantidade: 2,
    pecas: 8,
    valorEmReais: '200.00',
    valorEmDolar: '36.00',
    valorEmGuarani: '223464.00',
  },
  {
    chave: 'atrasadas',
    rotulo: 'Maletas atrasadas',
    quantidade: 1,
    pecas: 4,
    valorEmReais: '100.00',
    valorEmDolar: '18.00',
    valorEmGuarani: '111732.00',
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
    'semanas.listar': () => pagina([{ id: ID_SEMANA, version: 0, numero: 12 }]),
    'relatorios.cadastros': () => ({
      ok: true,
      dados: {
        itens: [EM_ABERTO(true, 1), EM_ABERTO(false, 2)],
        total: 2,
        pagina: 0,
        tamanho: 20,
        totais: TOTAIS_EM_ABERTO,
      },
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

describe('Relatórios da maleta', () => {
  it('o menu tem o grupo e os cinco relatórios com os nomes do glossário', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    expect(await screen.findByText('Relatórios da maleta')).toBeInTheDocument();
    for (const t of [
      'Maletas por vendedor e semana',
      'O que ainda está com o vendedor, peça a peça',
      'Mercadoria com cada vendedor',
      'Maletas em aberto e atrasadas',
      'Reposições',
    ]) {
      expect(await screen.findByRole('button', { name: t })).toBeInTheDocument();
    }
  });

  it('maleta atrasada aparece escrita "Atrasada" e o valor sai nas três moedas do dia do envio', async () => {
    const m = montar();
    await abrir(m, 'Maletas em aberto e atrasadas');
    await screen.findByRole('heading', { name: 'Maletas em aberto e atrasadas', level: 1 });
    expect(await screen.findByText('Atrasada')).toBeInTheDocument();
    expect(screen.getAllByText('No prazo').length).toBeGreaterThan(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'maleta-em-aberto', pagina: 0 });
    // O Dólar vem da linha (cotação do envio), não da cotação de hoje (R$ 100 / 5,50 = US$ 18,17).
    expect(screen.getAllByText(/US\$\s*18,00/).length).toBeGreaterThan(0);
    // O total também é o do servidor.
    const totais = screen.getByRole('region', { name: 'Totais' });
    expect(within(totais).getAllByText(/US\$\s*36,00/).length).toBeGreaterThan(0);
  });

  it('o prazo para atrasar vai ao servidor e "Ver a maleta" abre a ficha dela', async () => {
    const m = montar({
      'maletas.ver': () => ({ ok: false, motivo: 'erro', mensagem: 'ficha pedida' }),
      'maletas.saldo': () => ({ ok: false, motivo: 'erro', mensagem: 'ficha pedida' }),
    });
    const u = await abrir(m, 'Maletas em aberto e atrasadas');
    await screen.findByText('Atrasada');
    const prazo = screen.getByLabelText('Prazo para atrasar (dias)');
    expect(prazo).toHaveAttribute('placeholder', '7');
    await u.type(prazo, '15');
    await waitFor(() => expect(dosRelatorios(m).some((p) => p.diasDeAtraso === 15)).toBe(true));
    await u.click((await screen.findAllByRole('button', { name: /Ver a maleta: S12-V7-1/ }))[0]!);
    await waitFor(() =>
      expect(
        m.chamadas.some(
          (c) => c.operacao === 'maletas.ver' && (c.parametros as Linha).id === ID_MALETA,
        ),
      ).toBe(true),
    );
    expect(window.location.hash).toBe('#/maletas/lista');
  });

  it('o arquivo usa o Dólar de cada maleta e a data da cotação do envio', async () => {
    const m = montar();
    const u = await abrir(m, 'Maletas em aberto e atrasadas');
    await screen.findByText('Atrasada');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('01/11/2026');
    expect(csv).toContain('18,00');
    expect(csv).toContain('111732');
    expect(csv).toContain('Atrasada');
    expect(csv).toContain('cotação do Banco Central do dia do envio');
  });

  it('mercadoria com cada vendedor mostra o aviso de limite e atraso em palavras', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              vendedorId: ID_VEND,
              vendedor: 'Maria Souza',
              maletasEmAberto: 2,
              maletasAtrasadas: 1,
              pecasEmAberto: 8,
              valorEmReais: '200.00',
              valorEmDolar: '36.00',
              valorEmGuarani: '223464.00',
              limite: '150.00',
              sobraDoLimite: '-50.00',
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [],
        },
      }),
    });
    await abrir(m, 'Mercadoria com cada vendedor');
    expect(await screen.findByText('Tem maleta atrasada; Acima do limite')).toBeInTheDocument();
  });
});

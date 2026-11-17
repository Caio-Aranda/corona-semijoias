// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
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

const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_SEM = '7a1d6c2e-0b3f-4a8e-9d52-5e1f0c3b8a11';
const ID_PRACA = '5d0c1c0b-6a53-4a4e-a1c3-0f6f3a2b9e77';
const ID_ACERTO = '9b2f3c4d-1e5a-4f60-8a71-b2c3d4e5f607';
const ID_MALETA = '3e4f5a6b-7c8d-4e9f-a0b1-c2d3e4f5a6b7';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const M = (real: string, dolar: string | null, guarani: string | null) => ({
  emReais: real,
  emDolar: dolar,
  emGuarani: guarani,
});

const PAINEL = {
  semana: { id: ID_SEM, numero: 46, inicio: '2026-11-09', fim: '2026-11-15', situacao: 'ABERTA' },
  previaIncluiSemanasAnteriores: true,
  avisoDaPrevia: null,
  pedidos: [
    {
      acertoId: ID_ACERTO,
      vendedorId: ID_VEND,
      vendedor: 'Maria Souza',
      pracaId: ID_PRACA,
      praca: 'Ciudad del Este',
      situacao: 'ACERTADO',
      previa: false,
      dataDaCotacao: '2026-11-12',
      percentualComissao: '20',
      enviadoPecas: 10,
      devolvidoPecas: 2,
      vendidasPecas: 6,
      emFaltaPecas: 2,
      enviado: M('1000.00', '200.00', '1400000'),
      devolvido: M('230.00', '46.00', '322000'),
      vendidoLiquido: M('430.00', '86.00', '602000'),
      comissao: M('86.00', '17.20', '120400'),
      liquido: M('344.00', '68.80', '481600'),
      maletas: [{ id: ID_MALETA, numero: 'M-12', tipo: 'NOVA', situacao: 'ACERTADA' }],
    },
    {
      acertoId: null,
      vendedorId: 'aaaaaaaa-0000-4000-8000-000000000001',
      vendedor: 'João Prévia',
      pracaId: null,
      praca: 'Sem praça',
      situacao: 'EM_ABERTO',
      previa: true,
      semCotacao: true,
      dataDaCotacao: null,
      enviadoPecas: 3,
      devolvidoPecas: 0,
      vendidasPecas: 1,
      emFaltaPecas: 0,
      enviado: M('300.00', null, null),
      devolvido: M('0.00', null, null),
      vendidoLiquido: M('100.00', null, null),
      comissao: M('20.00', null, null),
      liquido: M('80.00', null, null),
      maletas: [],
    },
  ],
  totais: {
    novasMaletas: {
      quantidade: 1,
      pecas: 10,
      valor: M('1000.00', '200.00', '1400000'),
      origem: {
        rota: '/api/dona/relatorios/maleta/remessas',
        filtros: { semanaId: ID_SEM },
      },
    },
    reposicoes: { quantidade: 0, pecas: 0, valor: M('0.00', '0.00', '0') },
    total: {
      maletas: 2,
      pecas: 13,
      vendas: 5,
      enviado: M('1300.00', '200.00', '1400000'),
      vendido: M('530.00', '86.00', '602000'),
      liquido: M('424.00', '68.80', '481600'),
    },
  },
};

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const pagina = (itens: unknown[]) => ({
    ok: true as const,
    dados: { itens, pagina: 0, tamanho: 100, total: itens.length },
  });
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () => pagina([{ id: ID_VEND, nome: 'Maria Souza' }]),
    'pracas.listar': () => pagina([{ id: ID_PRACA, nome: 'Ciudad del Este', ativo: true }]),
    'semanas.listar': () => pagina([{ id: ID_SEM, numero: 46 }]),
    'painel.semanal': () => ({ ok: true, dados: PAINEL }),
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

const doPainel = (m: ReturnType<typeof montar>) =>
  // A tela inicial também pede o painel (sem parâmetros, só para o resumo); aqui contam só os pedidos da tela do painel.
  m.chamadas
    .filter((c) => c.operacao === 'painel.semanal' && c.parametros !== undefined)
    .map((c) => c.parametros);

async function abrir(m: ReturnType<typeof montar>) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const inicio = await screen.findByRole('navigation', { name: 'O que você quer fazer?' });
  await u.click(within(inicio).getByRole('button', { name: 'Painel da semana' }));
  await screen.findByRole('heading', { name: 'Painel da semana', level: 1 });
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  entregues.length = 0;
});

describe('Painel da semana (tarefa 8.8)', () => {
  it('abre pelo botão grande da tela inicial e mostra a semana, os atalhos e as duas abas', async () => {
    const m = montar();
    await abrir(m);
    expect(await screen.findByText('Semana 46: de 09/11/2026 a 15/11/2026')).toBeInTheDocument();
    const atalhos = screen.getByRole('navigation', { name: 'Atalhos' });
    for (const a of ['Nova maleta', 'Acerto', 'Reposição', 'Troca', 'Vendedores', 'Estoque']) {
      expect(within(atalhos).getByRole('button', { name: a })).toBeInTheDocument();
    }
    expect(screen.getByRole('tab', { name: 'Maletas' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Totais' })).toBeInTheDocument();
    // Primeira chamada: sem filtros, o servidor escolhe a semana de hoje.
    expect(doPainel(m)[0]).toEqual({});
  });

  it('mostra o que o servidor mandou nas três moedas, sem recalcular, e avisa da prévia', async () => {
    const m = montar();
    await abrir(m);
    expect(await screen.findByText('João Prévia')).toBeInTheDocument();
    expect(screen.getAllByText('R$ 1.000,00').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/US\$ 200,00 · Gs\. 1\.400\.000/).length).toBeGreaterThan(0);
    expect(screen.getByText('Cotação de 12/11/2026')).toBeInTheDocument();
    // Prévia sem cotação: só o Real, com o motivo dito em palavras da loja.
    expect(screen.getByText('João Prévia')).toBeInTheDocument();
    expect(screen.getAllByText('Sem cotação').length).toBeGreaterThan(0);
    expect(screen.getByText(/Prévia: ainda não é o acerto/)).toBeInTheDocument();
    expect(screen.getByText(/ainda não acertou de semanas anteriores/)).toBeInTheDocument();
  });

  it('os filtros pedem ao servidor e limpar volta ao padrão', async () => {
    const m = montar();
    const u = await abrir(m);
    await screen.findByText('João Prévia');
    await u.selectOptions(await screen.findByLabelText('Vendedor'), ID_VEND);
    await waitFor(() =>
      expect(doPainel(m).some((p) => (p as Record<string, string>).vendedor === ID_VEND)).toBe(
        true,
      ),
    );
    await u.selectOptions(screen.getByLabelText('Situação'), 'EM_ACERTO');
    await u.selectOptions(screen.getByLabelText('Semana'), ID_SEM);
    await u.selectOptions(screen.getByLabelText('Praça'), ID_PRACA);
    await waitFor(() =>
      expect(doPainel(m).at(-1)).toEqual({
        semana: ID_SEM,
        vendedor: ID_VEND,
        praca: ID_PRACA,
        situacao: 'EM_ACERTO',
      }),
    );
    await u.click(screen.getByRole('button', { name: 'Limpar filtros' }));
    await waitFor(() => expect(doPainel(m).at(-1)).toEqual({}));
  });

  it('clicar na linha abre o acerto ou a maleta', async () => {
    const m = montar();
    const u = await abrir(m);
    await u.click(
      await screen.findByRole('button', { name: 'Abrir a maleta M-12 de Maria Souza' }),
    );
    expect(window.location.hash).toBe('#/maletas/lista');

    cleanup();
    window.location.hash = '';
    const m2 = montar({
      'acertos.ver': () => ({ ok: false, motivo: 'erro' }),
    });
    const u2 = await abrir(m2);
    await u2.click(await screen.findByRole('button', { name: 'Abrir o acerto de Maria Souza' }));
    expect(window.location.hash).toBe('#/acertos/lista');
  });

  it('a aba Totais mostra os blocos e o clique no total abre os registros com os mesmos filtros', async () => {
    const m = montar();
    const u = await abrir(m);
    await screen.findByText('João Prévia');
    await u.click(screen.getByRole('tab', { name: 'Totais' }));
    expect(await screen.findByRole('heading', { name: 'Novas maletas' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Total da semana' })).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Ver os registros de Maletas' }));
    expect(window.location.hash).toBe('#/relatorios/maleta-remessas');
    // O relatório abre já com a semana do painel.
    await waitFor(() =>
      expect(
        m.chamadas.some(
          (c) =>
            c.operacao === 'relatorios.cadastros' &&
            (c.parametros as Record<string, string>).relatorio === 'maleta-remessas' &&
            (c.parametros as Record<string, string>).semanaId === ID_SEM,
        ),
      ).toBe(true),
    );
  });

  it('guarda em CSV, Excel e PDF com as três moedas e a data da cotação de cada linha', async () => {
    const m = montar();
    const u = await abrir(m);
    await screen.findByText('João Prévia');
    await u.click(screen.getByRole('button', { name: 'CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('Maria Souza');
    expect(csv).toContain('1000,00');
    expect(csv).toContain('200,00');
    expect(csv).toContain('1400000');
    expect(csv).toContain('12/11/2026');
    expect(csv).toContain('cotação do dia de cada lançamento');
    await u.click(screen.getByRole('button', { name: 'Excel' }));
    await u.click(screen.getByRole('button', { name: 'PDF' }));
    await waitFor(() => expect(entregues).toHaveLength(3));
    expect(entregues.map((e) => e.nome.split('.').pop())).toEqual(['csv', 'xlsx', 'pdf']);
  });

  it('sem internet mostra o aviso e deixa tentar de novo; sem vendedores diz isso em palavras da loja', async () => {
    const m = montar({ 'painel.semanal': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m);
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();

    cleanup();
    window.location.hash = '';
    const vazio = montar({
      'painel.semanal': () => ({ ok: true, dados: { ...PAINEL, pedidos: [] } }),
    });
    const u = await abrir(vazio);
    expect(
      await screen.findByText('Nenhum vendedor com movimento nesta semana.'),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'CSV' }));
    expect(await screen.findByText(/Não há vendedores para guardar/)).toBeInTheDocument();
  });
});

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

type Linha = Record<string, unknown>;
type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const AUDITORIA: Linha = {
  id: '11111111-1111-4111-8111-111111111111',
  ocorridaEm: '2026-11-10T15:30:00Z',
  quem: 'Maria Souza',
  usuarioId: '22222222-2222-4222-8222-222222222222',
  origem: 'API',
  acao: 'PRECO_VENDA_ALTERADO',
  oQueFoi: 'Preço de venda alterado',
  tabela: 'produtos',
  area: 'Produto',
  registroId: '33333333-3333-4333-8333-333333333333',
  antes: 'Preço de venda: 10.00',
  depois: 'Preço de venda: 12.50',
};

const ACESSO: Linha = {
  id: '44444444-4444-4444-8444-444444444444',
  ocorridaEm: '2026-11-10T15:30:00Z',
  login: 'ninguem',
  pessoa: null,
  aplicativo: 'Corona Gestão',
  resultado: 'SENHA_INCORRETA',
  resultadoNaTela: 'Senha errada',
  ip: '203.0.113.7',
  bloqueadoAte: null,
  ipBloqueadoAte: null,
};

const COMPUTADOR: Linha = {
  id: '55555555-5555-4555-8555-555555555555',
  vendedorId: '66666666-6666-4666-8666-666666666666',
  vendedor: 'Maria Souza',
  situacao: 'ATIVO',
  situacaoNaTela: 'Em uso',
  versaoApp: '2.3.0',
  ultimoRecebimentoEm: '2026-11-10T13:30:00Z',
  ultimoEnvioEm: '2026-11-10T14:30:00Z',
  aguardandoEnvio: 3,
  enviosComProblema: 2,
  emQuarentena: 1,
};

const totais = (chave: string, rotulo: string, quantidade: number) => [
  {
    chave,
    rotulo,
    quantidade,
    pecas: null,
    valorEmReais: null,
    valorEmDolar: null,
    valorEmGuarani: null,
  },
];

function montar(linha: Linha) {
  const chamadas: PedidoDeChamada[] = [];
  const todos: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () => pagina([]),
    'usuarios.listar': () => pagina([]),
    'usuarios.escolha': () => pagina([]),
    'fornecedores.escolha': () => pagina([]),
    'pracas.listar': () => pagina([]),
    'categorias.listar': () => pagina([]),
    'semanas.listar': () => pagina([]),
    'relatorios.cadastros': () => ({
      ok: true,
      dados: {
        itens: [linha],
        total: 1,
        pagina: 0,
        tamanho: 20,
        totais: totais('todos', 'Registros', 1),
      },
    }),
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

beforeEach(() => {
  window.location.hash = '';
});

describe('Relatórios de controle (tarefa 8.9)', () => {
  it('o menu tem o grupo Controle com os três relatórios, sem termo técnico', async () => {
    const m = montar(AUDITORIA);
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    const cartoes = await abrirAreaDoMenu(u, 'controle');
    for (const t of [
      'Auditoria: quem fez o quê',
      'Computadores e envios',
      'Acessos: entradas e erros',
    ]) {
      expect(within(cartoes).getByText(t)).toBeInTheDocument();
    }
  });

  it('a auditoria mostra quem, o quê e o antes e o depois em texto da loja', async () => {
    const m = montar(AUDITORIA);
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await abrirRelatorioPeloMenu(u, 'Auditoria: quem fez o quê');
    expect(await screen.findByText('Preço de venda alterado')).toBeInTheDocument();
    expect(screen.getByText('Maria Souza')).toBeInTheDocument();
    expect(screen.getByText('Preço de venda: 10.00')).toBeInTheDocument();
    expect(screen.getByText('Preço de venda: 12.50')).toBeInTheDocument();
    const pedido = m.chamadas
      .filter((c) => c.operacao === 'relatorios.cadastros')
      .map((c) => c.parametros as Linha)
      .at(-1);
    expect(pedido?.relatorio).toBe('controle-auditoria');
  });

  it('os computadores mostram o último envio, os que esperam e os com problema', async () => {
    const m = montar(COMPUTADOR);
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await abrirRelatorioPeloMenu(u, 'Computadores e envios');
    expect((await screen.findAllByText('Em uso')).length).toBeGreaterThan(0);
    expect(screen.getByText('2.3.0')).toBeInTheDocument();
    expect(screen.getAllByText('Maria Souza').length).toBeGreaterThan(0);
  });

  it('os acessos mostram o usuário que não existe sem nome de pessoa', async () => {
    const m = montar(ACESSO);
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await abrirRelatorioPeloMenu(u, 'Acessos: entradas e erros');
    expect(await screen.findByText('Usuário que não existe')).toBeInTheDocument();
    expect(screen.getAllByText('Senha errada').length).toBeGreaterThan(0);
    expect(screen.getByText('203.0.113.7')).toBeInTheDocument();
    await waitFor(() => {
      expect(
        m.chamadas.some(
          (c) =>
            c.operacao === 'relatorios.cadastros' &&
            (c.parametros as Linha).relatorio === 'controle-acessos',
        ),
      ).toBe(true);
    });
  });

  it('a conferência dos pedidos aceita os filtros novos e recusa os inventados', () => {
    const base = { relatorio: 'controle-computadores', pagina: 0, tamanho: 20 };
    expect(
      conferirRelatorio({ ...base, situacao: 'BLOQUEADO', comProblema: 'SIM' }),
    ).not.toBeNull();
    expect(conferirRelatorio({ ...base, situacao: 'QUALQUER' })).toBeNull();
    expect(conferirRelatorio({ ...base, comProblema: 'TALVEZ' })).toBeNull();
    const acessos = { relatorio: 'controle-acessos', pagina: 0, tamanho: 20 };
    expect(
      conferirRelatorio({ ...acessos, resultado: 'SENHA_INCORRETA', aplicativo: 'vendas' }),
    ).not.toBeNull();
    expect(conferirRelatorio({ ...acessos, aplicativo: 'outro' })).toBeNull();
    expect(conferirRelatorio({ ...acessos, resultado: 'NADA' })).toBeNull();
  });
});

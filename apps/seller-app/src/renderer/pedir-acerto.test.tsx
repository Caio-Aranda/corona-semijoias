// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { FichaDoAcertoAgora, LinhaDaMaleta, Maleta, ResumoDoAcerto } from '../shared/dados';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';

/**
 * Tarefa 8.10: o vendedor pede o acerto e conta as sobras no Corona Vendas, só com internet (rotas da 7.4). A tela só manda
 * ids, a peça, a quantidade (1 ou -1) e o estado; mostra o que o servidor devolve; sem internet diz o que fazer, sem falar
 * de fila, envio nem pendência; só a dona fecha e desiste. Escrito antes da tela.
 */
const ANEL = '11111111-1111-4111-8111-111111111111';
const MALETA = '33333333-3333-4333-8333-333333333333';
const ACERTO = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const produto = (id: string, nome: string, codigo: string) => ({
  id,
  nome,
  codigo,
  fotoHash: null,
  categoria: null,
  codigoFornecedor: null,
});
const LINHA_ANEL: LinhaDaMaleta = {
  produto: produto(ANEL, 'Anel de prata', 'A-1'),
  enviada: 3,
  vendida: 0,
  devolvida: 0,
  saldo: 3,
  precoUnitario: '50.00',
  valorEmAberto: '0.00',
};
const maleta: Maleta = {
  id: MALETA,
  numero: 'S12-V7-1',
  semanaNumero: 12,
  tipo: 'NOVA',
  status: 'CONFERIDA',
  enviadaEm: '2026-11-10T12:00:00Z',
  pecasEmAberto: 3,
  valorEmAberto: '150.00',
  itens: [LINHA_ANEL],
};

const ficha = (extra: Partial<FichaDoAcertoAgora> = {}): FichaDoAcertoAgora => ({
  id: ACERTO,
  semanaNumero: 46,
  status: 'EM_ACERTO',
  pedidoPeloVendedor: true,
  maletas: [{ id: MALETA, numero: 'S12-V7-1', tipo: 'NOVA', status: 'EM_ACERTO' }],
  contagem: [],
  ...extra,
});
const contagem = (
  quantidade: number,
  estadoPeca: 'BOA' | 'DEFEITO' = 'BOA',
): FichaDoAcertoAgora['contagem'] => [
  { produto: produto(ANEL, 'Anel de prata', 'A-1'), estadoPeca, quantidade },
];
const resumo = (extra: Partial<ResumoDoAcerto> = {}): ResumoDoAcerto => ({
  id: ACERTO,
  semanaNumero: 46,
  situacao: 'EM_ACERTO',
  pedidoPeloVendedor: true,
  iniciadoEm: '2026-11-09T12:00:00Z',
  fechadoEm: null,
  ...extra,
});

type Manipulador = (pedido: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

function ponteDe(manipuladores: Partial<Record<PedidoDeChamada['operacao'], Manipulador>> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'maletas.listar': () => ({ ok: true, dados: [maleta] }),
    'cotacao.hoje': () => ({
      ok: true,
      dados: {
        disponivel: false,
        dataDeHoje: '2026-11-12',
        dataDaCotacao: null,
        dolar: null,
        guarani: null,
        deDiaAnterior: false,
      },
    }),
    'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
    'comissao.previa': () => ({ ok: false, motivo: 'sem-conexao' }),
    'acertos.listar': () => ({ ok: true, dados: [] }),
  };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Ana' } }),
    ),
    chamarApi: vi.fn((pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = manipuladores[pedido.operacao] ?? padrao[pedido.operacao];
      return Promise.resolve(m ? m(pedido) : { ok: false as const, motivo: 'erro' as const });
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
    aoMudarASincronizacao: () => () => undefined,
  };
  return { ponte, daOperacao: (op: string) => chamadas.filter((c) => c.operacao === op) };
}

type Usuario = ReturnType<typeof userEvent.setup>;
type Leitura = Extract<PedidoDeChamada, { operacao: 'acertos.ler' }>;

async function abrirMeuAcerto(ponte: PonteDoCorona) {
  const u = userEvent.setup();
  render(<App ponte={ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Meus números' }));
  await u.click(await screen.findByRole('button', { name: 'Meu acerto e minha comissão' }));
  return u;
}

async function pedir(u: Usuario) {
  await u.click(await screen.findByRole('button', { name: 'Pedir acerto' }));
  await u.click(await screen.findByRole('button', { name: 'Sim, pedir o acerto' }));
}

const SEM_FILA = /fila|sincroniz|pendente|aguardando/i;
const SEM_INTERNET_NA_CONTAGEM =
  'Para contar as sobras é preciso internet. Conecte-se e leia o código de novo.';

describe('Pedir o acerto', () => {
  it('pede com um UUID gerado na tela, depois de confirmar, e já abre a contagem', async () => {
    const { ponte, daOperacao } = ponteDe({
      'acertos.pedir': () => ({ ok: true, dados: ficha() }),
      'acertos.agora': () => ({ ok: true, dados: ficha() }),
    });
    const u = await abrirMeuAcerto(ponte);
    await u.click(await screen.findByRole('button', { name: 'Pedir acerto' }));
    // Antes de confirmar, nada foi pedido.
    expect(daOperacao('acertos.pedir')).toHaveLength(0);
    await u.click(await screen.findByRole('button', { name: 'Sim, pedir o acerto' }));
    expect(await screen.findByRole('heading', { name: /Contar as sobras/ })).toBeInTheDocument();
    const pedidos = daOperacao('acertos.pedir');
    expect(pedidos).toHaveLength(1);
    expect((pedidos[0] as { id: string }).id).toMatch(UUID);
  });

  it('sem internet diz o que fazer, e a nova tentativa reaproveita o mesmo UUID', async () => {
    let tentativa = 0;
    const { ponte, daOperacao } = ponteDe({
      'acertos.pedir': () =>
        ++tentativa === 1 ? { ok: false, motivo: 'sem-conexao' } : { ok: true, dados: ficha() },
      'acertos.agora': () => ({ ok: true, dados: ficha() }),
    });
    const u = await abrirMeuAcerto(ponte);
    await pedir(u);
    expect(
      await screen.findByText(
        'Para pedir o acerto é preciso internet. Conecte-se e tente de novo.',
      ),
    ).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toMatch(SEM_FILA);
    await pedir(u);
    await screen.findByRole('heading', { name: /Contar as sobras/ });
    const [a, b] = daOperacao('acertos.pedir') as { id: string }[];
    expect(a?.id).toMatch(UUID);
    expect(b?.id).toBe(a?.id);
  });

  it('mostra em português o motivo que o servidor deu para recusar', async () => {
    const { ponte } = ponteDe({
      'acertos.pedir': () => ({
        ok: false,
        motivo: 'erro',
        mensagem: 'Você tem vendas para enviar. Conecte este computador e tente de novo.',
      }),
    });
    const u = await abrirMeuAcerto(ponte);
    await pedir(u);
    expect(
      await screen.findByText(
        'Você tem vendas para enviar. Conecte este computador e tente de novo.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pedir acerto' })).toBeInTheDocument();
  });

  it('com acerto em andamento não oferece pedir de novo: oferece contar as sobras', async () => {
    const { ponte } = ponteDe({
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
      'acertos.agora': () => ({ ok: true, dados: ficha() }),
    });
    const u = await abrirMeuAcerto(ponte);
    await screen.findByText('Em acerto');
    expect(screen.queryByRole('button', { name: 'Pedir acerto' })).toBeNull();
    await u.click(screen.getByRole('button', { name: 'Contar as sobras' }));
    expect(await screen.findByRole('heading', { name: /Contar as sobras/ })).toBeInTheDocument();
  });

  it('acerto já fechado não impede pedir o próximo', async () => {
    const { ponte } = ponteDe({
      'acertos.listar': () => ({
        ok: true,
        dados: [resumo({ situacao: 'ACERTADO', fechadoEm: '2026-11-10T15:00:00Z' })],
      }),
    });
    await abrirMeuAcerto(ponte);
    expect(await screen.findByRole('button', { name: 'Pedir acerto' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Contar as sobras' })).toBeNull();
  });
});

describe('Contar as sobras', () => {
  async function abrirContagem(
    manipuladores: Parameters<typeof ponteDe>[0],
    fichaInicial: FichaDoAcertoAgora = ficha(),
  ) {
    const r = ponteDe({
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
      'acertos.agora': () => ({ ok: true, dados: fichaInicial }),
      ...manipuladores,
    });
    const u = await abrirMeuAcerto(r.ponte);
    await u.click(await screen.findByRole('button', { name: 'Contar as sobras' }));
    await screen.findByRole('heading', { name: /Contar as sobras/ });
    return { ...r, u };
  }

  it('ler o código manda uma leitura de 1 peça boa com UUID e mostra a contagem do servidor', async () => {
    const { u, daOperacao } = await abrirContagem({
      'acertos.ler': () => ({ ok: true, dados: ficha({ contagem: contagem(1) }) }),
    });
    await u.type(screen.getByLabelText('Ler o código da peça'), 'A-1{Enter}');
    const tabela = await screen.findByRole('table', { name: 'Peças contadas' });
    expect(within(tabela).getByText('Anel de prata')).toBeInTheDocument();
    expect(within(tabela).getByText('Boa')).toBeInTheDocument();
    const [leitura] = daOperacao('acertos.ler') as Leitura[];
    expect(leitura?.id).toBe(ACERTO);
    expect(leitura?.leitura).toMatchObject({ produtoId: ANEL, quantidade: 1, estadoPeca: 'BOA' });
    expect(leitura?.leitura.id).toMatch(UUID);
  });

  it('escolhendo "Com defeito", a leitura vai como defeito', async () => {
    const { u, daOperacao } = await abrirContagem({
      'acertos.ler': () => ({ ok: true, dados: ficha({ contagem: contagem(1, 'DEFEITO') }) }),
    });
    await u.click(screen.getByRole('radio', { name: 'Com defeito' }));
    await u.type(screen.getByLabelText('Ler o código da peça'), 'A-1{Enter}');
    await screen.findByRole('table', { name: 'Peças contadas' });
    expect(daOperacao('acertos.ler')[0]).toMatchObject({
      leitura: { estadoPeca: 'DEFEITO', quantidade: 1 },
    });
  });

  it('cada leitura tem UUID novo; a mesma leitura que falhou reaproveita o UUID', async () => {
    let n = 0;
    const { u, daOperacao } = await abrirContagem({
      'acertos.ler': () =>
        ++n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: ficha({ contagem: contagem(n - 1) }) },
    });
    const campo = screen.getByLabelText('Ler o código da peça');
    await u.type(campo, 'A-1{Enter}');
    expect(await screen.findByText(SEM_INTERNET_NA_CONTAGEM)).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toMatch(SEM_FILA);
    await u.type(campo, 'A-1{Enter}');
    await screen.findByRole('table', { name: 'Peças contadas' });
    await u.type(campo, 'A-1{Enter}');
    await vi.waitFor(() => expect(daOperacao('acertos.ler')).toHaveLength(3));
    const ids = (daOperacao('acertos.ler') as Leitura[]).map((c) => c.leitura.id);
    expect(ids[1]).toBe(ids[0]);
    expect(ids[2]).not.toBe(ids[1]);
  });

  it('desfazer uma leitura manda -1 da mesma peça e estado', async () => {
    const { u, daOperacao } = await abrirContagem(
      { 'acertos.ler': () => ({ ok: true, dados: ficha({ contagem: contagem(1) }) }) },
      ficha({ contagem: contagem(2) }),
    );
    await u.click(
      await screen.findByRole('button', { name: 'Desfazer uma leitura de Anel de prata' }),
    );
    await vi.waitFor(() => expect(daOperacao('acertos.ler')).toHaveLength(1));
    const [leitura] = daOperacao('acertos.ler') as Leitura[];
    expect(leitura?.leitura).toMatchObject({ produtoId: ANEL, quantidade: -1, estadoPeca: 'BOA' });
    expect(leitura?.leitura.id).toMatch(UUID);
  });

  it('peça que não é de nenhuma maleta do acerto: diz em português e não grava leitura', async () => {
    const { u, daOperacao } = await abrirContagem({
      'maletas.peca': () => ({
        ok: false,
        motivo: 'erro',
        mensagem: 'Esta peça não consta na sua maleta.',
      }),
    });
    await u.type(screen.getByLabelText('Ler o código da peça'), 'X-9{Enter}');
    expect(await screen.findByText('Esta peça não consta na sua maleta.')).toBeInTheDocument();
    expect(daOperacao('acertos.ler')).toHaveLength(0);
  });

  it('erro do servidor na leitura (por exemplo, acerto fechado) aparece como veio', async () => {
    const { u } = await abrirContagem({
      'acertos.ler': () => ({
        ok: false,
        motivo: 'erro',
        mensagem: 'Este acerto não está mais em andamento.',
      }),
    });
    await u.type(screen.getByLabelText('Ler o código da peça'), 'A-1{Enter}');
    expect(await screen.findByText('Este acerto não está mais em andamento.')).toBeInTheDocument();
  });

  it('sem internet ao abrir a contagem diz o que fazer', async () => {
    const r = ponteDe({
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
      'acertos.agora': () => ({ ok: false, motivo: 'sem-conexao' }),
    });
    const u = await abrirMeuAcerto(r.ponte);
    await u.click(await screen.findByRole('button', { name: 'Contar as sobras' }));
    expect(await screen.findByText(SEM_INTERNET_NA_CONTAGEM)).toBeInTheDocument();
    expect(document.body.textContent ?? '').not.toMatch(SEM_FILA);
  });

  it('acerto que a dona já fechou ou desistiu não deixa ler, e o vendedor nunca fecha nem desiste', async () => {
    await abrirContagem({}, ficha({ status: 'ACERTADO' }));
    expect(
      screen.getByText('Este acerto não está mais em andamento. A dona cuida do resto.'),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Ler o código da peça')).toBeNull();
    expect(screen.queryByRole('button', { name: /fechar|desistir/i })).toBeNull();
  });

  it('em andamento, a tela não tem botão de fechar nem de desistir, nem custo, comissão ou total', async () => {
    await abrirContagem({});
    expect(screen.queryByRole('button', { name: /fechar|desistir/i })).toBeNull();
    expect(document.body.textContent ?? '').not.toMatch(/custo|comiss[ãa]o|total|bipar|bipe/i);
  });
});

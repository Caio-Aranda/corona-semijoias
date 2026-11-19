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
import { esquecerImagens } from '../../componentes/ImagemDoProduto';

const entregues: { nome: string; tipo: string; bytes: Uint8Array }[] = [];
vi.mock('../../nucleo/arquivos', async (original) => ({
  ...(await original<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: (nome: string, tipo: string, bytes: Uint8Array) => {
    entregues.push({ nome, tipo, bytes });
  },
}));

const NOTA = {
  loja: 'Corona Semijoias',
  codigo: '0007',
  numero: 'S12-V7-1',
  vendedor: { id: '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11', codigo: 7, nome: 'Joana Representante' },
  praca: 'Centro',
  semana: { numero: 12 },
  tipo: 'NOVA',
  enviadaEm: '2026-11-10T12:00:00Z',
  cancelada: false,
  itens: [
    {
      codigo: '0012',
      nome: 'Anel Solitário',
      quantidade: 3,
      precoRemessa: '70.00',
      subtotal: '210.00',
    },
  ],
  totalPecas: 3,
  totalValor: '210.00',
  cotacao: {
    data: '2026-11-10',
    dolar: '5.5',
    guarani: '0.0009',
    totalEmDolar: '38.18',
    totalEmGuarani: '233000.00',
  },
};

const ID_VEND = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_SEMANA = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const ID_PRACA = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_ANEL = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_BRINCO = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_MALETA = '5f6e28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';
const ID_MOV = '6a7e28ba-2fa1-41d3-b9a5-2c3a9a8f6b77';
const HASH = 'a'.repeat(64);

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const produto = (id: string, nome: string, codigo: string, extra: object = {}) => ({
  id,
  version: 1,
  nome,
  referencia: 'R',
  categoriaId: ID_PRACA,
  precoVenda: 70,
  precoCusto: 10,
  estoqueMinimo: 0,
  estoqueAtual: 5,
  ativo: true,
  fotoPrincipal: { hash: HASH },
  codigos: [{ id, codigo, tipo: 'LOJA', principal: true, ativo: true }],
  ...extra,
});

const PRODUTOS: Record<string, unknown> = {
  '0012': produto(ID_ANEL, 'Anel Solitário', '0012'),
  'b-7': produto(ID_BRINCO, 'Brinco de Argola', 'B-7'),
};

const maletaEnviada = (status = 'ENVIADA') => ({
  id: ID_MALETA,
  codigo: '0007',
  version: 3,
  vendedorId: ID_VEND,
  vendedorNome: 'Joana Representante',
  semanaId: ID_SEMANA,
  semanaNumero: 12,
  pracaId: ID_PRACA,
  pracaNome: 'Centro',
  tipo: 'NOVA',
  status,
  totalPecas: 3,
  totalValor: 210,
  criadaEm: '2026-11-09T12:00:00Z',
  enviadaEm: '2026-11-10T13:00:00Z',
  itens: [],
  avisos: [],
});

const saldoDaMaleta = (devolvida = 0, comMovimento = false) => ({
  id: ID_MALETA,
  status: 'ENVIADA',
  vendedorId: ID_VEND,
  pecasEmAberto: 3 - devolvida,
  valorEmAberto: 70 * (3 - devolvida),
  itens: [
    {
      produto: {
        id: ID_ANEL,
        nome: 'Anel Solitário',
        codigo: '0012',
        fotoPrincipal: { hash: HASH },
      },
      enviada: 3,
      devolvida,
      saldo: 3 - devolvida,
      precoUnitario: 70,
      valorEmAberto: 70 * (3 - devolvida),
    },
  ],
  movimentos: comMovimento
    ? [
        {
          id: ID_MOV,
          tipo: 'DEVOLUCAO_DONA',
          produtoId: ID_ANEL,
          produtoNome: 'Anel Solitário',
          quantidade: -devolvida,
          desfeito: false,
          em: '2026-11-11T10:00:00Z',
        },
      ]
    : [],
});

function montar(extras: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () =>
      pagina([
        { id: ID_VEND, nome: 'Joana Representante', codigo: 7, pracaId: ID_PRACA, ativo: true },
      ]),
    'semanas.listar': () => pagina([{ id: ID_SEMANA, numero: 12, situacao: 'ABERTA' }]),
    'produtos.listar': () => pagina([]),
    'produtos.porCodigo': (p) => {
      const achado = PRODUTOS[(p.parametros as { codigo: string }).codigo.toLowerCase()];
      return achado
        ? { ok: true, dados: achado }
        : { ok: false, motivo: 'erro', status: 404, mensagem: 'Não achou.' };
    },
    'produtos.fotos.imagem': () => ({
      ok: true,
      dados: { nome: 'x.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
    }),
    'maletas.listar': () => pagina([maletaEnviada()]),
    'maletas.ver': () => ({ ok: true, dados: maletaEnviada() }),
    'maletas.saldo': () => ({ ok: true, dados: saldoDaMaleta() }),
    'maletas.emAberto': () => ({
      ok: true,
      dados: {
        vendedorId: ID_VEND,
        vendedorNome: 'Joana Representante',
        limite: 500,
        valorEmAberto: 210,
        limiteRestante: 290,
        maletas: [
          {
            id: ID_MALETA,
            semanaNumero: 12,
            tipo: 'NOVA',
            status: 'ENVIADA',
            pecasEmAberto: 3,
            valorEmAberto: 210,
          },
        ],
      },
    }),
    ...extras,
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
      const m = padrao[pedido.operacao];
      return m ? m(pedido) : { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

type Montagem = ReturnType<typeof montar>;
const chamadasDe = (m: Montagem, op: string) => m.chamadas.filter((c) => c.operacao === op);
const ultima = (m: Montagem, op: string) => chamadasDe(m, op).at(-1)?.parametros;

async function abrirLista(m: Montagem) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  await u.click(within(nav).getAllByRole('button', { name: 'Maletas' })[0]!);
  await screen.findByRole('heading', { name: 'Maletas', level: 1 });
  return u;
}

async function abrirFicha(m: Montagem) {
  const u = await abrirLista(m);
  await u.click(
    await screen.findByRole('button', { name: 'Abrir a maleta de Joana Representante, semana 12' }),
  );
  await screen.findByRole('heading', { name: 'Maleta enviada' });
  return u;
}

beforeEach(() => {
  entregues.length = 0;
  window.location.hash = '';
  esquecerImagens();
  URL.createObjectURL = vi.fn(() => 'blob:foto');
  URL.revokeObjectURL = vi.fn();
});

describe('Lista de maletas', () => {
  it('mostra as maletas e filtra por situação', async () => {
    const m = montar();
    const u = await abrirLista(m);
    const linha = (await screen.findByText('Joana Representante', { selector: 'td' })).closest(
      'tr',
    )!;
    expect(within(linha).getByText('Enviada')).toBeTruthy();
    expect(within(linha).getByText('Maleta nova')).toBeTruthy();
    // O código da maleta (0007), gerado pelo servidor, aparece na lista.
    expect(within(linha).getByText('0007')).toBeTruthy();
    await u.selectOptions(screen.getByLabelText('Situação'), 'Cancelada');
    await waitFor(() => expect(ultima(m, 'maletas.listar')).toMatchObject({ status: 'CANCELADA' }));
  });

  it('visão por representante mostra o que está em aberto e quanto sobra do limite', async () => {
    const m = montar();
    const u = await abrirLista(m);
    await u.click(screen.getByRole('button', { name: 'Por representante' }));
    const linha = (await screen.findByText('Joana Representante', { selector: 'td' })).closest(
      'tr',
    )!;
    expect(within(linha).getByText(/210,00/)).toBeTruthy();
    expect(within(linha).getByText(/290,00/)).toBeTruthy();
    expect(ultima(m, 'maletas.emAberto')).toEqual({ vendedorId: ID_VEND });
  });
});

describe('Ficha da maleta', () => {
  it('mostra enviada, devolvida e saldo de cada peça e os movimentos', async () => {
    const m = montar({ 'maletas.saldo': () => ({ ok: true, dados: saldoDaMaleta(1, true) }) });
    await abrirFicha(m);
    const linha = (await screen.findAllByText('Anel Solitário', { selector: 'td' }))[0]!.closest(
      'tr',
    )!;
    const celulas = within(linha)
      .getAllByRole('cell')
      .map((c) => c.textContent);
    expect(celulas).toEqual(expect.arrayContaining(['3', '1', '2']));
    expect(screen.getByText('Devolvida à dona')).toBeTruthy();
  });
});

describe('Maleta já conferida pelo representante (Fase 4)', () => {
  it('continua com Devolver e Repor, não oferece Cancelar e mostra as vendas nos movimentos', async () => {
    const m = montar({
      'maletas.ver': () => ({ ok: true, dados: maletaEnviada('CONFERIDA') }),
      'maletas.conferencia': () => ({
        ok: true,
        dados: {
          id: '88888888-8888-4888-8888-888888888888',
          remessaId: ID_MALETA,
          conferidaEm: '2026-11-11T14:00:00Z',
          divergencias: 1,
          itens: [
            {
              produtoId: ID_ANEL,
              produtoNome: 'Anel Solitário',
              esperado: 3,
              contado: 2,
              diferenca: -1,
            },
          ],
        },
      }),
      'maletas.saldo': () => ({
        ok: true,
        dados: {
          ...saldoDaMaleta(0, false),
          status: 'CONFERIDA',
          movimentos: [
            {
              id: ID_MOV,
              tipo: 'VENDA',
              produtoId: ID_ANEL,
              produtoNome: 'Anel Solitário',
              quantidade: -1,
              desfeito: false,
              em: '2026-11-11T10:00:00Z',
            },
            {
              id: '99999999-9999-4999-8999-999999999999',
              tipo: 'ESTORNO_VENDA',
              produtoId: ID_ANEL,
              produtoNome: 'Anel Solitário',
              quantidade: 1,
              estornaId: ID_MOV,
              desfeito: false,
              em: '2026-11-11T11:00:00Z',
            },
          ],
        },
      }),
    });
    const u = await abrirLista(m);
    await u.click(
      await screen.findByRole('button', {
        name: 'Abrir a maleta de Joana Representante, semana 12',
      }),
    );
    expect(await screen.findByRole('button', { name: 'Devolver peças à dona' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Repor esta maleta' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Cancelar maleta' })).toBeNull();
    expect(screen.getByText('Vendida pelo representante')).toBeTruthy();
    expect(screen.getByText('Venda desfeita (estorno)')).toBeTruthy();
    expect(screen.getByText('Conferida', { selector: 'dd' })).toBeTruthy();
    expect(screen.getByText('Conferência do representante')).toBeTruthy();
    expect(screen.getByText(/Há diferença em 1 peça/)).toBeTruthy();
    const tabela = screen.getByRole('table', { name: 'O que o representante contou' });
    expect(within(tabela).getByText('-1')).toBeTruthy();
  });
});

describe('Maleta com tudo devolvido', () => {
  it('continua enviada: a ficha avisa que não há peças com o representante', async () => {
    const m = montar({ 'maletas.saldo': () => ({ ok: true, dados: saldoDaMaleta(3, true) }) });
    await abrirFicha(m);
    expect(await screen.findByText('Sem peças com o representante')).toBeTruthy();
    expect(screen.getByText('Enviada', { selector: 'dd' })).toBeTruthy();
  });

  it('a lista Por representante não conta a maleta de saldo zero', async () => {
    const m = montar({
      'maletas.emAberto': () => ({
        ok: true,
        dados: {
          vendedorId: ID_VEND,
          vendedorNome: 'Joana Representante',
          limite: 500,
          valorEmAberto: 0,
          limiteRestante: 500,
          maletas: [
            {
              id: ID_MALETA,
              semanaNumero: 12,
              tipo: 'NOVA',
              status: 'ENVIADA',
              pecasEmAberto: 0,
              valorEmAberto: 0,
            },
          ],
        },
      }),
    });
    const u = await abrirLista(m);
    await u.click(screen.getByRole('button', { name: 'Por representante' }));
    const linha = (await screen.findByText('Joana Representante', { selector: 'td' })).closest(
      'tr',
    )!;
    const celulas = within(linha)
      .getAllByRole('cell')
      .map((c) => c.textContent);
    expect(celulas[1]).toBe('0');
  });
});

describe('Devolução à dona', () => {
  async function ateALeitura(m: Montagem) {
    const u = await abrirFicha(m);
    await u.click(await screen.findByRole('button', { name: 'Devolver peças à dona' }));
    await screen.findByLabelText('Código da peça ou nome');
    return u;
  }
  const ler = (u: ReturnType<typeof userEvent.setup>, codigo: string) =>
    u.type(screen.getByLabelText('Código da peça ou nome'), `${codigo}{Enter}`);

  it('lê a peça, soma 1, confirma e manda o id de cada linha gerado na tela', async () => {
    const m = montar({
      'maletas.devolver': () => ({ ok: true, dados: saldoDaMaleta(2, true), criado: true }),
    });
    const u = await ateALeitura(m);
    await ler(u, '0012');
    await ler(u, '0012');
    expect(
      await screen.findByLabelText('Quantidade de Anel Solitário que está voltando'),
    ).toHaveProperty('value', '2');
    await u.click(screen.getByRole('button', { name: 'Devolver as peças' }));
    const dialogo = await screen.findByRole('dialog');
    await u.click(within(dialogo).getByRole('button', { name: 'Devolver as peças' }));
    expect(await screen.findByText(/Peças devolvidas/)).toBeTruthy();
    const p = ultima(m, 'maletas.devolver') as { id: string; itens: Record<string, unknown>[] };
    expect(p.id).toBe(ID_MALETA);
    expect(p.itens).toHaveLength(1);
    expect(p.itens[0]).toMatchObject({ produtoId: ID_ANEL, quantidade: 2 });
    expect(p.itens[0]?.id).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('nunca deixa passar do saldo e recusa peça que não está na maleta', async () => {
    const m = montar();
    const u = await ateALeitura(m);
    for (let i = 0; i < 4; i += 1) await ler(u, '0012');
    expect(await screen.findByText(/só tem 3 peças de Anel Solitário/)).toBeTruthy();
    expect(screen.getByLabelText('Quantidade de Anel Solitário que está voltando')).toHaveProperty(
      'value',
      '3',
    );
    await ler(u, 'B-7');
    expect(await screen.findByText(/Brinco de Argola não está nesta maleta/)).toBeTruthy();
    const campo = screen.getByLabelText('Quantidade de Anel Solitário que está voltando');
    await u.clear(campo);
    await u.type(campo, '9');
    expect(await screen.findByText('Digite uma quantidade de 1 até 3.')).toBeTruthy();
    expect(chamadasDe(m, 'maletas.devolver')).toHaveLength(0);
  });

  it('avisa quando o servidor já tinha registrado a devolução (200)', async () => {
    const m = montar({
      'maletas.devolver': () => ({ ok: true, dados: saldoDaMaleta(1, true), criado: false }),
    });
    const u = await ateALeitura(m);
    await ler(u, '0012');
    await u.click(await screen.findByRole('button', { name: 'Devolver as peças' }));
    const dialogo = await screen.findByRole('dialog');
    await u.click(within(dialogo).getByRole('button', { name: 'Devolver as peças' }));
    expect(await screen.findByText('Esta devolução já estava registrada')).toBeTruthy();
  });

  it('desfaz uma devolução com motivo e mostra a recusa do servidor', async () => {
    const m = montar({
      'maletas.saldo': () => ({ ok: true, dados: saldoDaMaleta(1, true) }),
      'maletas.desfazerDevolucao': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'As peças já saíram do estoque da loja. Não dá para desfazer.',
      }),
    });
    const u = await abrirFicha(m);
    await u.click(
      await screen.findByRole('button', { name: 'Desfazer a devolução de Anel Solitário' }),
    );
    const dialogo = await screen.findByRole('dialog');
    await u.click(within(dialogo).getByRole('button', { name: 'Desfazer devolução' }));
    expect(
      await within(dialogo).findByText('Conte o motivo para desfazer a devolução.'),
    ).toBeTruthy();
    expect(chamadasDe(m, 'maletas.desfazerDevolucao')).toHaveLength(0);
    await u.type(within(dialogo).getByLabelText('Por que está desfazendo?'), 'Foi engano');
    await u.click(within(dialogo).getByRole('button', { name: 'Desfazer devolução' }));
    expect(await within(dialogo).findByText(/já saíram do estoque/)).toBeTruthy();
    const primeira = ultima(m, 'maletas.desfazerDevolucao') as Record<string, string>;
    expect(primeira).toMatchObject({ id: ID_MALETA, devolucaoId: ID_MOV, motivo: 'Foi engano' });
    // Tentar de novo com o mesmo motivo reenvia o mesmo pedido.
    await u.click(within(dialogo).getByRole('button', { name: 'Desfazer devolução' }));
    await waitFor(() => expect(chamadasDe(m, 'maletas.desfazerDevolucao')).toHaveLength(2));
    expect((ultima(m, 'maletas.desfazerDevolucao') as Record<string, string>).estornoId).toBe(
      primeira.estornoId,
    );
  });
});

describe('Cancelar maleta', () => {
  it('pede o motivo e mostra a recusa com a sugestão de devolver as peças', async () => {
    const m = montar({
      'maletas.cancelar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'Esta maleta já foi movimentada e não pode ser cancelada.',
      }),
    });
    const u = await abrirFicha(m);
    await u.click(await screen.findByRole('button', { name: 'Cancelar maleta' }));
    const dialogo = await screen.findByRole('dialog');
    expect(within(dialogo).getByText(/As peças voltam para o estoque/)).toBeTruthy();
    await u.click(within(dialogo).getByRole('button', { name: 'Cancelar maleta' }));
    expect(await within(dialogo).findByText('Conte o motivo para cancelar a maleta.')).toBeTruthy();
    await u.type(
      within(dialogo).getByLabelText('Por que está cancelando?'),
      'Representante desistiu',
    );
    await u.click(within(dialogo).getByRole('button', { name: 'Cancelar maleta' }));
    expect(await within(dialogo).findByText(/já foi movimentada/)).toBeTruthy();
    expect(
      within(dialogo).getByText(/devolva as peças que ainda estão com o representante/),
    ).toBeTruthy();
    expect(ultima(m, 'maletas.cancelar')).toEqual({
      id: ID_MALETA,
      motivo: 'Representante desistiu',
    });
  });

  it('cancela e mostra a ficha atualizada', async () => {
    let cancelada = false;
    const m = montar({
      'maletas.ver': () => ({
        ok: true,
        dados: maletaEnviada(cancelada ? 'CANCELADA' : 'ENVIADA'),
      }),
      'maletas.cancelar': () => {
        cancelada = true;
        return { ok: true, dados: maletaEnviada('CANCELADA') };
      },
    });
    const u = await abrirFicha(m);
    await u.click(await screen.findByRole('button', { name: 'Cancelar maleta' }));
    const dialogo = await screen.findByRole('dialog');
    await u.type(within(dialogo).getByLabelText('Por que está cancelando?'), 'Engano');
    await u.click(within(dialogo).getByRole('button', { name: 'Cancelar maleta' }));
    expect(await screen.findByText(/Maleta cancelada\. As peças voltaram/)).toBeTruthy();
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Devolver peças à dona' })).toBeNull(),
    );
  });
});

describe('Reposição', () => {
  it('cria uma maleta nova do tipo reposição, do mesmo representante, semana e praça', async () => {
    const m = montar({
      'maletas.criar': () => ({
        ok: true,
        dados: {
          ...maletaEnviada('RASCUNHO'),
          id: ID_ANEL,
          tipo: 'REPOSICAO',
          itens: [],
          enviadaEm: null,
        },
        criado: true,
      }),
    });
    const u = await abrirFicha(m);
    await u.click(await screen.findByRole('button', { name: 'Repor esta maleta' }));
    expect(await screen.findByText('Sobra')).toBeTruthy();
    expect(screen.getByText(/Reposição é sempre uma maleta nova/)).toBeTruthy();
    await u.click(screen.getByRole('button', { name: 'Começar a repor' }));
    await screen.findByLabelText('Código da peça ou nome');
    const p = ultima(m, 'maletas.criar') as Record<string, string>;
    expect(p).toMatchObject({
      vendedorId: ID_VEND,
      semanaId: ID_SEMANA,
      pracaId: ID_PRACA,
      tipo: 'REPOSICAO',
    });
    expect(p.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(p).not.toHaveProperty('itens');
  });

  it('a partir da visão por representante pede a semana', async () => {
    const m = montar();
    const u = await abrirLista(m);
    await u.click(screen.getByRole('button', { name: 'Por representante' }));
    await u.click(
      await screen.findByRole('button', { name: 'Nova reposição para Joana Representante' }),
    );
    await u.click(await screen.findByRole('button', { name: 'Começar a repor' }));
    expect(await screen.findByText('Escolha a semana.')).toBeTruthy();
    expect(chamadasDe(m, 'maletas.criar')).toHaveLength(0);
  });
});

describe('Imprimir nota', () => {
  it('na lista e na ficha da maleta enviada gera o PDF da nota pela ponte de arquivos', async () => {
    const m = montar({ 'maletas.nota': () => ({ ok: true, dados: NOTA }) });
    const u = await abrirLista(m);
    await u.click(
      await screen.findByRole('button', { name: 'Imprimir nota: Joana Representante' }),
    );
    await waitFor(() => expect(entregues).toHaveLength(1));
    expect(ultima(m, 'maletas.nota')).toEqual({ id: ID_MALETA });
    expect(entregues[0]).toMatchObject({
      nome: 'nota-da-maleta-S12-V7-1.pdf',
      tipo: 'application/pdf',
    });
    const pdf = new TextDecoder('latin1').decode(entregues[0]!.bytes);
    expect(pdf).toContain('Anel Solitário');
    // A nota traz o código da maleta, com o número longo entre parênteses.
    expect(pdf).toContain('0007 \\(S12-V7-1\\)');
    expect(
      await screen.findByText(/A nota nota-da-maleta-S12-V7-1.pdf foi preparada/),
    ).toBeTruthy();

    await u.click(
      await screen.findByRole('button', {
        name: 'Abrir a maleta de Joana Representante, semana 12',
      }),
    );
    await screen.findByRole('heading', { name: 'Maleta enviada' });
    await u.click(screen.getByRole('button', { name: 'Imprimir nota: Joana Representante' }));
    await waitFor(() => expect(entregues).toHaveLength(2));
  });

  it('se o servidor recusar (maleta ainda em montagem), mostra o texto dele e não gera arquivo', async () => {
    const m = montar({
      'maletas.nota': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem:
          'A nota só fica pronta depois que a maleta for enviada. Envie a maleta e tente de novo.',
      }),
    });
    const u = await abrirLista(m);
    await u.click(
      await screen.findByRole('button', { name: 'Imprimir nota: Joana Representante' }),
    );
    expect(
      await screen.findByText(/A nota só fica pronta depois que a maleta for enviada/),
    ).toBeTruthy();
    expect(entregues).toHaveLength(0);
  });

  it('rascunho não tem botão de nota', async () => {
    const m = montar({
      'maletas.listar': () =>
        pagina([
          {
            ...maletaEnviada('RASCUNHO'),
            enviadaEm: null,
            itens: [],
            totalPecas: 0,
            totalValor: 0,
          },
        ]),
    });
    await abrirLista(m);
    await screen.findByText('Joana Representante', { selector: 'td' });
    expect(screen.queryByRole('button', { name: /Imprimir nota/ })).toBeNull();
  });
});

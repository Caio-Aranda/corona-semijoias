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

const ID_P = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_OUTRO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const ID_CAT = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_FORN = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_CONTAGEM = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_L1 = 'aaaaaaaa-2fa1-41d3-b9a5-2c3a9a8f6b01';
const ID_L2 = 'aaaaaaaa-2fa1-41d3-b9a5-2c3a9a8f6b02';
const ID_L3 = 'aaaaaaaa-2fa1-41d3-b9a5-2c3a9a8f6b03';
const HASH = 'a'.repeat(64);

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const PECA = {
  id: ID_P,
  nome: 'Anel Solitário',
  referencia: 'R-10',
  codigo: '0012',
  estoqueMinimo: 2,
  ativo: true,
  fotoPrincipal: { hash: HASH },
};
const SEM_FOTO = {
  id: ID_OUTRO,
  nome: 'Brinco de Argola',
  referencia: '',
  codigo: 'B-7',
  estoqueMinimo: 0,
  ativo: true,
  fotoPrincipal: {},
};
/** Como o cadastro devolve a peça (com o saldo e o custo, que só a dona vê). */
const CADASTRO = {
  id: ID_P,
  version: 4,
  nome: 'Anel Solitário',
  referencia: 'R-10',
  categoriaId: ID_CAT,
  fornecedorId: ID_FORN,
  precoVenda: 70,
  precoCusto: 12.5,
  estoqueMinimo: 2,
  estoqueAtual: 2,
  ativo: true,
  codigos: [{ id: ID_P, codigo: '0012', tipo: 'LOJA', principal: true, ativo: true }],
  fotoPrincipal: { hash: HASH },
};

const lancamento = (id: string, extra: Record<string, unknown>) => ({
  id,
  produto: PECA,
  tipo: 'ENTRADA',
  quantidade: 5,
  saldoDepois: 5,
  origem: 'MANUAL',
  motivo: '',
  usuarioNome: 'Maria',
  ocorridaEm: '2026-11-10T15:00:00Z',
  estornaId: null,
  desfeitoPorId: null,
  ...extra,
});
const HISTORICO = [
  lancamento(ID_L1, { desfeitoPorId: ID_L2 }),
  lancamento(ID_L2, { quantidade: -5, saldoDepois: 0, estornaId: ID_L1, motivo: 'Lancei errado' }),
  lancamento(ID_L3, { tipo: 'SAIDA', quantidade: -1, saldoDepois: 2, motivo: 'Venda no balcão' }),
];

const resumo = (extra: Record<string, unknown> = {}) => ({
  totalDePecas: 3,
  lidos: 1,
  naoLidos: 2,
  comDiferenca: 1,
  faltam: 1,
  sobram: 0,
  pecasEsperadas: 5,
  pecasContadas: 4,
  ...extra,
});
const contagem = (extra: Record<string, unknown> = {}) => ({
  id: ID_CONTAGEM,
  escopo: 'TODOS',
  escopoNome: '',
  situacao: 'EM_ANDAMENTO',
  iniciadoEm: '2026-11-10T14:00:00Z',
  resumo: resumo(),
  ajustes: 0,
  ...extra,
});
const item = (extra: Record<string, unknown> = {}) => ({
  produto: PECA,
  esperado: 5,
  contado: 4,
  diferenca: -1,
  saldoAtual: 5,
  ...extra,
});

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'categorias.listar': () => pagina([{ id: ID_CAT, version: 0, nome: 'Anéis' }]),
    'fornecedores.listar': () => pagina([{ id: ID_FORN, version: 0, razaoSocial: 'Ouro Ltda' }]),
    'usuarios.listar': () =>
      pagina([{ id: ID_OUTRO, version: 0, nome: 'Maria', login: 'maria', perfil: 'ADMIN' }]),
    'produtos.listar': () =>
      pagina([
        CADASTRO,
        {
          ...CADASTRO,
          id: ID_OUTRO,
          nome: 'Brinco de Argola',
          estoqueMinimo: 0,
          estoqueAtual: 9,
          fotoPrincipal: null,
          codigos: [
            { id: ID_OUTRO, codigo: 'B-7', tipo: 'FORNECEDOR', principal: true, ativo: true },
          ],
        },
      ]),
    'produtos.ver': () => ({ ok: true, dados: CADASTRO }),
    'produtos.porCodigo': () => ({ ok: true, dados: CADASTRO }),
    'produtos.editar': () => ({ ok: true, dados: CADASTRO }),
    'produtos.estoqueMinimo': () => ({ ok: true, dados: CADASTRO }),
    'produtos.fotos.imagem': () => ({
      ok: true,
      dados: { nome: 'x.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
    }),
    'estoque.ficha': () => ({
      ok: true,
      dados: {
        produto: PECA,
        saldo: 2,
        historico: { itens: HISTORICO, pagina: 0, tamanho: 10, total: 3 },
      },
    }),
    'estoque.lancar': () => ({ ok: true, dados: { id: ID_L3 } }),
    'estoque.ajustar': () => ({ ok: true, dados: { id: ID_L3 } }),
    'estoque.desfazer': () => ({ ok: true, dados: { id: ID_L3 } }),
    'estoque.lancamentos': () => ({
      ok: true,
      dados: { itens: HISTORICO, pagina: 0, tamanho: 20, total: 3 },
    }),
    'estoque.acabando': () => pagina([{ produto: PECA, saldo: 1, minimo: 2, falta: 1 }]),
    'contagens.listar': () => pagina([]),
    'contagens.comecar': () => ({ ok: true, dados: contagem() }),
    'contagens.ver': () => ({ ok: true, dados: contagem() }),
    'contagens.itens': () => pagina([item()]),
    'contagens.ler': () => ({
      ok: true,
      dados: { item: item({ contado: 5, diferenca: 0 }), resumo: resumo() },
    }),
    'contagens.corrigir': () => ({
      ok: true,
      dados: { item: item({ contado: 3, diferenca: -2 }), resumo: resumo() },
    }),
    'contagens.fechar': () => ({
      ok: true,
      dados: contagem({ situacao: 'FECHADO', fechadoEm: '2026-11-10T16:00:00Z', ajustes: 1 }),
    }),
    'contagens.cancelar': () => ({
      ok: true,
      dados: contagem({ situacao: 'CANCELADO', canceladoEm: '2026-11-10T16:00:00Z' }),
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

type Montagem = ReturnType<typeof montar>;
const chamadasDe = (m: Montagem, op: string) => m.chamadas.filter((c) => c.operacao === op);
const ultima = (m: Montagem, op: string) => chamadasDe(m, op).at(-1)?.parametros;

async function menu(nome: string) {
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  return within(nav).getByRole('button', { name: nome });
}

async function ir(m: Montagem, item: string, titulo: string) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await menu(item));
  await screen.findByRole('heading', { name: titulo, level: 1 });
  return u;
}

async function abrirFicha(m: Montagem) {
  const u = await ir(m, 'Estoque', 'Estoque');
  await u.click(await screen.findByRole('button', { name: 'Abrir Anel Solitário' }));
  await screen.findByRole('heading', { name: 'Anel Solitário', level: 1 });
  await screen.findByText('Venda no balcão');
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  esquecerImagens();
  URL.createObjectURL = vi.fn(() => 'blob:foto');
  URL.revokeObjectURL = vi.fn();
});

describe('Estoque: lista de peças', () => {
  it('mostra foto, quantidade, mínimo e destaca o que está acabando; sem foto não trava', async () => {
    const m = montar();
    await ir(m, 'Estoque', 'Estoque');
    const anel = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(anel).getByText('Está acabando')).toBeInTheDocument();
    expect(await within(anel).findByAltText(/Anel Solitário/)).toBeInTheDocument();
    const brinco = screen.getByRole('row', { name: /Brinco de Argola/ });
    expect(within(brinco).getByText('Em dia')).toBeInTheDocument();
    expect(within(brinco).getByText('Sem foto')).toBeInTheDocument();
  });

  it('"Está acabando" separa "acabou de verdade" de "está nas maletas"', async () => {
    const m = montar({
      'estoque.acabando': () =>
        pagina([
          { produto: PECA, saldo: 0, minimo: 2, falta: 2, emMaletas: 0, vendedoresEmMaletas: [] },
          {
            produto: SEM_FOTO,
            saldo: 0,
            minimo: 1,
            falta: 1,
            emMaletas: 3,
            vendedoresEmMaletas: [
              { nome: 'Maria', quantidade: 2 },
              { nome: 'João', quantidade: 1 },
            ],
          },
        ]),
    });
    await ir(m, 'Está acabando', 'Está acabando');
    const acabou = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(acabou).getByText('Acabou')).toBeInTheDocument();
    const emMaleta = screen.getByRole('row', { name: /Brinco de Argola/ });
    expect(within(emMaleta).getByText('0 na loja')).toBeInTheDocument();
    expect(within(emMaleta).getByText('Está em maleta: 3 (Maria, João)')).toBeInTheDocument();
    expect(within(emMaleta).queryByText('Acabou')).toBeNull();
  });

  it('"Só os que estão acabando" usa a lista do aviso; os filtros vão para a API', async () => {
    const m = montar();
    const u = await ir(m, 'Estoque', 'Estoque');
    await screen.findByRole('row', { name: /Anel Solitário/ });
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await waitFor(() =>
      expect(ultima(m, 'produtos.listar')).toMatchObject({ categoriaId: ID_CAT }),
    );
    await u.click(screen.getByLabelText('Só os que estão acabando'));
    await waitFor(() => expect(chamadasDe(m, 'estoque.acabando').length).toBeGreaterThan(0));
    expect(ultima(m, 'estoque.acabando')).toMatchObject({ categoriaId: ID_CAT });
  });

  it('a tela Está acabando mostra quanto falta, com foto', async () => {
    const m = montar();
    await ir(m, 'Está acabando', 'Está acabando');
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(linha).getAllByRole('cell')[5]).toHaveTextContent('1');
    expect(await within(linha).findByAltText(/Anel Solitário/)).toBeInTheDocument();
  });

  it('lista vazia de "está acabando" diz que nenhum produto está acabando', async () => {
    const m = montar({ 'estoque.acabando': () => pagina([]) });
    await ir(m, 'Está acabando', 'Está acabando');
    expect(await screen.findByText('Nenhum produto está acabando.')).toBeInTheDocument();
  });
});

describe('Ficha da peça e histórico', () => {
  it('mostra o histórico em palavras da loja, com sinal e custo só para a dona', async () => {
    const m = montar();
    await abrirFicha(m);
    const entrada = screen
      .getAllByRole('row')
      .find((r) => /Lancei errado/.test(r.textContent ?? ''))!;
    expect(within(entrada).getByText('Entrada')).toBeInTheDocument();
    expect(within(entrada).getByText('-5')).toBeInTheDocument();
    expect(within(entrada).getByText(/Desfaz Entrada \+5 de/)).toBeInTheDocument();
    const saida = screen.getByRole('row', { name: /Venda no balcão/ });
    expect(within(saida).getByText('Saída')).toBeInTheDocument();
    expect(within(saida).getByText('-1')).toBeInTheDocument();
    expect(screen.getByText('Custo de cada peça')).toBeInTheDocument();
    expect(screen.getByText('Está acabando', { selector: '.cs-aviso__texto' })).toBeInTheDocument();
  });

  it('só oferece desfazer no que ainda vale: nem o desfeito nem o desfazer', async () => {
    const m = montar();
    await abrirFicha(m);
    expect(screen.getAllByRole('button', { name: /^Desfazer lançamento:/ })).toHaveLength(1);
    expect(screen.getByRole('button', { name: /Saída -1 de Anel Solitário/ })).toBeInTheDocument();
    expect(screen.getByText('Desfeito')).toBeInTheDocument();
  });

  it('desfazer pede o motivo, explica e manda o pedido com identificador próprio', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: /Saída -1 de Anel Solitário/ }));
    const janela = await screen.findByRole('dialog', { name: 'Desfazer este lançamento?' });
    expect(
      within(janela).getByText(/O lançamento antigo continua no histórico/),
    ).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Desfazer lançamento' }));
    expect(await within(janela).findByText(/Diga o motivo para desfazer/)).toBeInTheDocument();
    expect(chamadasDe(m, 'estoque.desfazer')).toHaveLength(0);
    await u.type(within(janela).getByLabelText('Motivo'), 'Lancei errado');
    await u.click(within(janela).getByRole('button', { name: 'Desfazer lançamento' }));
    await waitFor(() => expect(chamadasDe(m, 'estoque.desfazer')).toHaveLength(1));
    expect(ultima(m, 'estoque.desfazer')).toMatchObject({ id: ID_L3, motivo: 'Lancei errado' });
    expect((ultima(m, 'estoque.desfazer') as { pedidoId: string }).pedidoId).toMatch(
      /^[0-9a-f-]{36}$/,
    );
    await screen.findByText('Pronto. O estoque desta peça foi atualizado.');
  });

  it('desfazer: a recusa do servidor aparece em português e o reenvio usa o mesmo pedido', async () => {
    let tentativa = 0;
    const m = montar({
      'estoque.desfazer': () =>
        ++tentativa === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: { id: ID_L3 } },
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: /Saída -1 de Anel Solitário/ }));
    const janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Motivo'), 'Lancei errado');
    await u.click(within(janela).getByRole('button', { name: 'Desfazer lançamento' }));
    await within(janela).findByText(/Sem conexão/);
    await u.click(within(janela).getByRole('button', { name: 'Desfazer lançamento' }));
    await waitFor(() => expect(chamadasDe(m, 'estoque.desfazer')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'estoque.desfazer').map(
      (c) => (c.parametros as { pedidoId: string }).pedidoId,
    );
    expect(a).toBe(b);
  });
});

describe('Lançamentos: entrada, saída, perda e ajuste', () => {
  it('entrada: quantidade inteira maior que zero e o pedido leva tipo e identificador', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Entrada' }));
    const janela = await screen.findByRole('dialog');
    await u.click(within(janela).getByRole('button', { name: 'Lançar entrada' }));
    expect(await within(janela).findByText(/número inteiro maior que zero/)).toBeInTheDocument();
    expect(chamadasDe(m, 'estoque.lancar')).toHaveLength(0);
    await u.type(within(janela).getByLabelText('Quantidade'), '3');
    await u.click(within(janela).getByRole('button', { name: 'Lançar entrada' }));
    await waitFor(() => expect(chamadasDe(m, 'estoque.lancar')).toHaveLength(1));
    expect(ultima(m, 'estoque.lancar')).toMatchObject({
      produtoId: ID_P,
      tipo: 'ENTRADA',
      quantidade: 3,
    });
    expect((ultima(m, 'estoque.lancar') as { id: string }).id).toMatch(/^[0-9a-f-]{36}$/);
    await screen.findByText('Pronto. O estoque desta peça foi atualizado.');
  });

  it('perda e saída usam o tipo certo', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Perda' }));
    let janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Quantidade'), '1{Enter}');
    await waitFor(() => expect(ultima(m, 'estoque.lancar')).toMatchObject({ tipo: 'AVARIA' }));
    await screen.findByText('Pronto. O estoque desta peça foi atualizado.');
    await u.click(screen.getByRole('button', { name: 'Saída' }));
    janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Quantidade'), '2{Enter}');
    await waitFor(() =>
      expect(ultima(m, 'estoque.lancar')).toMatchObject({ tipo: 'SAIDA', quantidade: 2 }),
    );
  });

  it('clicar duas vezes seguidas envia uma vez só', async () => {
    let solta: (r: ResultadoDeChamada) => void = () => undefined;
    const m = montar({
      'estoque.lancar': () => new Promise<ResultadoDeChamada>((resolve) => (solta = resolve)),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Entrada' }));
    const janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Quantidade'), '3');
    const botao = within(janela).getByRole('button', { name: 'Lançar entrada' });
    await u.dblClick(botao);
    expect(chamadasDe(m, 'estoque.lancar')).toHaveLength(1);
    solta({ ok: true, dados: { id: ID_L3 } });
    await screen.findByText('Pronto. O estoque desta peça foi atualizado.');
    expect(chamadasDe(m, 'estoque.lancar')).toHaveLength(1);
  });

  it('saída sem peças suficientes mostra o texto do servidor; reenviar o mesmo usa o mesmo identificador', async () => {
    const m = montar({
      'estoque.lancar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem:
          'Não há peças suficientes no estoque. Há 2 peça(s) de "Anel Solitário". Confira a quantidade ou receba a compra antes.',
      }),
    });
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Saída' }));
    const janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Quantidade'), '9');
    await u.click(within(janela).getByRole('button', { name: 'Lançar saída' }));
    expect(
      await within(janela).findByText(/Não há peças suficientes no estoque/),
    ).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Lançar saída' }));
    await waitFor(() => expect(chamadasDe(m, 'estoque.lancar')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'estoque.lancar').map((c) => (c.parametros as { id: string }).id);
    expect(a).toBe(b);
    // Mudou a quantidade: é um lançamento novo, com outro identificador.
    await u.clear(within(janela).getByLabelText('Quantidade'));
    await u.type(within(janela).getByLabelText('Quantidade'), '1');
    await u.click(within(janela).getByRole('button', { name: 'Lançar saída' }));
    await waitFor(() => expect(chamadasDe(m, 'estoque.lancar')).toHaveLength(3));
    expect((ultima(m, 'estoque.lancar') as { id: string }).id).not.toBe(a);
  });

  it('ajuste "contei": pede o motivo e manda a nova quantidade (zero vale)', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Ajustar o estoque' }));
    const janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Quantas peças você contou?'), '0');
    await u.click(within(janela).getByRole('button', { name: 'Ajustar o estoque' }));
    expect(await within(janela).findByText(/Diga o motivo do ajuste/)).toBeInTheDocument();
    expect(chamadasDe(m, 'estoque.ajustar')).toHaveLength(0);
    await u.type(within(janela).getByLabelText('Motivo do ajuste'), 'Contei tudo');
    await u.click(within(janela).getByRole('button', { name: 'Ajustar o estoque' }));
    await waitFor(() =>
      expect(ultima(m, 'estoque.ajustar')).toMatchObject({
        novaQuantidade: 0,
        motivo: 'Contei tudo',
      }),
    );
  });

  it('ajuste por diferença: recusa o que passaria de zero e manda a diferença com sinal', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Ajustar o estoque' }));
    const janela = await screen.findByRole('dialog');
    await u.click(within(janela).getByLabelText('Quero dizer a diferença'));
    await u.type(within(janela).getByLabelText(/Diferença \(/), '-9');
    await u.type(within(janela).getByLabelText('Motivo do ajuste'), 'Perdi');
    await u.click(within(janela).getByRole('button', { name: 'Ajustar o estoque' }));
    expect(await within(janela).findByText(/há só 2 no estoque/)).toBeInTheDocument();
    expect(chamadasDe(m, 'estoque.ajustar')).toHaveLength(0);
    await u.clear(within(janela).getByLabelText(/Diferença \(/));
    await u.type(within(janela).getByLabelText(/Diferença \(/), '-1');
    await u.click(within(janela).getByRole('button', { name: 'Ajustar o estoque' }));
    await waitFor(() => expect(ultima(m, 'estoque.ajustar')).toMatchObject({ diferenca: -1 }));
  });
});

describe('Estoque mínimo', () => {
  it('recusa valor negativo e grava o novo mínimo com a versão atual da peça', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    await u.click(screen.getByRole('button', { name: 'Mudar o estoque mínimo' }));
    const janela = await screen.findByRole('dialog');
    const campo = within(janela).getByLabelText('Estoque mínimo');
    await u.clear(campo);
    await u.type(campo, '-1');
    await u.click(within(janela).getByRole('button', { name: 'Salvar' }));
    expect(await within(janela).findByText(/número inteiro/)).toBeInTheDocument();
    expect(chamadasDe(m, 'produtos.estoqueMinimo')).toHaveLength(0);
    await u.clear(campo);
    await u.type(campo, '5');
    await u.click(within(janela).getByRole('button', { name: 'Salvar' }));
    await waitFor(() =>
      expect(ultima(m, 'produtos.estoqueMinimo')).toEqual({
        id: ID_P,
        version: 4,
        estoqueMinimo: 5,
      }),
    );
    // Só o estoque mínimo vai: o cadastro inteiro não é reenviado.
    expect(chamadasDe(m, 'produtos.editar')).toHaveLength(0);
    await screen.findByText('Pronto. O estoque mínimo foi atualizado.');
  });
});

describe('Entradas e saídas (todas as peças)', () => {
  it('lista com foto e filtros de período, tipo e quem lançou; período invertido avisa', async () => {
    const m = montar();
    const u = await ir(m, 'Entradas e saídas', 'Entradas e saídas');
    await screen.findByRole('row', { name: /Venda no balcão/ });
    await u.selectOptions(screen.getByLabelText('Tipo'), 'Perda');
    await waitFor(() => expect(ultima(m, 'estoque.lancamentos')).toMatchObject({ tipo: 'AVARIA' }));
    await u.selectOptions(screen.getByLabelText('Quem lançou'), 'Maria');
    await waitFor(() =>
      expect(ultima(m, 'estoque.lancamentos')).toMatchObject({ usuarioId: ID_OUTRO }),
    );
    await u.type(screen.getByLabelText('De'), '2026-11-10');
    await u.type(screen.getByLabelText('Até'), '2026-11-01');
    expect(
      await screen.findByText(/A data final precisa ser depois da inicial/),
    ).toBeInTheDocument();
  });

  it('filtra por peça pelo código lido e permite tirar o filtro', async () => {
    const m = montar();
    const u = await ir(m, 'Entradas e saídas', 'Entradas e saídas');
    await screen.findByRole('row', { name: /Venda no balcão/ });
    await u.type(screen.getByLabelText('Peça (código)'), '0012{Enter}');
    await waitFor(() =>
      expect(ultima(m, 'estoque.lancamentos')).toMatchObject({ produtoId: ID_P }),
    );
    expect(screen.getByText('Só da peça: Anel Solitário.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Tirar a peça' }));
    await waitFor(() => expect(ultima(m, 'estoque.lancamentos')).not.toHaveProperty('produtoId'));
  });

  it('desfazer também funciona por aqui', async () => {
    const m = montar();
    const u = await ir(m, 'Entradas e saídas', 'Entradas e saídas');
    await u.click(await screen.findByRole('button', { name: /Saída -1 de Anel Solitário/ }));
    const janela = await screen.findByRole('dialog');
    await u.type(within(janela).getByLabelText('Motivo'), 'Erro meu');
    await u.click(within(janela).getByRole('button', { name: 'Desfazer lançamento' }));
    await waitFor(() => expect(chamadasDe(m, 'estoque.desfazer')).toHaveLength(1));
  });
});

describe('Aviso na tela inicial', () => {
  it('"estão acabando" no painel leva à lista', async () => {
    const m = montar({
      'avisos.listar': () => ({
        ok: true,
        dados: {
          avisos: [
            {
              titulo: '2 produtos estão acabando.',
              texto: 'Esses produtos chegaram ao estoque mínimo.',
            },
          ],
        },
      }),
    });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await screen.findByText('2 produtos estão acabando.');
    await u.click(screen.getByRole('button', { name: 'Ver a lista' }));
    await screen.findByRole('heading', { name: 'Está acabando', level: 1 });
  });
});

async function comecarContagem(m: Montagem) {
  const u = await ir(m, 'Contagem do estoque', 'Contagem do estoque');
  await u.click(await screen.findByRole('button', { name: 'Começar a contagem' }));
  await screen.findByRole('heading', { name: 'Contando o estoque', level: 1 });
  return u;
}

describe('Contagem do estoque', () => {
  it('explica em linguagem simples o que acontece ao começar e envia o escopo escolhido', async () => {
    const m = montar();
    const u = await ir(m, 'Contagem do estoque', 'Contagem do estoque');
    expect(await screen.findByText(/o sistema anota quantas peças esperava/)).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('O que você vai contar?'), 'Uma categoria');
    await u.click(screen.getByRole('button', { name: 'Começar a contagem' }));
    expect(await screen.findByText('Escolha o que você vai contar.')).toBeInTheDocument();
    expect(chamadasDe(m, 'contagens.comecar')).toHaveLength(0);
    await u.selectOptions(screen.getByLabelText('Escolha a categoria'), 'Anéis');
    await u.click(screen.getByRole('button', { name: 'Começar a contagem' }));
    await waitFor(() =>
      expect(ultima(m, 'contagens.comecar')).toMatchObject({
        escopo: 'CATEGORIA',
        escopoId: ID_CAT,
      }),
    );
    await screen.findByRole('heading', { name: 'Contando o estoque', level: 1 });
  });

  it('ler o código com Enter soma 1, mostra a foto grande e mantém o foco no campo', async () => {
    const m = montar();
    const u = await comecarContagem(m);
    const campo = screen.getByLabelText('Código da peça');
    expect(campo).toHaveFocus();
    await u.type(campo, '0012{Enter}');
    await waitFor(() => expect(chamadasDe(m, 'contagens.ler')).toHaveLength(1));
    expect(ultima(m, 'contagens.ler')).toMatchObject({ id: ID_CONTAGEM, codigo: '0012' });
    expect((ultima(m, 'contagens.ler') as { leituraId: string }).leituraId).toMatch(
      /^[0-9a-f-]{36}$/,
    );
    const painel = await screen.findByRole('region', { name: 'Última peça lida' });
    expect(await within(painel).findByAltText(/Anel Solitário/)).toBeInTheDocument();
    expect(
      await screen.findByText('Anel Solitário: agora são 5 peças contadas.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Código da peça')).toHaveFocus());
    expect(screen.getByLabelText('Código da peça')).toHaveValue('');
  });

  it('leitura que o servidor já tinha registrado (200) avisa e não diz que somou', async () => {
    const m = montar({
      'contagens.ler': () => ({
        ok: true,
        criado: false,
        dados: { item: item({ contado: 5, diferenca: 0 }), resumo: resumo() },
      }),
    });
    const u = await comecarContagem(m);
    await u.type(screen.getByLabelText('Código da peça'), '0012{Enter}');
    expect(
      await screen.findByText('Esta leitura já estava registrada. Nada foi somado de novo.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/agora são 5 peças contadas/)).toBeNull();
  });

  it('cada leitura atualiza a lista de diferenças na hora', async () => {
    const m = montar();
    const u = await comecarContagem(m);
    await screen.findByRole('row', { name: /Anel Solitário/ });
    const antes = chamadasDe(m, 'contagens.itens').length;
    await u.type(screen.getByLabelText('Código da peça'), '0012{Enter}');
    await waitFor(() => expect(chamadasDe(m, 'contagens.itens').length).toBeGreaterThan(antes));
  });

  it('código que não é da contagem: mostra o texto do servidor e deixa ler outro', async () => {
    const m = montar({
      'contagens.ler': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Esta peça não faz parte desta contagem.',
      }),
    });
    const u = await comecarContagem(m);
    await u.type(screen.getByLabelText('Código da peça'), 'XYZ{Enter}');
    expect(await screen.findByText('Esta peça não faz parte desta contagem.')).toBeInTheDocument();
    expect(screen.getByLabelText('Código da peça')).toBeEnabled();
  });

  it('leitura que falhou por falta de internet é repetida com o mesmo identificador', async () => {
    let n = 0;
    const m = montar({
      'contagens.ler': () =>
        ++n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: { item: item({ contado: 5, diferenca: 0 }), resumo: resumo() } },
    });
    const u = await comecarContagem(m);
    await u.type(screen.getByLabelText('Código da peça'), '0012{Enter}');
    await screen.findByRole('button', { name: 'Tentar de novo' });
    await u.type(screen.getByLabelText('Código da peça'), '{Enter}');
    await waitFor(() => expect(chamadasDe(m, 'contagens.ler')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'contagens.ler').map(
      (c) => (c.parametros as { leituraId: string }).leituraId,
    );
    expect(a).toBe(b);
  });

  it('corrigir um valor define o contado (zero vale) e recusa o que não é número', async () => {
    const m = montar();
    const u = await comecarContagem(m);
    await u.type(screen.getByLabelText('Código da peça'), '0012{Enter}');
    const painel = await screen.findByRole('region', { name: 'Última peça lida' });
    const campo = within(painel).getByLabelText('Quantidade contada');
    await u.clear(campo);
    await u.type(campo, 'abc');
    await u.click(within(painel).getByRole('button', { name: 'Definir a quantidade' }));
    expect(await within(painel).findByText(/número inteiro/)).toBeInTheDocument();
    expect(chamadasDe(m, 'contagens.corrigir')).toHaveLength(0);
    await u.clear(campo);
    await u.type(campo, '3');
    await u.click(within(painel).getByRole('button', { name: 'Definir a quantidade' }));
    await waitFor(() =>
      expect(ultima(m, 'contagens.corrigir')).toMatchObject({
        id: ID_CONTAGEM,
        produtoId: ID_P,
        contado: 3,
      }),
    );
    expect(
      await screen.findByText('Anel Solitário: a quantidade contada agora é 3.'),
    ).toBeInTheDocument();
  });

  it('a lista mostra esperado, contado e diferença com sinal; "ainda não li" filtra os não lidos', async () => {
    const m = montar({
      'contagens.itens': (p) =>
        (p.parametros as { filtro?: string }).filtro === 'NAO_LIDOS'
          ? pagina([item({ produto: SEM_FOTO, contado: null, diferenca: null, esperado: 2 })])
          : pagina([item()]),
    });
    const u = await comecarContagem(m);
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(linha).getByText('-1')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Mostrar'), 'As que ainda não li');
    const naoLida = await screen.findByRole('row', { name: /Brinco de Argola/ });
    expect(within(naoLida).getByText('Não lido')).toBeInTheDocument();
    expect(within(naoLida).getByText('Sem foto')).toBeInTheDocument();
  });

  it('fechamento que deixou peça excluída sem ajuste avisa quais peças', async () => {
    const m = montar({
      'contagens.fechar': () => ({
        ok: true,
        dados: contagem({
          situacao: 'FECHADO',
          fechadoEm: '2026-11-10T16:00:00Z',
          ajustes: 1,
          naoAjustadas: [{ nome: 'Colar Antigo', diferenca: -2 }],
        }),
      }),
    });
    const u = await comecarContagem(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a contagem' }));
    const janela = await screen.findByRole('dialog', { name: 'Fechar a contagem?' });
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    await u.click(await within(janela).findByLabelText('Ficam como estão'));
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    expect(
      await screen.findByText(
        /Sem ajuste, porque a peça foi excluída durante a contagem: Colar Antigo\. A diferença ficou registrada\./,
      ),
    ).toBeInTheDocument();
  });

  it('fechar com peças não lidas: mostra os ajustes e só fecha depois de escolher o que fazer com elas', async () => {
    const m = montar();
    const u = await comecarContagem(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a contagem' }));
    const janela = await screen.findByRole('dialog', { name: 'Fechar a contagem?' });
    expect(
      await within(janela).findByText(/O estoque será ajustado em 1 produto com diferença/),
    ).toBeInTheDocument();
    expect(within(janela).getByText(/2 produtos não foram lidos/)).toBeInTheDocument();
    expect(within(janela).getByRole('row', { name: /Anel Solitário/ })).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    expect(
      await within(janela).findByText('Escolha o que fazer com os produtos que não foram lidos.'),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'contagens.fechar')).toHaveLength(0);
    await u.click(within(janela).getByLabelText('Ficam com zero peças'));
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    await waitFor(() =>
      expect(ultima(m, 'contagens.fechar')).toMatchObject({ id: ID_CONTAGEM, zerarNaoLidos: true }),
    );
    expect(
      await screen.findByText('Contagem fechada. O estoque foi ajustado em 1 produto.'),
    ).toBeInTheDocument();
  });

  it('fechar com as não lidas "como estão" manda zerarNaoLidos falso', async () => {
    const m = montar();
    const u = await comecarContagem(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a contagem' }));
    const janela = await screen.findByRole('dialog', { name: 'Fechar a contagem?' });
    await u.click(await within(janela).findByLabelText('Ficam como estão'));
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    await waitFor(() =>
      expect(ultima(m, 'contagens.fechar')).toMatchObject({ zerarNaoLidos: false }),
    );
  });

  it('fechar sem peças não lidas não pergunta nada', async () => {
    const m = montar({
      'contagens.ver': () => ({
        ok: true,
        dados: contagem({ resumo: resumo({ naoLidos: 0, lidos: 3 }) }),
      }),
    });
    const u = await comecarContagem(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a contagem' }));
    const janela = await screen.findByRole('dialog', { name: 'Fechar a contagem?' });
    await within(janela).findByText(/O estoque será ajustado/);
    expect(within(janela).queryByText(/não foram lidos/)).not.toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    await waitFor(() =>
      expect(ultima(m, 'contagens.fechar')).toMatchObject({ zerarNaoLidos: false }),
    );
  });

  it('recusa do fechamento (peça abaixo de zero) aparece clara e a contagem continua aberta', async () => {
    const m = montar({
      'contagens.ver': () => ({ ok: true, dados: contagem({ resumo: resumo({ naoLidos: 0 }) }) }),
      'contagens.fechar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem:
          'Não é possível fechar esta contagem. De "Anel Solitário" há 1 no estoque agora e a contagem pede tirar 3. Confira a peça, lance a compra que falta ou refaça a contagem dela.',
      }),
    });
    const u = await comecarContagem(m);
    await u.click(screen.getByRole('button', { name: 'Fechar a contagem' }));
    const janela = await screen.findByRole('dialog', { name: 'Fechar a contagem?' });
    await within(janela).findByText(/O estoque será ajustado/);
    await u.click(within(janela).getByRole('button', { name: 'Fechar a contagem' }));
    expect(
      await within(janela).findByText(/Não é possível fechar esta contagem/),
    ).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Continuar contando' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Código da peça')).toBeInTheDocument();
  });

  it('cancelar a contagem tem motivo opcional e não mexe no estoque', async () => {
    const m = montar();
    const u = await comecarContagem(m);
    await u.click(screen.getByRole('button', { name: 'Cancelar a contagem' }));
    const janela = await screen.findByRole('dialog', { name: 'Cancelar esta contagem?' });
    expect(within(janela).getByText(/Nada muda no estoque/)).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Cancelar a contagem' }));
    await waitFor(() => expect(chamadasDe(m, 'contagens.cancelar')).toHaveLength(1));
    expect(await screen.findByText('Contagem cancelada. O estoque não mudou.')).toBeInTheDocument();
  });

  it('a lista traz as contagens anteriores e abre uma fechada só para leitura', async () => {
    const m = montar({
      'contagens.listar': () =>
        pagina([
          {
            id: ID_CONTAGEM,
            escopo: 'TODOS',
            escopoNome: '',
            situacao: 'FECHADO',
            iniciadoEm: '2026-11-01T10:00:00Z',
            fechadoEm: '2026-11-01T11:00:00Z',
            lidos: 3,
            totalDePecas: 3,
          },
        ]),
      'contagens.ver': () => ({
        ok: true,
        dados: contagem({
          situacao: 'FECHADO',
          fechadoEm: '2026-11-01T11:00:00Z',
          fechadoPorNome: 'Maria',
          ajustes: 2,
        }),
      }),
    });
    const u = await ir(m, 'Contagem do estoque', 'Contagem do estoque');
    const linha = await screen.findByRole('row', { name: /Fechada/ });
    expect(within(linha).getByText('3 de 3')).toBeInTheDocument();
    await u.click(within(linha).getByRole('button', { name: 'Ver a contagem' }));
    expect(await screen.findByText(/Contagem fechada em/)).toBeInTheDocument();
    expect(screen.getByText(/2 ajustes no estoque/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Código da peça')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Corrigir' })).not.toBeInTheDocument();
  });

  it('contagem já em andamento: a recusa de começar outra vem em português', async () => {
    const m = montar({
      'contagens.comecar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem:
          'Já existe uma contagem em andamento. Feche ou cancele essa contagem antes de começar outra.',
      }),
    });
    const u = await ir(m, 'Contagem do estoque', 'Contagem do estoque');
    await u.click(await screen.findByRole('button', { name: 'Começar a contagem' }));
    expect(await screen.findByText(/Já existe uma contagem em andamento/)).toBeInTheDocument();
  });
});

describe('Textos das telas de estoque', () => {
  it('nenhuma tela mostra palavra técnica ou do bloco proibido do glossário', async () => {
    const m = montar();
    const u = await abrirFicha(m);
    const telas: string[] = [];
    const ver = () => telas.push(document.body.textContent ?? '');
    ver();
    for (const botao of ['Entrada', 'Ajustar o estoque', 'Mudar o estoque mínimo']) {
      await u.click(screen.getByRole('button', { name: botao }));
      ver();
      await u.click(within(await screen.findByRole('dialog')).getAllByRole('button')[0]!);
    }
    await u.click(screen.getByRole('button', { name: /Saída -1 de Anel Solitário/ }));
    ver();
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Voltar' }),
    );
    await u.click(await menu('Entradas e saídas'));
    await screen.findByRole('heading', { name: 'Entradas e saídas', level: 1 });
    await screen.findByRole('row', { name: /Venda no balcão/ });
    ver();
    await u.click(await menu('Está acabando'));
    await screen.findByRole('heading', { name: 'Está acabando', level: 1 });
    await screen.findByRole('row', { name: /Anel Solitário/ });
    ver();
    await u.click(await menu('Contagem do estoque'));
    await u.click(await screen.findByRole('button', { name: 'Começar a contagem' }));
    await screen.findByRole('heading', { name: 'Contando o estoque', level: 1 });
    await screen.findByRole('row', { name: /Anel Solitário/ });
    ver();
    await u.click(screen.getByRole('button', { name: 'Fechar a contagem' }));
    await screen.findByText(/não foram lidos/);
    ver();
    await u.click(screen.getByRole('button', { name: 'Continuar contando' }));
    await u.click(screen.getByRole('button', { name: 'Cancelar a contagem' }));
    ver();
    const tudo = telas.join('\n').toLowerCase();
    const proibidas = [
      'outbox',
      'sync',
      'sincroniza',
      'payload',
      'uuid',
      'token',
      'refresh',
      'error',
      'failed',
      'invalid',
      'unauthorized',
      'forbidden',
      'timeout',
      'null',
      'undefined',
      'exception',
      'stack trace',
      'movimenta',
      'remessa',
      'bipar',
      'bipe',
      'ptax',
      'estorno',
      'estornar',
      'inventário',
      'inventario',
      'congelar',
      'congela',
      'saldo',
      'divergência',
      'divergencia',
      'discrepância',
      'abaixo do mínimo',
      'alerta',
      'estoque_',
      'avaria',
    ];
    for (const p of proibidas) expect(tudo, `"${p}" não pode aparecer`).not.toContain(p);
    const bloco = telas.join('\n');
    for (const p of [
      'ENTRADA',
      'SAIDA',
      'AVARIA',
      'AJUSTE',
      'EM_ANDAMENTO',
      'FECHADO',
      'CANCELADO',
      'ESTORNO',
      'MANUAL',
      'RASCUNHO',
      'CANCELADA',
      'FECHADA',
    ]) {
      expect(bloco, `"${p}" não pode aparecer`).not.toContain(p);
    }
  });
});

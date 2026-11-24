// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
const ID_COMPRA = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_NOVO = '4e5e28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';
const HASH = 'a'.repeat(64);

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const ANEL = {
  id: ID_P,
  version: 3,
  nome: 'Anel Solitário',
  referencia: 'R-10',
  categoriaId: ID_CAT,
  precoVenda: 70,
  precoCusto: 10,
  estoqueMinimo: 2,
  estoqueAtual: 0,
  ativo: true,
  codigos: [{ id: ID_P, codigo: '0012', tipo: 'LOJA', principal: true, ativo: true }],
  fotoPrincipal: { hash: HASH },
};
const BRINCO = {
  ...ANEL,
  id: ID_OUTRO,
  nome: 'Brinco de Argola',
  precoCusto: null,
  codigos: [{ id: ID_OUTRO, codigo: 'B-7', tipo: 'FORNECEDOR', principal: true, ativo: true }],
  fotoPrincipal: null,
};

const naoAchou: ResultadoDeChamada = {
  ok: false,
  motivo: 'erro',
  status: 404,
  mensagem: 'Não achou.',
};

/** Compra como o servidor devolve (o total vem dele, não da tela). */
const compraGravada = (extra: Record<string, unknown> = {}) => ({
  compra: {
    id: ID_COMPRA,
    fornecedorId: ID_FORN,
    fornecedorNome: 'Ouro Ltda',
    dataCompra: '2026-11-10',
    numero: 'NF-9',
    total: 20,
    situacao: 'REGISTRADA',
    itens: 1,
    pecas: 2,
    registradaEm: '2026-11-10T12:00:00Z',
  },
  observacoes: '',
  usuarioNome: 'Maria',
  itens: [
    {
      id: ID_NOVO,
      produto: {
        id: ID_P,
        nome: 'Anel Solitário',
        referencia: 'R-10',
        codigo: '0012',
        fotoPrincipal: { hash: HASH },
      },
      quantidade: 2,
      custoUnitario: 10,
      total: 20,
    },
  ],
  ...extra,
});

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'categorias.listar': () => pagina([{ id: ID_CAT, version: 0, nome: 'Anéis' }]),
    'fornecedores.listar': () => pagina([{ id: ID_FORN, version: 0, razaoSocial: 'Ouro Ltda' }]),
    'produtos.listar': () => pagina([]),
    'produtos.porCodigo': (p) => {
      const codigo = (p.parametros as { codigo: string }).codigo.toLowerCase();
      if (codigo === '0012') return { ok: true, dados: ANEL };
      if (codigo === 'b-7') return { ok: true, dados: BRINCO };
      return naoAchou;
    },
    'produtos.fotos.imagem': () => ({
      ok: true,
      dados: { nome: 'x.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
    }),
    'compras.receber': () => ({ ok: true, dados: compraGravada() }),
    'compras.listar': () => ({
      ok: true,
      dados: {
        itens: [compraGravada().compra],
        pagina: 0,
        tamanho: 20,
        total: 1,
      },
    }),
    'compras.ver': () => ({ ok: true, dados: compraGravada() }),
    'compras.desfazer': () => ({
      ok: true,
      dados: compraGravada({
        compra: { ...compraGravada().compra, situacao: 'ESTORNADA' },
        motivoDeDesfeita: 'Veio errado',
        desfeitaEm: '2026-11-10T13:00:00Z',
      }),
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

/** Item do menu lateral (a tela inicial também tem atalhos com o mesmo nome). */
async function menu(nome: string) {
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  return within(nav).getAllByRole('button', { name: nome })[0]!;
}

/** A linha da prévia da planilha, pelo número que aparece na primeira coluna. */
function linhaDa(numero: number) {
  const achada = screen
    .getAllByRole('row')
    .find((r) => within(r).queryAllByRole('cell')[0]?.textContent === String(numero));
  if (!achada) throw new Error(`Linha ${numero} não encontrada`);
  return achada;
}

async function escolherFornecedorDaPlanilha(u: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('option', { name: 'Ouro Ltda' });
  await u.selectOptions(
    screen.getByLabelText('Fornecedor', { selector: '#planilha-compra-fornecedor' }),
    'Ouro Ltda',
  );
}

async function abrirReceber(m: Montagem) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await menu('Receber compra'));
  await screen.findByRole('heading', { name: 'Receber compra', level: 1 });
  await u.selectOptions(await screen.findByLabelText('Fornecedor'), 'Ouro Ltda');
  return u;
}

async function ler(u: ReturnType<typeof userEvent.setup>, codigo: string) {
  await u.type(screen.getByLabelText('Código da peça ou nome'), `${codigo}{Enter}`);
}

beforeEach(() => {
  window.location.hash = '';
  esquecerImagens();
  URL.createObjectURL = vi.fn(() => 'blob:foto');
  URL.revokeObjectURL = vi.fn();
});

describe('Receber compra: leitor e totais', () => {
  it('ler o código põe a peça na compra com foto, custo sugerido e total; ler de novo soma 1', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await ler(u, '0012');
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(linha).getByLabelText('Quantidade de Anel Solitário')).toHaveValue('1');
    expect(within(linha).getByLabelText('Custo de cada peça de Anel Solitário')).toHaveValue(
      '10,00',
    );
    expect(within(linha).getByText('R$ 10,00')).toBeInTheDocument();
    // A foto da peça aparece na linha (ou o marcador, se a foto ainda não chegou).
    expect(
      within(linha).getAllByRole('img').length + within(linha).queryAllByText('Sem foto').length,
    ).toBeGreaterThan(0);
    expect(await screen.findByText(/Anel Solitário entrou na compra/)).toBeInTheDocument();
    // O foco voltou ao campo do leitor: a próxima leitura já pode acontecer.
    await waitFor(() => expect(screen.getByLabelText('Código da peça ou nome')).toHaveFocus());

    await ler(u, '0012');
    expect(await screen.findByText(/agora são 2 peças nesta compra/)).toBeInTheDocument();
    expect(screen.getAllByRole('row', { name: /Anel Solitário/ })).toHaveLength(1);
    expect(screen.getByLabelText('Quantidade de Anel Solitário')).toHaveValue('2');
    expect(screen.getByText('R$ 20,00', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText(/Total da compra:/)).toHaveTextContent('R$ 20,00');
  });

  it('editar quantidade e custo recalcula o total em centavos certos', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await ler(u, '0012');
    const qtd = await screen.findByLabelText('Quantidade de Anel Solitário');
    const custo = screen.getByLabelText('Custo de cada peça de Anel Solitário');
    await u.clear(qtd);
    await u.type(qtd, '3');
    await u.clear(custo);
    await u.type(custo, '0,10');
    expect(screen.getByText('R$ 0,30', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText(/Total da compra:/)).toHaveTextContent('R$ 0,30');
  });

  it('peça sem custo cadastrado entra com o custo vazio e não deixa confirmar', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await ler(u, 'B-7');
    expect(await screen.findByLabelText('Custo de cada peça de Brinco de Argola')).toHaveValue('');
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    expect(
      await screen.findByText(
        'Digite o custo em reais, com no máximo 2 números depois da vírgula.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('Confirmar esta compra?')).not.toBeInTheDocument();
    expect(chamadasDe(m, 'compras.receber')).toHaveLength(0);
  });

  it('sem fornecedor ou sem peças a compra não é gravada', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await menu('Receber compra'));
    await u.click(await screen.findByRole('button', { name: 'Confirmar compra' }));
    expect(
      await screen.findByText('Leia pelo menos uma peça para confirmar a compra.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Escolha o fornecedor da compra.')).toBeInTheDocument();
    expect(chamadasDe(m, 'compras.receber')).toHaveLength(0);
  });

  it('busca pelo nome quando o texto não é código: uma peça entra, várias pedem escolha', async () => {
    const m = montar({
      'produtos.listar': (p) =>
        (p.parametros as { busca: string }).busca === 'anel'
          ? pagina([ANEL])
          : pagina([ANEL, BRINCO]),
    });
    const u = await abrirReceber(m);
    await ler(u, 'anel');
    expect(await screen.findByRole('row', { name: /Anel Solitário/ })).toBeInTheDocument();
    await ler(u, 'peça');
    expect(await screen.findByText(/Achamos 2 peças com esse nome/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Escolher Brinco de Argola' }));
    expect(await screen.findByRole('row', { name: /Brinco de Argola/ })).toBeInTheDocument();
  });

  it('tirar a peça tira a linha e o total', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await ler(u, '0012');
    await u.click(await screen.findByRole('button', { name: 'Tirar Anel Solitário da compra' }));
    expect(screen.queryByRole('row', { name: /Anel Solitário/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Total da compra:/)).toHaveTextContent('R$ 0,00');
  });
});

describe('Receber compra: cadastro rápido', () => {
  it('código que não existe abre o cadastro rápido, cria o produto e já põe na compra', async () => {
    const m = montar({
      'produtos.criar': (p) => ({
        ok: true,
        dados: {
          ...ANEL,
          id: (p.parametros as { id: string }).id,
          nome: 'Colar Novo',
          precoCusto: 4.5,
          codigos: [{ id: ID_NOVO, codigo: 'XYZ-1', tipo: 'LOJA', principal: true, ativo: true }],
          fotoPrincipal: null,
        },
      }),
    });
    const u = await abrirReceber(m);
    await ler(u, 'XYZ-1');
    expect(
      await screen.findByText('Não encontramos nenhuma peça com o código XYZ-1.'),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Cadastrar esta peça agora' }));
    expect(await screen.findByRole('heading', { name: 'Cadastro rápido' })).toBeInTheDocument();
    expect(screen.getByLabelText('Código')).toHaveValue('XYZ-1');

    // Faltando dados: avisa e não cria.
    await u.click(screen.getByRole('button', { name: 'Cadastrar e pôr na compra' }));
    expect(await screen.findByText('Informe o nome da peça.')).toBeInTheDocument();
    expect(chamadasDe(m, 'produtos.criar')).toHaveLength(0);

    await u.type(screen.getByLabelText('Nome'), 'Colar Novo');
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await u.type(screen.getByLabelText('Preço de venda'), '30');
    await u.type(screen.getByLabelText('Custo de cada peça'), '4,50');
    await u.click(screen.getByRole('button', { name: 'Cadastrar e pôr na compra' }));

    const linha = await screen.findByRole('row', { name: /Colar Novo/ });
    expect(within(linha).getByLabelText('Custo de cada peça de Colar Novo')).toHaveValue('4,50');
    expect(ultima(m, 'produtos.criar')).toMatchObject({
      nome: 'Colar Novo',
      categoriaId: ID_CAT,
      precoVenda: '30.00',
      precoCusto: '4.50',
      codigos: [{ codigo: 'XYZ-1', tipo: 'LOJA', principal: true }],
    });
    expect(chamadasDe(m, 'produtos.criar')).toHaveLength(1);
    // Voltou o campo do leitor.
    expect(await screen.findByLabelText('Código da peça ou nome')).toBeInTheDocument();
  });

  it('código de barras com número errado é recusado antes de ir ao servidor', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await ler(u, '7891234567890');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar esta peça agora' }));
    await u.selectOptions(await screen.findByLabelText('Tipo do código'), 'Código de barras');
    expect(await screen.findByText(/não confere/)).toBeInTheDocument();
    await u.type(screen.getByLabelText('Nome'), 'X');
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await u.type(screen.getByLabelText('Preço de venda'), '3');
    await u.type(screen.getByLabelText('Custo de cada peça'), '1');
    await u.click(screen.getByRole('button', { name: 'Cadastrar e pôr na compra' }));
    expect(chamadasDe(m, 'produtos.criar')).toHaveLength(0);
  });

  it('erro do servidor aparece em português e a pessoa pode tentar de novo com o mesmo identificador', async () => {
    let n = 0;
    const m = montar({
      'produtos.criar': () => {
        n += 1;
        return n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: { ...ANEL, nome: 'Colar Novo' } };
      },
    });
    const u = await abrirReceber(m);
    await ler(u, 'XYZ-1');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar esta peça agora' }));
    await u.type(await screen.findByLabelText('Nome'), 'Colar Novo');
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await u.type(screen.getByLabelText('Preço de venda'), '30');
    await u.type(screen.getByLabelText('Custo de cada peça'), '4');
    await u.click(screen.getByRole('button', { name: 'Cadastrar e pôr na compra' }));
    expect(
      await screen.findByText('Sem conexão com a internet. Tente de novo.'),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Cadastrar e pôr na compra' }));
    await screen.findByRole('row', { name: /Colar Novo/ });
    const [a, b] = chamadasDe(m, 'produtos.criar').map((c) => (c.parametros as { id: string }).id);
    expect(a).toBeTruthy();
    expect(a).toBe(b);
  });
});

describe('Receber compra: confirmar', () => {
  async function comUmaPeca(m: Montagem) {
    const u = await abrirReceber(m);
    await ler(u, '0012');
    await screen.findByRole('row', { name: /Anel Solitário/ });
    return u;
  }

  it('confirma uma vez só: o servidor recebe o identificador e os itens, nunca o total, e o resumo é o dele', async () => {
    const m = montar();
    const u = await comUmaPeca(m);
    await u.type(screen.getByLabelText('Número da nota (se tiver)'), 'NF-9');
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    expect(await screen.findByText('Confirmar esta compra?')).toBeInTheDocument();
    await u.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    expect(await screen.findByText('Compra gravada.')).toBeInTheDocument();

    const pedido = ultima(m, 'compras.receber') as Record<string, unknown>;
    expect(pedido).toMatchObject({
      fornecedorId: ID_FORN,
      numero: 'NF-9',
      itens: [{ produtoId: ID_P, quantidade: 1, custoUnitario: '10.00' }],
    });
    expect(pedido.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.stringify(pedido)).not.toMatch(/total/i);
    // O texto mostra o total que o servidor devolveu (2 peças, R$ 20,00), não o da tela (1 peça).
    expect(
      screen.getByText('Entraram 2 peças no estoque, num total de R$ 20,00.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Desfazer compra' })).toBeInTheDocument();
  });

  it('compra que o servidor já tinha registrado (200) avisa que nada foi somado de novo', async () => {
    const m = montar({
      'compras.receber': () => ({ ok: true, criado: false, dados: compraGravada() }),
    });
    const u = await comUmaPeca(m);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    expect(
      await screen.findByText('Esta compra já estava registrada. Nada foi somado de novo.'),
    ).toBeInTheDocument();
  });

  it('compra nova (201) não mostra o aviso de já registrada', async () => {
    const m = montar({
      'compras.receber': () => ({ ok: true, criado: true, dados: compraGravada() }),
    });
    const u = await comUmaPeca(m);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    await screen.findByText('Compra gravada.');
    expect(screen.queryByText(/já estava registrada/)).toBeNull();
  });

  it('duplo clique em Confirmar não grava duas vezes', async () => {
    let liberar: (r: ResultadoDeChamada) => void = () => undefined;
    const m = montar({
      'compras.receber': () =>
        new Promise<ResultadoDeChamada>((resolve) => {
          liberar = resolve;
        }),
    });
    const u = await comUmaPeca(m);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    const dialogo = await screen.findByRole('dialog');
    const botao = within(dialogo).getByRole('button', { name: 'Confirmar compra' });
    fireEvent.click(botao);
    fireEvent.click(botao);
    fireEvent.click(botao);
    liberar({ ok: true, dados: compraGravada() });
    expect(await screen.findByText('Compra gravada.')).toBeInTheDocument();
    expect(chamadasDe(m, 'compras.receber')).toHaveLength(1);
  });

  it('se a internet cair, reenviar usa o mesmo identificador (não duplica a compra)', async () => {
    let n = 0;
    const m = montar({
      'compras.receber': () => {
        n += 1;
        return n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: compraGravada() };
      },
    });
    const u = await comUmaPeca(m);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    expect(
      await screen.findByText('Sem conexão com a internet. Tente de novo.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /Anel Solitário/ })).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    expect(await screen.findByText('Compra gravada.')).toBeInTheDocument();
    const [a, b] = chamadasDe(m, 'compras.receber').map((c) => (c.parametros as { id: string }).id);
    expect(a).toBe(b);
  });

  it('erro por linha do servidor aparece na linha certa e nada é perdido', async () => {
    const m = montar({
      'compras.receber': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Confira os dados da compra.',
        campos: [
          {
            campo: 'itens[0].quantidade',
            mensagem: 'A quantidade é grande demais. Confira o número.',
          },
        ],
      }),
    });
    const u = await abrirReceber(m);
    await ler(u, '0012');
    await ler(u, 'B-7');
    await screen.findByRole('row', { name: /Brinco de Argola/ });
    await u.type(screen.getByLabelText('Custo de cada peça de Brinco de Argola'), '5');
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(
      within(linha).getByText('A quantidade é grande demais. Confira o número.'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('row', { name: /Brinco de Argola/ })).queryByText(/grande demais/),
    ).not.toBeInTheDocument();
    // As linhas continuam, a pessoa corrige e tenta de novo.
    expect(screen.getByLabelText('Quantidade de Anel Solitário')).toHaveValue('1');
  });

  it('erro de campo do cabeçalho (fornecedor, data) aparece no campo', async () => {
    const m = montar({
      'compras.receber': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Confira os dados da compra.',
        campos: [{ campo: 'dataCompra', mensagem: 'A data da compra não pode ser no futuro.' }],
      }),
    });
    const u = await comUmaPeca(m);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    expect(await screen.findByText('A data da compra não pode ser no futuro.')).toBeInTheDocument();
  });

  it('nenhum custo vai para o registro de diagnóstico do aplicativo', async () => {
    const espioes = (['log', 'info', 'warn', 'error', 'debug'] as const).map((n) =>
      vi.spyOn(console, n).mockImplementation(() => undefined),
    );
    const m = montar();
    const u = await comUmaPeca(m);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirmar compra' }),
    );
    await screen.findByText('Compra gravada.');
    const escrito = espioes
      .flatMap((e) => e.mock.calls)
      .map((c) => JSON.stringify(c))
      .join('\n');
    expect(escrito).not.toMatch(/10[,.]00|custo/i);
    espioes.forEach((e) => e.mockRestore());
  });
});

describe('Compras: ver e desfazer', () => {
  async function abrirGravada(m: Montagem) {
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await menu('Compras'));
    await u.click(await screen.findByRole('button', { name: 'Abrir compra' }));
    await screen.findByRole('heading', { name: 'Compra de 10/11/2026' });
    return u;
  }

  it('abre a compra com as peças, as fotos e os valores', async () => {
    const m = montar();
    await abrirGravada(m);
    const linha = screen.getByRole('row', { name: /Anel Solitário/ });
    expect(within(linha).getByText('0012')).toBeInTheDocument();
    expect(within(linha).getByText('R$ 20,00')).toBeInTheDocument();
    expect(screen.getByText('Ouro Ltda')).toBeInTheDocument();
    expect(chamadasDe(m, 'produtos.fotos.imagem').length).toBeGreaterThan(0);
  });

  it('Desfazer compra pede o motivo, manda o pedido e mostra a compra desfeita', async () => {
    const m = montar();
    const u = await abrirGravada(m);
    await u.click(screen.getByRole('button', { name: 'Desfazer compra' }));
    const dialogo = await screen.findByRole('dialog');
    await u.click(within(dialogo).getByRole('button', { name: 'Desfazer compra' }));
    expect(await screen.findByText('Diga o motivo para desfazer a compra.')).toBeInTheDocument();
    expect(chamadasDe(m, 'compras.desfazer')).toHaveLength(0);
    await u.type(within(dialogo).getByLabelText('Motivo'), 'Veio errado');
    await u.click(within(dialogo).getByRole('button', { name: 'Desfazer compra' }));
    expect(await screen.findByText('A compra foi desfeita.')).toBeInTheDocument();
    expect(ultima(m, 'compras.desfazer')).toMatchObject({ id: ID_COMPRA, motivo: 'Veio errado' });
    expect(screen.getByText(/Motivo: Veio errado/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Desfazer compra' })).not.toBeInTheDocument();
  });

  it('se já saíram peças, o texto simples do servidor aparece na janela', async () => {
    const m = montar({
      'compras.desfazer': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem:
          'Não é possível desfazer esta compra. De "Anel Solitário" já saíram peças e há só 1 no estoque.',
      }),
    });
    const u = await abrirGravada(m);
    await u.click(screen.getByRole('button', { name: 'Desfazer compra' }));
    const dialogo = await screen.findByRole('dialog');
    await u.type(within(dialogo).getByLabelText('Motivo'), 'Erro');
    await u.click(within(dialogo).getByRole('button', { name: 'Desfazer compra' }));
    expect(
      await within(dialogo).findByText(/Não é possível desfazer esta compra/),
    ).toBeInTheDocument();
    // A compra continua como estava.
    expect(screen.getAllByRole('button', { name: 'Desfazer compra' }).length).toBeGreaterThan(0);
  });

  it('duplo clique em Desfazer não desfaz duas vezes', async () => {
    let liberar: (r: ResultadoDeChamada) => void = () => undefined;
    const m = montar({
      'compras.desfazer': () =>
        new Promise<ResultadoDeChamada>((resolve) => {
          liberar = resolve;
        }),
    });
    const u = await abrirGravada(m);
    await u.click(screen.getByRole('button', { name: 'Desfazer compra' }));
    const dialogo = await screen.findByRole('dialog');
    await u.type(within(dialogo).getByLabelText('Motivo'), 'Erro');
    const botao = within(dialogo).getByRole('button', { name: 'Desfazer compra' });
    fireEvent.click(botao);
    fireEvent.click(botao);
    liberar({
      ok: true,
      dados: compraGravada({ compra: { ...compraGravada().compra, situacao: 'ESTORNADA' } }),
    });
    await screen.findByText('A compra foi desfeita.');
    expect(chamadasDe(m, 'compras.desfazer')).toHaveLength(1);
  });
});

describe('Compras: lista', () => {
  it('filtros por período, fornecedor e situação vão para o servidor; período invertido avisa', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await menu('Compras'));
    await screen.findByRole('row', { name: /Ouro Ltda/ });
    expect(screen.getByText('R$ 20,00', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('Desfeita', { selector: 'option' })).toBeInTheDocument();

    await u.selectOptions(screen.getByLabelText('Fornecedor'), 'Ouro Ltda');
    await u.selectOptions(screen.getByLabelText('Situação'), 'Desfeita');
    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-01' } });
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-30' } });
    await waitFor(() =>
      expect(ultima(m, 'compras.listar')).toMatchObject({
        fornecedorId: ID_FORN,
        situacao: 'ESTORNADA',
        de: '2026-11-01',
        ate: '2026-11-30',
        pagina: 0,
      }),
    );
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-10-01' } });
    expect(
      await screen.findByText('A data final não pode ser antes da data inicial.'),
    ).toBeInTheDocument();
  });

  it('lista vazia com e sem filtro', async () => {
    const m = montar({ 'compras.listar': () => pagina([]) });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await menu('Compras'));
    expect(await screen.findByText('Ainda não há compras.')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Situação'), 'Registrada');
    expect(await screen.findByText(/Nenhuma compra com estes filtros/)).toBeInTheDocument();
  });
});

describe('Importar a compra por planilha', () => {
  const CSV = 'código;quantidade;custo\n0012;2;10,00\nZZ-9;1;3,50\n0012;1;1,00\nB-7;x;2,00\n';

  async function escolher(m: Montagem, csv = CSV) {
    const u = await abrirReceber(m);
    await u.click(screen.getByRole('tab', { name: 'Importar planilha' }));
    fireEvent.change(screen.getByTestId('escolher-planilha-de-compra'), {
      target: { files: [new File([csv], 'compra.csv', { type: 'text/csv' })] },
    });
    return u;
  }

  it('prévia com erro por linha ANTES de gravar; nada vai ao servidor até confirmar', async () => {
    const m = montar();
    await escolher(m);
    expect(await screen.findByText('Prévia: nada foi gravado ainda.')).toBeInTheDocument();
    expect(
      screen.getByText('1 linhas prontas, 1 com código que não existe, 2 com problema.'),
    ).toBeInTheDocument();
    const repetida = linhaDa(4);
    expect(within(repetida).getByText(/Este código já aparece na linha 2/)).toBeInTheDocument();
    const ruim = linhaDa(5);
    expect(within(ruim).getByText(/quantidade precisa ser um número inteiro/)).toBeInTheDocument();
    expect(within(linhaDa(3)).getByText('Peça não cadastrada')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Confirmar compra' }),
      'só dá para confirmar com tudo certo',
    ).toBeDisabled();
    expect(chamadasDe(m, 'compras.receber')).toHaveLength(0);
  });

  it('desconhecido: cadastrar a peça ou corrigir o código; depois grava uma compra só', async () => {
    const m = montar({
      'produtos.criar': () => ({
        ok: true,
        dados: {
          ...ANEL,
          id: ID_NOVO,
          nome: 'Peça nova',
          codigos: [{ id: ID_NOVO, codigo: 'ZZ-9', tipo: 'LOJA', principal: true, ativo: true }],
        },
      }),
    });
    const u = await escolher(m, 'código;quantidade;custo\n0012;2;10,00\nZZ-9;1;3,50\nQQ;4;1,25\n');
    await screen.findByText('Prévia: nada foi gravado ainda.');

    // Cadastrar a peça do ZZ-9 na hora.
    await u.click(screen.getByRole('button', { name: 'Cadastrar a peça do código ZZ-9' }));
    expect(screen.getByLabelText('Custo de cada peça')).toHaveValue('3,50');
    await u.type(screen.getByLabelText('Nome'), 'Peça nova');
    await u.selectOptions(screen.getByLabelText('Categoria'), 'Anéis');
    await u.type(screen.getByLabelText('Preço de venda'), '9');
    await u.click(screen.getByRole('button', { name: 'Cadastrar e pôr na compra' }));
    await waitFor(() =>
      expect(within(linhaDa(3)).getByText('Entra na compra')).toBeInTheDocument(),
    );

    // Corrigir o código QQ para B-7.
    await u.click(screen.getByRole('button', { name: 'Corrigir código 4' }));
    const campo = screen.getByLabelText('Código certo da linha 4');
    await u.clear(campo);
    await u.type(campo, 'B-7{Enter}');
    await waitFor(() =>
      expect(within(linhaDa(4)).getByText('Brinco de Argola')).toBeInTheDocument(),
    );

    await escolherFornecedorDaPlanilha(u);
    const confirmar = screen.getByRole('button', { name: 'Confirmar compra' });
    expect(confirmar).toBeEnabled();
    fireEvent.click(confirmar);
    fireEvent.click(confirmar);
    expect(await screen.findByText('Compra importada.')).toBeInTheDocument();
    expect(chamadasDe(m, 'compras.receber')).toHaveLength(1);
    expect(ultima(m, 'compras.receber')).toMatchObject({
      fornecedorId: ID_FORN,
      itens: [
        { produtoId: ID_P, quantidade: 2, custoUnitario: '10.00' },
        { produtoId: ID_NOVO, quantidade: 1, custoUnitario: '3.50' },
        { produtoId: ID_OUTRO, quantidade: 4, custoUnitario: '1.25' },
      ],
    });
  });

  it('tirar a linha com problema libera a gravação', async () => {
    const m = montar();
    const u = await escolher(m, 'código;quantidade;custo\n0012;2;10,00\nQQ;4;1,25\n');
    await screen.findByText('Prévia: nada foi gravado ainda.');
    await u.click(screen.getByRole('button', { name: 'Tirar linha 3' }));
    expect(screen.getByRole('button', { name: 'Confirmar compra' })).toBeEnabled();
  });

  it('se o servidor recusar uma linha, a mensagem aparece nela e nada é considerado gravado', async () => {
    const m = montar({
      'compras.receber': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Confira os dados da compra.',
        campos: [
          {
            campo: 'itens[0].produtoId',
            mensagem: 'Esta peça não existe mais. Atualize a tela e escolha de novo.',
          },
        ],
      }),
    });
    const u = await escolher(m, 'código;quantidade;custo\n0012;2;10,00\n');
    await screen.findByText('Prévia: nada foi gravado ainda.');
    await escolherFornecedorDaPlanilha(u);
    await u.click(screen.getByRole('button', { name: 'Confirmar compra' }));
    const linha = await waitFor(() => linhaDa(2));
    expect(
      within(linha).getByText('Esta peça não existe mais. Atualize a tela e escolha de novo.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/A compra não foi gravada/)).toBeInTheDocument();
    expect(screen.queryByText('Compra importada.')).not.toBeInTheDocument();
  });

  it('arquivo que não é planilha é recusado em português', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await u.click(screen.getByRole('tab', { name: 'Importar planilha' }));
    fireEvent.change(screen.getByTestId('escolher-planilha-de-compra'), {
      target: { files: [new File(['x'], 'compra.pdf')] },
    });
    expect(await screen.findByText('Escolha uma planilha .xlsx ou .csv.')).toBeInTheDocument();
  });

  it('o modelo para baixar existe', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    await u.click(screen.getByRole('tab', { name: 'Importar planilha' }));
    expect(screen.getByRole('button', { name: 'Baixar a planilha modelo' })).toBeInTheDocument();
  });
});

describe('Textos das telas de compra', () => {
  it('nenhuma tela mostra palavra técnica ou do bloco proibido do glossário', async () => {
    const m = montar();
    const u = await abrirReceber(m);
    const telas: string[] = [];
    const ver = () => telas.push(document.body.textContent ?? '');
    ver();
    await ler(u, '0012');
    await screen.findByRole('row', { name: /Anel Solitário/ });
    await ler(u, 'XYZ');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar esta peça agora' }));
    await screen.findByRole('heading', { name: 'Cadastro rápido' });
    ver();
    await u.click(screen.getByRole('button', { name: 'Agora não' }));
    await u.click(screen.getByRole('tab', { name: 'Importar planilha' }));
    ver();
    await u.click(
      within(screen.getByRole('navigation', { name: 'Menu do Corona Gestão' })).getAllByRole(
        'button',
        { name: 'Compras' },
      )[0]!,
    );
    await screen.findByRole('row', { name: /Ouro Ltda/ });
    ver();
    await u.click(screen.getByRole('button', { name: 'Abrir compra' }));
    await screen.findByRole('heading', { name: 'Compra de 10/11/2026' });
    await u.click(screen.getByRole('button', { name: 'Desfazer compra' }));
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
    ];
    for (const p of proibidas) expect(tudo, `"${p}" não pode aparecer`).not.toContain(p);
    for (const p of ['RASCUNHO', 'ENVIADA', 'ESTORNADA', 'REGISTRADA', 'CANCELADA', 'ABERTA']) {
      expect(telas.join('\n'), `"${p}" não pode aparecer`).not.toContain(p);
    }
  });
});

describe('Atalho da tela inicial', () => {
  it('o botão grande Receber compra abre a tela de receber compra', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    const atalhos = await screen.findByRole('navigation', { name: 'O que você quer fazer?' });
    await u.click(within(atalhos).getByRole('button', { name: 'Receber compra' }));
    expect(
      await screen.findByRole('heading', { name: 'Receber compra', level: 1 }),
    ).toBeInTheDocument();
  });
});

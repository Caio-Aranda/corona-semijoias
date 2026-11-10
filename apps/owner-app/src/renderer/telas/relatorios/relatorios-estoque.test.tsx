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

const ID_CAT = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_FORN = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_USU = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_PROD = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_COMPRA = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const ID_CONTAGEM = '7b14e45f-ceea-4672-a1b9-3a1a0f7c4d55';

type Linha = Record<string, unknown>;
type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

/** Uma linha de cada relatório, como a API da tarefa 2.5 devolve. */
const LINHAS: Record<string, (i: number) => Linha> = {
  'estoque-posicao': (i) => ({
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    categoria: 'Anéis',
    fornecedor: 'Ouro Ltda',
    saldo: 5,
    estoqueMinimo: 2,
    custoUnitario: '10.00',
    valorACusto: '50.00',
    ativo: true,
    fotoPrincipalHash: null,
  }),
  'estoque-movimentos': (i) => ({
    id: `m${i}`,
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    tipo: 'AVARIA',
    quantidade: -2,
    saldoDepois: 3,
    origem: 'ESTORNO',
    motivo: 'Caiu no chão',
    usuario: 'Maria',
    ocorridaEm: '2026-11-09T15:00:00Z',
    desfeito: i % 2 === 0,
    estorno: false,
  }),
  'estoque-minimo': (i) => ({
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    categoria: 'Anéis',
    fornecedor: 'Ouro Ltda',
    saldo: 1,
    minimo: 3,
    falta: 2,
  }),
  'estoque-parados': (i) => ({
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    categoria: 'Anéis',
    fornecedor: 'Ouro Ltda',
    saldo: 4,
    ultimaSaidaEm: '2026-08-01',
    diasSemSair: 101,
    valorACusto: '40.00',
  }),
  'estoque-contagens': (i) => ({
    inventarioId: ID_CONTAGEM,
    situacao: 'FECHADO',
    escopo: 'CATEGORIA',
    escopoNome: 'Anéis',
    iniciadoEm: '2026-11-01T12:00:00Z',
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    esperado: 5,
    contado: 4,
    diferenca: -1,
    ajuste: -1,
  }),
  'estoque-conferencia': (i) => ({
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    saldoGuardado: 5,
    somaDosMovimentos: 4,
    diferenca: 1,
  }),
  'compras-por-compra': (i) => ({
    id: ID_COMPRA,
    dataCompra: '2026-11-05',
    numero: `NF-${i}`,
    fornecedor: 'Ouro Ltda',
    itens: 2,
    pecas: 10,
    total: '100.00',
    situacao: 'REGISTRADA',
    estornada: false,
    usuario: 'Maria',
  }),
  'compras-por-fornecedor': (i) => ({
    fornecedorId: ID_FORN,
    fornecedor: `Ouro ${i}`,
    compras: 2,
    pecas: 10,
    total: '100.00',
    comprasEstornadas: 0,
    totalEstornado: '0.00',
  }),
  'compras-custos': (i) => ({
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    dataCompra: '2026-11-05',
    fornecedor: 'Ouro Ltda',
    numeroDaCompra: 'NF-1',
    custo: '12.00',
    custoAnterior: '10.00',
    variacao: '2.00',
    variacaoPercentual: '20.00',
    compraEstornada: false,
  }),
  'compras-pecas': (i) => ({
    produtoId: ID_PROD,
    nome: `Anel ${i}`,
    codigo: `A${i}`,
    quantidade: 5,
    custoUnitario: '10.00',
    total: '50.00',
    situacaoDaCompra: 'REGISTRADA',
  }),
};

/** O texto do servidor traz termos que a tela não usa: a tela troca por palavras da loja. */
const TOTAIS: Record<string, Linha[]> = {
  'estoque-posicao': [
    { chave: 'todos', rotulo: 'Produtos', quantidade: 3, valorEmReais: null, pecas: null },
    {
      chave: 'valor-a-custo',
      rotulo: 'Peças em estoque e valor a custo',
      quantidade: 3,
      valorEmReais: '150.00',
      pecas: 15,
    },
  ],
  'estoque-movimentos': [
    { chave: 'todos', rotulo: 'Movimentos', quantidade: 3, valorEmReais: null, pecas: null },
    { chave: 'tipo-remessa', rotulo: 'Remessas', quantidade: 0, valorEmReais: null, pecas: 0 },
  ],
  'estoque-minimo': [
    {
      chave: 'todos',
      rotulo: 'Produtos no mínimo ou abaixo',
      quantidade: 3,
      valorEmReais: null,
      pecas: null,
    },
    { chave: 'falta', rotulo: 'Peças que faltam', quantidade: 3, valorEmReais: null, pecas: 6 },
  ],
  'estoque-parados': [
    { chave: 'todos', rotulo: 'Produtos parados', quantidade: 3, valorEmReais: null, pecas: null },
    { chave: 'valor-a-custo', rotulo: 'x', quantidade: 3, valorEmReais: '120.00', pecas: 12 },
  ],
  'estoque-contagens': [
    { chave: 'todos', rotulo: 'Itens contados', quantidade: 3, valorEmReais: null, pecas: null },
    {
      chave: 'com-divergencia',
      rotulo: 'Com divergência',
      quantidade: 3,
      valorEmReais: null,
      pecas: null,
    },
  ],
  'estoque-conferencia': [
    {
      chave: 'todos',
      rotulo: 'Produtos com diferença',
      quantidade: 3,
      valorEmReais: null,
      pecas: null,
    },
  ],
  'compras-por-compra': [
    { chave: 'todos', rotulo: 'Compras listadas', quantidade: 3, valorEmReais: null, pecas: null },
    {
      chave: 'registradas',
      rotulo: 'Compras registradas',
      quantidade: 3,
      valorEmReais: '300.00',
      pecas: 30,
    },
    {
      chave: 'estornadas',
      rotulo: 'Compras estornadas',
      quantidade: 0,
      valorEmReais: '0.00',
      pecas: 0,
    },
  ],
  'compras-por-fornecedor': [
    { chave: 'todos', rotulo: 'Fornecedores', quantidade: 3, valorEmReais: null, pecas: null },
    { chave: 'geral', rotulo: 'Total geral', quantidade: 3, valorEmReais: '300.00', pecas: 30 },
  ],
  'compras-custos': [
    {
      chave: 'todos',
      rotulo: 'Custos registrados',
      quantidade: 3,
      valorEmReais: null,
      pecas: null,
    },
  ],
  'compras-pecas': [
    { chave: 'todos', rotulo: 'Itens', quantidade: 3, valorEmReais: null, pecas: null },
    { chave: 'total', rotulo: 'Peças e total', quantidade: 3, valorEmReais: '150.00', pecas: 15 },
  ],
};

/** Um servidor de mentira que respeita página e tamanho, como o de verdade. */
function servidor(nome: string, quantas: number) {
  return (p: PedidoDeChamada): ResultadoDeChamada => {
    const q = p.parametros as { pagina?: number; tamanho?: number };
    const todas = Array.from({ length: quantas }, (_, i) => LINHAS[nome]!(i + 1));
    const inicio = (q.pagina ?? 0) * (q.tamanho ?? 20);
    return {
      ok: true,
      dados: {
        itens: todas.slice(inicio, inicio + (q.tamanho ?? 20)),
        total: quantas,
        pagina: q.pagina ?? 0,
        tamanho: q.tamanho ?? 20,
        totais: TOTAIS[nome]!.map((t) => ({ ...t, quantidade: quantas })),
      },
    };
  };
}

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
    'categorias.listar': () => pagina([{ id: ID_CAT, version: 0, nome: 'Anéis' }]),
    'fornecedores.listar': () => pagina([{ id: ID_FORN, version: 0, razaoSocial: 'Ouro Ltda' }]),
    'usuarios.listar': () =>
      pagina([
        { id: ID_USU, version: 0, nome: 'Maria', login: 'maria', perfil: 'ADMIN', ativo: true },
      ]),
    'relatorios.cadastros': (p) => servidor(String((p.parametros as Linha).relatorio), 3)(p),
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

const texto = (b: Uint8Array) => new TextDecoder().decode(b);

beforeEach(() => {
  window.location.hash = '';
  entregues.length = 0;
});

/** nome interno, título no menu, texto que a primeira linha mostra, texto do vazio. */
const RELATORIOS: [string, string, RegExp, string][] = [
  [
    'estoque-posicao',
    'Estoque agora, com valor a custo',
    /Anel 1/,
    'Nenhum produto com estes filtros.',
  ],
  [
    'estoque-movimentos',
    'Entradas e saídas por período',
    /Perda/,
    'Nenhum lançamento neste período.',
  ],
  ['estoque-minimo', 'Peças que estão acabando', /Anel 1/, 'Nenhuma peça está acabando.'],
  ['estoque-parados', 'Peças paradas', /Anel 1/, 'Nenhuma peça parada com estes filtros.'],
  [
    'estoque-contagens',
    'Contagens do estoque e diferenças',
    /Categoria Anéis/,
    'Nenhuma contagem com estes filtros.',
  ],
  [
    'estoque-conferencia',
    'Conferência do estoque',
    /Anel 1/,
    'Tudo certo: a quantidade de todas as peças bate com o que entrou e saiu.',
  ],
  ['compras-por-compra', 'Compras por período', /NF-1/, 'Nenhuma compra com estes filtros.'],
  [
    'compras-por-fornecedor',
    'Compras por fornecedor',
    /Ouro 1/,
    'Nenhuma compra com estes filtros.',
  ],
  [
    'compras-custos',
    'Histórico de custo das peças',
    /Anel 1/,
    'Nenhum custo registrado com estes filtros.',
  ],
];

describe('Relatórios de estoque e de compras: menu', () => {
  it('há um grupo de estoque e um de compras, com os nomes do glossário', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    expect(await screen.findByText('Relatórios de estoque')).toBeInTheDocument();
    expect(screen.getByText('Relatórios de compras')).toBeInTheDocument();
    for (const [, titulo] of RELATORIOS) {
      expect(await screen.findByRole('button', { name: titulo })).toBeInTheDocument();
    }
  });
});

describe.each(RELATORIOS)('Relatório %s', (nome, titulo, primeira, vazio) => {
  it('mostra linhas, totais com texto da loja e a data da cotação nos valores', async () => {
    const m = montar();
    await abrir(m, titulo);
    await screen.findByRole('heading', { name: titulo, level: 1 });
    expect(await screen.findAllByText(primeira)).not.toHaveLength(0);
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: nome, pagina: 0, tamanho: 20 });
    expect(screen.getByRole('region', { name: 'Totais' })).toBeInTheDocument();
  });

  it('sem linhas mostra o texto de vazio em português', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: { itens: [], total: 0, pagina: 0, tamanho: 20, totais: [] },
      }),
    });
    await abrir(m, titulo);
    expect(await screen.findByText(vazio)).toBeInTheDocument();
  });

  it('com a rede fora mostra um erro simples e deixa tentar de novo', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: false,
        motivo: 'erro',
        mensagem: 'Não foi possível abrir o relatório agora.',
      }),
    });
    await abrir(m, titulo);
    expect(await screen.findByRole('button', { name: /tentar de novo/i })).toBeInTheDocument();
  });

  it('o arquivo traz as mesmas linhas e os mesmos filtros da tela (páginas de 100)', async () => {
    const m = montar({ 'relatorios.cadastros': servidor(nome, 230) });
    const u = await abrir(m, titulo);
    await screen.findAllByText(primeira);
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = texto(entregues[0]!.bytes);
    const chamadasDoArquivo = dosRelatorios(m).filter((p) => p.tamanho === 100);
    expect(chamadasDoArquivo.map((p) => p.pagina)).toEqual([0, 1, 2]);
    // Mesmos filtros da tela: tudo igual, menos a página e o tamanho.
    const semPagina = (p: Linha) =>
      Object.fromEntries(Object.entries(p).filter(([k]) => k !== 'pagina' && k !== 'tamanho'));
    const daTela = semPagina(dosRelatorios(m)[0]!);
    for (const p of chamadasDoArquivo) expect(semPagina(p)).toEqual(daTela);
    // Cabeçalho do arquivo.
    expect(csv).toContain('Corona Semijoias');
    expect(csv).toContain(titulo);
    expect(csv).toContain('Filtros:');
    expect(csv).toContain('por Maria');
    // 230 linhas de dados (a foto não vai no arquivo).
    const linhasDeDados = csv.split('\r\n').filter((l) => /Anel \d+|NF-\d+|Ouro \d+/.test(l));
    expect(linhasDeDados.length).toBeGreaterThanOrEqual(230);
    expect(csv).not.toContain('Foto');
  });
});

describe('Relatórios de estoque e de compras: filtros que a API aceita', () => {
  it('entradas e saídas: período, tipo, origem, quem lançou e peça', async () => {
    const m = montar();
    const u = await abrir(m, 'Entradas e saídas por período');
    await screen.findAllByText(/Perda/);
    await u.type(screen.getByLabelText('De'), '2026-11-01');
    await u.type(screen.getByLabelText('Até'), '2026-11-09');
    await u.selectOptions(screen.getByLabelText('Tipo de lançamento'), 'Perda');
    await u.selectOptions(screen.getByLabelText('De onde veio'), 'Compra');
    await u.selectOptions(await screen.findByLabelText('Quem lançou'), 'Maria');
    await u.selectOptions(await screen.findByLabelText('Categoria'), 'Anéis');
    await u.type(screen.getByLabelText(/Buscar/), 'anel');
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({
        relatorio: 'estoque-movimentos',
        de: '2026-11-01',
        ate: '2026-11-09',
        tipo: 'AVARIA',
        origem: 'COMPRA',
        usuarioId: ID_USU,
        categoriaId: ID_CAT,
        busca: 'anel',
      }),
    );
  });

  it('data final antes da inicial avisa e não consulta', async () => {
    const m = montar();
    const u = await abrir(m, 'Entradas e saídas por período');
    await screen.findAllByText(/Perda/);
    const antes = dosRelatorios(m).length;
    await u.type(screen.getByLabelText('De'), '2026-11-09');
    await u.type(screen.getByLabelText('Até'), '2026-11-01');
    expect(
      await screen.findByText('A data final não pode ser antes da data inicial.'),
    ).toBeInTheDocument();
    expect(
      dosRelatorios(m)
        .slice(antes)
        .filter((p) => p.ate === '2026-11-01'),
    ).toHaveLength(0);
  });

  it('peças paradas: quantos dias (padrão 30 no cabeçalho do arquivo)', async () => {
    const m = montar();
    const u = await abrir(m, 'Peças paradas');
    await screen.findAllByText(/Anel 1/);
    expect(dosRelatorios(m)[0]).not.toHaveProperty('dias');
    await u.type(screen.getByLabelText('Sem sair há quantos dias'), '60');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ dias: 60 }));
    await u.clear(screen.getByLabelText('Sem sair há quantos dias'));
    await waitFor(() => expect(dosRelatorios(m).at(-1)).not.toHaveProperty('dias'));
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    expect(texto(entregues[0]!.bytes)).toContain('Sem sair há quantos dias: 30');
  });

  it('compras: situação desfeitas e todas, fornecedor e categoria', async () => {
    const m = montar();
    const u = await abrir(m, 'Compras por período');
    await screen.findAllByText(/NF-1/);
    await u.selectOptions(screen.getByLabelText('Situação'), 'Só desfeitas');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ situacao: 'ESTORNADAS' }));
    await u.selectOptions(screen.getByLabelText('Situação'), 'Todas (desfeitas marcadas)');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ situacao: 'TODAS' }));
    await u.selectOptions(await screen.findByLabelText('Fornecedor'), 'Ouro Ltda');
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({ situacao: 'TODAS', fornecedorId: ID_FORN }),
    );
  });

  it('contagens: situação e só com diferença', async () => {
    const m = montar();
    const u = await abrir(m, 'Contagens do estoque e diferenças');
    await screen.findAllByText(/Categoria Anéis/);
    await u.selectOptions(screen.getByLabelText('Situação'), 'Fechadas');
    await u.selectOptions(screen.getByLabelText('Diferença'), 'Só com diferença');
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({ situacao: 'FECHADO', divergencia: 'SIM' }),
    );
  });

  it('a conferência do estoque só tem a busca (a API não aceita mais nada)', async () => {
    const m = montar();
    await abrir(m, 'Conferência do estoque');
    await screen.findAllByText(/Anel 1/);
    expect(screen.queryByLabelText('Situação')).toBeNull();
    expect(screen.queryByLabelText('Fornecedor')).toBeNull();
    expect(screen.getByLabelText(/Buscar/)).toBeInTheDocument();
  });
});

describe('Relatórios de estoque e de compras: foto, totais e compras desfeitas', () => {
  it('toda linha de produto tem a foto ou o marcador "Sem foto", e nada trava', async () => {
    const m = montar({
      'relatorios.cadastros': (p) => {
        const r = servidor('estoque-posicao', 2)(p);
        if (r.ok) (r.dados as { itens: Linha[] }).itens[1]!.fotoPrincipalHash = 'a'.repeat(64);
        return r;
      },
    });
    await abrir(m, 'Estoque agora, com valor a custo');
    const linha1 = await screen.findByRole('row', { name: /Anel 1/ });
    expect(within(linha1).getByText('Sem foto')).toBeInTheDocument();
    const linha2 = await screen.findByRole('row', { name: /Anel 2/ });
    expect(linha2).toBeInTheDocument();
  });

  it('o valor a custo vem da API (nunca é conta do cliente) e mostra as três moedas', async () => {
    const m = montar();
    await abrir(m, 'Estoque agora, com valor a custo');
    const linha = await screen.findByRole('row', { name: /Anel 1/ });
    // O valor a custo da linha é o que a API mandou (50,00), e não saldo × custo calculado aqui.
    expect(within(linha).getByText('R$ 50,00')).toBeInTheDocument();
    expect(within(linha).getByText(/US\$ 9,09 · Gs\. 55\.866/)).toBeInTheDocument();
    const totais = screen.getByRole('region', { name: 'Totais' });
    await waitFor(() => expect(totais.textContent).toContain('15 peças · R$ 150,00'));
    expect(within(totais).getByText('Peças no estoque e valor a custo')).toBeInTheDocument();
  });

  it('compra desfeita aparece marcada e fica fora do total em Dólar e Guarani', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            { ...LINHAS['compras-por-compra']!(1) },
            {
              ...LINHAS['compras-por-compra']!(2),
              id: '5e14e45f-ceea-4672-a1b9-3a1a0f7c4d12',
              total: '50.00',
              situacao: 'ESTORNADA',
              estornada: true,
              motivoDoEstorno: 'Peças erradas',
            },
          ],
          total: 2,
          pagina: 0,
          tamanho: 20,
          totais: [
            {
              chave: 'todos',
              rotulo: 'Compras listadas',
              quantidade: 2,
              valorEmReais: null,
              pecas: null,
            },
            { chave: 'registradas', rotulo: 'x', quantidade: 1, valorEmReais: '100.00', pecas: 10 },
            { chave: 'estornadas', rotulo: 'y', quantidade: 1, valorEmReais: '50.00', pecas: 10 },
          ],
        },
      }),
    });
    const u = await abrir(m, 'Compras por período');
    const desfeita = await screen.findByRole('row', { name: /NF-2/ });
    expect(within(desfeita).getByText('Desfeita')).toBeInTheDocument();
    expect(within(desfeita).getByText('Peças erradas')).toBeInTheDocument();
    const totais = screen.getByRole('region', { name: 'Totais' });
    // 100,00 ÷ 5,5013 = US$ 18,18: a desfeita (50,00) não entra na soma.
    await within(totais).findByText(/US\$ 18,18/);
    expect(within(totais).getAllByText(/R\$ 100,00/).length).toBeGreaterThan(0);
    expect(within(totais).getByText('Compras registradas (valem no total)')).toBeInTheDocument();
    expect(within(totais).getByText('Compras desfeitas (fora do total)')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    expect(texto(entregues[0]!.bytes)).toContain('Desfeita');
  });

  it('"Ver os registros" pede o recorte do total à API', async () => {
    const m = montar();
    const u = await abrir(m, 'Estoque agora, com valor a custo');
    await screen.findByRole('row', { name: /Anel 1/ });
    await u.click(
      screen.getByRole('button', { name: 'Ver os registros: Peças no estoque e valor a custo' }),
    );
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({ recorte: 'valor-a-custo' }),
    );
  });
});

describe('Relatórios de estoque e de compras: abrir uma linha', () => {
  it('compra → peças da compra, com voltar', async () => {
    const m = montar();
    const u = await abrir(m, 'Compras por período');
    await screen.findByRole('row', { name: /NF-1/ });
    await u.click(screen.getAllByRole('button', { name: /^Ver as peças/ })[0]!);
    await screen.findByRole('heading', { name: 'Peças da compra', level: 1 });
    expect(
      await screen.findByText(/Mostrando só: Compra de 05\/11\/2026 — Ouro Ltda\./),
    ).toBeInTheDocument();
    expect(dosRelatorios(m).at(-1)).toMatchObject({
      relatorio: 'compras-pecas',
      compraId: ID_COMPRA,
    });
    expect(screen.getByRole('row', { name: /Anel 1/ })).toBeInTheDocument();
    // O arquivo das peças leva a mesma compra.
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    expect(texto(entregues[0]!.bytes)).toContain('Compra de 05/11/2026 — Ouro Ltda');
    expect(
      dosRelatorios(m)
        .filter((p) => p.tamanho === 100)
        .at(-1),
    ).toMatchObject({
      compraId: ID_COMPRA,
    });
    await u.click(screen.getByRole('button', { name: 'Voltar ao relatório' }));
    await screen.findByRole('heading', { name: 'Compras por período', level: 1 });
  });

  it('contagem → só as peças daquela contagem', async () => {
    const m = montar();
    const u = await abrir(m, 'Contagens do estoque e diferenças');
    await screen.findAllByText(/Categoria Anéis/);
    await u.click(screen.getAllByRole('button', { name: /^Ver só esta contagem/ })[0]!);
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({
        relatorio: 'estoque-contagens',
        inventarioId: ID_CONTAGEM,
      }),
    );
    expect(await screen.findByText(/Mostrando só: Contagem de /)).toBeInTheDocument();
  });

  it('peça → histórico de custo dela', async () => {
    const m = montar();
    const u = await abrir(m, 'Estoque agora, com valor a custo');
    await screen.findByRole('row', { name: /Anel 1/ });
    await u.click(screen.getAllByRole('button', { name: /^Ver o histórico de custo/ })[0]!);
    await screen.findByRole('heading', { name: 'Histórico de custo das peças', level: 1 });
    expect(dosRelatorios(m).at(-1)).toMatchObject({
      relatorio: 'compras-custos',
      produtoId: ID_PROD,
    });
  });
});

describe('Relatórios de estoque e de compras: textos', () => {
  it('nenhuma tela mostra palavra técnica nem termo proibido do glossário (nem o texto do servidor)', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    const telas: string[] = [];
    for (const [, titulo] of RELATORIOS) {
      await u.click(await screen.findByRole('button', { name: titulo }));
      await screen.findByRole('heading', { name: titulo, level: 1 });
      await screen.findByRole('region', { name: 'Totais' });
      await new Promise((r) => setTimeout(r, 20));
      telas.push(document.querySelector('main')?.textContent ?? document.body.textContent ?? '');
    }
    await u.click(await screen.findByRole('button', { name: 'Compras por período' }));
    await u.click((await screen.findAllByRole('button', { name: /^Ver as peças/ }))[0]!);
    await screen.findByRole('heading', { name: 'Peças da compra', level: 1 });
    telas.push(document.body.textContent ?? '');
    const tudo = telas.join('\n').toLowerCase();
    for (const p of [
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
      'estornad',
      'inventário',
      'inventario',
      'saldo',
      'divergência',
      'divergencia',
      'abaixo do mínimo',
      'avaria',
      'estoque_',
      'conciliação',
    ]) {
      expect(tudo, `"${p}" não pode aparecer`).not.toContain(p);
    }
    const bloco = telas.join('\n');
    for (const p of [
      'ENTRADA',
      'SAIDA',
      'AVARIA',
      'AJUSTE',
      'EM_ANDAMENTO',
      'FECHADO',
      'CANCELADO',
      'REGISTRADA',
      'ESTORNADA',
      'ESTORNO',
      'MANUAL',
      'INVENTARIO',
    ]) {
      expect(bloco, `"${p}" não pode aparecer`).not.toContain(p);
    }
  });
});

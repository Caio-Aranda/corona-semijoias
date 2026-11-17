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
import { abrirAreaDoMenu, abrirRelatorioPeloMenu } from '../../apoio-de-testes';
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

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const ANEL = {
  id: '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11',
  nome: 'Anel Solitário',
  referencia: 'R-10',
  codigoPrincipal: '0012',
  categoria: 'Anéis',
  fornecedor: 'Ouro Ltda',
  precoVenda: '1234.56',
  precoCusto: '10.00',
  estoqueMinimo: 2,
  estoqueAtual: 5,
  ativo: true,
};
const BRINCO = {
  ...ANEL,
  id: '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99',
  nome: 'Brinco',
  precoVenda: '1.00',
  precoCusto: '0.50',
};

const TOTAIS_DE_PRODUTOS = [
  { chave: 'todos', rotulo: 'Produtos', quantidade: 2, valorEmReais: null },
  { chave: 'ativos', rotulo: 'Ativos', quantidade: 2, valorEmReais: null },
  { chave: 'inativos', rotulo: 'Inativos', quantidade: 0, valorEmReais: null },
  {
    chave: 'preco-venda',
    rotulo: 'Soma dos preços de venda',
    quantidade: 2,
    valorEmReais: '1235.56',
  },
  { chave: 'custo', rotulo: 'Soma dos custos', quantidade: 2, valorEmReais: '10.50' },
];

function relatorioDeProdutos(itens: unknown[], recorte?: string) {
  return {
    ok: true as const,
    dados: {
      itens,
      total: itens.length,
      pagina: 0,
      tamanho: 20,
      totais: TOTAIS_DE_PRODUTOS,
      recorte: recorte ?? null,
    },
  };
}

function montar(manipuladores: Record<string, Manipulador> = {}, comCotacao = true) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () =>
      comCotacao
        ? {
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
          }
        : { ok: true, dados: { disponivel: false } },
    'categorias.listar': () => pagina([{ id: ID_CAT, version: 0, nome: 'Anéis' }]),
    'fornecedores.listar': () => pagina([{ id: ID_FORN, version: 0, razaoSocial: 'Ouro Ltda' }]),
    'relatorios.cadastros': (p) =>
      relatorioDeProdutos(
        [ANEL, BRINCO],
        (p.parametros as { recorte?: string } | undefined)?.recorte,
      ),
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
  m.chamadas
    .filter((c) => c.operacao === 'relatorios.cadastros')
    .map((c) => c.parametros as Record<string, unknown>);

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

describe('Relatórios de cadastro: menu e tabela', () => {
  it('o menu tem um item para cada relatório, com o nome do glossário', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    const cartoes = await abrirAreaDoMenu(u, 'cadastros');
    for (const nome of [
      'Vendedores: limite e situação',
      'Clientes cadastrados',
      'Fornecedores cadastrados',
      'Produtos e custos',
      'Produtos sem foto',
      'Produtos sem código de barras',
      'Códigos repetidos ou parecidos',
      'Aniversariantes do mês',
      'Mudanças nos produtos',
      'Resultado das importações',
    ]) {
      expect(within(cartoes).getByText(nome)).toBeInTheDocument();
    }
  });

  it('mostra o custo e as três moedas pela cotação de hoje', async () => {
    const m = montar();
    await abrir(m, 'Produtos e custos');
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(linha).getByText('R$ 1.234,56')).toBeInTheDocument();
    expect(within(linha).getByText(/US\$ 224,41 · Gs\. 1\.379\.397/)).toBeInTheDocument();
    expect(within(linha).getByText('R$ 10,00')).toBeInTheDocument();
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'produtos', pagina: 0, tamanho: 20 });
  });

  it('sem cotação, mostra só o Real e não trava', async () => {
    const m = montar({}, false);
    await abrir(m, 'Produtos e custos');
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    expect(within(linha).getByText('R$ 1.234,56')).toBeInTheDocument();
    expect(within(linha).queryByText(/US\$/)).toBeNull();
  });

  it('filtros viram parâmetros do relatório e valem na hora', async () => {
    const m = montar();
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    await u.selectOptions(screen.getByLabelText('Situação'), 'TODOS');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ situacao: 'TODOS' }));
    await u.selectOptions(await screen.findByLabelText('Categoria'), 'Anéis');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ categoriaId: ID_CAT }));
  });

  it('produtos: estoque, faixa de preço e foto viram parâmetros (valor pela metade não vira filtro)', async () => {
    const m = montar();
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    await u.selectOptions(screen.getByLabelText('Estoque'), 'Abaixo do mínimo');
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({ estoque: 'ABAIXO_DO_MINIMO' }),
    );
    await u.selectOptions(screen.getByLabelText('Foto'), 'Só sem foto');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ foto: 'SEM' }));
    await u.type(screen.getByLabelText('Preço de venda a partir de (R$)'), '10,5');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ precoDe: '10.5' }));
    for (const p of dosRelatorios(m)) {
      expect(typeof p.precoDe === 'string' ? /^\d+(\.\d{1,2})?$/.test(p.precoDe) : true).toBe(true);
    }
  });

  it('vendedores: faixa do limite e com restrição; fornecedores e clientes: filtros de vínculo', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: { itens: [], total: 0, pagina: 0, tamanho: 20, totais: [] },
      }),
    });
    const u = await abrir(m, 'Vendedores: limite e situação');
    await u.type(await screen.findByLabelText('Limite a partir de (R$)'), '200');
    await u.selectOptions(screen.getByLabelText('Com restrição'), 'Só com restrição');
    await waitFor(() =>
      expect(dosRelatorios(m).at(-1)).toMatchObject({ limiteDe: '200', comRestricao: 'SIM' }),
    );
    await abrirRelatorioPeloMenu(u, 'Fornecedores cadastrados');
    await u.selectOptions(
      await screen.findByLabelText('Produtos ativos'),
      'Só quem não tem produtos',
    );
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ comProdutos: 'NAO' }));
    await abrirRelatorioPeloMenu(u, 'Clientes cadastrados');
    await u.selectOptions(await screen.findByLabelText('Vendedor responsável'), 'Só sem vendedor');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ comVendedor: 'NAO' }));
  });

  it('resultado das importações: filtros de tipo e resultado, e a linha recusada mostra o motivo', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              id: ID_CAT,
              tipoNaTela: 'Planilha de produtos',
              arquivo: 'produtos.xlsx',
              referencia: 'Linha 4',
              resultadoNaTela: 'Recusado',
              motivo: 'Preço de venda inválido',
              quem: 'Maria',
              quando: '2026-11-10T12:00:00Z',
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [
            {
              chave: 'todos',
              rotulo: 'Linhas e fotos importadas',
              quantidade: 1,
              valorEmReais: null,
            },
          ],
        },
      }),
    });
    const u = await abrir(m, 'Resultado das importações');
    expect(await screen.findByText('Preço de venda inválido')).toBeInTheDocument();
    await u.selectOptions(screen.getByLabelText('Resultado'), 'Recusado');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ resultado: 'RECUSADO' }));
    await u.selectOptions(screen.getByLabelText('O que foi importado'), 'Fotos em massa');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ tipo: 'FOTOS' }));
  });

  it('período invertido mostra o aviso e não busca de novo', async () => {
    const m = montar();
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    const visiveis = () => dosRelatorios(m).filter((p) => p.tamanho === 20).length;
    await u.type(screen.getByLabelText('De'), '2026-11-10');
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ de: '2026-11-10' }));
    const antes = visiveis();
    await u.type(screen.getByLabelText('Até'), '2026-11-01');
    expect(
      await screen.findByText('A data final não pode ser antes da data inicial.'),
    ).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 400));
    expect(visiveis()).toBe(antes);
  });

  it('erro de internet aparece em português simples', async () => {
    const m = montar({ 'relatorios.cadastros': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m, 'Produtos e custos');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
  });
});

describe('Relatórios de cadastro: totais e detalhamento', () => {
  it('o rodapé fecha com as linhas: total, soma em Dólar e em Guarani das linhas convertidas', async () => {
    const m = montar();
    await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    const rodape = screen.getByRole('region', { name: 'Totais' });
    expect(within(rodape).getByText('Produtos').nextElementSibling?.textContent).toBe('2');
    // R$ 1.234,56 → US$ 224,41; R$ 1,00 → US$ 0,18; soma = US$ 224,59 (não a conversão do total).
    // Guarani: 1.379.397 + 1.117 = 1.380.514.
    await waitFor(() => {
      const venda = within(rodape).getByText('Soma dos preços de venda').nextElementSibling;
      expect(venda?.textContent).toContain('R$ 1.235,56');
      expect(venda?.textContent).toContain('US$ 224,59');
      expect(venda?.textContent).toContain('Gs. 1.380.514');
    });
    // O servidor foi chamado com páginas de 100 para somar as linhas.
    expect(dosRelatorios(m).some((p) => p.tamanho === 100)).toBe(true);
  });

  it('"Ver os registros" abre o detalhamento daquele total e dá para voltar', async () => {
    const m = montar({
      'relatorios.cadastros': (p) => {
        const recorte = (p.parametros as { recorte?: string }).recorte;
        return relatorioDeProdutos(recorte === 'inativos' ? [] : [ANEL, BRINCO], recorte);
      },
    });
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    await u.click(screen.getByRole('button', { name: 'Ver os registros: Inativos' }));
    await waitFor(() => expect(dosRelatorios(m).at(-1)).toMatchObject({ recorte: 'inativos' }));
    expect(await screen.findByText('Mostrando só: Inativos.')).toBeInTheDocument();
    expect(await screen.findByText('Nenhum produto com estes filtros.')).toBeInTheDocument();
    // O rodapé continua mostrando o relatório inteiro.
    expect(screen.getByText('Soma dos custos')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Ver o relatório inteiro' }));
    await screen.findByText('Anel Solitário');
  });
});

describe('Relatórios de cadastro: exportação', () => {
  const texto = (b: Uint8Array) => new TextDecoder().decode(b);

  it('CSV com cabeçalho, três moedas, data da cotação e totais; o arquivo traz todas as linhas', async () => {
    const m = montar();
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const arquivo = entregues[0]!;
    expect(arquivo.nome).toMatch(/^produtos-\d{4}-\d{2}-\d{2}\.csv$/);
    const csv = texto(arquivo.bytes);
    expect(csv).toContain('Corona Semijoias');
    expect(csv).toContain('Produtos e custos');
    expect(csv).toContain('Filtros: Situação: Só ativos');
    expect(csv).toContain('por Maria');
    expect(csv).toContain('Cotação do Banco Central de 06/11/2026.');
    expect(csv).toContain('Preço de custo (US$)');
    expect(csv).toContain('Data da cotação');
    expect(csv).toContain(
      'Anel Solitário;R-10;0012;Anéis;Ouro Ltda;2;5;1234,56;224,41;1379397;10,00',
    );
    expect(csv).toContain('06/11/2026');
    expect(csv).toContain('Soma dos preços de venda');
    // O arquivo pediu o relatório inteiro, em páginas de 100, sem recorte.
    const completas = dosRelatorios(m).filter((p) => p.tamanho === 100);
    expect(completas.length).toBeGreaterThan(0);
    expect(completas.every((p) => p.recorte === undefined)).toBe(true);
    expect(await screen.findByText(/O arquivo produtos-.*\.csv foi preparado/)).toBeInTheDocument();
  });

  it('Excel e PDF também saem, com a extensão certa', async () => {
    const m = montar();
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    await u.click(screen.getByRole('button', { name: 'Baixar em Excel' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    expect(entregues[0]!.nome).toMatch(/\.xlsx$/);
    expect([...entregues[0]!.bytes.slice(0, 2)]).toEqual([0x50, 0x4b]);
    await u.click(screen.getByRole('button', { name: 'Baixar em PDF' }));
    await waitFor(() => expect(entregues).toHaveLength(2));
    expect(entregues[1]!.nome).toMatch(/\.pdf$/);
    expect(texto(entregues[1]!.bytes.slice(0, 5))).toBe('%PDF-');
  });

  it('exportar sem nada para mostrar avisa e não gera arquivo', async () => {
    const m = montar({ 'relatorios.cadastros': () => relatorioDeProdutos([]) });
    const u = await abrir(m, 'Produtos e custos');
    await screen.findByText('Nenhum produto com estes filtros.');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    expect(
      await screen.findByText('Não há nada para exportar com estes filtros.'),
    ).toBeInTheDocument();
    expect(entregues).toHaveLength(0);
  });
});

describe('Relatórios de cadastro: os outros relatórios', () => {
  it('produtos sem foto usa a rota que já existia, só com ativos e inativos', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              id: ANEL.id,
              nome: 'Anel Solitário',
              referencia: 'R-10',
              codigoPrincipal: '0012',
              categoriaId: ID_CAT,
              fornecedorId: ID_FORN,
              ativo: true,
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
        },
      }),
    });
    await abrir(m, 'Produtos sem foto');
    const linha = await screen.findByRole('row', { name: /Anel Solitário/ });
    // Os nomes da categoria e do fornecedor vêm das listas, não do código interno.
    await waitFor(() => expect(within(linha).getByText('Anéis')).toBeInTheDocument());
    expect(within(linha).getByText('Ouro Ltda')).toBeInTheDocument();
    expect(
      within(screen.getByLabelText('Situação')).queryByRole('option', { name: 'Todos' }),
    ).toBeNull();
    expect(dosRelatorios(m)[0]).toMatchObject({ relatorio: 'sem-foto' });
    const rodape = screen.getByRole('region', { name: 'Totais' });
    expect(within(rodape).getByText('Produtos sem foto')).toBeInTheDocument();
  });

  it('aniversariantes pede o mês de hoje e avisa que são só vendedores', async () => {
    const m = montar({
      'relatorios.cadastros': () => ({
        ok: true,
        dados: {
          itens: [
            {
              id: ANEL.id,
              nome: 'Ana',
              dia: 31,
              mes: 3,
              dataNascimento: '1990-03-31',
              whatsapp: '(11) 91234-5678',
              telefone: null,
              praca: 'Centro',
              ativo: true,
            },
          ],
          total: 1,
          pagina: 0,
          tamanho: 20,
          totais: [
            { chave: 'todos', rotulo: 'Aniversariantes', quantidade: 1, valorEmReais: null },
          ],
        },
      }),
    });
    await abrir(m, 'Aniversariantes do mês');
    const linha = await screen.findByRole('row', { name: /Ana/ });
    // A data de calendário não anda de dia: 31/03/1990, sem deslocar para 30/03.
    expect(within(linha).getByText('31/03/1990')).toBeInTheDocument();
    expect(screen.getByText(/Só vendedores/)).toBeInTheDocument();
    expect(typeof dosRelatorios(m)[0]!.mes).toBe('number');
  });

  it('nenhuma tela mostra palavras técnicas ou do bloco proibido', async () => {
    const m = montar();
    await abrir(m, 'Produtos e custos');
    await screen.findByText('Anel Solitário');
    const tela = document.body.textContent ?? '';
    for (const proibida of ['remessa', 'sync', 'payload', 'token', 'null', 'undefined', 'UUID']) {
      expect(tela.toLowerCase()).not.toContain(proibida.toLowerCase());
    }
  });
});

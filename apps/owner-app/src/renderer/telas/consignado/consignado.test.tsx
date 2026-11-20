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
import { TEXTOS } from '../../textos';
import { TEXTOS_DA_INICIAL } from '../../textos-inicial';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';
import { TEXTOS_DE_CONSIGNADO } from '../../textos-consignado';
import { TEXTOS_DE_DEVOLUCOES } from '../../textos-devolucoes';
import { TEXTOS_DE_SINCRONIZACAO } from '../../textos-sincronizacao';

const entregues: { nome: string; tipo: string; bytes: Uint8Array }[] = [];
vi.mock('../../nucleo/arquivos', async (original) => ({
  ...(await original<typeof import('../../nucleo/arquivos')>()),
  entregarArquivo: (nome: string, tipo: string, bytes: Uint8Array) => {
    entregues.push({ nome, tipo, bytes });
  },
}));

const T = TEXTOS_DE_CONSIGNADO;
const ID_MALETA = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_SEMANA = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_ANEL = '4e5f28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';
const ID_CLIENTE = '5f6a28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';
const ID_NOTA = '6a7b28ba-2fa1-41d3-b9a5-2c3a9a8f6b77';
const ID_ALERTA = '7b8c28ba-2fa1-41d3-b9a5-2c3a9a8f6b88';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const maleta = (status: string) => ({
  id: ID_MALETA,
  codigo: '0007',
  version: 3,
  vendedorId: ID_VEND,
  vendedorNome: 'Joana Representante',
  semanaId: ID_SEMANA,
  semanaNumero: 12,
  pracaId: ID_SEMANA,
  pracaNome: 'Ponta Porã',
  tipo: 'NOVA',
  status,
  totalPecas: 10,
  totalValor: 1000,
  criadaEm: '2026-11-09T12:00:00Z',
  enviadaEm: '2026-11-10T13:00:00Z',
  itens: [],
  avisos: [],
});

const anel = {
  id: ID_ANEL,
  version: 1,
  nome: 'Anel Solitário',
  referencia: 'R',
  categoriaId: ID_SEMANA,
  precoVenda: 100,
  precoCusto: 10,
  estoqueMinimo: 0,
  estoqueAtual: 5,
  ativo: true,
  fotoPrincipal: null,
  codigos: [{ id: ID_ANEL, codigo: 'F-100', tipo: 'FORNECEDOR', principal: true, ativo: true }],
};

const RELATORIO = {
  itens: [
    {
      id: ID_ANEL,
      nome: 'Anel Solitário',
      enviadas: 10,
      devolvidasPelosClientes: 2,
      vendidas: 3,
      comClientes: 2,
      comQuem: 'Maria Cliente (nota 65-21): 2',
      esperadoNaMaleta: 5,
      bipadas: 3,
      diferenca: -2,
    },
  ],
  total: 1,
  pagina: 0,
  tamanho: 20,
  totais: [
    { chave: 'enviadas', rotulo: 'x', quantidade: 1, pecas: 10 },
    { chave: 'devolvidas', rotulo: 'x', quantidade: 1, pecas: 2 },
    { chave: 'vendidas', rotulo: 'x', quantidade: 1, pecas: 3 },
    { chave: 'comClientes', rotulo: 'x', quantidade: 1, pecas: 2 },
    { chave: 'bipadas', rotulo: 'x', quantidade: 1, pecas: 3 },
    { chave: 'diferenca', rotulo: 'x', quantidade: 1, pecas: -2 },
    {
      chave: 'vd',
      rotulo: 'x',
      quantidade: 1,
      valorEmReais: '300.00',
      valorEmDolar: '50.00',
      valorEmGuarani: '250000',
    },
    { chave: 'comissao', rotulo: 'x', quantidade: 1, valorEmReais: '90.00' },
    { chave: 'recebido', rotulo: 'x', quantidade: 1, valorEmReais: '100.00' },
    { chave: 'pendencias', rotulo: 'x', quantidade: 1, valorEmReais: '110.00' },
  ],
};

const leitura = (situacao: string) => ({
  remessaId: ID_MALETA,
  situacaoDoFechamento: situacao,
  iniciadoEm: '2026-11-19T12:00:00Z',
  concluidoEm: situacao === 'CONCLUIDO' ? '2026-11-19T13:00:00Z' : null,
  pecas: [
    {
      produtoId: ID_ANEL,
      nome: 'Anel Solitário',
      comClientes: 2,
      diferenca: -2,
      aindaComClientes: [],
    },
  ],
  valores: {
    vd: '300.00',
    comissaoDosClientes: '90.00',
    recebido: '100.00',
    pendenciasAReceber: '110.00',
  },
});

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'painel.semanal': () => ({ ok: false, motivo: 'erro' }),
    'telas.ler': () => ({ ok: true, dados: { acertoDoVendedorVisivel: false } }),
    'vendedores.escolha': () =>
      pagina([{ id: ID_VEND, nome: 'Joana Representante', codigo: 7, ativo: true }]),
    'maletas.listar': (p) =>
      (p.parametros as { status: string }).status === 'ENVIADA'
        ? pagina([maleta('ENVIADA')])
        : pagina([]),
    'produtos.porCodigo': (p) =>
      (p.parametros as { codigo: string }).codigo.toLowerCase() === 'f-100'
        ? { ok: true, dados: anel }
        : { ok: false, motivo: 'erro', status: 404, mensagem: 'Não achou.' },
    'produtos.listar': () => pagina([]),
    ...manipuladores,
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

async function abrirPeloMenu(m: ReturnType<typeof montar>, item: string) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const menu = await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo });
  await u.click(within(menu).getByRole('button', { name: item }));
  return u;
}

beforeEach(() => {
  window.location.hash = '';
  entregues.length = 0;
});

describe('O acerto antigo fica escondido (ADR-0016, item 15)', () => {
  it('com a configuração em "não", o menu e a tela inicial não têm o acerto, e as rotas continuam no servidor', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    const menu = await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo });
    expect(within(menu).queryByText(TEXTOS_DE_ACERTOS.menu.grupo)).toBeNull();
    expect(within(menu).queryByRole('button', { name: TEXTOS_DE_ACERTOS.menu.acertos })).toBeNull();
    expect(
      within(menu).queryByRole('button', { name: TEXTOS_DE_ACERTOS.menu.comissao }),
    ).toBeNull();
    expect(
      within(menu).queryByRole('button', { name: TEXTOS_DE_ACERTOS.menu.pendencias }),
    ).toBeNull();
    // Em vez disso, o menu tem o Fechamento da maleta e o grupo do consignado.
    expect(within(menu).getByRole('button', { name: T.menu.fechamento })).toBeInTheDocument();
    expect(within(menu).getByText(T.menu.grupo)).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: TEXTOS_DA_INICIAL.atalhos.acerto.nome }),
    ).toBeNull();
    expect(m.chamadas.some((c) => c.operacao === 'telas.ler')).toBe(true);
  });

  it('também esconde o fluxo antigo de venda ao consumidor: devoluções e créditos e vendas de computador bloqueado', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    const menu = await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo });
    expect(within(menu).queryByText(TEXTOS_DE_DEVOLUCOES.menu.grupo)).toBeNull();
    for (const nome of [
      TEXTOS_DE_DEVOLUCOES.menu.autorizar,
      TEXTOS_DE_DEVOLUCOES.menu.revisar,
      TEXTOS_DE_DEVOLUCOES.menu.devolucoes,
      TEXTOS_DE_DEVOLUCOES.menu.trocas,
      TEXTOS_DE_DEVOLUCOES.menu.creditos,
      TEXTOS_DE_DEVOLUCOES.menu.garantia,
      TEXTOS_DE_SINCRONIZACAO.menu.vendas,
    ]) {
      expect(within(menu).queryByRole('button', { name: nome })).toBeNull();
    }
    // O resto do grupo dos computadores continua.
    expect(
      within(menu).getByRole('button', { name: TEXTOS_DE_SINCRONIZACAO.menu.computadores }),
    ).toBeInTheDocument();
    expect(
      within(menu).getByRole('button', { name: TEXTOS_DE_SINCRONIZACAO.menu.envios }),
    ).toBeInTheDocument();
  });

  it('as rotas escondidas do menu continuam abrindo pelo endereço (nada é apagado)', async () => {
    window.location.hash = '#/devolucoes/trocas';
    const m = montar({ 'trocas.listar': () => pagina([]) });
    render(<App ponte={m.ponte} />);
    await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo });
    expect(
      await screen.findByRole('heading', { name: TEXTOS_DE_DEVOLUCOES.trocas.titulo }),
    ).toBeInTheDocument();
  });

  it('o endereço do acerto cai no início enquanto estiver escondido', async () => {
    window.location.hash = '#/acertos/lista';
    const m = montar();
    render(<App ponte={m.ponte} />);
    await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo });
    expect(screen.queryByRole('heading', { name: TEXTOS_DE_ACERTOS.titulo })).toBeNull();
  });

  it('com a configuração em "sim", tudo volta ao menu', async () => {
    const m = montar({
      'telas.ler': () => ({ ok: true, dados: { acertoDoVendedorVisivel: true } }),
    });
    render(<App ponte={m.ponte} />);
    const menu = await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo });
    expect(
      within(menu).getByRole('button', { name: TEXTOS_DE_DEVOLUCOES.menu.trocas }),
    ).toBeInTheDocument();
    expect(
      within(menu).getByRole('button', { name: TEXTOS_DE_SINCRONIZACAO.menu.vendas }),
    ).toBeInTheDocument();
    expect(
      within(menu).getByRole('button', { name: TEXTOS_DE_ACERTOS.menu.acertos }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: TEXTOS_DA_INICIAL.atalhos.acerto.nome }),
    ).toBeInTheDocument();
  });
});

describe('Fechamento da maleta (§23.10)', () => {
  it('começa, lê as peças, mostra o relatório e conclui depois de confirmar', async () => {
    let situacao: 'nao-comecou' | 'EM_ANDAMENTO' | 'CONCLUIDO' = 'nao-comecou';
    const m = montar({
      'fechamento.ler': () =>
        situacao === 'nao-comecou'
          ? { ok: false, motivo: 'erro', status: 404, mensagem: 'Ainda não começou.' }
          : { ok: true, dados: leitura(situacao) },
      'fechamento.iniciar': () => {
        situacao = 'EM_ANDAMENTO';
        return { ok: true, dados: { fechamentoId: ID_MALETA } };
      },
      'fechamento.leituras': () => ({ ok: true, dados: { novas: 1 } }),
      'fechamento.concluir': () => {
        situacao = 'CONCLUIDO';
        return { ok: true, dados: null };
      },
      'relatorios.cadastros': () => ({ ok: true, dados: RELATORIO }),
    });
    const u = await abrirPeloMenu(m, T.menu.fechamento);

    // 1. Escolhe a maleta que voltou (a lista mostra o código da maleta).
    expect(
      await screen.findByRole('option', { name: /Maleta 0007, de Joana Representante/ }),
    ).toBeInTheDocument();
    await u.selectOptions(await screen.findByLabelText(T.fechamento.escolher), ID_MALETA);
    await u.click(screen.getByRole('button', { name: T.fechamento.abrir }));

    // 2. O fechamento ainda não começou: começa.
    await u.click(await screen.findByRole('button', { name: T.fechamento.comecar }));

    // 3. O relatório mostra tudo, com quem estão as peças que faltam.
    expect(await screen.findByText('Maria Cliente (nota 65-21): 2')).toBeInTheDocument();
    expect(
      screen.getByRole('columnheader', { name: 'Devolvidas pelos clientes' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Lidas na volta' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Diferença' })).toBeInTheDocument();
    // Duas abas: as peças ficam numa e o dinheiro na outra, sem se misturar.
    expect(screen.getByRole('tab', { name: 'Peças da maleta' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    const pecas = screen.getByRole('tabpanel', { name: 'Peças da maleta' });
    expect(within(pecas).getByText('Peças enviadas')).toBeInTheDocument();
    expect(within(pecas).getByText('Lidas na volta')).toBeInTheDocument();
    expect(within(pecas).queryByText('Valor vendido')).toBeNull();
    expect(screen.getByText(/Faltam 2 peças/)).toBeInTheDocument();
    await u.click(screen.getByRole('tab', { name: 'Dinheiro' }));
    const dinheiro = screen.getByRole('tabpanel', { name: 'Dinheiro' });
    expect(within(dinheiro).getByText('Valor vendido')).toBeInTheDocument();
    // Cada lançamento já vem convertido pela cotação do dia dele (9.8): o total mostra Dólar e Guarani.
    expect(within(dinheiro).getByText('US$ 50,00 · Gs. 250.000')).toBeInTheDocument();
    expect(within(dinheiro).getByText('Comissão dos clientes')).toBeInTheDocument();
    expect(within(dinheiro).getByText('Valor recebido')).toBeInTheDocument();
    expect(within(dinheiro).getByText('Pendências a receber')).toBeInTheDocument();
    expect(within(dinheiro).queryByText('Peças enviadas')).toBeNull();
    // Na aba do dinheiro não há tabela de peças.
    expect(screen.queryByText('Maria Cliente (nota 65-21): 2')).toBeNull();
    await u.click(screen.getByRole('tab', { name: 'Peças da maleta' }));
    expect(await screen.findByText('Maria Cliente (nota 65-21): 2')).toBeInTheDocument();

    // 4. Lê o código de uma peça: soma 1, com um id novo para o reenvio não duplicar.
    await u.type(screen.getByLabelText(/código/i), 'F-100{Enter}');
    await waitFor(() =>
      expect(m.chamadas.filter((c) => c.operacao === 'fechamento.leituras')).toHaveLength(1),
    );
    const envio = m.chamadas.find((c) => c.operacao === 'fechamento.leituras')!.parametros as {
      id: string;
      leituras: { id: string; produtoId: string; quantidade: number }[];
    };
    expect(envio.id).toBe(ID_MALETA);
    expect(envio.leituras).toHaveLength(1);
    expect(envio.leituras[0]).toMatchObject({ produtoId: ID_ANEL, quantidade: 1 });
    expect(envio.leituras[0]!.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByText(T.fechamento.lida('Anel Solitário'))).toBeInTheDocument();

    // 5. Concluir pede confirmação, e diz o que acontece.
    await u.click(screen.getByRole('button', { name: T.fechamento.concluir }));
    expect(screen.getByText(T.fechamento.confirmar.texto)).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'fechamento.concluir')).toBe(false);
    await u.click(screen.getByRole('button', { name: T.fechamento.confirmar.concluir }));
    await waitFor(() =>
      expect(m.chamadas.some((c) => c.operacao === 'fechamento.concluir')).toBe(true),
    );
    expect(await screen.findByText(T.fechamento.concluiu)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: T.fechamento.concluir })).toBeNull();
  });

  it('o relatório sai em CSV com as mesmas linhas e os totais da tela', async () => {
    const m = montar({
      'fechamento.ler': () => ({ ok: true, dados: leitura('EM_ANDAMENTO') }),
      'relatorios.cadastros': () => ({ ok: true, dados: RELATORIO }),
    });
    const u = await abrirPeloMenu(m, T.menu.fechamento);
    await u.selectOptions(await screen.findByLabelText(T.fechamento.escolher), ID_MALETA);
    await u.click(screen.getByRole('button', { name: T.fechamento.abrir }));
    await screen.findByText('Maria Cliente (nota 65-21): 2');
    await u.click(screen.getByRole('button', { name: 'Baixar em CSV' }));
    await waitFor(() => expect(entregues).toHaveLength(1));
    const csv = new TextDecoder().decode(entregues[0]!.bytes);
    expect(csv).toContain('Fechamento da maleta');
    expect(csv).toContain('Anel Solitário');
    expect(csv).toContain('Maria Cliente (nota 65-21): 2');
    expect(csv).toContain('Valor vendido');
    expect(csv).toContain('300,00');
    expect(csv).toContain('Pendências a receber');
    // Duas seções, uma depois da outra: peças da maleta e dinheiro.
    const iPecas = csv.indexOf('Peças da maleta');
    const iDinheiro = csv.indexOf('Dinheiro');
    expect(iPecas).toBeGreaterThan(-1);
    expect(iDinheiro).toBeGreaterThan(iPecas);
    expect(csv.indexOf('Lidas na volta;')).toBeLessThan(iDinheiro);
    expect(csv.indexOf('Valor vendido')).toBeGreaterThan(iDinheiro);
    expect(csv.indexOf('Pendências a receber')).toBeGreaterThan(iDinheiro);
    // O pedido ao servidor é pela maleta escolhida.
    const pedido = m.chamadas.find((c) => c.operacao === 'relatorios.cadastros')!.parametros as {
      relatorio: string;
      remessaId: string;
    };
    expect(pedido.relatorio).toBe('maleta-fechamento');
    expect(pedido.remessaId).toBe(ID_MALETA);
  });

  it('peça que o servidor recusa (fora da maleta) mostra o motivo em português e nada some', async () => {
    const m = montar({
      'fechamento.ler': () => ({ ok: true, dados: leitura('EM_ANDAMENTO') }),
      'fechamento.leituras': () => ({
        ok: false,
        motivo: 'erro',
        status: 422,
        mensagem: 'Esta peça não faz parte desta maleta.',
      }),
      'relatorios.cadastros': () => ({ ok: true, dados: RELATORIO }),
    });
    const u = await abrirPeloMenu(m, T.menu.fechamento);
    await u.selectOptions(await screen.findByLabelText(T.fechamento.escolher), ID_MALETA);
    await u.click(screen.getByRole('button', { name: T.fechamento.abrir }));
    await screen.findByText('Maria Cliente (nota 65-21): 2');
    await u.type(screen.getByLabelText(/código/i), 'F-100{Enter}');
    expect(await screen.findByText('Esta peça não faz parte desta maleta.')).toBeInTheDocument();
    expect(screen.getByText('Maria Cliente (nota 65-21): 2')).toBeInTheDocument();
  });

  it('peça que existe mas é de outra maleta diz que não é desta maleta, e de qual', async () => {
    const m = montar({
      'fechamento.ler': () => ({ ok: true, dados: leitura('EM_ANDAMENTO') }),
      'fechamento.leituras': () => ({
        ok: false,
        motivo: 'erro',
        status: 422,
        codigo: 'PECA_NAO_E_DESTA_MALETA',
        mensagem: 'Esta peça não é desta maleta. Ela está na maleta 0009.',
      }),
      'relatorios.cadastros': () => ({ ok: true, dados: RELATORIO }),
    });
    const u = await abrirPeloMenu(m, T.menu.fechamento);
    await u.selectOptions(await screen.findByLabelText(T.fechamento.escolher), ID_MALETA);
    await u.click(screen.getByRole('button', { name: T.fechamento.abrir }));
    await screen.findByText('Maria Cliente (nota 65-21): 2');
    await u.type(screen.getByLabelText(/código/i), 'F-100{Enter}');
    expect(
      await screen.findByText('Esta peça não é desta maleta. Ela está na maleta 0009.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/desativada ou foi excluída/)).toBeNull();
  });

  it('fechamento concluído só mostra o relatório, sem leitor nem botão de concluir', async () => {
    const m = montar({
      'maletas.listar': (p) =>
        (p.parametros as { status: string }).status === 'FECHADA'
          ? pagina([maleta('FECHADA')])
          : pagina([]),
      'fechamento.ler': () => ({ ok: true, dados: leitura('CONCLUIDO') }),
      'relatorios.cadastros': () => ({ ok: true, dados: RELATORIO }),
    });
    const u = await abrirPeloMenu(m, T.menu.fechamento);
    await u.selectOptions(await screen.findByLabelText(T.fechamento.escolher), ID_MALETA);
    await u.click(screen.getByRole('button', { name: T.fechamento.abrir }));
    await screen.findByText('Maria Cliente (nota 65-21): 2');
    expect(screen.getByText(/Fechamento concluído em/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: T.fechamento.concluir })).toBeNull();
    expect(screen.queryByLabelText(/código/i)).toBeNull();
  });
});

describe('Notas, semanas, clientes e avisos do consignado (só leitura)', () => {
  const NOTA = {
    id: ID_NOTA,
    numero: '0000065-21',
    vendedorId: ID_VEND,
    semanaId: ID_SEMANA,
    semana: 65,
    sequencia: 21,
    clienteId: ID_CLIENTE,
    cliente: 'Maria Cliente',
    saidaEm: '2026-11-10T13:00:00Z',
    dataCobranca: '2026-12-25',
    situacao: 'PENDENTE',
    itensDx: 5,
    valorDx: 500,
  };

  it('lista as notas com a situação em palavras da loja e abre o detalhe do acerto e dos recibos', async () => {
    const m = montar({
      'consignado.notas': () => ({ ok: true, dados: [NOTA] }),
      'consignado.nota': () => ({
        ok: true,
        dados: {
          nota: NOTA,
          linhas: [{ produto: 'Anel Solitário', dx: 5, precoUnitario: 100, produtoId: ID_ANEL }],
          acertos: [
            {
              id: ID_NOTA,
              semana: 65,
              acertadoEm: '2026-11-17T13:00:00Z',
              comissaoPercentual: 30,
              itensDx: 5,
              itensDv: 2,
              itensVd: 3,
              valorDx: 500,
              valorDv: 200,
              valorVd: 300,
              comissao: 90,
              bruto: 210,
              pendenciasAnteriores: 0,
              totalAReceber: 210,
              totalPago: 100,
              ficouDevendo: 110,
              saldoDoDebito: 110,
              estornado: false,
            },
          ],
          recibos: [
            {
              id: ID_ALERTA,
              semana: 65,
              sequencia: 53,
              pagoEm: '2026-11-17T13:00:00Z',
              total: 100,
              aAplicar: 0,
              estornado: false,
              formas: [{ forma: 'PIX', valor: 100 }],
            },
          ],
        },
      }),
    });
    const u = await abrirPeloMenu(m, T.menu.notas);
    const linha = (await screen.findByText('0000065-21')).closest('tr')!;
    expect(within(linha).getByText('Pendente')).toBeInTheDocument();
    expect(within(linha).getByText('Maria Cliente')).toBeInTheDocument();
    await u.click(within(linha).getByRole('button', { name: T.notas.verDaNota('0000065-21') }));
    expect(await screen.findByRole('heading', { name: 'Nota 0000065-21' })).toBeInTheDocument();
    expect(screen.getByText('Comissão (30%)')).toBeInTheDocument();
    expect(screen.getByText('Ficou devendo')).toBeInTheDocument();
    expect(screen.getByText(/R 0000065-53/)).toBeInTheDocument();
    expect(screen.getByText(/Pix: /)).toBeInTheDocument();
    // Só leitura: nada que crie, edite ou estorne.
    expect(screen.queryByRole('button', { name: /estornar|editar|salvar|excluir/i })).toBeNull();
  });

  it('as semanas do representante mostram os totais e levam às notas da semana', async () => {
    const m = montar({
      'consignado.semanas': () => ({
        ok: true,
        dados: [
          {
            id: ID_SEMANA,
            numero: 65,
            situacao: 'ABERTA',
            abertaEm: '2026-11-10T13:00:00Z',
            notas: 4,
            valorDx: 2000,
            valorVd: 900,
            comissao: 270,
            recebido: 400,
          },
        ],
      }),
      'consignado.notas': () => ({ ok: true, dados: [NOTA] }),
    });
    const u = await abrirPeloMenu(m, T.menu.semanas);
    await u.selectOptions(await screen.findByLabelText(T.semanas.representante), ID_VEND);
    const linha = (await screen.findByText(/Em andamento/)).closest('tr')!;
    expect(within(linha).getByText('4')).toBeInTheDocument();
    await u.click(within(linha).getByRole('button', { name: T.semanas.verNotasDa(65) }));
    expect(await screen.findByText('0000065-21')).toBeInTheDocument();
    const filtros = m.chamadas.filter((c) => c.operacao === 'consignado.notas').pop()!
      .parametros as { vendedorId: string; semanaId: string };
    expect(filtros).toMatchObject({ vendedorId: ID_VEND, semanaId: ID_SEMANA });
  });

  it('a ficha do cliente mostra os campos novos, comissão, pendência e notas, e as fotos só abrem quando a pessoa pede', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:foto');
    URL.revokeObjectURL = vi.fn();
    const m = montar({
      'clientes.listar': () =>
        pagina([
          {
            id: ID_CLIENTE,
            version: 1,
            nome: 'Maria Cliente',
            telefone: '67 99999-0000',
            ativo: true,
          },
        ]),
      'consignado.cliente': () => ({
        ok: true,
        dados: {
          clienteId: ID_CLIENTE,
          nome: 'Maria Cliente',
          pendencia: 110,
          notas: [NOTA],
          pecasComEle: [{ produto: 'Anel Solitário', quantidade: 2, notaId: ID_NOTA }],
          cadastro: {
            telefone: '67 99999-0000',
            whatsapp: '67 98888-0000',
            endereco: 'Rua A, 10, Centro, Ponta Porã - MS',
            documento: '1234567',
            documentoTipo: 'CI',
            indicadoPor: 'Ana',
            comoConheceu: 'Pela feira',
            comissaoPercentual: 35,
            representante: 'Joana Representante',
            temFoto: true,
            temFotoDoDocumentoFrente: true,
            temFotoDoDocumentoVerso: false,
          },
        },
      }),
      'consignado.fotoDoCliente': () => ({
        ok: true,
        dados: { nome: 'cliente.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1, 2, 3]) },
      }),
    });
    const u = await abrirPeloMenu(m, T.menu.clientes);
    await u.click(await screen.findByRole('button', { name: T.clientes.abrirDe('Maria Cliente') }));
    expect(
      await screen.findByRole('heading', { name: 'Ficha de Maria Cliente' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Rua A, 10, Centro, Ponta Porã - MS')).toBeInTheDocument();
    expect(screen.getByText('CI: 1234567')).toBeInTheDocument();
    expect(screen.getByText('Ana')).toBeInTheDocument();
    expect(screen.getByText('Pela feira')).toBeInTheDocument();
    expect(screen.getByText('35%')).toBeInTheDocument();
    expect(screen.getByText(/R\$\s?110,00/)).toBeInTheDocument();
    expect(screen.getByText('0000065-21')).toBeInTheDocument();
    // Foto de documento é dado pessoal: nada é baixado até a pessoa pedir.
    expect(m.chamadas.some((c) => c.operacao === 'consignado.fotoDoCliente')).toBe(false);
    // O verso ainda não chegou: o aviso é simples e a ficha continua inteira.
    expect(screen.getByText(T.clientes.ficha.semFoto)).toBeInTheDocument();
    await u.click(
      screen.getByRole('button', { name: T.clientes.ficha.verFoto(T.clientes.ficha.foto) }),
    );
    const img = await screen.findByRole('img', { name: T.clientes.ficha.foto });
    expect(img).toHaveAttribute('src', 'blob:foto');
    const pedida = m.chamadas.find((c) => c.operacao === 'consignado.fotoDoCliente')!.parametros;
    expect(pedida).toEqual({ id: ID_CLIENTE, tipo: 'cliente' });
  });

  it('os avisos do consignado ficam em português, e a dona marca como visto', async () => {
    let visto = false;
    const m = montar({
      'consignado.alertas': () => ({
        ok: true,
        dados: visto
          ? []
          : [
              {
                id: ID_ALERTA,
                tipo: 'PRECO_DIFERENTE_DA_MALETA',
                vendedorId: ID_VEND,
                criadoEm: '2026-11-17T13:00:00Z',
              },
            ],
      }),
      'consignado.alertaVisto': () => {
        visto = true;
        return { ok: true, dados: null };
      },
    });
    const u = await abrirPeloMenu(m, T.menu.avisos);
    expect(await screen.findByText(T.avisos.tipos.PRECO_DIFERENTE_DA_MALETA!)).toBeInTheDocument();
    await u.click(
      screen.getByRole('button', {
        name: T.avisos.marcarDe(T.avisos.tipos.PRECO_DIFERENTE_DA_MALETA!),
      }),
    );
    await waitFor(() =>
      expect(m.chamadas.find((c) => c.operacao === 'consignado.alertaVisto')?.parametros).toEqual({
        id: ID_ALERTA,
      }),
    );
    expect(await screen.findByText(T.avisos.vazio)).toBeInTheDocument();
  });
});

describe('Tela inicial em espanhol (tarefa 9.7)', () => {
  it('todos os avisos de "Lo que necesita su atención" saem em espanhol', async () => {
    const { definirIdioma } = await import('../../idioma/idioma');
    definirIdioma('es');
    try {
      const m = montar({
        'avisos.listar': () => ({
          ok: true,
          dados: {
            avisos: [
              {
                tipo: 'CONFERENCIA_NAO_RODOU',
                titulo: 'A conferência de hoje não rodou',
                texto:
                  'A conferência automática não rodou nas últimas 26 horas. Toque em Conferir agora.',
                dias: [],
              },
              {
                tipo: 'COTACAO_NAO_CHEGOU',
                titulo: 'As cotações de 17/11/2026 e 18/11/2026 não chegaram.',
                texto:
                  'Os valores aparecem só em reais por enquanto. O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.',
                dias: [],
              },
            ],
          },
        }),
      });
      render(<App ponte={m.ponte} />);
      expect(await screen.findByText('La verificación de hoy no se ejecutó')).toBeInTheDocument();
      expect(
        screen.getByText(/La verificación automática no se ejecutó en las últimas 26 horas/),
      ).toBeInTheDocument();
      expect(
        screen.getByText('Las cotizaciones de 17/11/2026 y 18/11/2026 no llegaron.'),
      ).toBeInTheDocument();
      expect(screen.queryByText(/A conferência/)).toBeNull();
      expect(screen.queryByText(/As cotações/)).toBeNull();
    } finally {
      definirIdioma('pt');
    }
  });
});

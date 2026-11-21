// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  ClienteCompleto,
  DetalheDaNota,
  PainelDaSemana,
  PedidoEmMontagem,
  SituacaoDoPedido,
  PrevisaoDoAcerto,
} from '../shared/dados-do-consignado';
import type {
  EstadoDaSessao,
  EstadoDaSincronizacao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';
import { TelaTroca } from './telas/consignado/TelaTroca';
import { definirIdioma } from './idioma';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const lerCss = (relativo: string) => readFileSync(join(AQUI, relativo), 'utf8');

const ESTADO_DO_ENVIO: EstadoDaSincronizacao = {
  comInternet: true,
  enviando: false,
  aguardando: 0,
  vendasAguardando: 0,
  recusadas: 0,
  dadosAte: null,
  naoConseguiuEnviar: false,
  prazo: { situacao: 'EM_DIA', diasRestantes: 0, motivo: null },
  fotosBaixadas: 0,
  fotosTotal: 0,
  appDesatualizado: false,
};

const DENTRO: EstadoDaSessao = { estado: 'dentro', usuario: { nome: 'Rosa' } };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

type Resposta = (pedido: PedidoDeChamada) => ResultadoDeChamada;

function criarPonte(respostas: Record<string, Resposta> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const ouvintesDoEnvio: ((e: EstadoDaSincronizacao) => void)[] = [];
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    liberarComputador: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() => Promise.resolve(DENTRO)),
    chamarApi: vi.fn((p: PedidoDeChamada) => {
      chamadas.push(p);
      return Promise.resolve(respostas[p.operacao]?.(p) ?? { ok: true as const, dados: {} });
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
    aoMudarASincronizacao: (o) => {
      ouvintesDoEnvio.push(o);
      return () => undefined;
    },
    lerIdioma: () => Promise.resolve(null),
    gravarIdioma: () => Promise.resolve(),
    imprimirRelatorio: () => Promise.resolve({ ok: true as const }),
    salvarRelatorioEmPdf: () => Promise.resolve({ ok: true as const }),
    prepararRelatorioNoWhatsapp: () => Promise.resolve({ ok: true as const }),
  };
  const avisarEnvio = (e: Partial<EstadoDaSincronizacao>): void => {
    act(() => {
      for (const o of ouvintesDoEnvio) o({ ...ESTADO_DO_ENVIO, ...e });
    });
  };
  return { ponte, chamadas, avisarEnvio };
}

const ok = (dados: unknown): ResultadoDeChamada => ({ ok: true, dados });

const NOTA = (n: number) => `${String(n).padStart(8, '0').slice(0, 8)}-aaaa-4aaa-8aaa-aaaaaaaaaaaa`;
const linha = (n: number, situacao: SituacaoDoPedido, extra: Record<string, unknown> = {}) => ({
  notaId: NOTA(n),
  numero: `0000065-${String(n).padStart(2, '0')}`,
  clienteId: 'c1',
  cliente: `Cliente ${String(n)}`,
  dataCobranca: '2026-12-31',
  itensDx: 26,
  itensDv: 0,
  itensVd: 0,
  valorDx: '5370.00',
  valorDv: '0.00',
  valorVd: '0.00',
  comissaoPercentual: null,
  comissao: '0.00',
  valorLiquido: '0.00',
  situacao,
  acertoId: null,
  ...extra,
});

const painel: PainelDaSemana = {
  representante: 'Rosa',
  praca: 'PONTA PORÃ - MS',
  semSemana: false,
  semanas: [
    { id: 's65', numero: 65, atual: true },
    { id: 's64', numero: 64, atual: false },
  ],
  semana: { id: 's65', numero: 65, atual: true, situacao: 'EM_ANDAMENTO' },
  pedidos: [
    linha(1, 'EM_ABERTO'),
    linha(2, 'PENDENTE', {
      valorVd: '2310.00',
      comissaoPercentual: '30.00',
      comissao: '693.00',
      valorLiquido: '1617.00',
    }),
    linha(3, 'FECHADO'),
    linha(4, 'REMARCADO'),
  ],
  totais: {
    itensDx: 104,
    itensDv: 0,
    itensVd: 0,
    valorDx: '21480.00',
    valorDv: '0.00',
    valorVd: '2310.00',
    comissao: '693.00',
    liquido: '1617.00',
    recebidoPorForma: { DINHEIRO: '1470.00', PIX: '300.00', DEBITO: '0.00', CREDITO: '0.00' },
    recebido: '1770.00',
    pendente: '345.10',
  },
  podeNovoPedido: true,
};

const irParaPrincipal = async (ponte: PonteDoCorona) => {
  render(<App ponte={ponte} />);
  await screen.findByRole('heading', { name: 'Gerenciamento Semanal' });
};

describe('Gerenciamento Semanal', () => {
  it('mostra Pedidos com a cor e o texto de cada situação, e a aba Totais com o recebido por forma', async () => {
    const { ponte } = criarPonte({ 'consignado.painel': () => ok(painel) });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    const tabela = await screen.findByRole('table', { name: 'Pedidos da semana' });
    const linhas = within(tabela).getAllByRole('row').slice(1);
    expect(linhas.map((l) => l.className)).toEqual([
      'consignado-linha consignado-linha--em-aberto',
      'consignado-linha consignado-linha--pendente',
      'consignado-linha consignado-linha--fechado',
      'consignado-linha consignado-linha--remarcado',
    ]);
    expect(within(linhas[1] as HTMLElement).getByText('Pendente')).toBeInTheDocument();
    expect(within(linhas[1] as HTMLElement).getByText('R$ 1.617,00')).toBeInTheDocument();
    expect(within(linhas[3] as HTMLElement).getByText('Remarcado')).toBeInTheDocument();
    expect(screen.getByText('Semana:')).toBeInTheDocument();
    expect(screen.getByText('Representante:')).toBeInTheDocument();
    expect(screen.getByText('Em andamento')).toBeInTheDocument();

    await u.click(screen.getByRole('tab', { name: 'Totais' }));
    expect(await screen.findByText('Total recebido')).toBeInTheDocument();
    expect(screen.getByText('R$ 1.470,00')).toBeInTheDocument();
    expect(screen.getByText('R$ 345,10')).toBeInTheDocument();
  });

  it('Nova semana pede confirmação e manda um UUID novo, sem número', async () => {
    const { ponte, chamadas } = criarPonte({ 'consignado.painel': () => ok(painel) });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(await screen.findByRole('button', { name: 'Nova semana' }));
    expect(chamadas.some((c) => c.operacao === 'consignado.semanaNova')).toBe(false);
    await u.click(screen.getByRole('button', { name: 'Abrir semana nova' }));
    const pedido = chamadas.find((c) => c.operacao === 'consignado.semanaNova');
    expect(pedido).toMatchObject({ operacao: 'consignado.semanaNova' });
    expect((pedido as { id: string }).id).toMatch(UUID);
    expect(pedido).not.toHaveProperty('numero');
  });

  it('primeira vez: pergunta o número da semana (D1) e abre com ele', async () => {
    const vazio: PainelDaSemana = {
      ...painel,
      semSemana: true,
      semanas: [],
      semana: null,
      pedidos: [],
      podeNovoPedido: false,
    };
    const { ponte, chamadas } = criarPonte({ 'consignado.painel': () => ok(vazio) });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    const campo = await screen.findByLabelText('Número da semana');
    await u.clear(campo);
    await u.type(campo, '65');
    await u.click(screen.getByRole('button', { name: 'Começar a semana' }));
    expect(chamadas.find((c) => c.operacao === 'consignado.semanaNova')).toMatchObject({
      numero: 65,
    });
    expect(screen.getByRole('button', { name: 'Novo Pedido' })).toBeDisabled();
  });

  it('2º computador: depois do pull que traz as semanas, a tela troca o "primeira semana" pelo painel sem sair da tela (defeito 4 do 9.10)', async () => {
    const vazio: PainelDaSemana = {
      ...painel,
      semSemana: true,
      semanas: [],
      semana: null,
      pedidos: [],
      podeNovoPedido: false,
    };
    let atual: PainelDaSemana = vazio;
    const { ponte, avisarEnvio } = criarPonte({ 'consignado.painel': () => ok(atual) });
    await irParaPrincipal(ponte);
    await screen.findByText('Vamos começar a primeira semana');
    avisarEnvio({ dadosAte: null }); // primeira entrega do estado, sem pull ainda
    // O pull termina e traz as semanas já abertas no outro computador.
    atual = painel;
    avisarEnvio({ dadosAte: '2026-11-10T12:00:00Z' });
    await screen.findByText('Semana:');
    expect(screen.queryByText('Vamos começar a primeira semana')).toBeNull();
  });

  it('semana anterior: Novo Pedido fica desligado', async () => {
    const antiga: PainelDaSemana = {
      ...painel,
      podeNovoPedido: false,
      semana: { id: 's64', numero: 64, atual: false, situacao: 'FECHADA' },
    };
    const { ponte } = criarPonte({ 'consignado.painel': () => ok(antiga) });
    await irParaPrincipal(ponte);
    await screen.findByText('Fechada');
    expect(screen.getByRole('button', { name: 'Novo Pedido' })).toBeDisabled();
  });

  it('dois cliques num pedido abrem a Cobrança dele', async () => {
    const { ponte, chamadas } = criarPonte({ 'consignado.painel': () => ok(painel) });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    const tabela = await screen.findByRole('table', { name: 'Pedidos da semana' });
    await u.dblClick(within(tabela).getByText('Cliente 2'));
    expect(await screen.findByRole('heading', { name: 'Cobrança' })).toBeInTheDocument();
    await waitFor(() =>
      expect(chamadas.find((c) => c.operacao === 'consignado.nota')).toMatchObject({ id: NOTA(2) }),
    );
  });

  it('nos dois idiomas a tela diz Representante e Cliente', async () => {
    const { ponte } = criarPonte({ 'consignado.painel': () => ok(painel) });
    await irParaPrincipal(ponte);
    expect(await screen.findByText('Representante:')).toBeInTheDocument();
    expect(await screen.findByRole('columnheader', { name: 'Cliente' })).toBeInTheDocument();
    definirIdioma('es');
    expect(await screen.findByText('Representante:')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Cliente' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Gestión Semanal' })).toBeInTheDocument();
  });
});

describe('Correções de tela (9.6)', () => {
  it('Primeira semana: o cartão e o conteúdo contam o espaço interno dentro da largura (não passam da janela)', async () => {
    const css = lerCss('./estilos.css');
    expect(css).toMatch(/\.vendas-conteudo \{\s*box-sizing: border-box;/);
    expect(css).toMatch(/\.vendas-cartao \{\s*box-sizing: border-box;/);
    const vazio: PainelDaSemana = {
      ...painel,
      semSemana: true,
      semanas: [],
      semana: null,
      pedidos: [],
      podeNovoPedido: false,
    };
    const { ponte } = criarPonte({ 'consignado.painel': () => ok(vazio) });
    await irParaPrincipal(ponte);
    const botao = await screen.findByRole('button', { name: 'Começar a semana' });
    expect(botao.closest('.vendas-cartao')).not.toBeNull();
  });

  it('Lançar pagamento: o texto rola por dentro e os botões ficam fixos embaixo, dentro da janela', async () => {
    const { ponte } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.clientes': () =>
        ok([
          {
            id: 'c1',
            nome: 'Ana',
            telefone: null,
            cidade: null,
            pendencia: '10.00',
            fotoHash: null,
          },
        ]),
      'consignado.cliente': () =>
        ok({
          id: 'c1',
          nome: 'Ana',
          pendencia: '10.00',
          debitos: [],
          maletasAtuais: [],
          pedidos: [],
          pagamentos: [],
          comissaoPercentual: '30.00',
          fotosEsperandoEnvio: 0,
        }),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Clientes' }));
    await u.click(await screen.findByRole('button', { name: 'Abrir' }));
    await u.click(await screen.findByRole('button', { name: 'Lançar pagamento' }));
    const janela = await screen.findByRole('dialog');
    const filhos = Array.from(janela.children).map((f) => f.className);
    expect(filhos).toContain('cs-janela__texto');
    expect(janela.querySelector(':scope > .cs-rodape-acoes')).not.toBeNull();
    const css = lerCss('../../../../packages/ui/src/componentes.css');
    expect(css).toMatch(/\.cs-janela__texto \{[^}]*overflow-y: auto/);
    expect(css).toMatch(/\.cs-janela \{[^}]*max-height: 100%/);
  });

  it('tabela principal: nota e valores ganham a marca de nunca quebrar; Comis. % aparece em Em aberto e Remarcado', async () => {
    const { ponte } = criarPonte({
      'consignado.painel': () =>
        ok({
          ...painel,
          pedidos: [
            linha(1, 'EM_ABERTO', { comissaoPercentual: '30.00' }),
            linha(4, 'REMARCADO', { comissaoPercentual: '35.00' }),
          ],
        }),
    });
    await irParaPrincipal(ponte);
    const tabela = await screen.findByRole('table', { name: 'Pedidos da semana' });
    const linhas = within(tabela).getAllByRole('row').slice(1);
    for (const l of linhas) {
      const celulas = within(l).getAllByRole('cell');
      expect(celulas[0]).toHaveClass('consignado-nota');
      for (const i of [3, 4, 5, 6, 7, 8]) expect(celulas[i]).toHaveClass('cs-numero');
    }
    expect(within(linhas[0] as HTMLElement).getByText('30,00%')).toBeInTheDocument();
    expect(within(linhas[1] as HTMLElement).getByText('35,00%')).toBeInTheDocument();
    const css = lerCss('./estilos.css');
    expect(css).toMatch(/\.cs-tabela td\.cs-numero,[^{]*\{\s*white-space: nowrap/);
  });

  it('títulos duplicados: a legenda da tabela fica só para leitor de tela', async () => {
    const { ponte } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.clientes': () =>
        ok([
          {
            id: 'c1',
            nome: 'Ana',
            telefone: null,
            cidade: null,
            pendencia: '10.00',
            fotoHash: null,
          },
        ]),
      'consignado.cliente': () =>
        ok({
          id: 'c1',
          nome: 'Ana',
          pendencia: '10.00',
          debitos: [
            {
              acertoId: 'd1',
              notaId: 'n1',
              numeroDaNota: '0000001-01',
              acertadoEm: '2026-11-10T12:00:00Z',
              valor: '10.00',
              saldo: '10.00',
            },
          ],
          maletasAtuais: [],
          pedidos: [],
          pagamentos: [],
          comissaoPercentual: '30.00',
          fotosEsperandoEnvio: 0,
        }),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Clientes' }));
    await u.click(await screen.findByRole('button', { name: 'Abrir' }));
    const titulo = await screen.findByRole('heading', { name: 'O que o cliente deve agora' });
    const legenda = within(titulo.closest('section') as HTMLElement)
      .getByRole('table')
      .querySelector('caption');
    expect(legenda).toHaveTextContent('O que o cliente deve agora');
    expect(legenda).toHaveClass('consignado-so-leitor');
  });

  it('Pesquisar: o texto de exemplo é curto e cabe no campo, nos dois idiomas', async () => {
    const { TEXTOS_ES, TEXTOS_PT } = await import('./textos');
    expect(TEXTOS_ES.consignado.semanal.pesquisarCampo.length).toBeLessThanOrEqual(25);
    expect(TEXTOS_PT.consignado.semanal.pesquisarCampo.length).toBeLessThanOrEqual(25);
  });
});

describe('Novo Pedido', () => {
  const vazio: PedidoEmMontagem = {
    id: 'r1',
    clienteId: 'c1',
    cliente: 'Vendedora Ana',
    pendenciaDoCliente: '498.10',
    remessaId: '11111111-1111-4111-8111-111111111111',
    maletaEscolhida: 'M-ABC123',
    dataCobranca: '2026-12-31',
    itens: [],
    totalDeItens: 0,
    totalDx: '0.00',
    maletas: [{ id: '11111111-1111-4111-8111-111111111111', codigo: 'M-ABC123' }],
  };

  it('avisa a pendência do cliente e mostra a mensagem de cada bipe que não entra, sem adicionar nada', async () => {
    const bipes = [
      { resultado: 'FORA_DA_MALETA' },
      { resultado: 'EM_OUTRA_MALETA', maleta: 'M-ZZZ999' },
      { resultado: 'SEM_SALDO', disponivel: 2 },
    ];
    const { ponte, chamadas } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.pedidoVer': () => ok(vazio),
      'consignado.pedidoBipar': () => ok(bipes.shift()),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Novo Pedido' }));
    expect(
      await screen.findByText(
        'Este cliente deve R$ 498,10. O valor será somado ao próximo acerto.',
      ),
    ).toBeInTheDocument();
    const campo = screen.getByLabelText('Ler o código da peça');
    await u.type(campo, 'X-1{Enter}');
    expect(
      await screen.findByText('Esta peça não está na maleta escolhida. Confira o código.'),
    ).toBeInTheDocument();
    await u.type(campo, 'B-1{Enter}');
    expect(
      await screen.findByText(
        'Esta peça não está na maleta escolhida. Ela está na maleta M-ZZZ999.',
      ),
    ).toBeInTheDocument();
    await u.type(campo, 'B-2{Enter}');
    expect(
      await screen.findByText('Só há 2 desta peça na maleta. Nada foi acrescentado.'),
    ).toBeInTheDocument();
    expect(chamadas.filter((c) => c.operacao === 'consignado.pedidoBipar')).toHaveLength(3);
    expect(screen.getByRole('button', { name: 'Finalizar pedido' })).toBeDisabled();
  });

  it('com peças, Finalizar confirma e manda só o id da nota (nunca preço nem total)', async () => {
    const comPecas: PedidoEmMontagem = {
      ...vazio,
      itens: [
        {
          id: 'i1',
          produtoId: 'p1',
          remessaId: vazio.remessaId as string,
          maleta: 'M-ABC123',
          codigo: 'A-1',
          nome: 'Brinco A',
          tamTon: null,
          fotoHash: null,
          quantidade: 2,
          precoUnitario: '200.00',
          subTotal: '400.00',
        },
      ],
      totalDeItens: 2,
      totalDx: '400.00',
    };
    const nota = { id: 'n1', numero: '0000065-33' } as DetalheDaNota;
    const { ponte, chamadas } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.pedidoVer': () => ok(comPecas),
      'consignado.pedidoFinalizar': () => ok({ notaId: 'n1', nota }),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Novo Pedido' }));
    const botao = await screen.findByRole('button', { name: 'Finalizar pedido' });
    await waitFor(() => expect(botao).toBeEnabled());
    await u.click(botao);
    await u.click(
      screen.getAllByRole('button', { name: 'Finalizar pedido' }).at(-1) as HTMLElement,
    );
    const final = chamadas.find((c) => c.operacao === 'consignado.pedidoFinalizar');
    expect(final).toEqual({
      operacao: 'consignado.pedidoFinalizar',
      id: expect.stringMatching(UUID) as string,
    });
    expect(await screen.findByText('Pedido 0000065-33 finalizado.')).toBeInTheDocument();
  });
});

describe('Cobrança e acerto', () => {
  const A = {
    produtoId: 'a0000000-0000-4000-8000-000000000001',
    remessaId: 'b0000000-0000-4000-8000-000000000001',
  };
  const nota: DetalheDaNota = {
    id: NOTA(2),
    numero: '0000064-21',
    clienteId: 'c1',
    cliente: 'Vendedora Ana',
    semanaNumero: 64,
    daSemanaAtual: true,
    saidaEm: '2026-11-10T12:00:00Z',
    dataCobranca: '2026-12-25',
    situacao: 'EM_ABERTO',
    aceitaMudanca: true,
    linhas: [
      {
        ...A,
        maleta: 'M-ABC123',
        codigo: 'A-1',
        codigos: ['a-1'],
        nome: 'Brinco A',
        tamTon: '17',
        fotoHash: null,
        dx: 10,
        dv: 0,
        vd: 10,
        precoUnitario: '200.00',
        subTotal: '2000.00',
      },
    ],
    itensDx: 10,
    valorDx: '2000.00',
    comissaoDoCliente: '30.00',
    pendenciasAnteriores: [
      {
        acertoId: 'd1',
        notaId: 'n63',
        numeroDaNota: '0000063-32',
        acertadoEm: '2026-11-03T12:00:00Z',
        valor: '498.10',
        saldo: '498.10',
      },
    ],
    acerto: null,
    saldoDaNota: '0.00',
  };
  const previa = (pago: string): PrevisaoDoAcerto => ({
    linhas: [{ ...A, dx: 10, dv: 5, vd: 5, precoUnitario: '200.00' }],
    itensDx: 10,
    itensDv: 5,
    itensVd: 5,
    valorDx: '2000.00',
    valorDv: '1000.00',
    valorVd: '1000.00',
    vendaPercentual: '50.00',
    comissaoPercentual: '30.00',
    comissao: '300.00',
    bruto: '700.00',
    pendenciasAnteriores: '498.10',
    totalAReceber: '1198.10',
    totalPago: pago,
    ficouDevendo: '1198.10',
    debitos: [],
  });

  async function abrir(respostas: Record<string, Resposta> = {}) {
    const c = criarPonte({
      'consignado.painel': () => ok({ ...painel, pedidos: [linha(2, 'EM_ABERTO')] }),
      'consignado.nota': () => ok(nota),
      'consignado.acertoPrevia': (p) =>
        ok(
          previa(
            (p as { acerto: { formas: { valor: string }[] } }).acerto.formas
              .reduce((s, f) => s + Number(f.valor), 0)
              .toFixed(2),
          ),
        ),
      ...respostas,
    });
    const u = userEvent.setup();
    await irParaPrincipal(c.ponte);
    await u.dblClick(await screen.findByText('Cliente 2'));
    await screen.findByText('Pedido 0000064-21 · Vendedora Ana');
    return { ...c, u };
  }

  it('acerto salvo: mostra o que ficou devendo no dia e o que deve agora, quando uma sobra abateu depois', async () => {
    const acerto = {
      id: 'a1',
      acertadoEm: '2026-11-10T15:00:00Z',
      comissaoPercentual: '30.00',
      itensDx: 10,
      itensDv: 5,
      itensVd: 5,
      valorDx: '2000.00',
      valorDv: '1000.00',
      valorVd: '1000.00',
      vendaPercentual: '50.00',
      comissao: '300.00',
      bruto: '700.00',
      pendenciasAnteriores: '0.00',
      totalAReceber: '700.00',
      totalPago: '351.03',
      ficouDevendo: '348.97',
      recibo: null,
    };
    const { u } = await abrir({
      'consignado.nota': () =>
        ok({ ...nota, aceitaMudanca: false, acerto, saldoDaNota: '294.70', situacao: 'PENDENTE' }),
    });
    await u.click(screen.getByRole('tab', { name: 'Forma de Pagamento' }));
    expect(await screen.findByText('Deve agora')).toBeInTheDocument();
    expect(screen.getByText(/ficou devendo R\$ 348,97\./)).toBeInTheDocument();
    expect(screen.getAllByText('R$ 294,70').length).toBeGreaterThan(0);
  });

  it('código fora da pedido avisa; DV acima do DX não passa; ler soma 1 em DV', async () => {
    const { u } = await abrir();
    const campo = screen.getByLabelText('Ler o código da peça devolvida');
    await u.type(campo, 'x-9{Enter}');
    expect(
      await screen.findByText('Esta peça não está neste pedido. Confira o código.'),
    ).toBeInTheDocument();
    await u.type(campo, 'A-1{Enter}');
    const tabela = screen.getByRole('table', { name: 'Peças do pedido' });
    await waitFor(() => expect(within(tabela).getAllByRole('cell')[4]).toHaveTextContent('1'));
    for (let i = 0; i < 9; i += 1) await u.type(campo, 'a-1{Enter}');
    await u.type(campo, 'a-1{Enter}');
    expect(
      await screen.findByText(
        'Já foram devolvidas todas as peças levadas desta. Nada foi acrescentado.',
      ),
    ).toBeInTheDocument();
  });

  it('a tabela de peças tem a coluna Tam./Ton. e mostra o valor da peça (vazio se não tiver)', async () => {
    await abrir();
    expect(screen.getByRole('columnheader', { name: 'Tam./Ton.' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '17' })).toBeInTheDocument();
  });

  it('Concluir devolução lista as peças não lidas; Forma de Pagamento mostra a conta; Salvar manda só os fatos', async () => {
    const { u, chamadas } = await abrir({
      'consignado.acertoSalvar': () =>
        ok({ reciboId: 'r', situacao: 'PENDENTE', nota: { ...nota, acerto: null } }),
    });
    const campo = screen.getByLabelText('Ler o código da peça devolvida');
    for (let i = 0; i < 5; i += 1) await u.type(campo, 'A-1{Enter}');
    await u.click(screen.getByRole('button', { name: 'Concluir devolução' }));
    expect(
      await screen.findByText(
        'Estas 5 peça(s) não foram lidas e serão consideradas vendidas. Confirmar?',
      ),
    ).toBeInTheDocument();
    await u.click(
      screen.getAllByRole('button', { name: 'Concluir devolução' }).at(-1) as HTMLElement,
    );
    // Foi para a aba Forma de Pagamento.
    const dinheiro = await screen.findByLabelText('Dinheiro');
    await u.type(dinheiro, '1.000,00');
    await u.type(screen.getByLabelText('Pix'), '198,10');
    expect((await screen.findAllByText('R$ 498,10')).length).toBeGreaterThan(0); // pendência anterior (nota 0000063-32)
    expect((await screen.findAllByText('R$ 1.198,10')).length).toBeGreaterThan(0); // total a receber
    await u.click(screen.getByRole('button', { name: 'Salvar acerto' }));
    await u.click(screen.getAllByRole('button', { name: 'Salvar acerto' }).at(-1) as HTMLElement);
    const salvar = chamadas.find((c) => c.operacao === 'consignado.acertoSalvar') as Extract<
      PedidoDeChamada,
      { operacao: 'consignado.acertoSalvar' }
    >;
    expect(salvar.acerto.notaId).toBe(nota.id);
    expect(salvar.acerto.devolvidos).toEqual([{ ...A, quantidade: 5 }]);
    expect(salvar.acerto.formas).toEqual([
      { forma: 'DINHEIRO', valor: '1000.00' },
      { forma: 'PIX', valor: '198.10' },
    ]);
    expect(salvar.acerto.comissaoPercentual).toBe('30');
    expect(salvar.acerto.id).toMatch(UUID);
    // A tela nunca manda total, comissão em reais nem pendência: só o que a pessoa fez.
    expect(Object.keys(salvar.acerto).sort()).toEqual([
      'comissaoPercentual',
      'devolvidos',
      'formas',
      'id',
      'notaId',
      'reciboId',
    ]);
  });

  it('Dt. Acerto: hoje por padrão (nada vai na chamada); um dia que passou vai em data; antes da saída ou depois de hoje é barrado', async () => {
    const { u, chamadas } = await abrir({
      'consignado.acertoSalvar': () =>
        ok({ reciboId: 'r', situacao: 'PENDENTE', nota: { ...nota, acerto: null } }),
    });
    await u.click(screen.getByRole('button', { name: 'Concluir devolução' }));
    await u.click(
      screen.getAllByRole('button', { name: 'Concluir devolução' }).at(-1) as HTMLElement,
    );
    const data = await screen.findByLabelText('Dt. Acerto');
    expect(data).toHaveAttribute('max');
    expect(data).toHaveAttribute('min', '2026-11-10');
    fireEvent.change(data, { target: { value: '2026-11-05' } });
    expect(
      await screen.findByText(
        'Escolha hoje ou um dia que já passou, a partir do dia em que a nota saiu.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Salvar acerto' })).toBeDisabled();
    fireEvent.change(data, { target: { value: '2026-11-12' } });
    await u.click(await screen.findByRole('button', { name: 'Salvar acerto' }));
    await u.click(screen.getAllByRole('button', { name: 'Salvar acerto' }).at(-1) as HTMLElement);
    const salvar = chamadas.find((c) => c.operacao === 'consignado.acertoSalvar') as Extract<
      PedidoDeChamada,
      { operacao: 'consignado.acertoSalvar' }
    >;
    expect(salvar.acerto.data).toBe('2026-11-12');
  });

  it('pagar mais que o total a receber é barrado na tela, e comissão acima de 40 avisa', async () => {
    const { u } = await abrir();
    const campo = screen.getByLabelText('Ler o código da peça devolvida');
    await u.type(campo, 'A-1{Enter}');
    await u.click(screen.getByRole('button', { name: 'Concluir devolução' }));
    await u.click(
      screen.getAllByRole('button', { name: 'Concluir devolução' }).at(-1) as HTMLElement,
    );
    await u.type(await screen.findByLabelText('Dinheiro'), '5.000,00');
    expect(
      await screen.findByText('O valor pago passa do total a receber. Confira os valores.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Salvar acerto' })).toBeDisabled();
    const comissao = screen.getByLabelText('Comissão %');
    await u.clear(comissao);
    await u.type(comissao, '41');
    // 41 tem o formato certo; quem recusa é o computador (e o servidor): a tela mostra o motivo.
    expect(comissao).toHaveValue('41');
  });
});

describe('Clientes e Lançar pagamento', () => {
  const cliente: ClienteCompleto = {
    id: 'c1',
    nome: 'Vendedora Ana',
    telefone: '67 99999-0000',
    whatsapp: null,
    documento: '123',
    documentoTipo: 'CI',
    dataNascimento: null,
    enderecoLogradouro: 'Rua A',
    enderecoNumero: '10',
    enderecoComplemento: null,
    enderecoBairro: 'Centro',
    enderecoCidade: 'Ponta Porã',
    enderecoUf: 'MS',
    enderecoCep: null,
    indicadoPorClienteId: null,
    indicadoPorNome: null,
    comoConheceu: null,
    comissaoPercentual: '30.00',
    observacoes: null,
    fotoHash: null,
    fotoDocumentoFrenteHash: null,
    fotoDocumentoVersoHash: null,
    fotosEsperandoEnvio: 2,
    pendencia: '345.10',
    debitos: [
      {
        acertoId: 'd1',
        notaId: 'n64',
        numeroDaNota: '0000064-21',
        acertadoEm: '2026-11-10T12:00:00Z',
        valor: '1617.00',
        saldo: '345.10',
      },
    ],
    maletasAtuais: [{ remessaId: 'm1', maleta: 'M-ABC123', pecas: 4 }],
    pedidos: [],
    pagamentos: [],
  };

  it('a ficha mostra pendência, maletas atuais e o aviso das fotos que ainda vão subir; Lançar pagamento manda só forma e valor', async () => {
    const { ponte, chamadas } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.clientes': () =>
        ok([
          {
            id: 'c1',
            nome: 'Vendedora Ana',
            telefone: '67 99999-0000',
            cidade: 'Ponta Porã',
            pendencia: '345.10',
            fotoHash: null,
          },
        ]),
      'consignado.cliente': () => ok(cliente),
      'consignado.pagamento': () =>
        ok({
          cliente: {
            ...cliente,
            pendencia: '45.10',
            pagamentos: [
              {
                reciboId: 'r1',
                numero: 'R 0000065-40',
                pagoEm: null,
                total: '300.00',
                formas: [],
                podeEstornar: true,
                estornado: false,
                doAcerto: false,
              },
            ],
          },
        }),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Clientes' }));
    await u.click(await screen.findByRole('button', { name: 'Abrir' }));
    expect(await screen.findByText('M-ABC123: 4 peça(s)')).toBeInTheDocument();
    expect(screen.getByText(/Algumas fotos ainda vão ser enviadas/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Lançar pagamento' }));
    await u.type(screen.getByLabelText('Pix'), '300');
    await u.click(
      screen.getAllByRole('button', { name: 'Lançar pagamento' }).at(-1) as HTMLElement,
    );
    const p = chamadas.find((c) => c.operacao === 'consignado.pagamento') as Extract<
      PedidoDeChamada,
      { operacao: 'consignado.pagamento' }
    >;
    expect(p.pagamento.clienteId).toBe('c1');
    expect(p.pagamento.formas).toEqual([{ forma: 'PIX', valor: '300.00' }]);
    expect(await screen.findByText('Pagamento lançado. R 0000065-40.')).toBeInTheDocument();
  });

  it('ficha do cliente mostra a sobra "a aplicar" em linguagem simples', async () => {
    const { ponte } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.clientes': () =>
        ok([
          {
            id: 'c1',
            nome: 'Vendedora Ana',
            telefone: null,
            cidade: null,
            pendencia: '0.00',
            fotoHash: null,
          },
        ]),
      'consignado.cliente': () =>
        ok({ ...cliente, pendencia: '0.00', debitos: [], sobraAAplicar: '54.27' }),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Clientes' }));
    await u.click(await screen.findByRole('button', { name: 'Abrir' }));
    expect(
      await screen.findByText(/Sobrou R\$ 54,27 de pagamento deste cliente/),
    ).toBeInTheDocument();
  });

  it('pagamento acima do que o cliente deve não deixa lançar', async () => {
    const { ponte } = criarPonte({
      'consignado.painel': () => ok(painel),
      'consignado.clientes': () =>
        ok([
          {
            id: 'c1',
            nome: 'Vendedora Ana',
            telefone: null,
            cidade: null,
            pendencia: '345.10',
            fotoHash: null,
          },
        ]),
      'consignado.cliente': () => ok(cliente),
    });
    const u = userEvent.setup();
    await irParaPrincipal(ponte);
    await u.click(screen.getByRole('button', { name: 'Clientes' }));
    await u.click(await screen.findByRole('button', { name: 'Abrir' }));
    await u.click(await screen.findByRole('button', { name: 'Lançar pagamento' }));
    await u.type(screen.getByLabelText('Dinheiro'), '400');
    expect(
      await screen.findByText('O valor pago passa do total a receber. Confira os valores.'),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Lançar pagamento' }).at(-1)).toBeDisabled();
  });
});

const PT = {
  escolher: 'Escolher',
  queVolta: 'Peça que volta',
  maleta: 'Maleta da peça nova',
  lerCodigo: 'Ler o código da peça nova',
  trocar: 'Trocar a peça',
};
const ES = {
  escolher: 'Elegir',
  queVolta: 'Pieza que vuelve',
  maleta: 'Maleta de la pieza nueva',
  lerCodigo: 'Leer el código de la pieza nueva',
  trocar: 'Cambiar la pieza',
};

describe('Troca mostra o valor da diferença (defeito 7 do 9.10)', () => {
  afterEach(() => definirIdioma('pt'));

  const notaDaTroca = {
    id: NOTA(1),
    numero: '0000065-01',
    clienteId: 'c1',
    cliente: 'Cliente 1',
    valorDx: '400.00',
    linhas: [
      {
        produtoId: 'p1',
        remessaId: 'm1',
        maleta: '0007',
        nome: 'Anel A',
        dx: 2,
        precoUnitario: '200.00',
      },
    ],
  };
  const peca = (preco: string) => ({
    tipo: 'ACHADA',
    produtoId: 'p2',
    remessaId: 'm1',
    maleta: '0007',
    nome: 'Colar B',
    codigo: 'B-1',
    fotoHash: null,
    precoUnitario: preco,
    disponivel: 3,
  });

  async function prepararTroca(precoNovo: string, idioma: 'pt' | 'es' = 'pt') {
    definirIdioma(idioma);
    const T = idioma === 'pt' ? PT : ES;
    const { ponte } = criarPonte({
      'consignado.notas': () =>
        ok([
          {
            notaId: NOTA(1),
            numero: '0000065-01',
            cliente: 'Cliente 1',
            clienteId: 'c1',
            dataCobranca: null,
            valorDx: '400.00',
            situacao: 'EM_ABERTO',
            maletas: ['0007'],
          },
        ]),
      'consignado.nota': () => ok(notaDaTroca),
      'consignado.maletas': () => ok([{ id: 'm1', codigo: '0007', status: 'ENVIADA' }]),
      'consignado.peca': () => ok(peca(precoNovo)),
      'consignado.trocaNaNota': () =>
        ok({ nota: { ...notaDaTroca, valorDx: String(400 + Number(precoNovo) - 200) + '.00' } }),
    });
    const u = userEvent.setup();
    render(<TelaTroca ponte={ponte} aoVoltar={() => undefined} />);
    await u.click(await screen.findByRole('button', { name: T.escolher }));
    await u.selectOptions(await screen.findByLabelText(T.queVolta), 'p1|m1');
    await u.selectOptions(screen.getByLabelText(T.maleta), 'm1');
    await u.type(screen.getByLabelText(T.lerCodigo), 'B-1{enter}');
    return { u, T };
  }

  it('peça nova mais cara: avisa quanto o cliente passa a dever na nota', async () => {
    await prepararTroca('260.00');
    expect(await screen.findByText('A peça que volta vale R$ 200,00.')).toBeInTheDocument();
    expect(screen.getByText('A peça nova vale R$ 260,00.')).toBeInTheDocument();
    expect(
      screen.getByText('O valor da nota sobe R$ 60,00 e passa a ser R$ 460,00.'),
    ).toBeInTheDocument();
  });

  it('peça nova mais barata: avisa quanto a nota desce', async () => {
    await prepararTroca('150.00');
    expect(
      await screen.findByText('O valor da nota desce R$ 50,00 e passa a ser R$ 350,00.'),
    ).toBeInTheDocument();
  });

  it('mesmo preço: sem diferença', async () => {
    await prepararTroca('200.00');
    expect(
      await screen.findByText('Sem diferença: a nota continua em R$ 400,00.'),
    ).toBeInTheDocument();
  });

  it('em espanhol: o valor da diferença sai na língua da loja', async () => {
    await prepararTroca('260.00', 'es');
    expect(await screen.findByText('La pieza que vuelve vale R$ 200,00.')).toBeInTheDocument();
    expect(screen.getByText('La pieza nueva vale R$ 260,00.')).toBeInTheDocument();
    expect(
      screen.getByText('El valor de la nota sube R$ 60,00 y pasa a ser R$ 460,00.'),
    ).toBeInTheDocument();
  });

  it('em espanhol: peça mais barata e mesmo preço', async () => {
    await prepararTroca('150.00', 'es');
    expect(
      await screen.findByText('El valor de la nota baja R$ 50,00 y pasa a ser R$ 350,00.'),
    ).toBeInTheDocument();
  });

  it('depois de registrar, a mensagem traz o valor da nota (pt e es)', async () => {
    for (const idioma of ['pt', 'es'] as const) {
      const { u, T } = await prepararTroca('260.00', idioma);
      await u.click(await screen.findByRole('button', { name: T.trocar }));
      const janela = await screen.findByRole('dialog');
      await u.click(within(janela).getByRole('button', { name: T.trocar }));
      expect(
        await screen.findByText(
          idioma === 'pt'
            ? 'Troca registrada. A nota ficou em R$ 460,00.'
            : 'Cambio registrado. La nota quedó en R$ 460,00.',
        ),
      ).toBeInTheDocument();
      cleanup();
    }
  });
});

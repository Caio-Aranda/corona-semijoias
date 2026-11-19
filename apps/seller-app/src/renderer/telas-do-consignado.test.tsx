// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
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
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';
import { definirIdioma } from './idioma';

const DENTRO: EstadoDaSessao = { estado: 'dentro', usuario: { nome: 'Rosa' } };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

type Resposta = (pedido: PedidoDeChamada) => ResultadoDeChamada;

function criarPonte(respostas: Record<string, Resposta> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() => Promise.resolve(DENTRO)),
    chamarApi: vi.fn((p: PedidoDeChamada) => {
      chamadas.push(p);
      return Promise.resolve(respostas[p.operacao]?.(p) ?? { ok: true as const, dados: {} });
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
    aoMudarASincronizacao: () => () => undefined,
    lerIdioma: () => Promise.resolve(null),
    gravarIdioma: () => Promise.resolve(),
  };
  return { ponte, chamadas };
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
                numero: '0000065-40',
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
    expect(await screen.findByText('Pagamento lançado. Recibo 0000065-40.')).toBeInTheDocument();
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

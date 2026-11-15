// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  Cliente,
  FichaDoAcerto,
  LinhaDaMaleta,
  Maleta,
  PreviaDaComissao,
  RecebiveisDoVendedor,
  ResumoDoAcerto,
  Venda,
  VendaAPrazo,
} from '../shared/dados';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';

/**
 * As telas da tarefa 7.11: vender com parcelas, "A receber" (receber do cliente e estornar no mesmo dia, o que o vendedor
 * deve à loja) e "Meu acerto" (prévia da comissão com quanto falta para a meta, ficha do acerto fechado). A tela nunca
 * calcula parcela, comissão nem total: só mostra o que o servidor devolveu e manda quantas parcelas e quando vence a
 * primeira. Escrito antes das telas.
 */
const ANEL = '11111111-1111-4111-8111-111111111111';
const MALETA = '33333333-3333-4333-8333-333333333333';
const MARIA = '44444444-4444-4444-8444-444444444444';
const VENDA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const RECEBIMENTO = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const ACERTO = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';

const LINHA_ANEL: LinhaDaMaleta = {
  produto: {
    id: ANEL,
    nome: 'Anel de prata',
    codigo: 'A-1',
    fotoHash: null,
    categoria: 'Anéis',
    codigoFornecedor: null,
  },
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
const maria: Cliente = {
  id: MARIA,
  nome: 'Maria Souza',
  cpf: null,
  dataNascimento: null,
  telefone: null,
  cidade: null,
  meu: true,
  whatsapp: null,
  observacoes: null,
  ativo: true,
};

const vendaGravada = (total = '50.00', pago = '0.00'): Venda => ({
  id: VENDA,
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  remessaId: MALETA,
  status: 'CONCLUIDA',
  subtotal: total,
  desconto: '0.00',
  total,
  totalDolar: null,
  totalGuarani: null,
  cotacao: null,
  pago,
  aReceber: (Number(total) - Number(pago)).toFixed(2),
  vendidaEm: '2026-11-11T15:00:00Z',
  estornadaEm: null,
  motivoDoEstorno: null,
  itens: [],
  pagamentos: [],
});

const vendaAPrazo = (extra: Partial<VendaAPrazo> = {}): VendaAPrazo => ({
  vendaId: VENDA,
  vendidaEm: '2026-11-11T15:00:00Z',
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  total: '100.00',
  pago: '40.00',
  aReceber: '60.00',
  aguardandoEnvio: false,
  recebimentos: [],
  parcelas: [
    {
      id: 'p1',
      numero: 1,
      total: 2,
      vencimento: '2026-11-10',
      valor: '30.00',
      valorPago: '0.00',
      valorCancelado: '0.00',
      situacao: 'ABERTA',
      vencida: true,
    },
    {
      id: 'p2',
      numero: 2,
      total: 2,
      vencimento: '2026-12-10',
      valor: '30.00',
      valorPago: '0.00',
      valorCancelado: '0.00',
      situacao: 'ABERTA',
      vencida: false,
    },
  ],
  ...extra,
});

type Manipulador = (pedido: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

function ponteDe(manipuladores: Partial<Record<PedidoDeChamada['operacao'], Manipulador>> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const vazio: Manipulador = () => ({
    ok: true,
    dados: { linhas: [], clientes: [], totais: { total: '0.00' } },
  });
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
    'clientes.listar': () => ({ ok: true, dados: [maria] }),
    'maletas.peca': () => ({ ok: true, dados: LINHA_ANEL }),
    'autorizacoes.listar': () => ({ ok: true, dados: { pedidos: [], avisos: [] } }),
    'relatorios.vendas': vazio,
    'relatorios.recebimentos': vazio,
    'relatorios.aReceber': vazio,
    'relatorios.devolucoes': vazio,
    'relatorios.creditos': vazio,
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
  return { ponte, chamadas, daOperacao: (op: string) => chamadas.filter((c) => c.operacao === op) };
}

beforeEach(() => {
  // Só o relógio: 12/11/2026 10:00 em Brasília.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-12T13:00:00.000Z'));
});
afterEach(() => {
  vi.useRealTimers();
});

async function abrirVender(ponte: PonteDoCorona) {
  const u = userEvent.setup({ advanceTimers: () => undefined });
  render(<App ponte={ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Vender' }));
  return u;
}

async function abrirDosNumeros(ponte: PonteDoCorona, botao: string) {
  const u = userEvent.setup({ advanceTimers: () => undefined });
  render(<App ponte={ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Meus números' }));
  await u.click(await screen.findByRole('button', { name: botao }));
  return u;
}

async function lerEEscolherCliente(u: ReturnType<typeof userEvent.setup>) {
  await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1{Enter}');
  await screen.findByText(/Anel de prata: 1/);
  await u.click(await screen.findByRole('button', { name: 'Escolher: Maria Souza' }));
  await screen.findByText('Maria Souza');
}

async function concluir(u: ReturnType<typeof userEvent.setup>) {
  await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
  const botoes = await screen.findAllByRole('button', { name: 'Concluir venda' });
  await u.click(botoes[botoes.length - 1]!);
}

const enviada = (chamadas: PedidoDeChamada[]) => {
  const c = chamadas.find((x) => x.operacao === 'vendas.vender');
  if (!c || c.operacao !== 'vendas.vender') throw new Error('sem venda');
  return c.venda;
};

describe('Vender com parcelas', () => {
  it('quando sobra a prazo pede quantas parcelas e o primeiro vencimento, com o mês seguinte já escolhido', async () => {
    const { ponte } = ponteDe();
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    // Pagando tudo agora, não há parcelas.
    expect(screen.queryByLabelText('Em quantas parcelas? (1 a 24)')).not.toBeInTheDocument();
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    expect(screen.getByLabelText('Em quantas parcelas? (1 a 24)')).toHaveValue(1);
    expect(screen.getByLabelText('Primeiro vencimento')).toHaveValue('2026-12-12');
  });

  it('manda só a quantidade e o vencimento escolhidos, sem valor de parcela nem total calculado', async () => {
    const { ponte, chamadas } = ponteDe({
      'vendas.vender': () => ({ ok: true, dados: vendaGravada('50.00', '0.00') }),
    });
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    fireEvent.change(screen.getByLabelText('Em quantas parcelas? (1 a 24)'), {
      target: { value: '3' },
    });
    fireEvent.change(screen.getByLabelText('Primeiro vencimento'), {
      target: { value: '2026-12-15' },
    });
    await concluir(u);
    await screen.findByText('Venda registrada.');
    const venda = enviada(chamadas);
    expect(venda.parcelamento).toEqual({ quantidade: 3, primeiroVencimento: '2026-12-15' });
    const texto = JSON.stringify(venda);
    expect(texto).not.toMatch(/"valor":"16|parcela[s]?Valor|entrada|cheque|cartao/i);
    // Nenhum valor de parcela é calculado na tela (R$ 50,00 ÷ 3 = 16,67).
    expect(document.body.textContent ?? '').not.toContain('16,67');
  });

  it('pagando parte agora, as parcelas valem para o que fica a prazo', async () => {
    const { ponte, chamadas } = ponteDe({
      'vendas.vender': () => ({ ok: true, dados: vendaGravada('50.00', '20.00') }),
    });
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await u.type(screen.getByLabelText('Valor pago agora (R$)'), '20,00');
    expect(screen.getByLabelText('Em quantas parcelas? (1 a 24)')).toBeInTheDocument();
    await concluir(u);
    await screen.findByText('Venda registrada.');
    expect(enviada(chamadas).parcelamento).toEqual({
      quantidade: 1,
      primeiroVencimento: '2026-12-12',
    });
    expect(enviada(chamadas).pagamentos).toHaveLength(1);
  });

  it('venda paga inteira não leva parcelamento', async () => {
    const { ponte, chamadas } = ponteDe({
      'vendas.vender': () => ({ ok: true, dados: vendaGravada('50.00', '50.00') }),
    });
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await concluir(u);
    await screen.findByText('Venda registrada.');
    expect(enviada(chamadas)).not.toHaveProperty('parcelamento');
  });

  it.each([
    ['0', 'O número de parcelas vai de 1 a 24.'],
    ['25', 'O número de parcelas vai de 1 a 24.'],
    ['2.5', 'O número de parcelas vai de 1 a 24.'],
  ])('recusa %s parcelas, em português, e não envia nada', async (digitado, texto) => {
    const { ponte, daOperacao } = ponteDe();
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    fireEvent.change(screen.getByLabelText('Em quantas parcelas? (1 a 24)'), {
      target: { value: digitado },
    });
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(await screen.findByText(texto)).toBeInTheDocument();
    expect(daOperacao('vendas.vender')).toHaveLength(0);
  });

  it('recusa vencimento antes de hoje, depois de um ano e sem data', async () => {
    const { ponte, daOperacao } = ponteDe();
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    const data = screen.getByLabelText('Primeiro vencimento');
    fireEvent.change(data, { target: { value: '2026-11-11' } });
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(
      await screen.findByText('O primeiro vencimento não pode ser antes de hoje.'),
    ).toBeInTheDocument();
    fireEvent.change(data, { target: { value: '2027-11-13' } });
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(await screen.findByText(/está longe demais/)).toBeInTheDocument();
    fireEvent.change(data, { target: { value: '' } });
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(await screen.findByText('Escolha a data do primeiro vencimento.')).toBeInTheDocument();
    expect(daOperacao('vendas.vender')).toHaveLength(0);
  });

  it('não oferece entrada, cheque nem cartão', async () => {
    const { ponte } = ponteDe();
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    const corpo = document.body.textContent ?? '';
    expect(corpo).not.toMatch(/entrada|cheque|cart[ãa]o/i);
  });

  it('ao tentar de novo depois de um erro, manda os mesmos ids e o mesmo parcelamento', async () => {
    let tentativa = 0;
    const { ponte, chamadas } = ponteDe({
      'vendas.vender': () => {
        tentativa += 1;
        return tentativa === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: vendaGravada('50.00', '0.00') };
      },
    });
    const u = await abrirVender(ponte);
    await lerEEscolherCliente(u);
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    fireEvent.change(screen.getByLabelText('Em quantas parcelas? (1 a 24)'), {
      target: { value: '2' },
    });
    await concluir(u);
    await screen.findByText('Sem internet. Conecte-se e tente de novo.');
    await concluir(u);
    await screen.findByText('Venda registrada.');
    const vendas = chamadas
      .filter((c) => c.operacao === 'vendas.vender')
      .map((c) => (c.operacao === 'vendas.vender' ? c.venda : null));
    expect(vendas[0]?.id).toBe(vendas[1]?.id);
    expect(vendas[0]?.parcelamento).toEqual(vendas[1]?.parcelamento);
  });
});

describe('A receber: o que os clientes devem', () => {
  const recebiveis = (dosClientes: VendaAPrazo[], daLoja: RecebiveisDoVendedor['daLoja'] = []) =>
    ({ ok: true, dados: { dosClientes, daLoja } }) as const;

  it('mostra cada venda a prazo com as parcelas que o servidor gerou, o vencimento e a vencida', async () => {
    const { ponte } = ponteDe({ 'recebiveis.ver': () => recebiveis([vendaAPrazo()]) });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    const venda = await screen.findByRole('region', { name: /Maria Souza/ });
    expect(within(venda).getByText('Parcela 1 de 2')).toBeInTheDocument();
    expect(within(venda).getByText('Parcela 2 de 2')).toBeInTheDocument();
    expect(within(venda).getByText(/10\/11\/2026/)).toBeInTheDocument();
    expect(within(venda).getAllByText('R$ 30,00').length).toBe(2);
    expect(within(venda).getAllByText('Vencida')).toHaveLength(1);
    expect(within(venda).getByText('R$ 60,00')).toBeInTheDocument();
  });

  it('a venda que ainda não chegou à loja diz que as parcelas aparecem depois', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () => recebiveis([vendaAPrazo({ parcelas: [], aguardandoEnvio: true })]),
    });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    expect(await screen.findByText(/As parcelas aparecem aqui depois/)).toBeInTheDocument();
    expect(screen.getByText('Aguardando envio')).toBeInTheDocument();
    expect(screen.getByText(/Estimativa:/)).toBeInTheDocument();
  });

  it('sem nada a receber diz isso', async () => {
    const { ponte } = ponteDe({ 'recebiveis.ver': () => recebiveis([]) });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    expect(
      await screen.findByText('Nenhum cliente está devendo. Tudo recebido.'),
    ).toBeInTheDocument();
  });

  it('receber: manda id, venda, valor e forma (só Dinheiro e Transferência), sem parcela nem total', async () => {
    const { ponte, daOperacao } = ponteDe({
      'recebiveis.ver': () => recebiveis([vendaAPrazo()]),
      'recebimentos.receber': () => ({ ok: true, dados: vendaGravada('100.00', '70.00') }),
    });
    const u = await abrirDosNumeros(ponte, 'A receber dos clientes');
    await u.click(await screen.findByRole('button', { name: 'Receber de Maria Souza' }));
    expect(screen.getByLabelText('Dinheiro')).toBeChecked();
    expect(screen.getByLabelText('Transferência')).toBeInTheDocument();
    expect(screen.queryByLabelText(/cheque|cart[ãa]o|boleto|entrada/i)).not.toBeInTheDocument();
    await u.type(screen.getByLabelText('Valor recebido (R$)'), '30,00');
    await u.click(screen.getByLabelText('Transferência'));
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(await screen.findByText('Recebimento registrado.')).toBeInTheDocument();
    const pedido = daOperacao('recebimentos.receber')[0];
    if (!pedido || pedido.operacao !== 'recebimentos.receber') throw new Error('sem pedido');
    expect(pedido.recebimento).toMatchObject({ vendaId: VENDA, valor: '30.00', forma: 'PIX' });
    expect(pedido.recebimento.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(Object.keys(pedido.recebimento).sort()).toEqual(['forma', 'id', 'valor', 'vendaId']);
  });

  it('valor vazio, zero ou fora do formato é avisado antes de enviar', async () => {
    const { ponte, daOperacao } = ponteDe({ 'recebiveis.ver': () => recebiveis([vendaAPrazo()]) });
    const u = await abrirDosNumeros(ponte, 'A receber dos clientes');
    await u.click(await screen.findByRole('button', { name: 'Receber de Maria Souza' }));
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(
      await screen.findByText('Digite o valor recebido em reais, por exemplo 50,00.'),
    ).toBeInTheDocument();
    await u.type(screen.getByLabelText('Valor recebido (R$)'), '0');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(
      await screen.findByText('Informe o valor recebido, maior que zero.'),
    ).toBeInTheDocument();
    expect(daOperacao('recebimentos.receber')).toHaveLength(0);
  });

  it('o valor acima do que falta mostra a frase do computador, sem montar texto próprio', async () => {
    const mensagem = 'O valor passa do que falta pagar nesta venda (R$ 60,00). Confira o valor.';
    const { ponte } = ponteDe({
      'recebiveis.ver': () => recebiveis([vendaAPrazo()]),
      'recebimentos.receber': () => ({ ok: false, motivo: 'erro', mensagem }),
    });
    const u = await abrirDosNumeros(ponte, 'A receber dos clientes');
    await u.click(await screen.findByRole('button', { name: 'Receber de Maria Souza' }));
    await u.type(screen.getByLabelText('Valor recebido (R$)'), '99,00');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(await screen.findByText(mensagem)).toBeInTheDocument();
  });

  it('tentar de novo depois de um erro usa o mesmo id do recebimento', async () => {
    let tentativa = 0;
    const { ponte, daOperacao } = ponteDe({
      'recebiveis.ver': () => recebiveis([vendaAPrazo()]),
      'recebimentos.receber': () => {
        tentativa += 1;
        return tentativa === 1
          ? { ok: false, motivo: 'erro', mensagem: 'Não deu agora.' }
          : { ok: true, dados: vendaGravada('100.00', '70.00') };
      },
    });
    const u = await abrirDosNumeros(ponte, 'A receber dos clientes');
    await u.click(await screen.findByRole('button', { name: 'Receber de Maria Souza' }));
    await u.type(screen.getByLabelText('Valor recebido (R$)'), '30,00');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await screen.findByText('Não deu agora.');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await screen.findByText('Recebimento registrado.');
    const ids = daOperacao('recebimentos.receber').map((c) =>
      c.operacao === 'recebimentos.receber' ? c.recebimento.id : '',
    );
    expect(ids).toHaveLength(2);
    expect(ids[0]).toBe(ids[1]);
  });

  const recebimento = (extra: Partial<VendaAPrazo['recebimentos'][number]> = {}) => ({
    id: RECEBIMENTO,
    valor: '10.00',
    forma: 'PIX',
    em: '2026-11-12T13:00:00.000Z',
    estornado: false,
    podeEstornar: true,
    aguardandoEnvio: false,
    ...extra,
  });

  it('estornar no mesmo dia: pede o motivo e manda os dois ids', async () => {
    const { ponte, daOperacao } = ponteDe({
      'recebiveis.ver': () => recebiveis([vendaAPrazo({ recebimentos: [recebimento()] })]),
      'recebimentos.estornar': () => ({ ok: true, dados: vendaGravada('100.00', '40.00') }),
    });
    const u = await abrirDosNumeros(ponte, 'A receber dos clientes');
    await u.click(await screen.findByRole('button', { name: 'Estornar recebimento' }));
    await u.click(screen.getByRole('button', { name: 'Estornar' }));
    expect(await screen.findByText('Diga o motivo de estornar o recebimento.')).toBeInTheDocument();
    expect(daOperacao('recebimentos.estornar')).toHaveLength(0);
    await u.type(screen.getByLabelText('Por que está estornando?'), 'Valor errado');
    await u.click(screen.getByRole('button', { name: 'Estornar' }));
    expect(await screen.findByText('Recebimento estornado.')).toBeInTheDocument();
    const pedido = daOperacao('recebimentos.estornar')[0];
    if (!pedido || pedido.operacao !== 'recebimentos.estornar') throw new Error('sem pedido');
    expect(pedido).toMatchObject({ id: RECEBIMENTO, motivo: 'Valor errado' });
    expect(pedido.estornoId).toMatch(/^[0-9a-f-]{36}$/);
    expect(pedido.estornoId).not.toBe(RECEBIMENTO);
  });

  it('o recebimento de outro dia não tem o botão de estornar e diz que só a dona estorna', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () =>
        recebiveis([
          vendaAPrazo({
            recebimentos: [recebimento({ podeEstornar: false, em: '2026-11-10T13:00:00.000Z' })],
          }),
        ]),
    });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    await screen.findByRole('region', { name: /Maria Souza/ });
    expect(screen.queryByRole('button', { name: 'Estornar recebimento' })).not.toBeInTheDocument();
    expect(
      screen.getByText('Depois do dia do recebimento, só a dona pode estornar.'),
    ).toBeInTheDocument();
  });

  it('o recebimento já estornado aparece como Estornado, sem botão', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () =>
        recebiveis([
          vendaAPrazo({ recebimentos: [recebimento({ estornado: true, podeEstornar: false })] }),
        ]),
    });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    const venda = await screen.findByRole('region', { name: /Maria Souza/ });
    expect(within(venda).getByText('Estornado')).toBeInTheDocument();
    expect(within(venda).queryByRole('button', { name: 'Estornar recebimento' })).toBeNull();
  });
});

describe('A receber: o que o vendedor deve à loja', () => {
  const conta = (extra: Partial<RecebiveisDoVendedor['daLoja'][number]> = {}) => ({
    id: 'c1',
    numero: 1,
    total: 1,
    vencimento: '2026-11-20',
    valor: '80.00',
    valorPago: '20.00',
    valorCancelado: '0.00',
    situacao: 'PARCIAL' as const,
    vencida: false,
    formaPrevista: null,
    acertoId: ACERTO,
    ...extra,
  });

  it('lista as contas da loja; o boleto aparece como conta, lido do servidor', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () => ({
        ok: true,
        dados: {
          dosClientes: [],
          daLoja: [
            conta(),
            conta({ id: 'c2', formaPrevista: 'BOLETO', valorPago: '0.00', situacao: 'ABERTA' }),
          ],
        },
      }),
    });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    const loja = await screen.findByRole('region', { name: 'O que você deve à loja' });
    expect(within(loja).getAllByText('R$ 80,00')).toHaveLength(2);
    expect(within(loja).getByText('Paga em parte')).toBeInTheDocument();
    expect(within(loja).getByText('Boleto')).toBeInTheDocument();
    expect(within(loja).getByText(/O boleto é uma conta/)).toBeInTheDocument();
    // Nunca cheque, cartão nem entrada.
    expect(loja.textContent ?? '').not.toMatch(/cheque|cart[ãa]o|entrada/i);
  });

  it('sem conta em aberto diz que não há nada com a loja', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () => ({ ok: true, dados: { dosClientes: [], daLoja: [] } }),
    });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    expect(await screen.findByText('Você não tem nada em aberto com a loja.')).toBeInTheDocument();
  });

  it('nada de custo, caixa nem contas a pagar na tela', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () => ({
        ok: true,
        dados: { dosClientes: [vendaAPrazo()], daLoja: [conta()] },
      }),
    });
    await abrirDosNumeros(ponte, 'A receber dos clientes');
    await screen.findByRole('region', { name: 'O que você deve à loja' });
    expect(document.body.textContent ?? '').not.toMatch(/custo|caixa|contas a pagar|fornecedor/i);
  });
});

describe('Meu acerto', () => {
  const previa = (extra: Partial<PreviaDaComissao> = {}): PreviaDaComissao => ({
    vendidoParaMetaGuarani: 500000,
    metaGuarani: 700000,
    percentualQueValeriaHoje: '30.00',
    faltaParaMetaGuarani: 200000,
    faltaParaMetaReais: '133.33',
    comissaoPrevista: '45.00',
    cotacaoData: '2026-11-12',
    ...extra,
  });
  const resumo = (extra: Partial<ResumoDoAcerto> = {}): ResumoDoAcerto => ({
    id: ACERTO,
    semanaNumero: 46,
    situacao: 'ACERTADO',
    pedidoPeloVendedor: true,
    iniciadoEm: '2026-11-09T12:00:00Z',
    fechadoEm: '2026-11-10T15:00:00Z',
    ...extra,
  });

  it('a prévia mostra quanto falta para a meta em Guarani e em Real, como estimativa', async () => {
    const { ponte, daOperacao } = ponteDe({
      'comissao.previa': () => ({ ok: true, dados: previa() }),
      'acertos.listar': () => ({ ok: true, dados: [] }),
    });
    await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    const secao = await screen.findByRole('region', { name: 'Minha comissão desta semana' });
    expect(within(secao).getByText(/é só uma estimativa/)).toBeInTheDocument();
    expect(within(secao).getByText(/Gs\. 200\.000/)).toBeInTheDocument();
    expect(within(secao).getByText(/R\$ 133,33/)).toBeInTheDocument();
    expect(within(secao).getByText(/Gs\. 500\.000/)).toBeInTheDocument();
    expect(within(secao).getByText(/Gs\. 700\.000/)).toBeInTheDocument();
    expect(within(secao).getByText(/30%/)).toBeInTheDocument();
    expect(within(secao).getByText(/R\$ 45,00/)).toBeInTheDocument();
    expect(daOperacao('comissao.previa')).toHaveLength(1);
  });

  it('com a meta batida (falta zero) diz que bateu, sem mostrar "falta"', async () => {
    const { ponte } = ponteDe({
      'comissao.previa': () => ({
        ok: true,
        dados: previa({
          faltaParaMetaGuarani: 0,
          faltaParaMetaReais: '0.00',
          vendidoParaMetaGuarani: 750000,
          percentualQueValeriaHoje: '40.00',
        }),
      }),
      'acertos.listar': () => ({ ok: true, dados: [] }),
    });
    await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    expect(await screen.findByText('Você já bateu a meta da semana.')).toBeInTheDocument();
    expect(screen.queryByText(/Falta para a meta/)).not.toBeInTheDocument();
  });

  it('sem internet a prévia explica e o resto da tela continua (acertos do computador)', async () => {
    const { ponte } = ponteDe({
      'comissao.previa': () => ({ ok: false, motivo: 'sem-conexao' }),
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
    });
    await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    expect(await screen.findByText(/A prévia da comissão precisa de internet/)).toBeInTheDocument();
    expect(await screen.findByText('Semana 46')).toBeInTheDocument();
  });

  it('lista os acertos com a situação e abre a ficha do fechado', async () => {
    const ficha: FichaDoAcerto = {
      ...resumo(),
      fechamento: {
        fechadoEm: '2026-11-10T15:00:00Z',
        cotacao: { data: '2026-11-10', dolar: '5.00', guarani: '0.00070000' },
        metaGuarani: 700000,
        percentualComissao: '40.00',
        vendidoParaMetaGuarani: 720000,
        vendidoBruto: '500.00',
        descontos: '20.00',
        devolvido: '30.00',
        vendidoLiquido: '450.00',
        comissao: '180.00',
        faltasCobradas: '10.00',
        liquido: '280.00',
        recebidoNoAcerto: '200.00',
        ficouDevendo: '80.00',
        pagoAoVendedor: null,
        creditoUsado: null,
        itens: [
          {
            produto: LINHA_ANEL.produto,
            esperado: 5,
            contadoBoa: 3,
            contadoDefeito: 1,
            falta: 1,
            defeitoAprovado: 0,
          },
        ],
        comissoes: [
          {
            vendaId: VENDA,
            clienteNome: 'Maria Souza',
            tipo: 'COMISSAO',
            base: '100.00',
            percentual: '40.00',
            valor: '40.00',
          },
        ],
        pendencias: [
          {
            id: 'pe1',
            tipo: 'FALTA',
            produto: LINHA_ANEL.produto,
            quantidade: 1,
            valor: '50.00',
            status: 'COBRADA',
          },
        ],
        recebimentos: [
          {
            id: 'r1',
            forma: 'PIX',
            valor: '200.00',
            aplicadoEm: 'ACERTO',
            em: '2026-11-10T15:00:00Z',
            estornado: false,
          },
        ],
        contas: [],
      },
    };
    const { ponte, daOperacao } = ponteDe({
      'comissao.previa': () => ({ ok: true, dados: previa() }),
      'acertos.listar': () => ({
        ok: true,
        dados: [
          resumo(),
          resumo({ id: 'x', situacao: 'EM_ACERTO', fechadoEm: null, semanaNumero: 47 }),
        ],
      }),
      'acertos.ver': () => ({ ok: true, dados: ficha }),
    });
    const u = await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    expect(await screen.findByText('Semana 47')).toBeInTheDocument();
    expect(screen.getByText('Em acerto')).toBeInTheDocument();
    await u.click(await screen.findByRole('button', { name: 'Ver acerto da semana 46' }));
    expect(daOperacao('acertos.ver')[0]).toMatchObject({ id: ACERTO });
    const resumoDoAcerto = await screen.findByRole('region', { name: 'Resumo do acerto' });
    expect(within(resumoDoAcerto).getByText('R$ 180,00')).toBeInTheDocument(); // comissão, como o servidor gravou
    expect(within(resumoDoAcerto).getByText('R$ 280,00')).toBeInTheDocument(); // líquido
    expect(within(resumoDoAcerto).getByText('R$ 80,00')).toBeInTheDocument(); // ficou devendo
    expect(
      within(resumoDoAcerto).getByText(/Comissão de 40.00% sobre as vendas/),
    ).toBeInTheDocument();
    expect(within(resumoDoAcerto).getByText(/Gs\. 700\.000/)).toBeInTheDocument();
    expect(
      screen.getByText(
        'Valores em Dólar e Guarani pela cotação de 10/11/2026. Vale o valor em reais.',
      ),
    ).toBeInTheDocument();
    // Peças, comissão de cada venda, pendências e o que pagou.
    expect(screen.getByRole('region', { name: 'Peças do acerto' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Comissão de cada venda' })).toHaveTextContent(
      'Maria Souza',
    );
    expect(screen.getByRole('region', { name: 'Pendências' })).toHaveTextContent('Peça que faltou');
    expect(screen.getByRole('region', { name: 'Pendências' })).toHaveTextContent('Cobrada');
    expect(screen.getByRole('region', { name: 'O que você pagou neste acerto' })).toHaveTextContent(
      'Pagou o acerto',
    );
    expect(document.body.textContent ?? '').not.toMatch(/custo|caixa/i);
  });

  it('acerto a favor do vendedor (líquido negativo) diz que a loja deve a ele', async () => {
    const ficha: FichaDoAcerto = {
      ...resumo(),
      fechamento: {
        fechadoEm: '2026-11-10T15:00:00Z',
        cotacao: null,
        metaGuarani: 700000,
        percentualComissao: '30.00',
        vendidoParaMetaGuarani: 100000,
        vendidoBruto: '100.00',
        descontos: '0.00',
        devolvido: '0.00',
        vendidoLiquido: '100.00',
        comissao: '135.00',
        faltasCobradas: '0.00',
        liquido: '-35.00',
        recebidoNoAcerto: '0.00',
        ficouDevendo: '-35.00',
        pagoAoVendedor: null,
        creditoUsado: null,
        itens: [],
        comissoes: [],
        pendencias: [],
        recebimentos: [],
        contas: [],
      },
    };
    const { ponte } = ponteDe({
      'comissao.previa': () => ({ ok: false, motivo: 'sem-conexao' }),
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
      'acertos.ver': () => ({ ok: true, dados: ficha }),
    });
    const u = await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    await u.click(await screen.findByRole('button', { name: 'Ver acerto da semana 46' }));
    expect(
      await screen.findByText(
        'A loja deve R$ 35,00 a você. O valor será abatido no próximo acerto.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('Sem cotação: os valores aparecem só em reais.')).toBeInTheDocument();
  });

  it('o acerto em andamento e o desistido explicam, sem valores', async () => {
    const { ponte } = ponteDe({
      'comissao.previa': () => ({ ok: false, motivo: 'sem-conexao' }),
      'acertos.listar': () => ({
        ok: true,
        dados: [resumo({ situacao: 'EM_ACERTO', fechadoEm: null })],
      }),
      'acertos.ver': () => ({
        ok: true,
        dados: { ...resumo({ situacao: 'EM_ACERTO', fechadoEm: null }), fechamento: null },
      }),
    });
    const u = await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    await u.click(await screen.findByRole('button', { name: 'Ver acerto da semana 46' }));
    expect(await screen.findByText(/Este acerto está acontecendo/)).toBeInTheDocument();
  });

  it('sem acertos diz isso', async () => {
    const { ponte } = ponteDe({
      'comissao.previa': () => ({ ok: true, dados: previa() }),
      'acertos.listar': () => ({ ok: true, dados: [] }),
    });
    await abrirDosNumeros(ponte, 'Meu acerto e minha comissão');
    expect(await screen.findByText('Você ainda não tem acerto.')).toBeInTheDocument();
  });
});

describe('palavras proibidas nas telas novas (glossário, parte 2)', () => {
  const raiz = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
  const glossario = readFileSync(join(raiz, 'docs', 'glossario-interface.md'), 'utf8');
  const bloco = (nome: string): string[] => {
    const inicio = glossario.indexOf(`<!-- ${nome}:INICIO -->`);
    const abre = glossario.indexOf('```', inicio);
    const fecha = glossario.indexOf('```', abre + 3);
    return glossario
      .slice(abre + 3, fecha)
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
  };
  const blocoA = bloco('PROIBIDAS-A').map((p) => p.toLowerCase());
  const blocoB = [...bloco('PROIBIDAS-B'), 'EM_ACERTO', 'ACERTADO', 'PARCIAL'];
  const extras = ['pendente', 'fila', 'sincroniz', 'corona gestão'];

  function conferir(texto: string) {
    expect(blocoA.length).toBeGreaterThan(0);
    const minusculo = texto.toLowerCase();
    for (const p of [...blocoA, ...extras]) {
      const regra = p.length <= 5 ? new RegExp(`\\b${p}\\b`) : null;
      expect(regra ? regra.test(minusculo) : minusculo.includes(p), p).toBe(false);
    }
    for (const p of blocoB) expect(texto.includes(p), p).toBe(false);
  }

  it('A receber, Meu acerto e as parcelas', async () => {
    const { ponte } = ponteDe({
      'recebiveis.ver': () => ({
        ok: true,
        dados: {
          dosClientes: [vendaAPrazo()],
          daLoja: [
            {
              id: 'c1',
              numero: 1,
              total: 1,
              vencimento: '2026-12-01',
              valor: '80.00',
              valorPago: '0.00',
              valorCancelado: '0.00',
              situacao: 'ABERTA',
              vencida: false,
              formaPrevista: 'BOLETO',
              acertoId: ACERTO,
            },
          ],
        },
      }),
      'comissao.previa': () => ({
        ok: true,
        dados: {
          vendidoParaMetaGuarani: 1,
          metaGuarani: 700000,
          percentualQueValeriaHoje: '30.00',
          faltaParaMetaGuarani: 699999,
          faltaParaMetaReais: '10.00',
          comissaoPrevista: '0.00',
          cotacaoData: null,
        },
      }),
      'acertos.listar': () => ({
        ok: true,
        dados: [
          {
            id: ACERTO,
            semanaNumero: 46,
            situacao: 'ACERTADO',
            pedidoPeloVendedor: false,
            iniciadoEm: null,
            fechadoEm: null,
          },
        ],
      }),
    });
    const u = await abrirDosNumeros(ponte, 'A receber dos clientes');
    await screen.findByRole('region', { name: 'O que você deve à loja' });
    conferir(document.body.textContent ?? '');
    await u.click(screen.getByRole('button', { name: 'Receber de Maria Souza' }));
    conferir(document.body.textContent ?? '');
    await u.click(screen.getByRole('button', { name: 'Voltar' }));
    await u.click(await screen.findByRole('button', { name: 'Meu acerto e minha comissão' }));
    await screen.findByText('Semana 46');
    conferir(document.body.textContent ?? '');
  });
});

// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Cliente, RelatorioAReceber, RelatorioDeVendas, Venda } from '../shared/dados';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';

const MARIA = '44444444-4444-4444-8444-444444444444';
const MALETA = '33333333-3333-4333-8333-333333333333';

const maria: Cliente = {
  id: MARIA,
  nome: 'Maria Souza',
  cpf: '52998224725',
  dataNascimento: '1990-05-01',
  telefone: '(11) 98888-7777',
  cidade: 'Ponta Porã',
  meu: true,
  whatsapp: null,
  observacoes: 'Prefere ouro',
  ativo: true,
};

const venda: Venda = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  remessaId: MALETA,
  status: 'CONCLUIDA',
  subtotal: '100.00',
  desconto: '0.00',
  total: '100.00',
  totalDolar: '25.00',
  totalGuarani: '125000',
  // A cotação do dia da venda (US$ 4,00); a de hoje, no teste, é 5,00.
  cotacao: { data: '2026-11-04', dolar: '4.00000000', guarani: '0.00080000' },
  pago: '60.00',
  aReceber: '40.00',
  vendidaEm: '2026-11-11T15:00:00Z',
  estornadaEm: null,
  motivoDoEstorno: null,
  itens: [],
  pagamentos: [],
};

const totais = (total: string) => ({
  total,
  totalEmDolar: null,
  totalEmGuarani: null,
  dataDaCotacao: null,
});

const vendasPorCliente: RelatorioDeVendas = {
  // O servidor já somou cada venda pela cotação do dia dela: US$ 45,00 (pela de hoje daria 40,00).
  linhas: [
    {
      id: MARIA,
      nome: 'Maria Souza',
      quantidade: 2,
      total: '200.00',
      desconto: '10.00',
      totalEmDolar: '45.00',
      totalEmGuarani: '262500',
      descontoEmDolar: '2.25',
      descontoEmGuarani: '13125',
    },
  ],
  totais: { ...totais('200.00'), totalEmDolar: '45.00', totalEmGuarani: '262500' },
};
const recebimentos: RelatorioDeVendas = {
  linhas: [
    { id: 'PIX', nome: 'PIX', quantidade: 1, total: '100.00', desconto: '0.00' },
    { id: 'DINHEIRO', nome: 'DINHEIRO', quantidade: 2, total: '60.00', desconto: '0.00' },
  ],
  totais: totais('160.00'),
};
const aReceber: RelatorioAReceber = {
  clientes: [
    {
      clienteId: MARIA,
      clienteNome: 'Maria Souza',
      vendido: '200.00',
      pago: '160.00',
      aReceber: '40.00',
      vendidoEmDolar: '45.00',
      vendidoEmGuarani: '262500',
      pagoEmDolar: '35.00',
      pagoEmGuarani: '212500',
      aReceberEmDolar: '10.00',
      aReceberEmGuarani: '50000',
    },
  ],
  totais: totais('40.00'),
};

const devolucoesDoPeriodo = {
  linhas: [
    {
      motivo: 'ARREPENDIMENTO',
      devolucoes: 2,
      valor: '200.00',
      valorEmDolar: '50.00',
      valorEmGuarani: '100000',
    },
    {
      motivo: 'DEFEITO',
      devolucoes: 1,
      valor: '80.00',
      valorEmDolar: '20.00',
      valorEmGuarani: '40000',
    },
  ],
  totais: {
    devolucoes: 2,
    trocas: 1,
    valorDevolvido: '280.00',
    valorDevolvidoEmDolar: '70.00',
    valorDevolvidoEmGuarani: '140000',
    emDinheiro: '180.00',
    emDinheiroEmDolar: '45.00',
    emDinheiroEmGuarani: '90000',
    emCredito: '100.00',
    emCreditoEmDolar: '25.00',
    emCreditoEmGuarani: '50000',
  },
};
const creditosDosClientes = {
  clientes: [
    {
      clienteId: MARIA,
      clienteNome: 'Maria Souza',
      gerado: '80.00',
      geradoEmDolar: '20.00',
      geradoEmGuarani: '40000',
      usado: '50.00',
      usadoEmDolar: '12.50',
      usadoEmGuarani: '25000',
      emAberto: '30.00',
      emAbertoEmDolar: '7.50',
      emAbertoEmGuarani: '15000',
    },
  ],
  totais: {
    gerado: '80.00',
    geradoEmDolar: '20.00',
    geradoEmGuarani: '40000',
    usado: '50.00',
    usadoEmDolar: '12.50',
    usadoEmGuarani: '25000',
    emAberto: '30.00',
    emAbertoEmDolar: '7.50',
    emAbertoEmGuarani: '15000',
  },
};
const semDevolucoes = {
  linhas: [],
  totais: {
    devolucoes: 0,
    trocas: 0,
    valorDevolvido: '0.00',
    valorDevolvidoEmDolar: '0.00',
    valorDevolvidoEmGuarani: '0',
    emDinheiro: '0.00',
    emDinheiroEmDolar: '0.00',
    emDinheiroEmGuarani: '0',
    emCredito: '0.00',
    emCreditoEmDolar: '0.00',
    emCreditoEmGuarani: '0',
  },
};

type Manipulador = (pedido: PedidoDeChamada) => ResultadoDeChamada;

function ponteDeConsulta(
  manipuladores: Partial<Record<PedidoDeChamada['operacao'], Manipulador>> = {},
) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'clientes.listar': () => ({ ok: true, dados: [maria] }),
    'vendas.listar': () => ({ ok: true, dados: [venda] }),
    'relatorios.vendas': () => ({ ok: true, dados: vendasPorCliente }),
    'relatorios.recebimentos': () => ({ ok: true, dados: recebimentos }),
    'relatorios.aReceber': () => ({ ok: true, dados: aReceber }),
    'relatorios.devolucoes': () => ({ ok: true, dados: devolucoesDoPeriodo }),
    'relatorios.creditos': () => ({ ok: true, dados: creditosDosClientes }),
    'cotacao.hoje': () => ({
      ok: true,
      dados: {
        disponivel: true,
        dataDeHoje: '2026-11-11',
        dataDaCotacao: '2026-11-11',
        dolar: '5.00000000',
        guarani: '0.00070000',
        deDiaAnterior: false,
      },
    }),
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
    lerIdioma: () => Promise.resolve(null),
    gravarIdioma: () => Promise.resolve(),
  };
  return { ponte, daOperacao: (op: string) => chamadas.filter((c) => c.operacao === op) };
}

async function abrir(ponte: PonteDoCorona, botao: string) {
  const u = userEvent.setup();
  render(<App ponte={ponte} />);
  await u.click(await screen.findByRole('button', { name: botao }));
  return u;
}

describe('Clientes', () => {
  it('lista só o que o servidor mandou, com o que cada um deve, e busca pelo texto digitado', async () => {
    const { ponte, daOperacao } = ponteDeConsulta();
    const u = await abrir(ponte, 'Clientes');
    expect(await screen.findByText('Maria Souza')).toBeInTheDocument();
    expect(await screen.findByText('R$ 40,00')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Buscar (nome, telefone ou CPF)'), 'maria');
    await vi.waitFor(() =>
      expect(daOperacao('clientes.listar').some((c) => 'busca' in c && c.busca === 'maria')).toBe(
        true,
      ),
    );
  });

  it('sem clientes convida a cadastrar o primeiro; busca sem resultado diz "Nenhum cliente encontrado"', async () => {
    const { ponte } = ponteDeConsulta({ 'clientes.listar': () => ({ ok: true, dados: [] }) });
    const u = await abrir(ponte, 'Clientes');
    expect(await screen.findByText(/Ainda não há clientes/)).toBeInTheDocument();
    await u.type(screen.getByLabelText('Buscar (nome, telefone ou CPF)'), 'zzz');
    expect(await screen.findByText('Nenhum cliente encontrado.')).toBeInTheDocument();
  });

  it('a ficha mostra o contato, o que a pessoa deve e as compras dela', async () => {
    const { ponte, daOperacao } = ponteDeConsulta();
    const u = await abrir(ponte, 'Clientes');
    await u.click(await screen.findByRole('button', { name: 'Ver: Maria Souza' }));
    expect(await screen.findByText(/\(11\) 98888-7777/)).toBeInTheDocument();
    expect(screen.getByText(/Prefere ouro/)).toBeInTheDocument();
    expect(screen.getByText(/01\/05\/1990/)).toBeInTheDocument();
    const tabela = await screen.findByRole('table', { name: 'Vendas deste cliente' });
    expect(within(tabela).getByText('R$ 100,00')).toBeInTheDocument();
    // A venda pela cotação do dia dela (US$ 4,00): US$ 25,00, e não US$ 20,00 pela de hoje.
    expect(within(tabela).getByText(/US\$ 25,00/)).toBeInTheDocument();
    expect(within(tabela).queryByText(/US\$ 20,00/)).toBeNull();
    expect(daOperacao('vendas.listar').some((c) => 'clienteId' in c && c.clienteId === MARIA)).toBe(
      true,
    );
  });

  it('cadastra um cliente novo e abre a ficha dele', async () => {
    const novo: Cliente = {
      ...maria,
      id: '55555555-5555-4555-8555-555555555555',
      nome: 'Joana Lima',
    };
    const { ponte, daOperacao } = ponteDeConsulta({
      'clientes.cadastrar': () => ({ ok: true, dados: novo }),
    });
    const u = await abrir(ponte, 'Clientes');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar cliente' }));
    await u.type(await screen.findByLabelText('Nome'), 'Joana Lima');
    await u.click(screen.getByRole('button', { name: 'Salvar cliente' }));
    expect(await screen.findByText('Cliente cadastrado.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Cliente: Joana Lima' })).toBeInTheDocument();
    expect(daOperacao('clientes.cadastrar')).toHaveLength(1);
  });

  it('cliente de outro vendedor aparece só com o diretório: nome, telefone e cidade, sem nascimento nem observações', async () => {
    const deOutro: Cliente = {
      ...maria,
      id: '66666666-6666-4666-8666-666666666666',
      nome: 'Rosa Prado',
      cidade: 'Dourados',
      meu: false,
      dataNascimento: null,
      whatsapp: null,
      observacoes: null,
    };
    const { ponte } = ponteDeConsulta({
      'clientes.listar': () => ({ ok: true, dados: [deOutro] }),
    });
    const u = await abrir(ponte, 'Clientes');
    expect(await screen.findByText('Rosa Prado')).toBeInTheDocument();
    expect(screen.getByText('Dourados')).toBeInTheDocument();
    await u.click(await screen.findByRole('button', { name: 'Ver: Rosa Prado' }));
    expect(await screen.findByText(/\(11\) 98888-7777/)).toBeInTheDocument();
    expect(screen.queryByText(/Observações/)).toBeNull();
    expect(screen.queryByText(/Nascimento/)).toBeNull();
  });

  it('CPF repetido: avisa que o cliente já está cadastrado e deixa usar o que já existe', async () => {
    const existente: Cliente = { ...maria, meu: false, observacoes: null, dataNascimento: null };
    const { ponte, daOperacao } = ponteDeConsulta({
      'clientes.cadastrar': () => ({
        ok: false,
        motivo: 'erro',
        mensagem: 'Este cliente já está cadastrado: Maria Souza. Escolha o cliente que já existe.',
        clienteExistente: { id: MARIA, nome: 'Maria Souza', telefone: '(11) 98888-7777' },
      }),
      'clientes.ver': () => ({ ok: true, dados: existente }),
    });
    const u = await abrir(ponte, 'Clientes');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar cliente' }));
    await u.type(await screen.findByLabelText('Nome'), 'Maria S.');
    await u.type(screen.getByLabelText('CPF (opcional)'), '52998224725');
    await u.click(screen.getByRole('button', { name: 'Salvar cliente' }));
    expect(
      await screen.findByText('Este cliente já está cadastrado: Maria Souza.'),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Usar este cliente' }));
    expect(
      await screen.findByRole('heading', { name: 'Cliente: Maria Souza' }),
    ).toBeInTheDocument();
    expect(daOperacao('clientes.ver').some((c) => 'id' in c && c.id === MARIA)).toBe(true);
    expect(daOperacao('clientes.cadastrar')).toHaveLength(1);
  });

  it('mostra a recusa do servidor no cadastro (por exemplo, CPF errado)', async () => {
    const mensagem = 'Confira o CPF: ele não parece estar certo.';
    const { ponte } = ponteDeConsulta({
      'clientes.cadastrar': () => ({ ok: false, motivo: 'erro', mensagem }),
    });
    const u = await abrir(ponte, 'Clientes');
    await u.click(await screen.findByRole('button', { name: 'Cadastrar cliente' }));
    await u.type(await screen.findByLabelText('Nome'), 'Joana');
    await u.type(screen.getByLabelText('CPF (opcional)'), '111');
    await u.click(screen.getByRole('button', { name: 'Salvar cliente' }));
    expect(await screen.findByText(mensagem)).toBeInTheDocument();
  });
});

describe('Meus números', () => {
  it('mostra vendas, recebimentos por forma e o que os clientes devem, em Real, Dólar e Guarani', async () => {
    const { ponte } = ponteDeConsulta();
    await abrir(ponte, 'Meus números');
    expect(await screen.findByText('Minhas vendas no período')).toBeInTheDocument();
    const vendas = await screen.findByRole('table', { name: /Minhas vendas no período/ });
    expect(within(vendas).getByText('Maria Souza')).toBeInTheDocument();
    expect(within(vendas).getAllByText('R$ 200,00').length).toBeGreaterThan(0);
    // Os valores convertidos vêm do servidor (cada venda pela cotação do seu dia), nunca da cotação de hoje.
    expect(within(vendas).getAllByText(/US\$ 45,00/).length).toBeGreaterThan(0);
    expect(within(vendas).queryAllByText(/US\$ 40,00/)).toHaveLength(0);

    const receb = screen.getByRole('table', { name: 'Recebimentos por forma de pagamento' });
    expect(within(receb).getByText('Transferência')).toBeInTheDocument();
    expect(within(receb).getByText('Dinheiro')).toBeInTheDocument();
    expect(screen.getByText(/Total recebido/)).toHaveTextContent('R$ 160,00');

    const deve = screen.getByRole('table', { name: 'Quanto os clientes ainda devem' });
    expect(within(deve).getByText('Maria Souza')).toBeInTheDocument();
    expect(screen.getByText(/Total a receber/)).toHaveTextContent('R$ 40,00');
    expect(within(deve).getAllByText(/US\$ 10,00/).length).toBeGreaterThan(0);
    expect(
      screen.getByText(/pela cotação do dia de cada venda e de cada pagamento/),
    ).toBeInTheDocument();
    expect(screen.getByText(/estornadas não entram/)).toBeInTheDocument();
  });

  it('mostra as devoluções e trocas e o crédito dos clientes, sem o crédito nos recebimentos', async () => {
    const { ponte } = ponteDeConsulta();
    await abrir(ponte, 'Meus números');
    const devolucoes = await screen.findByRole('table', { name: 'Devoluções e trocas no período' });
    expect(within(devolucoes).getByText('Desistência')).toBeInTheDocument();
    expect(within(devolucoes).getByText('Defeito')).toBeInTheDocument();
    expect(within(devolucoes).getAllByText(/US\$ 50,00/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Voltou em dinheiro/)).toHaveTextContent('R$ 180,00');
    expect(screen.getByText(/Voltou em crédito/)).toHaveTextContent('R$ 100,00');
    expect(screen.getByText(/^Devoluções: 2/)).toHaveTextContent('Trocas: 1');

    const creditos = screen.getByRole('table', { name: 'Crédito dos meus clientes' });
    expect(within(creditos).getByText('Maria Souza')).toBeInTheDocument();
    expect(within(creditos).getAllByText(/US\$ 7,50/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Crédito em aberto/)).toHaveTextContent('R$ 30,00');
    expect(
      screen.getByText('O crédito do cliente não entra aqui. Veja Crédito dos meus clientes.'),
    ).toBeInTheDocument();
    // Nenhum custo, nenhum termo técnico.
    expect(devolucoes.textContent + creditos.textContent).not.toMatch(/custo|REEMBOLSO|ESTORNO/i);
  });

  it('muda o período e o agrupamento e pede de novo ao servidor', async () => {
    const { ponte, daOperacao } = ponteDeConsulta();
    const u = await abrir(ponte, 'Meus números');
    await screen.findByText('Minhas vendas no período');
    await u.type(screen.getByLabelText('De'), '2026-11-01');
    await u.type(screen.getByLabelText('Até'), '2026-11-30');
    await u.selectOptions(screen.getByLabelText('Mostrar por'), 'peca');
    await u.click(screen.getByRole('button', { name: 'Ver números' }));
    await vi.waitFor(() => {
      const ultima = daOperacao('relatorios.vendas').at(-1);
      expect(ultima).toMatchObject({ de: '2026-11-01', ate: '2026-11-30', agrupar: 'peca' });
    });
    expect(daOperacao('relatorios.recebimentos').at(-1)).toMatchObject({
      de: '2026-11-01',
      ate: '2026-11-30',
    });
    expect(daOperacao('relatorios.devolucoes').at(-1)).toMatchObject({
      de: '2026-11-01',
      ate: '2026-11-30',
    });
  });

  it('período de trás para frente é avisado antes de pedir', async () => {
    const { ponte, daOperacao } = ponteDeConsulta();
    const u = await abrir(ponte, 'Meus números');
    await screen.findByText('Minhas vendas no período');
    const antes = daOperacao('relatorios.vendas').length;
    await u.type(screen.getByLabelText('De'), '2026-12-01');
    await u.type(screen.getByLabelText('Até'), '2026-11-01');
    await u.click(screen.getByRole('button', { name: 'Ver números' }));
    expect(
      await screen.findByText('A data inicial não pode ser depois da final.'),
    ).toBeInTheDocument();
    expect(daOperacao('relatorios.vendas')).toHaveLength(antes);
  });

  it('sem vendas, sem recebimentos e sem devedores, cada parte diz o que não há', async () => {
    const vazio: RelatorioDeVendas = { linhas: [], totais: totais('0.00') };
    const { ponte } = ponteDeConsulta({
      'relatorios.vendas': () => ({ ok: true, dados: vazio }),
      'relatorios.recebimentos': () => ({ ok: true, dados: vazio }),
      'relatorios.aReceber': () => ({ ok: true, dados: { clientes: [], totais: totais('0.00') } }),
      'relatorios.devolucoes': () => ({ ok: true, dados: semDevolucoes }),
      'relatorios.creditos': () => ({
        ok: true,
        dados: { clientes: [], totais: { ...creditosDosClientes.totais, emAberto: '0.00' } },
      }),
      'cotacao.hoje': () => ({ ok: false, motivo: 'sem-conexao' }),
    });
    await abrir(ponte, 'Meus números');
    expect(await screen.findByText('Nenhuma venda neste período.')).toBeInTheDocument();
    expect(screen.getByText('Nenhum recebimento neste período.')).toBeInTheDocument();
    expect(screen.getByText('Nenhum cliente deve nada agora.')).toBeInTheDocument();
    expect(screen.getByText('Nenhuma devolução ou troca neste período.')).toBeInTheDocument();
    expect(screen.getByText('Nenhum cliente seu tem crédito agora.')).toBeInTheDocument();
    expect(screen.getByText(/Total vendido/)).toHaveTextContent('R$ 0,00');
    expect(screen.getByText(/Total vendido/)).not.toHaveTextContent('US$');
  });

  it('falha do servidor aparece em português e o botão Ver minhas vendas abre a lista', async () => {
    const { ponte } = ponteDeConsulta({
      'relatorios.recebimentos': () => ({ ok: false, motivo: 'sem-conexao' }),
    });
    await abrir(ponte, 'Meus números');
    expect(
      await screen.findByText('Sem internet. Conecte-se e tente de novo.'),
    ).toBeInTheDocument();
  });

  it('Ver minhas vendas leva à lista e o Voltar traz de volta para Meus números', async () => {
    const { ponte } = ponteDeConsulta();
    const u = await abrir(ponte, 'Meus números');
    await u.click(await screen.findByRole('button', { name: 'Ver minhas vendas' }));
    expect(await screen.findByRole('heading', { name: 'Minhas vendas' })).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Voltar' }));
    expect(await screen.findByRole('heading', { name: 'Meus números' })).toBeInTheDocument();
  });
});

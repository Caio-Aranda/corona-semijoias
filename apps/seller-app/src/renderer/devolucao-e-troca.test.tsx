// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';
import type {
  AutorizacoesDoVendedor,
  Cliente,
  DevolucaoOuTroca,
  LinhaDaMaleta,
  Maleta,
  PreviaDaDevolucao,
  PreviaDaTroca,
  Venda,
  VendaComAPeca,
} from '../shared/dados';
import type { PedidoDeChamada, PonteDoCorona, ResultadoDeChamada } from '../shared/ponte';
import { App } from './App';

/**
 * As telas da devolução, troca e crédito no Corona Vendas (tarefa 6.8; plano, teste 16): cada tela e cada botão
 * nomeado, as confirmações no modelo da §19.5 com Voltar, o bloqueio fora do prazo e o pedido de autorização, o "vale
 * até", a revisão da dona, o "aguardando envio", a diferença nos três casos, Usar crédito, a foto e "sem foto", e nenhuma
 * palavra proibida. Escrito antes do código.
 */
const VENDA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const ANEL = '11111111-1111-4111-8111-111111111111';
const COLAR = '22222222-2222-4222-8222-222222222222';
const MALETA = '33333333-3333-4333-8333-333333333333';
const MARIA = '44444444-4444-4444-8444-444444444444';
const CREDITO = '55555555-5555-4555-8555-555555555555';

const peca = (id: string, nome: string, fotoHash: string | null = null) => ({
  id,
  nome,
  codigo: 'A-1',
  fotoHash,
  categoria: null,
  codigoFornecedor: null,
});

const vendaComAPeca = (extra: Partial<VendaComAPeca> = {}): VendaComAPeca => ({
  vendaId: VENDA,
  vendidaEm: '2026-11-12T13:00:00.000Z',
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  peca: peca(ANEL, 'Anel de prata', 'f'.repeat(64)),
  vendidas: 2,
  jaDevolvidas: 0,
  podemVoltar: 2,
  desistenciaAte: '2026-11-19',
  defeitoAte: '2026-12-12',
  bloqueio: null,
  ...extra,
});

const previa = (extra: Partial<PreviaDaDevolucao> = {}): PreviaDaDevolucao => ({
  vendaId: VENDA,
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  vendidaEm: '2026-11-12T13:00:00.000Z',
  remessaId: MALETA,
  pecas: [{ produto: peca(ANEL, 'Anel de prata'), vendidas: 2, jaDevolvidas: 0, podemVoltar: 2 }],
  valor: '50.00',
  abatido: '0.00',
  emDinheiro: '50.00',
  emCredito: '0.00',
  destinos: [{ produtoId: ANEL, estado: 'BOA', destino: 'MALETA' }],
  dentroDoPrazo: true,
  desistenciaAte: '2026-11-19',
  defeitoAte: '2026-12-12',
  autorizacao: null,
  pedidoEsperando: false,
  problema: null,
  podePedirAutorizacao: false,
  ...extra,
});

const linhaDaMaleta = (id: string, nome: string, preco: string): LinhaDaMaleta => ({
  produto: peca(id, nome),
  enviada: 3,
  vendida: 0,
  devolvida: 0,
  saldo: 3,
  precoUnitario: preco,
  valorEmAberto: '0.00',
});

const maleta: Maleta = {
  id: MALETA,
  numero: 'S12-1',
  semanaNumero: 12,
  tipo: 'NOVA',
  status: 'CONFERIDA',
  enviadaEm: '2026-11-10T12:00:00Z',
  pecasEmAberto: 6,
  valorEmAberto: '0.00',
  itens: [
    linhaDaMaleta(ANEL, 'Anel de prata', '50.00'),
    linhaDaMaleta(COLAR, 'Colar dourado', '80.00'),
  ],
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

const venda: Venda = {
  id: VENDA,
  clienteId: MARIA,
  clienteNome: 'Maria Souza',
  remessaId: MALETA,
  status: 'CONCLUIDA',
  subtotal: '100.00',
  desconto: '0.00',
  total: '100.00',
  totalDolar: null,
  totalGuarani: null,
  cotacao: null,
  pago: '30.00',
  devolvido: '50.00',
  aReceber: '20.00',
  vendidaEm: '2026-11-12T13:00:00.000Z',
  estornadaEm: null,
  motivoDoEstorno: null,
  trocaId: null,
  creditoSemSaldo: true,
  itens: [
    {
      produto: peca(ANEL, 'Anel de prata'),
      quantidade: 2,
      precoUnitario: '50.00',
      desconto: '0.00',
      total: '100.00',
    },
  ],
  pagamentos: [
    {
      id: '1',
      tipo: 'PAGAMENTO',
      forma: 'CREDITO',
      valor: '40.00',
      em: '2026-11-12T13:00:00.000Z',
    },
    { id: '2', tipo: 'PAGAMENTO', forma: 'PIX', valor: '60.00', em: '2026-11-12T13:00:00.000Z' },
    {
      id: '3',
      tipo: 'REEMBOLSO',
      forma: 'PIX',
      valor: '-50.00',
      em: '2026-11-12T14:00:00.000Z',
      daDevolucao: true,
    },
    {
      id: '4',
      tipo: 'ESTORNO_REEMBOLSO',
      forma: 'PIX',
      valor: '50.00',
      em: '2026-11-12T15:00:00.000Z',
      daDevolucao: true,
    },
    {
      id: '5',
      tipo: 'REEMBOLSO',
      forma: 'PIX',
      valor: '-50.00',
      em: '2026-11-12T16:00:00.000Z',
      daDevolucao: true,
    },
  ],
};

const semCotacao = {
  ok: true as const,
  dados: {
    disponivel: false,
    dataDeHoje: '2026-11-12',
    dataDaCotacao: null,
    dolar: null,
    guarani: null,
    deDiaAnterior: false,
  },
};

const lista = (
  situacao: DevolucaoOuTroca['situacao'],
  extra: Partial<DevolucaoOuTroca> = {},
): DevolucaoOuTroca => ({
  id: '66666666-6666-4666-8666-666666666666',
  tipo: 'DEVOLUCAO',
  em: '2026-11-12T14:00:00.000Z',
  vendaId: VENDA,
  clienteNome: 'Maria Souza',
  pecas: 1,
  valor: '50.00',
  abatido: '0.00',
  emDinheiro: '50.00',
  emCredito: '0.00',
  diferenca: null,
  situacao,
  podeCancelar: true,
  cotacao: null,
  ...extra,
});

const TOTAIS = { total: '0.00', totalEmDolar: null, totalEmGuarani: null, dataDaCotacao: null };

type Manipulador = (pedido: PedidoDeChamada) => ResultadoDeChamada;

function criarPonte(manipuladores: Partial<Record<PedidoDeChamada['operacao'], Manipulador>> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Partial<Record<PedidoDeChamada['operacao'], Manipulador>> = {
    'cotacao.hoje': () => semCotacao,
    'autorizacoes.listar': () => ({
      ok: true,
      dados: { pedidos: [], avisos: [] } satisfies AutorizacoesDoVendedor,
    }),
    'devolucoes.vendasComAPeca': () => ({ ok: true, dados: [vendaComAPeca()] }),
    'devolucoes.previa': () => ({ ok: true, dados: previa() }),
    'devolucoes.registrar': () => ({ ok: true, dados: { operacaoId: 'x' } }),
    'devolucoes.cancelar': () => ({ ok: true, dados: { operacaoId: 'x' } }),
    'devolucoes.listar': () => ({ ok: true, dados: [lista('AGUARDANDO_ENVIO')] }),
    'autorizacoes.pedir': () => ({ ok: true, dados: { operacaoId: 'x' } }),
    'trocas.previa': () => ({
      ok: true,
      dados: {
        remessaId: MALETA,
        valorQueVolta: '50.00',
        valorNovo: '80.00',
        abatido: '0.00',
        diferenca: '30.00',
        problema: null,
      } satisfies PreviaDaTroca,
    }),
    'trocas.registrar': () => ({ ok: true, dados: { operacaoId: 'x' } }),
    'maletas.listar': () => ({ ok: true, dados: [maleta] }),
    'maletas.peca': () => ({ ok: true, dados: linhaDaMaleta(COLAR, 'Colar dourado', '80.00') }),
    'maletas.buscarPeca': () => ({ ok: true, dados: [] }),
    'clientes.listar': () => ({ ok: true, dados: [maria] }),
    'creditos.doCliente': () => ({
      ok: true,
      dados: { total: '40.00', creditos: [{ id: CREDITO, saldo: '40.00' }] },
    }),
    'vendas.listar': () => ({ ok: true, dados: [venda] }),
    'vendas.vender': () => ({ ok: true, dados: { ...venda, pagamentos: [] } }),
    'envios.naoAceitos': () => ({ ok: true, dados: [] }),
    'relatorios.vendas': () => ({ ok: true, dados: { linhas: [], totais: TOTAIS } }),
    'relatorios.recebimentos': () => ({ ok: true, dados: { linhas: [], totais: TOTAIS } }),
    'relatorios.aReceber': () => ({ ok: true, dados: { clientes: [], totais: TOTAIS } }),
    'relatorios.devolucoes': () => ({
      ok: true,
      dados: {
        linhas: [],
        totais: {
          devolucoes: 0,
          trocas: 0,
          valorDevolvido: '0.00',
          valorDevolvidoEmDolar: null,
          valorDevolvidoEmGuarani: null,
          emDinheiro: '0.00',
          emDinheiroEmDolar: null,
          emDinheiroEmGuarani: null,
          emCredito: '0.00',
          emCreditoEmDolar: null,
          emCreditoEmGuarani: null,
        },
      },
    }),
    'relatorios.creditos': () => ({
      ok: true,
      dados: {
        clientes: [],
        totais: {
          gerado: '0.00',
          geradoEmDolar: null,
          geradoEmGuarani: null,
          usado: '0.00',
          usadoEmDolar: null,
          usadoEmGuarani: null,
          emAberto: '0.00',
          emAbertoEmDolar: null,
          emAbertoEmGuarani: null,
        },
      },
    }),
    'fotos.ver': () => ({ ok: true, dados: { dataUrl: 'data:image/jpeg;base64,AAAA' } }),
  };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve({ estado: 'dentro' as const, usuario: { nome: 'Ana' } }),
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

async function abrirDevolucao(ponte: PonteDoCorona) {
  const u = userEvent.setup();
  render(<App ponte={ponte} />);
  await u.click(await screen.findByRole('button', { name: 'Devolução e troca' }));
  expect(await screen.findByRole('heading', { name: 'Devolução e troca' })).toBeInTheDocument();
  return u;
}

async function lerPeca(u: ReturnType<typeof userEvent.setup>) {
  await u.type(screen.getByLabelText('Código da peça'), 'A-1');
  await u.click(screen.getByRole('button', { name: 'Buscar vendas' }));
  await screen.findByRole('table', { name: 'Vendas com esta peça' });
}

describe('tela inicial', () => {
  it('tem o quinto botão e os avisos das autorizações respondidas', async () => {
    const { ponte } = criarPonte({
      'autorizacoes.listar': () => ({
        ok: true,
        dados: {
          pedidos: [],
          avisos: [
            {
              id: '1',
              tipo: 'APROVADA',
              vendaId: VENDA,
              clienteNome: 'Maria Souza',
              validaAte: '2026-11-28',
            },
            { id: '2', tipo: 'NEGADA', vendaId: VENDA, clienteNome: 'João', validaAte: null },
          ],
        },
      }),
    });
    render(<App ponte={ponte} />);
    for (const nome of ['Vender', 'Minha maleta', 'Clientes', 'Meus números', 'Devolução e troca'])
      expect(await screen.findByRole('button', { name: nome })).toBeInTheDocument();
    expect(
      await screen.findByText(
        'A dona aprovou o pedido da venda de Maria Souza. Registre a devolução até 28/11.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('A dona negou o pedido da venda de João.')).toBeInTheDocument();
  });
});

describe('Devolução e troca: ler a peça e escolher a venda', () => {
  it('mostra as vendas com a peça, a foto, a garantia e os botões; a venda de maleta em acerto não tem botões', async () => {
    const { ponte } = criarPonte({
      'devolucoes.vendasComAPeca': () => ({
        ok: true,
        dados: [
          vendaComAPeca(),
          vendaComAPeca({
            vendaId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
            clienteNome: 'João',
            bloqueio:
              'O acerto desta maleta está acontecendo. Espere o acerto terminar e registre a devolução de novo.',
          }),
        ],
      }),
    });
    const u = await abrirDevolucao(ponte);
    expect(screen.getByText('Leia o código da peça que o cliente trouxe.')).toBeInTheDocument();
    await lerPeca(u);
    const tabela = screen.getByRole('table', { name: 'Vendas com esta peça' });
    expect(within(tabela).getAllByText('Desistência até 19/11 · Defeito até 12/12')).toHaveLength(
      2,
    );
    expect(
      within(tabela).getByRole('button', { name: /^Devolver: Maria Souza/ }),
    ).toBeInTheDocument();
    expect(
      within(tabela).getByRole('button', { name: /^Trocar: Maria Souza/ }),
    ).toBeInTheDocument();
    expect(within(tabela).queryByRole('button', { name: /João/ })).not.toBeInTheDocument();
    expect(
      within(tabela).getByText(
        'O acerto desta maleta está acontecendo. Espere o acerto terminar e registre a devolução de novo.',
      ),
    ).toBeInTheDocument();
    expect(await screen.findByAltText('Foto de Anel de prata')).toBeInTheDocument();
  });

  it('sem venda com a peça, diz o que fazer', async () => {
    const { ponte } = criarPonte({ 'devolucoes.vendasComAPeca': () => ({ ok: true, dados: [] }) });
    const u = await abrirDevolucao(ponte);
    await u.type(screen.getByLabelText('Código da peça'), 'X');
    await u.click(screen.getByRole('button', { name: 'Buscar vendas' }));
    expect(
      await screen.findByText('Não achamos venda sua com esta peça. Confira o código.'),
    ).toBeInTheDocument();
  });
});

describe('Devolução', () => {
  it('quantas voltam, estado, motivo e forma; a prévia; a confirmação com Voltar; e o pedido sem nenhum valor', async () => {
    const { ponte, daOperacao } = criarPonte();
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    expect(
      await screen.findByRole('heading', { name: 'Devolução de Maria Souza' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Sem foto')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Quantas voltam: Anel de prata'), '1');
    await u.selectOptions(screen.getByLabelText('Motivo da devolução'), 'Não serviu');
    // Na tela só Dinheiro e Transferência (tarefa 6.13); Cartão e Cheque saem das opções.
    expect(screen.queryByLabelText('Cartão')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Cheque')).not.toBeInTheDocument();
    await u.click(screen.getByLabelText('Transferência'));
    expect(await screen.findByText(/Valor da devolução:/)).toBeInTheDocument();
    expect(
      screen.getByText('O valor é uma prévia. A loja confere quando os dados forem enviados.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Voltou para a maleta')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Registrar devolução' }));
    const janela = await screen.findByRole('dialog', { name: 'Registrar esta devolução?' });
    expect(within(janela).getByRole('button', { name: 'Voltar' })).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Registrar devolução' }));
    expect(await screen.findByText('Devolução registrada.')).toBeInTheDocument();
    const [pedido] = daOperacao('devolucoes.registrar');
    expect(pedido).toMatchObject({
      devolucao: {
        vendaId: VENDA,
        motivo: 'TAMANHO',
        formaReembolso: 'PIX',
        itens: [{ produtoId: ANEL, quantidade: 1, estado: 'BOA' }],
      },
    });
    expect(JSON.stringify(pedido)).not.toMatch(/valor|total|diferenca/i);
  });

  it('a linha "Abatido do que o cliente deve" só aparece quando a devolução abate parcelas (venda a prazo)', async () => {
    const { ponte } = criarPonte({
      'devolucoes.previa': () => ({
        ok: true,
        dados: previa({ valor: '50.00', abatido: '50.00', emDinheiro: '0.00' }),
      }),
    });
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    await u.type(await screen.findByLabelText('Quantas voltam: Anel de prata'), '1');
    expect(await screen.findByText(/Abatido do que o cliente deve:/)).toBeInTheDocument();
    expect(screen.getByText(/Volta em dinheiro:/)).toBeInTheDocument();
  });

  it('sem abatimento (venda à vista) a linha não aparece', async () => {
    const { ponte } = criarPonte();
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    await u.type(await screen.findByLabelText('Quantas voltam: Anel de prata'), '1');
    expect(await screen.findByText(/Valor da devolução:/)).toBeInTheDocument();
    expect(screen.queryByText(/Abatido do que o cliente deve/)).not.toBeInTheDocument();
  });

  it('sem peça marcada, avisa e não registra', async () => {
    const { ponte, daOperacao } = criarPonte();
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    await u.click(await screen.findByRole('button', { name: 'Registrar devolução' }));
    expect(await screen.findByText('Informe pelo menos uma peça devolvida.')).toBeInTheDocument();
    expect(daOperacao('devolucoes.registrar')).toHaveLength(0);
  });

  it('fora do prazo: o botão some, aparece o bloqueio e Pedir autorização à dona', async () => {
    const { ponte, daOperacao } = criarPonte({
      'devolucoes.previa': () => ({
        ok: true,
        dados: previa({
          dentroDoPrazo: false,
          problema: 'Passou do prazo da garantia. Peça autorização à dona.',
          podePedirAutorizacao: true,
        }),
      }),
    });
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    expect(
      await screen.findByText('Passou do prazo da garantia. Peça autorização à dona.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Registrar devolução' })).not.toBeInTheDocument();
    await u.type(screen.getByLabelText('Quantas voltam: Anel de prata'), '1');
    await u.click(screen.getByRole('button', { name: 'Pedir autorização à dona' }));
    const janela = await screen.findByRole('dialog', { name: 'Pedir autorização à dona?' });
    await u.click(within(janela).getByRole('button', { name: 'Pedir autorização à dona' }));
    expect(
      await screen.findByText('Pedido registrado. Espere a resposta da dona.'),
    ).toBeInTheDocument();
    expect(daOperacao('autorizacoes.pedir')[0]).toMatchObject({
      pedido: {
        vendaId: VENDA,
        motivo: 'ARREPENDIMENTO',
        itens: [{ produtoId: ANEL, quantidade: 1, estado: 'BOA' }],
      },
    });
  });

  it('com a autorização aprovada mostra o "vale até" e deixa registrar', async () => {
    const { ponte } = criarPonte({
      'devolucoes.previa': () => ({
        ok: true,
        dados: previa({ dentroDoPrazo: false, autorizacao: { id: 'x', validaAte: '2026-11-28' } }),
      }),
    });
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    expect(
      await screen.findByText('A dona aprovou esta devolução. Vale até 28/11.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Registrar devolução' })).toBeInTheDocument();
  });
});

describe('Troca', () => {
  it('peças que voltam, peças que o cliente leva, a diferença a pagar e "Receba R$ X do cliente."', async () => {
    const { ponte, daOperacao } = criarPonte();
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Trocar: Maria Souza/ }));
    expect(
      await screen.findByRole('heading', { name: 'Troca de Maria Souza' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Peças que voltam' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Peças que o cliente leva' })).toBeInTheDocument();
    await u.type(screen.getByLabelText('Quantas voltam: Anel de prata'), '1');
    await u.type(screen.getByLabelText('Ler o código da peça nova'), 'C-1');
    await u.click(screen.getByRole('button', { name: 'Ler o código' }));
    expect(
      await screen.findByRole('table', { name: 'Peças que o cliente leva' }),
    ).toBeInTheDocument();
    expect(await screen.findByText(/Diferença a pagar: R\$ 30,00/)).toBeInTheDocument();
    await u.click(screen.getByLabelText('Dinheiro'));
    await u.click(screen.getByRole('button', { name: 'Registrar troca' }));
    const janela = await screen.findByRole('dialog', { name: 'Registrar esta troca?' });
    await u.click(within(janela).getByRole('button', { name: 'Registrar troca' }));
    expect(
      await screen.findByText('Troca registrada. Receba R$ 30,00 do cliente.'),
    ).toBeInTheDocument();
    const [pedido] = daOperacao('trocas.registrar') as Extract<
      PedidoDeChamada,
      { operacao: 'trocas.registrar' }
    >[];
    expect(pedido?.troca.venda.itens).toEqual([{ produtoId: COLAR, quantidade: 1 }]);
    expect(pedido?.troca.venda.pagamentos).toMatchObject([{ valor: '30.00', forma: 'DINHEIRO' }]);
    expect(Object.keys(pedido?.troca.devolucao ?? {})).not.toContain('valor');
  });

  it('a diferença a prazo pede as parcelas e manda só quantidade e vencimento (tarefa 7.11); paga na hora, não pede', async () => {
    const { ponte, daOperacao } = criarPonte();
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Trocar: Maria Souza/ }));
    await u.type(await screen.findByLabelText('Quantas voltam: Anel de prata'), '1');
    await u.type(screen.getByLabelText('Ler o código da peça nova'), 'C-1');
    await u.click(screen.getByRole('button', { name: 'Ler o código' }));
    await screen.findByText(/Diferença a pagar: R\$ 30,00/);
    // Pagando tudo agora, não há parcelas.
    expect(screen.queryByLabelText('Em quantas parcelas? (1 a 24)')).not.toBeInTheDocument();
    await u.click(screen.getByLabelText('A prazo (fiado)'));
    fireEvent.change(screen.getByLabelText('Em quantas parcelas? (1 a 24)'), {
      target: { value: '3' },
    });
    fireEvent.change(screen.getByLabelText('Primeiro vencimento'), {
      target: { value: '2999-01-01' },
    });
    await u.click(screen.getByRole('button', { name: 'Registrar troca' }));
    expect(await screen.findByText(/está longe demais/)).toBeInTheDocument();
    expect(daOperacao('trocas.registrar')).toHaveLength(0);
    const hoje = new Date();
    const mesQueVem = new Date(Date.UTC(hoje.getUTCFullYear(), hoje.getUTCMonth() + 1, 28));
    fireEvent.change(screen.getByLabelText('Primeiro vencimento'), {
      target: { value: mesQueVem.toISOString().slice(0, 10) },
    });
    await u.click(screen.getByRole('button', { name: 'Registrar troca' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Registrar troca' }),
    );
    await screen.findByText(/Troca registrada/);
    const [pedido] = daOperacao('trocas.registrar') as Extract<
      PedidoDeChamada,
      { operacao: 'trocas.registrar' }
    >[];
    expect(pedido?.troca.venda.parcelamento).toEqual({
      quantidade: 3,
      primeiroVencimento: mesQueVem.toISOString().slice(0, 10),
    });
    expect(pedido?.troca.venda.pagamentos).toEqual([]);
  });

  it.each([
    [
      '-20.00',
      'Fica de crédito: R$ 20,00',
      'Troca registrada. Ficou de crédito para Maria Souza: R$ 20,00.',
    ],
    ['0.00', 'Sem diferença', 'Troca registrada.'],
  ])('diferença %s mostra "%s" e depois "%s"', async (diferenca, naTela, depois) => {
    const { ponte } = criarPonte({
      'trocas.previa': () => ({
        ok: true,
        dados: {
          remessaId: MALETA,
          valorQueVolta: '50.00',
          valorNovo: '30.00',
          diferenca,
          problema: null,
        },
      }),
    });
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Trocar: Maria Souza/ }));
    await u.type(await screen.findByLabelText('Quantas voltam: Anel de prata'), '1');
    await u.type(screen.getByLabelText('Ler o código da peça nova'), 'C-1');
    await u.click(screen.getByRole('button', { name: 'Ler o código' }));
    expect(await screen.findByText(naTela)).toBeInTheDocument();
    expect(screen.queryByText('Como o cliente pagou a diferença')).not.toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Registrar troca' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Registrar troca' }),
    );
    expect(await screen.findByText(depois)).toBeInTheDocument();
  });

  it('sem maleta conferida, avisa', async () => {
    const { ponte } = criarPonte({
      'trocas.previa': () => ({
        ok: true,
        dados: {
          remessaId: null,
          valorQueVolta: '0.00',
          valorNovo: '0.00',
          diferenca: '0.00',
          problema: null,
        },
      }),
    });
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    await u.click(screen.getByRole('button', { name: /^Trocar: Maria Souza/ }));
    expect(await screen.findByText('Para trocar, confira a sua maleta antes.')).toBeInTheDocument();
  });
});

describe('Minhas devoluções e trocas', () => {
  it('aguardando envio, para revisão da dona, e cancelar com motivo e Voltar', async () => {
    const { ponte, daOperacao } = criarPonte({
      'devolucoes.listar': () => ({
        ok: true,
        dados: [
          lista('AGUARDANDO_ENVIO'),
          lista('EM_REVISAO', { id: '77777777-7777-4777-8777-777777777777', podeCancelar: false }),
          lista('FEITA', {
            id: '88888888-8888-4888-8888-888888888888',
            tipo: 'TROCA',
            diferenca: '30.00',
            emDinheiro: '0.00',
            emCredito: '50.00',
          }),
        ],
      }),
      'autorizacoes.listar': () => ({
        ok: true,
        dados: {
          pedidos: [
            {
              id: '1',
              vendaId: VENDA,
              clienteNome: 'Maria Souza',
              pedidaEm: '2026-11-20T12:00:00.000Z',
              pecas: 1,
              situacao: 'APROVADA',
              validaAte: '2026-11-28',
            },
            {
              id: '2',
              vendaId: VENDA,
              clienteNome: 'João',
              pedidaEm: '2026-11-20T12:00:00.000Z',
              pecas: 1,
              situacao: 'PEDIDA',
              validaAte: null,
            },
          ],
          avisos: [],
        },
      }),
    });
    const u = await abrirDevolucao(ponte);
    await u.click(screen.getByRole('button', { name: 'Minhas devoluções e trocas' }));
    const tabela = await screen.findByRole('table', { name: 'Minhas devoluções e trocas' });
    expect(within(tabela).getByText('Aguardando envio')).toBeInTheDocument();
    expect(within(tabela).getByText('Para revisão da dona')).toBeInTheDocument();
    expect(within(tabela).getByText('A dona vai olhar esta devolução.')).toBeInTheDocument();
    expect(
      within(tabela).getByText('Só dá para cancelar no mesmo dia. Peça à dona para cancelar.'),
    ).toBeInTheDocument();
    expect(within(tabela).getByText('Diferença a pagar: R$ 30,00')).toBeInTheDocument();
    const pedidos = screen.getByRole('table', { name: 'Pedidos de autorização' });
    expect(within(pedidos).getByText('Aprovado (vale até 28/11)')).toBeInTheDocument();
    expect(within(pedidos).getByText('Esperando a dona')).toBeInTheDocument();

    await u.click(within(tabela).getByRole('button', { name: 'Cancelar devolução' }));
    const janela = await screen.findByRole('dialog', { name: 'Cancelar esta devolução?' });
    await u.click(within(janela).getByRole('button', { name: 'Cancelar devolução' }));
    expect(
      await within(janela).findByText('Diga o motivo de cancelar a devolução.'),
    ).toBeInTheDocument();
    await u.type(within(janela).getByLabelText('Motivo'), 'Errei a peça');
    await u.click(within(janela).getByRole('button', { name: 'Cancelar devolução' }));
    expect(await screen.findByText('Devolução cancelada.')).toBeInTheDocument();
    expect(daOperacao('devolucoes.cancelar')[0]).toMatchObject({ motivo: 'Errei a peça' });
    await u.click(within(tabela).getByRole('button', { name: 'Cancelar troca' }));
    expect(await screen.findByRole('dialog', { name: 'Cancelar esta troca?' })).toBeInTheDocument();
  });
});

describe('Vender com crédito, a venda e o cliente', () => {
  it('Usar crédito põe o menor entre o crédito e o total; o resto vai na forma de sempre', async () => {
    const { ponte, daOperacao } = criarPonte();
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Vender' }));
    await u.type(await screen.findByLabelText('Ler o código da peça'), 'A-1');
    await u.click(screen.getByRole('button', { name: 'Ler o código' }));
    await u.click(await screen.findByRole('button', { name: 'Escolher: Maria Souza' }));
    expect(await screen.findByText('Crédito do cliente: R$ 40,00 em aberto.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Usar crédito' }));
    expect(screen.getByLabelText('Valor do crédito (R$)')).toHaveValue('40,00');
    await u.clear(screen.getByLabelText('Valor do crédito (R$)'));
    await u.type(screen.getByLabelText('Valor do crédito (R$)'), '60,00');
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    expect(
      await screen.findByText(
        'O valor passa do que sobra neste crédito. Diminua o valor e pague o resto de outra forma.',
      ),
    ).toBeInTheDocument();
    await u.clear(screen.getByLabelText('Valor do crédito (R$)'));
    await u.type(screen.getByLabelText('Valor do crédito (R$)'), '10,00');
    await u.click(screen.getByRole('button', { name: 'Concluir venda' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Concluir venda' }),
    );
    await waitFor(() => expect(daOperacao('vendas.vender')).toHaveLength(1));
    const [pedido] = daOperacao('vendas.vender') as Extract<
      PedidoDeChamada,
      { operacao: 'vendas.vender' }
    >[];
    expect(pedido?.venda.pagamentos).toMatchObject([
      { valor: '10.00', forma: 'CREDITO', creditoId: CREDITO },
      { valor: '70.00', forma: 'DINHEIRO' },
    ]);
  });

  it('a venda mostra Crédito do cliente, Devolução, Devolução cancelada, o devolvido, o aviso do crédito e Devolver e Trocar', async () => {
    const { ponte } = criarPonte();
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Meus números' }));
    await u.click(await screen.findByRole('button', { name: 'Ver minhas vendas' }));
    await u.click(await screen.findByRole('button', { name: 'Ver: Maria Souza' }));
    expect(await screen.findByText('Crédito do cliente')).toBeInTheDocument();
    expect(screen.getAllByText('Devolução: Transferência')).toHaveLength(2);
    expect(screen.getByText('Devolução cancelada: Transferência')).toBeInTheDocument();
    expect(screen.getByText(/Devolvido:/)).toBeInTheDocument();
    expect(
      screen.getByText('O crédito do cliente já tinha acabado. A dona vai olhar esta venda.'),
    ).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Trocar' }));
    expect(
      await screen.findByRole('heading', { name: 'Troca de Maria Souza' }),
    ).toBeInTheDocument();
  });

  it('a ficha do cliente mostra o crédito em aberto', async () => {
    const { ponte } = criarPonte();
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Clientes' }));
    await u.click(await screen.findByRole('button', { name: /^Ver/ }));
    expect(await screen.findByText('Crédito do cliente: R$ 40,00 em aberto.')).toBeInTheDocument();
  });
});

describe('palavras proibidas nas telas novas (glossário, parte 2)', () => {
  const raiz = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
  const glossario = readFileSync(join(raiz, 'docs', 'glossario-interface.md'), 'utf8');
  const bloco = (nome: string): string[] => {
    const m = new RegExp(`<!-- ${nome}:INICIO -->\\s*\`\`\`\\s*([\\s\\S]*?)\\s*\`\`\``).exec(
      glossario,
    );
    return (m?.[1] ?? '').split(/\r?\n/).filter(Boolean);
  };
  const blocoA = bloco('PROIBIDAS-A').map((p) => p.toLowerCase());
  const blocoB = [...bloco('PROIBIDAS-B'), 'CREDITO', 'EM_REVISAO', 'PEDIDA'];
  const extras = ['pendente', 'fila', 'sincroniz', 'estorno', 'corona gestão'];

  function conferir(texto: string) {
    const minusculo = texto.toLowerCase();
    for (const p of [...blocoA, ...extras]) {
      const regra = p.length <= 5 ? new RegExp(`\\b${p}\\b`) : null;
      expect(regra ? regra.test(minusculo) : minusculo.includes(p), p).toBe(false);
    }
    for (const p of blocoB) expect(texto.includes(p), p).toBe(false);
  }

  it('busca, devolução, troca e a lista', async () => {
    const { ponte } = criarPonte({
      'devolucoes.listar': () => ({
        ok: true,
        dados: [lista('EM_REVISAO'), lista('CANCELADA', { id: '9' })],
      }),
    });
    const u = await abrirDevolucao(ponte);
    await lerPeca(u);
    conferir(document.body.textContent ?? '');
    await u.click(screen.getByRole('button', { name: /^Devolver: Maria Souza/ }));
    await screen.findByRole('heading', { name: 'Devolução de Maria Souza' });
    conferir(document.body.textContent ?? '');
    await u.click(screen.getByRole('button', { name: 'Voltar' }));
    await u.click(await screen.findByRole('button', { name: /^Trocar: Maria Souza/ }));
    await screen.findByRole('heading', { name: 'Troca de Maria Souza' });
    conferir(document.body.textContent ?? '');
    await u.click(screen.getByRole('button', { name: 'Voltar' }));
    await u.click(await screen.findByRole('button', { name: 'Minhas devoluções e trocas' }));
    await screen.findByRole('table', { name: 'Minhas devoluções e trocas' });
    conferir(document.body.textContent ?? '');
  });
});

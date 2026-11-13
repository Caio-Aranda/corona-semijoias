// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';

const ID_VEND = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_OUTRO_VEND = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d12';
const ID_PEDIDO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_DEVOLUCAO = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_TROCA = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_CREDITO = '4e5e28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';
const ID_VENDA = '5f6e28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';
const ID_PECA = '6a7e28ba-2fa1-41d3-b9a5-2c3a9a8f6b77';
const ID_LINHA = '7b8e28ba-2fa1-41d3-b9a5-2c3a9a8f6b88';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

function montar(extras: Record<string, Manipulador> = {}, avisos: unknown[] = []) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () => ({
      ok: true,
      dados: {
        itens: [
          { id: ID_VEND, nome: 'Joana Vendedora', ativo: true },
          { id: ID_OUTRO_VEND, nome: 'Marta Vendedora', ativo: true },
        ],
      },
    }),
    'devolucoes.autorizacoes': (p) => ({
      ok: true,
      dados:
        p.parametros?.situacao === 'APROVADA'
          ? [
              {
                id: ID_PEDIDO,
                vendedorNome: 'Joana Vendedora',
                clienteNome: 'Dora',
                motivo: 'DEFEITO',
                tipo: 'FORA_DO_PRAZO',
                situacao: 'APROVADA',
                valorPedido: '80.00',
                validaAte: '2026-11-20',
                itens: [{ produto: { nome: 'Anel Lua' }, quantidade: 1 }],
              },
            ]
          : [
              {
                id: ID_PEDIDO,
                vendedorNome: 'Joana Vendedora',
                clienteNome: 'Dora',
                motivo: 'ARREPENDIMENTO',
                tipo: 'FORA_DO_PRAZO',
                situacao: 'PEDIDA',
                valorPedido: '80.00',
                valorPedidoDolar: '14.55',
                valorPedidoGuarani: '111732',
                pedidaEm: '2026-11-10T15:30:00Z',
                prazoDaGarantiaAte: '2026-11-08',
                itens: [{ produto: { nome: 'Anel Lua' }, quantidade: 2 }],
              },
            ],
    }),
    'devolucoes.listar': (p) => ({
      ok: true,
      dados:
        p.parametros?.situacao === 'EM_REVISAO'
          ? [
              {
                id: ID_DEVOLUCAO,
                vendedorId: ID_VEND,
                clienteNome: 'Dora',
                motivo: 'ARREPENDIMENTO',
                situacao: 'EM_REVISAO',
                valorDevolvido: '80.00',
                valorReembolsado: '80.00',
                valorEmCredito: '0.00',
                devolvidaEm: '2026-11-11T12:00:00Z',
                motivosRevisao: ['FORA_DO_PRAZO', 'ACIMA_DO_LIMITE_SEMANAL'],
                itens: [{ produto: { nome: 'Anel Lua' }, quantidade: 1 }],
              },
            ]
          : [
              {
                id: ID_DEVOLUCAO,
                vendedorId: ID_VEND,
                clienteNome: 'Dora',
                motivo: 'DEFEITO',
                situacao: 'ACEITA',
                valorDevolvido: '80.00',
                valorDevolvidoDolar: '14.55',
                valorDevolvidoGuarani: '111732',
                valorReembolsado: '50.00',
                valorEmCredito: '30.00',
                devolvidaEm: '2026-11-11T12:00:00Z',
                motivosRevisao: [],
                itens: [{ produto: { nome: 'Anel Lua' }, quantidade: 1 }],
              },
            ],
    }),
    'trocas.listar': () => ({
      ok: true,
      dados: [
        {
          id: ID_TROCA,
          vendedorId: ID_VEND,
          clienteNome: 'Dora',
          situacao: 'ATIVA',
          diferenca: '-40.00',
          diferencaDolar: '-7.27',
          diferencaGuarani: '-44693',
          trocadaEm: '2026-11-11T12:00:00Z',
          voltaram: [{ produto: { nome: 'Anel Lua' }, quantidade: 1 }],
          sairam: [{ produto: { nome: 'Brinco Sol' }, quantidade: 1 }],
        },
        {
          id: ID_VENDA,
          vendedorId: ID_VEND,
          clienteNome: 'Rita',
          situacao: 'ATIVA',
          diferenca: '50.00',
          trocadaEm: '2026-11-11T13:00:00Z',
          voltaram: [],
          sairam: [],
        },
      ],
    }),
    'creditos.listar': () => ({
      ok: true,
      dados: [
        {
          id: ID_CREDITO,
          vendedorId: ID_VEND,
          clienteNome: 'Dora',
          origem: 'TROCA',
          situacao: 'ABERTO',
          valor: '40.00',
          valorUtilizado: '10.00',
          saldo: '30.00',
          saldoDolar: '5.45',
          saldoGuarani: '33519',
          geradoEm: '2026-11-11T12:00:00Z',
        },
      ],
    }),
    'garantia.ler': () => ({
      ok: true,
      dados: {
        prazoArrependimentoDias: 7,
        prazoDefeitoDias: 30,
        valorAprovacaoReembolso: '500.00',
        percentualAlertaDevolucao: 20,
      },
    }),
    'devolucoes.vendasComAPeca': () => ({
      ok: true,
      dados: {
        peca: { id: ID_PECA, nome: 'Anel Lua' },
        vendas: [
          {
            vendaId: ID_VENDA,
            clienteNome: 'Dora',
            vendidaEm: '2026-11-10T12:00:00Z',
            total: '100.00',
            aPrazo: false,
            prazoArrependimentoAte: '2026-11-17',
            prazoDefeitoAte: '2026-12-10',
            itens: [
              {
                itemVendaId: ID_LINHA,
                produto: { id: ID_PECA, nome: 'Anel Lua' },
                vendidas: 3,
                devolvidas: 1,
                podemVoltar: 2,
              },
            ],
          },
          {
            vendaId: ID_PEDIDO,
            clienteNome: 'Rita',
            vendidaEm: '2026-11-09T12:00:00Z',
            total: '60.00',
            aPrazo: true,
            prazoArrependimentoAte: '2026-11-16',
            prazoDefeitoAte: '2026-12-09',
            itens: [],
          },
        ],
      },
    }),
    ...extras,
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

const ultima = (m: ReturnType<typeof montar>, op: string) =>
  m.chamadas.filter((c) => c.operacao === op).at(-1)?.parametros;

async function abrir(m: ReturnType<typeof montar>, item: string, titulo: string) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  await u.click(within(nav).getByRole('button', { name: item }));
  await screen.findByRole('heading', { name: titulo, level: 1 });
  return u;
}

describe('menu das devoluções e créditos (tarefa 6.9)', () => {
  it('tem o grupo com as seis telas, com os nomes do glossário', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
    expect(within(nav).getByText('Devoluções e créditos')).toBeInTheDocument();
    for (const nome of [
      'Devoluções para autorizar',
      'Devoluções para revisar',
      'Devoluções',
      'Trocas',
      'Créditos dos clientes',
      'Garantia e limites',
    ]) {
      expect(within(nav).getByRole('button', { name: nome })).toBeInTheDocument();
    }
  });

  it('cada aviso de devolução na tela inicial tem o botão Abrir para a tela certa', async () => {
    const aviso = (tipo: string, titulo: string) => ({ tipo, titulo, texto: 'Veja.', dias: [] });
    const m = montar({}, [
      aviso('DEVOLUCOES_PARA_AUTORIZAR', 'Há 1 pedido de autorização de devolução esperando você.'),
      aviso('DEVOLUCOES_PARA_REVISAR', 'Há 1 devolução para revisar.'),
      aviso('MUITAS_DEVOLUCOES', 'Muitas devoluções de Joana Vendedora.'),
    ]);
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    const botoes = await screen.findAllByRole('button', { name: 'Abrir' });
    expect(botoes).toHaveLength(3);
    await u.click(botoes[1]!);
    expect(
      await screen.findByRole('heading', { name: 'Devoluções para revisar', level: 1 }),
    ).toBeInTheDocument();
    window.location.hash = '';
  });
});

describe('Devoluções para autorizar (tarefa 6.9)', () => {
  it('mostra o pedido e só pede o que está esperando a dona', async () => {
    const m = montar();
    const u = await abrir(m, 'Devoluções para autorizar', 'Devoluções para autorizar');
    expect(await screen.findByText('Dora')).toBeInTheDocument();
    expect(screen.getByText('Passou do prazo da garantia')).toBeInTheDocument();
    expect(screen.getByText('Desistência')).toBeInTheDocument();
    expect(screen.getByText('2 × Anel Lua')).toBeInTheDocument();
    // Dólar e Guarani vêm do servidor, pela cotação do dia do pedido.
    expect(screen.getByText('R$ 80,00')).toBeInTheDocument();
    expect(screen.getByText(/US\$ 14,55 · Gs\. 111\.732/)).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.autorizacoes')).toEqual({ situacao: 'PEDIDA' });

    await u.selectOptions(screen.getByLabelText('Situação'), 'APROVADA');
    expect(await screen.findByText('Vale até 20/11/2026')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Aprovar reembolso/ })).toBeNull();
  });

  it('aprovar pergunta antes, chama a API e mostra até quando vale', async () => {
    const m = montar({
      'devolucoes.aprovarAutorizacao': () => ({
        ok: true,
        dados: { id: ID_PEDIDO, situacao: 'APROVADA', validaAte: '2026-11-20' },
      }),
    });
    const u = await abrir(m, 'Devoluções para autorizar', 'Devoluções para autorizar');
    await u.click(await screen.findByRole('button', { name: /Aprovar reembolso do pedido de/ }));
    expect(screen.getByText('Aprovar este reembolso?')).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.aprovarAutorizacao')).toBeUndefined();
    await u.click(screen.getByRole('button', { name: 'Aprovar reembolso' }));
    await waitFor(() =>
      expect(ultima(m, 'devolucoes.aprovarAutorizacao')).toEqual({ id: ID_PEDIDO }),
    );
    expect(
      await screen.findByText(
        'Reembolso aprovado. O vendedor pode registrar a devolução até 20/11/2026.',
      ),
    ).toBeInTheDocument();
  });

  it('negar exige o motivo e a recusa do servidor aparece na janela', async () => {
    let tentativa = 0;
    const m = montar({
      'devolucoes.negarAutorizacao': () =>
        ++tentativa === 1
          ? { ok: false, motivo: 'erro', status: 409, mensagem: 'Este pedido já foi decidido.' }
          : { ok: true, dados: {} },
    });
    const u = await abrir(m, 'Devoluções para autorizar', 'Devoluções para autorizar');
    await u.click(await screen.findByRole('button', { name: /Negar o pedido de/ }));
    await u.click(screen.getByRole('button', { name: 'Negar' }));
    expect(await screen.findByText('Diga o motivo de negar o pedido.')).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.negarAutorizacao')).toBeUndefined();
    await u.type(screen.getByLabelText('Motivo'), 'Peça usada');
    await u.click(screen.getByRole('button', { name: 'Negar' }));
    expect(await screen.findByText('Este pedido já foi decidido.')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Negar' }));
    await waitFor(() =>
      expect(ultima(m, 'devolucoes.negarAutorizacao')).toEqual({
        id: ID_PEDIDO,
        motivo: 'Peça usada',
      }),
    );
    expect(await screen.findByText('Pedido negado.')).toBeInTheDocument();
  });
});

describe('Devoluções para revisar (tarefa 6.9)', () => {
  it('lista os motivos da revisão com as palavras da loja', async () => {
    const m = montar();
    await abrir(m, 'Devoluções para revisar', 'Devoluções para revisar');
    expect(
      await screen.findByText('Passou do prazo; Passou do limite da semana'),
    ).toBeInTheDocument();
    expect(screen.getByText('Joana Vendedora')).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.listar')).toEqual({ situacao: 'EM_REVISAO', vendedorId: '' });
  });

  it('manter pergunta antes e chama a API', async () => {
    const m = montar({ 'devolucoes.manter': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Devoluções para revisar', 'Devoluções para revisar');
    await u.click(await screen.findByRole('button', { name: /Manter a devolução de/ }));
    expect(
      screen.getByText('A devolução vale como está. Nada muda na maleta nem no dinheiro.'),
    ).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.manter')).toBeUndefined();
    await u.click(screen.getByRole('button', { name: 'Manter' }));
    await waitFor(() => expect(ultima(m, 'devolucoes.manter')).toEqual({ id: ID_DEVOLUCAO }));
    expect(await screen.findByText('Devolução mantida.')).toBeInTheDocument();
  });

  it('não aceitar pede o motivo e diz que vira pendência do vendedor', async () => {
    const m = montar({ 'devolucoes.naoAceitar': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Devoluções para revisar', 'Devoluções para revisar');
    await u.click(await screen.findByRole('button', { name: /Não aceitar a devolução de/ }));
    expect(screen.getByText(/fica como pendência do vendedor/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Não aceitar' }));
    expect(await screen.findByText('Diga por que a devolução não foi aceita.')).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.naoAceitar')).toBeUndefined();
    await u.type(screen.getByLabelText('Por que não aceita?'), 'Peça usada');
    await u.click(screen.getByRole('button', { name: 'Não aceitar' }));
    await waitFor(() =>
      expect(ultima(m, 'devolucoes.naoAceitar')).toEqual({
        id: ID_DEVOLUCAO,
        motivo: 'Peça usada',
      }),
    );
    expect(
      await screen.findByText('Devolução não aceita. O valor ficou como pendência do vendedor.'),
    ).toBeInTheDocument();
  });
});

describe('Devoluções (tarefa 6.9)', () => {
  it('mostra o que voltou em dinheiro e em crédito e filtra pela situação e pelo vendedor', async () => {
    const m = montar();
    const u = await abrir(m, 'Devoluções', 'Devoluções');
    expect(await screen.findByText('Em dinheiro: R$ 50,00')).toBeInTheDocument();
    expect(screen.getByText('Em crédito: R$ 30,00')).toBeInTheDocument();
    expect(screen.getByText('Defeito')).toBeInTheDocument();
    expect(screen.getByText('Aceita')).toBeInTheDocument();
    expect(screen.getByText(/US\$ 14,55 · Gs\. 111\.732/)).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.listar')).toEqual({ situacao: 'ACEITA', vendedorId: '' });
    await u.selectOptions(screen.getByLabelText('Vendedor'), ID_OUTRO_VEND);
    await waitFor(() =>
      expect(ultima(m, 'devolucoes.listar')).toEqual({
        situacao: 'ACEITA',
        vendedorId: ID_OUTRO_VEND,
      }),
    );
  });

  it('cancelar devolução pede o motivo e manda um id de cancelamento novo', async () => {
    const m = montar({ 'devolucoes.cancelar': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Devoluções', 'Devoluções');
    await u.click(await screen.findByRole('button', { name: /Cancelar a devolução de Dora/ }));
    expect(screen.getByText('Cancelar esta devolução?')).toBeInTheDocument();
    await u.click(screen.getAllByRole('button', { name: 'Cancelar devolução' }).at(-1)!);
    expect(await screen.findByText('Diga o motivo de cancelar a devolução.')).toBeInTheDocument();
    expect(ultima(m, 'devolucoes.cancelar')).toBeUndefined();
    await u.type(screen.getByLabelText('Motivo'), 'Foi engano');
    await u.click(screen.getAllByRole('button', { name: 'Cancelar devolução' }).at(-1)!);
    await waitFor(() => expect(ultima(m, 'devolucoes.cancelar')).toBeDefined());
    const p = ultima(m, 'devolucoes.cancelar') as Record<string, string>;
    expect(p.id).toBe(ID_DEVOLUCAO);
    expect(p.motivo).toBe('Foi engano');
    expect(p.cancelamentoId).toMatch(UUID);
    expect(await screen.findByText('Devolução cancelada.')).toBeInTheDocument();
  });

  describe('registrar pela dona', () => {
    async function ateAEscolhaDaVenda(m: ReturnType<typeof montar>) {
      const u = await abrir(m, 'Devoluções', 'Devoluções');
      await u.click(await screen.findByRole('button', { name: 'Registrar devolução' }));
      await screen.findByRole('heading', { name: 'Registrar devolução', level: 1 });
      await u.type(screen.getByLabelText('Código da peça'), 'AB-1');
      await u.click(screen.getByRole('button', { name: 'Buscar vendas' }));
      await screen.findByText('Vendas com esta peça');
      return u;
    }

    it('acha as vendas pelo código, marca a venda a prazo e não deixa escolhê-la', async () => {
      const m = montar();
      await ateAEscolhaDaVenda(m);
      expect(ultima(m, 'devolucoes.vendasComAPeca')).toEqual({ codigo: 'AB-1' });
      expect(
        screen.getByText('Desistência até 17/11/2026, defeito até 10/12/2026'),
      ).toBeInTheDocument();
      expect(
        screen.getByText('Esta venda é a prazo. A devolução dela chega em breve.'),
      ).toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /Escolher a venda de/ })).toHaveLength(1);
    });

    it('não registra sem peças, motivo e forma, e pede observação no motivo Outro', async () => {
      const m = montar({ 'devolucoes.registrar': () => ({ ok: true, dados: {} }) });
      const u = await ateAEscolhaDaVenda(m);
      await u.click(screen.getByRole('button', { name: /Escolher a venda de Dora/ }));
      expect(
        screen.getByText(
          'A peça devolvida vai para a loja. Ela não volta para a maleta do vendedor.',
        ),
      ).toBeInTheDocument();
      await u.click(screen.getByRole('button', { name: 'Registrar devolução' }));
      expect(await screen.findByText('Escolha quantas peças voltam.')).toBeInTheDocument();
      expect(ultima(m, 'devolucoes.registrar')).toBeUndefined();

      // Só Dinheiro e Transferência (tarefa 6.13): Cartão e Cheque saem da tela.
      expect(
        within(screen.getByLabelText('Como o dinheiro foi devolvido'))
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toContain('Transferência');
      expect(screen.queryByRole('option', { name: 'Cartão' })).not.toBeInTheDocument();
      expect(screen.queryByRole('option', { name: 'Cheque' })).not.toBeInTheDocument();
      await u.type(screen.getByLabelText('Quantas voltam: Anel Lua'), '3');
      await u.selectOptions(screen.getByLabelText('Motivo da devolução'), 'OUTRO');
      await u.selectOptions(screen.getByLabelText('Como o dinheiro foi devolvido'), 'PIX');
      await u.click(screen.getByRole('button', { name: 'Registrar devolução' }));
      // 3 é mais do que as 2 que ainda podem voltar, e o motivo Outro pede observação.
      expect(
        await screen.findByText('Conte o motivo da devolução no campo de observação.'),
      ).toBeInTheDocument();
      expect(ultima(m, 'devolucoes.registrar')).toBeUndefined();
    });

    it('registra mandando só venda, peças, estado, motivo e forma (nunca valor)', async () => {
      const m = montar({
        'devolucoes.registrar': () => ({ ok: true, dados: { id: ID_DEVOLUCAO }, criado: true }),
      });
      const u = await ateAEscolhaDaVenda(m);
      await u.click(screen.getByRole('button', { name: /Escolher a venda de Dora/ }));
      await u.type(screen.getByLabelText('Quantas voltam: Anel Lua'), '2');
      await u.selectOptions(screen.getByLabelText('Motivo da devolução'), 'TAMANHO');
      await u.selectOptions(screen.getByLabelText('Como o dinheiro foi devolvido'), 'PIX');
      await u.click(screen.getByRole('button', { name: 'Registrar devolução' }));
      await waitFor(() => expect(ultima(m, 'devolucoes.registrar')).toBeDefined());
      const corpo = (ultima(m, 'devolucoes.registrar') as { corpo: Record<string, unknown> }).corpo;
      expect(corpo).toMatchObject({
        vendaId: ID_VENDA,
        motivo: 'TAMANHO',
        formaReembolso: 'PIX',
        itens: [{ produtoId: ID_PECA, quantidade: 2, estado: 'BOA' }],
      });
      expect(corpo.id).toMatch(UUID);
      expect(JSON.stringify(corpo)).not.toMatch(/valor/i);
      expect(await screen.findByText('Devolução registrada.')).toBeInTheDocument();
    });

    it('com o motivo Defeito a peça sai marcada como com defeito', async () => {
      const m = montar({ 'devolucoes.registrar': () => ({ ok: true, dados: {} }) });
      const u = await ateAEscolhaDaVenda(m);
      await u.click(screen.getByRole('button', { name: /Escolher a venda de Dora/ }));
      await u.type(screen.getByLabelText('Quantas voltam: Anel Lua'), '1');
      await u.selectOptions(screen.getByLabelText('Motivo da devolução'), 'DEFEITO');
      expect(screen.getByLabelText('Estado da peça: Anel Lua')).toHaveValue('DEFEITO');
      await u.selectOptions(screen.getByLabelText('Como o dinheiro foi devolvido'), 'DINHEIRO');
      await u.click(screen.getByRole('button', { name: 'Registrar devolução' }));
      await waitFor(() => expect(ultima(m, 'devolucoes.registrar')).toBeDefined());
      const corpo = (
        ultima(m, 'devolucoes.registrar') as { corpo: { itens: { estado: string }[] } }
      ).corpo;
      expect(corpo.itens[0]?.estado).toBe('DEFEITO');
    });

    it('fora do prazo, explica o que fazer com as palavras da dona', async () => {
      const m = montar({
        'devolucoes.registrar': () => ({
          ok: false,
          motivo: 'erro',
          status: 409,
          codigo: 'GARANTIA_VENCIDA',
          mensagem: 'Passou do prazo da garantia. Peça autorização à dona.',
        }),
      });
      const u = await ateAEscolhaDaVenda(m);
      await u.click(screen.getByRole('button', { name: /Escolher a venda de Dora/ }));
      await u.type(screen.getByLabelText('Quantas voltam: Anel Lua'), '1');
      await u.selectOptions(screen.getByLabelText('Motivo da devolução'), 'ARREPENDIMENTO');
      await u.selectOptions(screen.getByLabelText('Como o dinheiro foi devolvido'), 'PIX');
      await u.click(screen.getByRole('button', { name: 'Registrar devolução' }));
      expect(
        await screen.findByText(/O vendedor pode pedir a autorização no computador dele/),
      ).toBeInTheDocument();
      expect(screen.queryByText(/Peça autorização à dona/)).toBeNull();
    });

    it('código sem peça e peça sem venda dizem o que fazer', async () => {
      const m = montar({
        'devolucoes.vendasComAPeca': () => ({ ok: true, dados: { vendas: [] } }),
      });
      const u = await abrir(m, 'Devoluções', 'Devoluções');
      await u.click(await screen.findByRole('button', { name: 'Registrar devolução' }));
      await u.type(await screen.findByLabelText('Código da peça'), 'XX');
      await u.click(screen.getByRole('button', { name: 'Buscar vendas' }));
      expect(
        await screen.findByText('Não achamos nenhuma peça com este código. Confira o código.'),
      ).toBeInTheDocument();
    });
  });
});

describe('Trocas (tarefa 6.9)', () => {
  it('mostra a diferença com as palavras da loja', async () => {
    const m = montar();
    await abrir(m, 'Trocas', 'Trocas');
    expect(await screen.findByText('Fica de crédito: R$ 40,00')).toBeInTheDocument();
    expect(screen.getByText('Diferença a pagar: R$ 50,00')).toBeInTheDocument();
    expect(screen.getByText(/US\$ 7,27 · Gs\. 44\.693/)).toBeInTheDocument();
    expect(screen.getAllByText('Feita').length).toBeGreaterThan(0);
    expect(ultima(m, 'trocas.listar')).toEqual({ situacao: 'ATIVA', vendedorId: '' });
  });

  it('a lista Todas não manda situação', async () => {
    const m = montar();
    const u = await abrir(m, 'Trocas', 'Trocas');
    await screen.findByText('Fica de crédito: R$ 40,00');
    await u.selectOptions(screen.getByLabelText('Situação'), '');
    await waitFor(() =>
      expect(ultima(m, 'trocas.listar')).toEqual({ situacao: '', vendedorId: '' }),
    );
  });

  it('cancelar troca pede o motivo e avisa do crédito já usado quando o servidor recusa', async () => {
    let tentativa = 0;
    const m = montar({
      'trocas.cancelar': () =>
        ++tentativa === 1
          ? {
              ok: false,
              motivo: 'erro',
              status: 409,
              mensagem: 'O crédito que sobrou desta troca já foi usado em outra compra.',
            }
          : { ok: true, dados: {} },
    });
    const u = await abrir(m, 'Trocas', 'Trocas');
    await u.click((await screen.findAllByRole('button', { name: /Cancelar a troca de Dora/ }))[0]!);
    expect(
      screen.getByText(/Não dá para cancelar se o crédito que sobrou já foi usado/),
    ).toBeInTheDocument();
    await u.click(screen.getAllByRole('button', { name: 'Cancelar troca' }).at(-1)!);
    expect(await screen.findByText('Diga o motivo de cancelar a troca.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Motivo'), 'Cliente voltou atrás');
    await u.click(screen.getAllByRole('button', { name: 'Cancelar troca' }).at(-1)!);
    expect(await screen.findByText(/já foi usado em outra compra/)).toBeInTheDocument();
    await u.click(screen.getAllByRole('button', { name: 'Cancelar troca' }).at(-1)!);
    await waitFor(() => expect(ultima(m, 'trocas.cancelar')).toBeDefined());
    const p = ultima(m, 'trocas.cancelar') as Record<string, string>;
    expect(p.id).toBe(ID_TROCA);
    expect(p.motivo).toBe('Cliente voltou atrás');
    expect(p.cancelamentoId).toMatch(UUID);
    expect(await screen.findByText('Troca cancelada.')).toBeInTheDocument();
  });
});

describe('Créditos dos clientes (tarefa 6.9)', () => {
  it('mostra valor, já usado e saldo; só o crédito em aberto transfere', async () => {
    const m = montar();
    await abrir(m, 'Créditos dos clientes', 'Créditos dos clientes');
    expect(await screen.findByText('Troca')).toBeInTheDocument();
    expect(screen.getByText('R$ 40,00')).toBeInTheDocument();
    expect(screen.getByText('R$ 10,00')).toBeInTheDocument();
    expect(screen.getByText(/US\$ 5,45 · Gs\. 33\.519/)).toBeInTheDocument();
    expect(screen.getAllByText('Em aberto').length).toBeGreaterThan(0);
    expect(ultima(m, 'creditos.listar')).toEqual({ situacao: 'ABERTO', vendedorId: '' });
  });

  it('transferir exige escolher o vendedor, não oferece o de agora e manda um id novo', async () => {
    const m = montar({ 'creditos.transferir': () => ({ ok: true, dados: {}, criado: true }) });
    const u = await abrir(m, 'Créditos dos clientes', 'Créditos dos clientes');
    await u.click(await screen.findByRole('button', { name: /Transferir o crédito de Dora/ }));
    expect(
      screen.getByText(/O saldo inteiro, R\$ 30,00, passa para outro vendedor/),
    ).toBeInTheDocument();
    const escolha = screen.getByLabelText('Passar para o vendedor');
    expect(within(escolha).queryByRole('option', { name: 'Joana Vendedora' })).toBeNull();
    await u.click(screen.getAllByRole('button', { name: 'Transferir crédito' }).at(-1)!);
    expect(
      await screen.findByText('Escolha o vendedor que vai receber o crédito.'),
    ).toBeInTheDocument();
    expect(ultima(m, 'creditos.transferir')).toBeUndefined();
    await u.selectOptions(escolha, ID_OUTRO_VEND);
    await u.click(screen.getAllByRole('button', { name: 'Transferir crédito' }).at(-1)!);
    await waitFor(() => expect(ultima(m, 'creditos.transferir')).toBeDefined());
    const p = ultima(m, 'creditos.transferir') as Record<string, string>;
    expect(p.id).toBe(ID_CREDITO);
    expect(p.vendedorId).toBe(ID_OUTRO_VEND);
    expect(p.novoId).toMatch(UUID);
    expect(await screen.findByText('Crédito transferido.')).toBeInTheDocument();
  });
});

describe('Garantia e limites (tarefa 6.9)', () => {
  it('mostra os valores de hoje, com o limite vazio como Sem limite', async () => {
    const m = montar();
    await abrir(m, 'Garantia e limites', 'Garantia e limites');
    expect(await screen.findByLabelText('Dias para o cliente desistir')).toHaveValue('7');
    expect(screen.getByLabelText('Dias para devolver por defeito')).toHaveValue('30');
    expect(screen.getByLabelText('Sem limite')).toBeChecked();
    expect(screen.getByLabelText('Não aprovar antes')).not.toBeChecked();
    expect(screen.getByLabelText('Valor a partir do qual você aprova antes')).toHaveValue('500,00');
    expect(
      screen.getByLabelText('Avisar quando um vendedor devolver mais de (%) do que vendeu'),
    ).toHaveValue('20');
  });

  it('não salva com dias fora da faixa e diz o que fazer', async () => {
    const m = montar({ 'garantia.alterar': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Garantia e limites', 'Garantia e limites');
    const dias = await screen.findByLabelText('Dias para o cliente desistir');
    await u.clear(dias);
    await u.type(dias, '400');
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Use um número de dias entre 1 e 365.')).toBeInTheDocument();
    expect(ultima(m, 'garantia.alterar')).toBeUndefined();
  });

  it('um limite ligado precisa de valor maior que zero', async () => {
    const m = montar({ 'garantia.alterar': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Garantia e limites', 'Garantia e limites');
    await u.click(await screen.findByLabelText('Sem limite'));
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Informe um valor maior que zero.')).toBeInTheDocument();
    expect(ultima(m, 'garantia.alterar')).toBeUndefined();
  });

  it('salva os cinco valores com o dinheiro em texto e "sem limite" como vazio', async () => {
    const m = montar({ 'garantia.alterar': () => ({ ok: true, dados: {} }) });
    const u = await abrir(m, 'Garantia e limites', 'Garantia e limites');
    const dias = await screen.findByLabelText('Dias para o cliente desistir');
    await u.clear(dias);
    await u.type(dias, '10');
    await u.click(screen.getByLabelText('Sem limite'));
    await u.type(
      screen.getByLabelText('Devolução em dinheiro por semana, por vendedor'),
      '1.234,50',
    );
    await u.click(screen.getByLabelText('Não aprovar antes'));
    await u.click(screen.getByRole('button', { name: 'Salvar' }));
    await waitFor(() => expect(ultima(m, 'garantia.alterar')).toBeDefined());
    expect(ultima(m, 'garantia.alterar')).toEqual({
      prazoArrependimentoDias: 10,
      prazoDefeitoDias: 30,
      limiteReembolsoSemanal: '1234.50',
      valorAprovacaoReembolso: null,
      percentualAlertaDevolucao: 20,
    });
    expect(await screen.findByText('Garantia e limites salvos.')).toBeInTheDocument();
  });

  it('mostra a recusa do servidor', async () => {
    const m = montar({
      'garantia.alterar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Use um número de dias entre 1 e 365.',
        campos: [{ campo: 'prazoDefeitoDias', mensagem: 'Use um número de dias entre 1 e 365.' }],
      }),
    });
    const u = await abrir(m, 'Garantia e limites', 'Garantia e limites');
    await u.click(await screen.findByRole('button', { name: 'Salvar' }));
    expect(await screen.findByText('Use um número de dias entre 1 e 365.')).toBeInTheDocument();
  });
});

describe('palavras da loja (tarefa 6.9)', () => {
  const PROIBIDAS =
    /EM_REVISAO|PEDIDA|APROVADA|CREDITO|ACEITA|ATIVA|ABERTO|estorno|sincroniza|fila|pendente|token|aparelho|quarentena/;

  it.each([
    ['Devoluções para autorizar', 'Dora'],
    ['Devoluções para revisar', 'Dora'],
    ['Devoluções', 'Dora'],
    ['Trocas', 'Fica de crédito: R$ 40,00'],
    ['Créditos dos clientes', 'Troca'],
    ['Garantia e limites', null],
  ])('a tela %s não mostra termo técnico', async (tela, esperar) => {
    const m = montar();
    await abrir(m, tela, tela);
    if (esperar) await screen.findAllByText(esperar);
    else await screen.findByLabelText('Dias para o cliente desistir');
    expect(document.body.textContent).not.toMatch(PROIBIDAS);
  });
});

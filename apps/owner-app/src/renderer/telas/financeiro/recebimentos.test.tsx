// @vitest-environment jsdom
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  ID_VENDEDOR,
  abrir,
  chamadasDe,
  ehUuid,
  montar,
  ultima,
  type Manipulador,
} from '../acertos/apoio-de-teste';

const ID_CONTA = 'c1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_CONTA_2 = 'c2000000-ceea-4672-a1b9-3a1a0f7c4d22';
const ID_RECEBIMENTO = 'e1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_NO_ACERTO = 'e2000000-ceea-4672-a1b9-3a1a0f7c4d22';
const ID_DESFEITO = 'e3000000-ceea-4672-a1b9-3a1a0f7c4d33';

const contaDoVendedor = (extra: Record<string, unknown> = {}) => ({
  id: ID_CONTA,
  nivel: 'VENDEDOR',
  origem: 'ACERTO',
  vendedorId: ID_VENDEDOR,
  vendedorNome: 'Ana Vendedora',
  numero: 1,
  totalDeParcelas: 2,
  vencimento: '2026-11-30',
  valor: '500.00',
  valorPago: '100.00',
  valorCancelado: '0.00',
  emAberto: '400.00',
  status: 'PARCIAL',
  vencida: false,
  ...extra,
});

const contaDoCliente = contaDoVendedor({
  id: ID_CONTA_2,
  nivel: 'CLIENTE',
  origem: 'VENDA',
  clienteNome: 'Maria Cliente',
  vendaId: 'v1',
  numero: 2,
  totalDeParcelas: 3,
  vencimento: '2026-11-10',
  valor: '90.00',
  valorPago: '0.00',
  emAberto: '90.00',
  status: 'ABERTA',
  vencida: true,
});

const recebimento = (extra: Record<string, unknown> = {}) => ({
  id: ID_RECEBIMENTO,
  vendedorId: ID_VENDEDOR,
  vendedorNome: 'Ana Vendedora',
  momento: 'AVULSO',
  aplicadoEm: 'CONTAS',
  forma: 'PIX',
  valor: '100.00',
  recebidoEm: '2026-11-12T15:00:00Z',
  estornado: false,
  contas: [{ contaId: ID_CONTA, valor: '100.00' }],
  ...extra,
});

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'contasAReceber.listar': (p) => ({
      ok: true,
      dados:
        p.parametros?.nivel === 'VENDEDOR'
          ? [contaDoVendedor()]
          : [contaDoVendedor(), contaDoCliente],
    }),
    'recebimentos.listar': () => ({
      ok: true,
      dados: [
        recebimento(),
        recebimento({
          id: ID_NO_ACERTO,
          momento: 'ACERTO',
          aplicadoEm: 'ACERTO',
          acertoId: 'a1',
          valor: '300.00',
          forma: 'DINHEIRO',
        }),
        recebimento({ id: ID_DESFEITO, estornado: true, valor: '50.00' }),
        recebimento({ id: 'e4', estornaId: ID_DESFEITO, valor: '-50.00', motivo: 'Errei' }),
      ],
    }),
    'recebimentos.receber': () => ({ ok: true, dados: {}, criado: true }),
    'recebimentos.estornar': () => ({ ok: true, dados: {}, criado: true }),
    ...extra,
  });
}

describe('Contas a receber', () => {
  async function abrirContas(m: ReturnType<typeof servidor>) {
    const u = await abrir(m, 'Contas a receber');
    await screen.findByRole('heading', { name: 'Contas a receber', level: 1 });
    await screen.findByText('Maria Cliente');
    return u;
  }

  it('lista as contas dos dois lados como o servidor mandou, sem refazer conta', async () => {
    await abrirContas(servidor());
    expect(screen.getByText('Do cliente ao vendedor', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('Do vendedor à loja', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('R$ 400,00')).toBeInTheDocument();
    expect(screen.getByText('1 de 2')).toBeInTheDocument();
    expect(screen.getByText('2 de 3')).toBeInTheDocument();
    expect(screen.getByText('30/11/2026')).toBeInTheDocument();
    expect(screen.getByText('Vencida', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('Parcial', { selector: 'td' })).toBeInTheDocument();
  });

  it('os filtros mandam vendedor, situação e de quem é a conta', async () => {
    const m = servidor();
    const u = await abrirContas(m);
    await u.selectOptions(screen.getByLabelText('Vendedor'), ID_VENDEDOR);
    await u.selectOptions(screen.getByLabelText('De quem é a conta'), 'VENDEDOR');
    await u.selectOptions(screen.getByLabelText('Situação'), 'EM_ABERTO');
    await waitFor(() =>
      expect(ultima(m, 'contasAReceber.listar')).toEqual({
        vendedorId: ID_VENDEDOR,
        nivel: 'VENDEDOR',
        situacao: 'EM_ABERTO',
      }),
    );
  });

  it('é só consulta: nenhum botão de registrar na lista', async () => {
    await abrirContas(servidor());
    expect(screen.queryByRole('button', { name: /Registrar/ })).toBeNull();
  });

  it('sem internet ao abrir: avisa e deixa tentar de novo', async () => {
    const m = servidor({ 'contasAReceber.listar': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m, 'Contas a receber');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();
  });
});

describe('Recebimentos do vendedor', () => {
  async function abrirRecebimentos(m: ReturnType<typeof servidor>) {
    const u = await abrir(m, 'Recebimentos do vendedor');
    await screen.findByRole('heading', { name: 'Recebimentos do vendedor', level: 1 });
    await screen.findAllByText('Recebimento avulso', { selector: 'td' });
    return u;
  }

  it('lista com a situação de cada um (desfeito e estorno marcados)', async () => {
    await abrirRecebimentos(servidor());
    expect(screen.getByText('Pago no acerto')).toBeInTheDocument();
    expect(screen.getByText('Desfeito')).toBeInTheDocument();
    expect(screen.getAllByText('Estorno').length).toBeGreaterThan(0);
    expect(screen.getByText('-R$ 50,00')).toBeInTheDocument();
  });

  it('só o recebimento que vale tem Estornar recebimento', async () => {
    await abrirRecebimentos(servidor());
    expect(screen.getAllByRole('button', { name: /^Estornar o recebimento de/ })).toHaveLength(2);
  });

  it('registrar: vendedor, conta em aberto do vendedor, três formas e valor com ponto', async () => {
    const m = servidor();
    const u = await abrirRecebimentos(m);
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    const formas = within(screen.getByLabelText('Forma')).getAllByRole('option');
    expect(formas.map((o) => o.textContent)).toEqual(['Dinheiro', 'Transferência (PIX)', 'Boleto']);
    await u.selectOptions(screen.getByLabelText('Vendedor'), ID_VENDEDOR);
    await waitFor(() =>
      expect(ultima(m, 'contasAReceber.listar')).toEqual({
        vendedorId: ID_VENDEDOR,
        nivel: 'VENDEDOR',
        situacao: 'EM_ABERTO',
      }),
    );
    const contas = within(await screen.findByLabelText('Conta a baixar')).getAllByRole('option');
    expect(contas.map((o) => o.textContent)).toEqual([
      'As contas em aberto, da mais antiga para a mais nova',
      'Vencimento 30/11/2026, em aberto R$ 400,00',
    ]);
    await u.selectOptions(screen.getByLabelText('Forma'), 'PIX');
    await u.type(screen.getByLabelText('Valor recebido'), '1.250,5');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await waitFor(() => expect(chamadasDe(m, 'recebimentos.receber')).toHaveLength(1));
    const p = ultima(m, 'recebimentos.receber');
    expect(p).toMatchObject({ vendedorId: ID_VENDEDOR, forma: 'PIX', valor: '1250.50' });
    expect(Object.keys(p ?? {}).sort()).toEqual(['forma', 'id', 'valor', 'vendedorId']);
    expect(ehUuid(p?.id)).toBe(true);
    expect(await screen.findByText('Recebimento registrado.')).toBeInTheDocument();
  });

  it('com conta escolhida, manda a conta', async () => {
    const m = servidor();
    const u = await abrirRecebimentos(m);
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await u.selectOptions(screen.getByLabelText('Vendedor'), ID_VENDEDOR);
    await u.selectOptions(await screen.findByLabelText('Conta a baixar'), ID_CONTA);
    await u.type(screen.getByLabelText('Valor recebido'), '10');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await waitFor(() => expect(chamadasDe(m, 'recebimentos.receber')).toHaveLength(1));
    expect(ultima(m, 'recebimentos.receber')).toMatchObject({ contaId: ID_CONTA, valor: '10.00' });
  });

  it('sem internet, a nova tentativa manda o mesmo id; depois de feito, o próximo recebimento tem id novo', async () => {
    let tentativas = 0;
    const m = servidor({
      'recebimentos.receber': () => {
        tentativas += 1;
        return tentativas === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: {}, criado: true };
      },
    });
    const u = await abrirRecebimentos(m);
    const preencher = async () => {
      await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
      await u.selectOptions(screen.getByLabelText('Vendedor'), ID_VENDEDOR);
      await u.type(screen.getByLabelText('Valor recebido'), '10');
    };
    await preencher();
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await waitFor(() => expect(chamadasDe(m, 'recebimentos.receber')).toHaveLength(2));
    await screen.findByText('Recebimento registrado.');
    await preencher();
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await waitFor(() => expect(chamadasDe(m, 'recebimentos.receber')).toHaveLength(3));
    const ids = chamadasDe(m, 'recebimentos.receber').map((c) => c.parametros?.id);
    expect(ids[1]).toBe(ids[0]);
    expect(ids[2]).not.toBe(ids[0]);
  });

  it('recusa vendedor e valor que faltam antes de mandar; mostra a recusa do servidor', async () => {
    const m = servidor({
      'recebimentos.receber': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'Este vendedor não tem conta em aberto.',
      }),
    });
    const u = await abrirRecebimentos(m);
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(await screen.findByText('Escolha o vendedor.')).toBeInTheDocument();
    expect(
      screen.getByText('Digite o valor recebido, em reais, maior que zero.'),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'recebimentos.receber')).toHaveLength(0);
    await u.selectOptions(screen.getByLabelText('Vendedor'), ID_VENDEDOR);
    await u.type(screen.getByLabelText('Valor recebido'), '10');
    await u.click(screen.getByRole('button', { name: 'Registrar recebimento' }));
    expect(await screen.findByText('Este vendedor não tem conta em aberto.')).toBeInTheDocument();
  });

  it('estornar pede o motivo e manda o id do comando; o vencimento só aparece se o recebimento pagou um acerto', async () => {
    const m = servidor();
    const u = await abrirRecebimentos(m);
    await u.click(screen.getAllByRole('button', { name: /^Estornar o recebimento de/ })[0]!);
    expect(screen.queryByLabelText(/Novo vencimento/)).toBeNull();
    await u.click(screen.getByRole('button', { name: 'Estornar recebimento' }));
    expect(await screen.findByText('Diga o motivo de estornar o recebimento.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Motivo do estorno'), 'Não caiu na conta');
    await u.click(screen.getByRole('button', { name: 'Estornar recebimento' }));
    await waitFor(() => expect(chamadasDe(m, 'recebimentos.estornar')).toHaveLength(1));
    const p = ultima(m, 'recebimentos.estornar');
    expect(p).toMatchObject({ id: ID_RECEBIMENTO, motivo: 'Não caiu na conta' });
    expect(ehUuid(p?.comandoId)).toBe(true);
    expect(p).not.toHaveProperty('vencimento');
    expect(await screen.findByText('Recebimento estornado.')).toBeInTheDocument();
  });

  it('estornar o que pagou o acerto deixa escolher o novo vencimento', async () => {
    const m = servidor();
    const u = await abrirRecebimentos(m);
    await u.click(screen.getAllByRole('button', { name: /^Estornar o recebimento de/ })[1]!);
    fireEvent.change(screen.getByLabelText(/Novo vencimento da conta/), {
      target: { value: '2026-12-01' },
    });
    await u.type(screen.getByLabelText('Motivo do estorno'), 'Errado');
    await u.click(screen.getByRole('button', { name: 'Estornar recebimento' }));
    await waitFor(() => expect(chamadasDe(m, 'recebimentos.estornar')).toHaveLength(1));
    expect(ultima(m, 'recebimentos.estornar')).toMatchObject({
      id: ID_NO_ACERTO,
      vencimento: '2026-12-01',
    });
  });

  it('sem internet ao abrir: avisa e deixa tentar de novo', async () => {
    const m = servidor({ 'recebimentos.listar': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m, 'Recebimentos do vendedor');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();
  });
});

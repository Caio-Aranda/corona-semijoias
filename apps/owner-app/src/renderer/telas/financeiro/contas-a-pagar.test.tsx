// @vitest-environment jsdom
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  abrir,
  chamadasDe,
  ehUuid,
  montar,
  pagina,
  ultima,
  type Manipulador,
} from '../acertos/apoio-de-teste';

const ID_CONTA = 'a1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_PAGA = 'a2000000-ceea-4672-a1b9-3a1a0f7c4d22';
const ID_FORNECEDOR = 'f1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_BAIXA = 'b1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_ESTORNO = 'b2000000-ceea-4672-a1b9-3a1a0f7c4d22';

const conta = (extra: Record<string, unknown> = {}) => ({
  id: ID_CONTA,
  compraId: 'c0000000-ceea-4672-a1b9-3a1a0f7c4d11',
  numeroDaCompra: '15',
  fornecedorId: ID_FORNECEDOR,
  fornecedorNome: 'Brilho Atacado',
  parcela: 1,
  totalParcelas: 3,
  vencimento: '2026-11-20',
  valor: '300.00',
  valorPago: '100.00',
  valorCancelado: '0.00',
  saldo: '200.00',
  situacao: 'PARCIAL',
  baixas: [],
  ...extra,
});

const paga = conta({
  id: ID_PAGA,
  parcela: 2,
  vencimento: '2026-10-20',
  valorPago: '300.00',
  saldo: '0.00',
  situacao: 'PAGA',
});

const baixa = (extra: Record<string, unknown> = {}) => ({
  id: ID_BAIXA,
  tipo: 'PAGAMENTO',
  valor: '100.00',
  forma: 'PIX',
  dataPagamento: '2026-11-05',
  motivo: null,
  estornaId: null,
  registradaEm: '2026-11-05T12:00:00Z',
  ...extra,
});

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'fornecedores.listar': () =>
      pagina([{ id: ID_FORNECEDOR, nome: 'Brilho Atacado', ativo: true }]),
    'contasAPagar.listar': () => pagina([conta(), paga]),
    'contasAPagar.ver': () => ({ ok: true, dados: conta({ baixas: [baixa()] }) }),
    'contasAPagar.pagar': () => ({ ok: true, dados: {}, criado: true }),
    'contasAPagar.estornar': () => ({ ok: true, dados: {}, criado: true }),
    ...extra,
  });
}

async function abrirContas(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, 'Contas a pagar');
  await screen.findByRole('heading', { name: 'Contas a pagar', level: 1 });
  await screen.findAllByText('Brilho Atacado', { selector: 'td' });
  return u;
}

describe('Contas a pagar', () => {
  it('lista as parcelas como o servidor mandou, sem refazer conta', async () => {
    const m = servidor();
    await abrirContas(m);
    expect(screen.getByText('1 de 3')).toBeInTheDocument();
    expect(screen.getByText('20/11/2026')).toBeInTheDocument();
    expect(screen.getByText('R$ 200,00')).toBeInTheDocument();
    expect(screen.getByText('Parcial', { selector: 'td' })).toBeInTheDocument();
    expect(screen.getByText('Paga', { selector: 'td' })).toBeInTheDocument();
    expect(ultima(m, 'contasAPagar.listar')).toEqual({ pagina: 0, tamanho: 20 });
  });

  it('os filtros mandam fornecedor e situação', async () => {
    const m = servidor();
    const u = await abrirContas(m);
    await u.selectOptions(screen.getByLabelText('Fornecedor'), ID_FORNECEDOR);
    await waitFor(() =>
      expect(ultima(m, 'contasAPagar.listar')).toMatchObject({ fornecedorId: ID_FORNECEDOR }),
    );
    await u.selectOptions(screen.getByLabelText('Situação'), 'VENCIDA');
    await waitFor(() =>
      expect(ultima(m, 'contasAPagar.listar')).toEqual({
        pagina: 0,
        tamanho: 20,
        fornecedorId: ID_FORNECEDOR,
        situacao: 'VENCIDA',
      }),
    );
  });

  it('só a conta com o que pagar tem Registrar pagamento', async () => {
    await abrirContas(servidor());
    expect(screen.getAllByRole('button', { name: /^Registrar pagamento de/ })).toHaveLength(1);
    expect(
      screen.getByRole('button', { name: 'Registrar pagamento de Brilho Atacado, parcela 1 de 3' }),
    ).toBeInTheDocument();
  });

  it('registrar pagamento: três formas, valor com ponto, data opcional e id do comando', async () => {
    const m = servidor();
    const u = await abrirContas(m);
    await u.click(
      screen.getByRole('button', { name: 'Registrar pagamento de Brilho Atacado, parcela 1 de 3' }),
    );
    expect(screen.getByText('Falta pagar R$ 200,00 desta parcela.')).toBeInTheDocument();
    const formas = within(screen.getByLabelText('Forma')).getAllByRole('option');
    expect(formas.map((o) => o.textContent)).toEqual(['Dinheiro', 'Transferência (PIX)', 'Boleto']);
    await u.selectOptions(screen.getByLabelText('Forma'), 'BOLETO');
    await u.type(screen.getByLabelText('Valor pago'), '80,5');
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    await waitFor(() => expect(chamadasDe(m, 'contasAPagar.pagar')).toHaveLength(1));
    const p = ultima(m, 'contasAPagar.pagar');
    expect(p).toMatchObject({ id: ID_CONTA, forma: 'BOLETO', valor: '80.50' });
    expect(ehUuid(p?.comandoId)).toBe(true);
    expect(p).not.toHaveProperty('data');
    expect(await screen.findByText('Pagamento registrado.')).toBeInTheDocument();
  });

  it('com data escolhida, manda a data', async () => {
    const m = servidor();
    const u = await abrirContas(m);
    await u.click(
      screen.getByRole('button', { name: 'Registrar pagamento de Brilho Atacado, parcela 1 de 3' }),
    );
    fireEvent.change(screen.getByLabelText('Data do pagamento'), {
      target: { value: '2026-11-10' },
    });
    await u.type(screen.getByLabelText('Valor pago'), '10');
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    await waitFor(() => expect(chamadasDe(m, 'contasAPagar.pagar')).toHaveLength(1));
    expect(ultima(m, 'contasAPagar.pagar')).toMatchObject({ data: '2026-11-10', valor: '10.00' });
  });

  it('sem internet, a nova tentativa manda o mesmo id; depois de feito, o próximo pagamento tem id novo', async () => {
    let tentativas = 0;
    const m = servidor({
      'contasAPagar.pagar': () => {
        tentativas += 1;
        return tentativas === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: {}, criado: true };
      },
    });
    const u = await abrirContas(m);
    const abrirForm = () =>
      u.click(
        screen.getByRole('button', {
          name: 'Registrar pagamento de Brilho Atacado, parcela 1 de 3',
        }),
      );
    await abrirForm();
    await u.type(screen.getByLabelText('Valor pago'), '10');
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    await waitFor(() => expect(chamadasDe(m, 'contasAPagar.pagar')).toHaveLength(2));
    await screen.findByText('Pagamento registrado.');
    await abrirForm();
    await u.type(screen.getByLabelText('Valor pago'), '10');
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    await waitFor(() => expect(chamadasDe(m, 'contasAPagar.pagar')).toHaveLength(3));
    const ids = chamadasDe(m, 'contasAPagar.pagar').map((c) => c.parametros?.comandoId);
    expect(ids[1]).toBe(ids[0]);
    expect(ids[2]).not.toBe(ids[0]);
  });

  it('recusa valor que falta antes de mandar e mostra a recusa do servidor', async () => {
    const m = servidor({
      'contasAPagar.pagar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'O valor passa do que falta pagar desta conta (R$ 200,00). Confira o valor.',
      }),
    });
    const u = await abrirContas(m);
    await u.click(
      screen.getByRole('button', { name: 'Registrar pagamento de Brilho Atacado, parcela 1 de 3' }),
    );
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    expect(
      await screen.findByText('Digite o valor pago, em reais, maior que zero.'),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'contasAPagar.pagar')).toHaveLength(0);
    await u.type(screen.getByLabelText('Valor pago'), '999');
    await u.click(screen.getByRole('button', { name: 'Registrar pagamento' }));
    expect(
      await screen.findByText(
        'O valor passa do que falta pagar desta conta (R$ 200,00). Confira o valor.',
      ),
    ).toBeInTheDocument();
  });

  it('Ver pagamentos mostra as baixas; só o pagamento ainda valendo tem Estornar pagamento', async () => {
    const m = servidor({
      'contasAPagar.ver': () => ({
        ok: true,
        dados: conta({
          baixas: [
            baixa(),
            baixa({
              id: ID_ESTORNO,
              valor: '-100.00',
              estornaId: ID_BAIXA,
              motivo: 'Errado',
              dataPagamento: '2026-11-06',
            }),
            baixa({ id: 'b3', dataPagamento: '2026-11-07', valor: '50.00' }),
          ],
        }),
      }),
    });
    const u = await abrirContas(m);
    await u.click(
      screen.getByRole('button', { name: 'Ver pagamentos de Brilho Atacado, parcela 1 de 3' }),
    );
    await screen.findByRole('heading', { name: 'Pagamentos desta parcela' });
    expect(ultima(m, 'contasAPagar.ver')).toEqual({ id: ID_CONTA });
    expect(screen.getAllByRole('button', { name: /^Estornar o pagamento de/ })).toHaveLength(1);
    expect(
      screen.getByRole('button', { name: 'Estornar o pagamento de 07/11/2026' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Estorno (Transferência (PIX))')).toBeInTheDocument();
  });

  it('estornar pagamento: pede o motivo, manda baixa e id do comando', async () => {
    const m = servidor();
    const u = await abrirContas(m);
    await u.click(
      screen.getByRole('button', { name: 'Ver pagamentos de Brilho Atacado, parcela 1 de 3' }),
    );
    await u.click(
      await screen.findByRole('button', { name: 'Estornar o pagamento de 05/11/2026' }),
    );
    await u.click(screen.getByRole('button', { name: 'Estornar pagamento' }));
    expect(await screen.findByText('Diga o motivo de estornar o pagamento.')).toBeInTheDocument();
    await u.type(screen.getByLabelText('Motivo do estorno'), 'Paguei errado');
    await u.click(screen.getByRole('button', { name: 'Estornar pagamento' }));
    await waitFor(() => expect(chamadasDe(m, 'contasAPagar.estornar')).toHaveLength(1));
    const p = ultima(m, 'contasAPagar.estornar');
    expect(p).toMatchObject({ baixaId: ID_BAIXA, motivo: 'Paguei errado' });
    expect(ehUuid(p?.comandoId)).toBe(true);
    expect(await screen.findByText('Pagamento estornado.')).toBeInTheDocument();
  });

  it('sem internet ao abrir: avisa e deixa tentar de novo', async () => {
    const m = servidor({ 'contasAPagar.listar': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m, 'Contas a pagar');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();
  });
});

// @vitest-environment jsdom
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { abrir, chamadasDe, ehUuid, montar, ultima } from '../acertos/apoio-de-teste';
import type { Manipulador } from '../acertos/apoio-de-teste';

const ID_CATEGORIA = 'c1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_LANCAMENTO = 'd1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_AUTOMATICO = 'd2000000-ceea-4672-a1b9-3a1a0f7c4d22';

const categoria = (extra: Record<string, unknown> = {}) => ({
  id: ID_CATEGORIA,
  nome: 'Aluguel',
  tipo: 'SAIDA',
  ativa: true,
  sistema: false,
  ...extra,
});

const lancamento = (extra: Record<string, unknown> = {}) => ({
  id: ID_LANCAMENTO,
  tipo: 'SAIDA',
  valor: '-150.50',
  forma: 'PIX',
  categoria: 'Aluguel',
  origemTipo: 'MANUAL',
  dataLancamento: '2026-11-10',
  lancadoEm: '2026-11-10T15:00:00Z',
  descricao: 'Aluguel de novembro',
  saldoDepois: '849.50',
  ...extra,
});

const automatico = lancamento({
  id: ID_AUTOMATICO,
  tipo: 'ENTRADA',
  valor: '200.00',
  forma: 'DINHEIRO',
  categoria: 'Repasse do representante',
  origemTipo: 'RECEBIMENTO_VENDEDOR',
  descricao: '',
  vendedorNome: 'Ana Representante',
  saldoDepois: '1000.00',
});

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'caixa.saldo': () => ({ ok: true, dados: { saldo: '849.50', negativo: false } }),
    'caixa.extrato': () => ({
      ok: true,
      dados: {
        de: '2026-11-01',
        ate: '2026-11-15',
        saldoAnterior: '800.00',
        entradas: '200.00',
        saidas: '-150.50',
        saldoFinal: '849.50',
        lancamentos: [automatico, lancamento()],
      },
    }),
    'caixa.categorias': () => ({
      ok: true,
      dados: [
        categoria(),
        categoria({ id: 'c2', nome: 'Venda extra', tipo: 'ENTRADA' }),
        categoria({ id: 'c3', nome: 'Antiga', ativa: false }),
        categoria({ id: 'c4', nome: 'Repasse do representante', tipo: 'ENTRADA', sistema: true }),
      ],
    }),
    'caixa.lancar': () => ({ ok: true, dados: {}, criado: true }),
    'caixa.estornar': () => ({ ok: true, dados: {}, criado: true }),
    ...extra,
  });
}

async function abrirCaixa(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, 'Caixa');
  await screen.findByRole('heading', { name: 'Caixa', level: 1 });
  await screen.findByText('Aluguel de novembro');
  return u;
}

describe('Caixa', () => {
  it('o menu tem o grupo Financeiro com as telas', async () => {
    await abrirCaixa(servidor());
    const menu = screen.getByRole('navigation', { name: 'Menu do Corona Gestão' });
    for (const nome of ['Caixa', 'Categorias do caixa']) {
      expect(within(menu).getByRole('button', { name: nome })).toBeInTheDocument();
    }
  });

  it('mostra o saldo, o resumo e o extrato como o servidor mandou, sem refazer conta', async () => {
    await abrirCaixa(servidor());
    expect(screen.getByText('Saldo')).toBeInTheDocument();
    expect(screen.getAllByText('R$ 849,50').length).toBeGreaterThan(0);
    expect(screen.getByText('R$ 800,00')).toBeInTheDocument();
    expect(screen.getAllByText('R$ 200,00').length).toBeGreaterThan(0);
    expect(screen.getAllByText('-R$ 150,50').length).toBeGreaterThan(0);
    expect(screen.getByText('Recebimento do representante')).toBeInTheDocument();
    expect(screen.getByText('Transferência (PIX)')).toBeInTheDocument();
    expect(screen.getAllByText('10/11/2026')).toHaveLength(2);
  });

  it('avisa quando o caixa está negativo', async () => {
    const m = servidor({
      'caixa.saldo': () => ({ ok: true, dados: { saldo: '-20.00', negativo: true } }),
    });
    await abrirCaixa(m);
    expect(screen.getByText('O caixa está negativo em R$ 20,00.')).toBeInTheDocument();
    expect(screen.getByText(/Entrou menos dinheiro do que saiu/)).toBeInTheDocument();
  });

  it('o período manda só as datas escolhidas', async () => {
    const m = servidor();
    await abrirCaixa(m);
    fireEvent.change(screen.getByLabelText('De'), { target: { value: '2026-11-05' } });
    await waitFor(() => expect(ultima(m, 'caixa.extrato')).toEqual({ de: '2026-11-05' }));
    fireEvent.change(screen.getByLabelText('Até'), { target: { value: '2026-11-12' } });
    await waitFor(() =>
      expect(ultima(m, 'caixa.extrato')).toEqual({ de: '2026-11-05', ate: '2026-11-12' }),
    );
  });

  it('só o lançamento da loja tem Estornar lançamento', async () => {
    await abrirCaixa(servidor());
    expect(
      screen.getByRole('button', { name: 'Estornar o lançamento Aluguel de novembro' }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /Estornar o lançamento/ })).toHaveLength(1);
  });

  it('estornar pede o motivo, manda o id do comando e reaproveita o id na nova tentativa', async () => {
    let tentativas = 0;
    const m = servidor({
      'caixa.estornar': () => {
        tentativas += 1;
        return tentativas === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: {}, criado: true };
      },
    });
    const u = await abrirCaixa(m);
    await u.click(
      screen.getByRole('button', { name: 'Estornar o lançamento Aluguel de novembro' }),
    );
    await u.click(screen.getByRole('button', { name: 'Estornar lançamento' }));
    expect(await screen.findByText('Diga o motivo de estornar o lançamento.')).toBeInTheDocument();
    expect(chamadasDe(m, 'caixa.estornar')).toHaveLength(0);
    await u.type(screen.getByLabelText('Motivo do estorno'), 'Lancei errado');
    await u.click(screen.getByRole('button', { name: 'Estornar lançamento' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.estornar')).toHaveLength(1));
    await u.click(screen.getByRole('button', { name: 'Estornar lançamento' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.estornar')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'caixa.estornar').map((c) => c.parametros);
    expect(a).toMatchObject({ id: ID_LANCAMENTO, motivo: 'Lancei errado' });
    expect(ehUuid(a?.comandoId)).toBe(true);
    expect(b?.comandoId).toBe(a?.comandoId);
    expect(await screen.findByText('Lançamento estornado.')).toBeInTheDocument();
  });

  it('lançar no caixa: só as três formas, só categorias da loja do mesmo tipo, valor como texto com ponto', async () => {
    const m = servidor();
    const u = await abrirCaixa(m);
    await u.click(screen.getByRole('button', { name: 'Lançar no caixa' }));
    const formas = within(screen.getByLabelText('Forma')).getAllByRole('option');
    expect(formas.map((o) => o.textContent)).toEqual(['Dinheiro', 'Transferência (PIX)', 'Boleto']);
    await u.click(screen.getByLabelText('Saiu'));
    const categorias = within(screen.getByLabelText('Categoria')).getAllByRole('option');
    expect(categorias.map((o) => o.textContent)).toEqual(['Escolha a categoria', 'Aluguel']);
    await u.selectOptions(screen.getByLabelText('Categoria'), ID_CATEGORIA);
    await u.selectOptions(screen.getByLabelText('Forma'), 'PIX');
    fireEvent.change(screen.getByLabelText('Data'), { target: { value: '2026-11-10' } });
    await u.type(screen.getByLabelText('Descrição'), ' Aluguel de dezembro ');
    await u.type(screen.getByLabelText('Valor'), '1.150,50');
    await u.click(screen.getByRole('button', { name: 'Lançar' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.lancar')).toHaveLength(1));
    const p = ultima(m, 'caixa.lancar');
    expect(p).toMatchObject({
      tipo: 'SAIDA',
      forma: 'PIX',
      categoriaId: ID_CATEGORIA,
      data: '2026-11-10',
      descricao: 'Aluguel de dezembro',
      valor: '1150.50',
    });
    expect(ehUuid(p?.id)).toBe(true);
    expect(Object.keys(p ?? {}).sort()).toEqual(
      ['categoriaId', 'data', 'descricao', 'forma', 'id', 'tipo', 'valor'].sort(),
    );
    expect(await screen.findByText('Lançamento feito.')).toBeInTheDocument();
  });

  it('lançar: sem internet, tentar de novo manda o mesmo id; depois de feito, o próximo lançamento tem id novo', async () => {
    let tentativas = 0;
    const m = servidor({
      'caixa.lancar': () => {
        tentativas += 1;
        return tentativas === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: {}, criado: true };
      },
    });
    const u = await abrirCaixa(m);
    const preencher = async () => {
      await u.click(screen.getByLabelText('Saiu'));
      await u.selectOptions(screen.getByLabelText('Categoria'), ID_CATEGORIA);
      fireEvent.change(screen.getByLabelText('Data'), { target: { value: '2026-11-10' } });
      await u.type(screen.getByLabelText('Descrição'), 'Luz');
      await u.type(screen.getByLabelText('Valor'), '10');
    };
    await u.click(screen.getByRole('button', { name: 'Lançar no caixa' }));
    await preencher();
    await u.click(screen.getByRole('button', { name: 'Lançar' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.lancar')).toHaveLength(1));
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Lançar' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.lancar')).toHaveLength(2));
    await screen.findByText('Lançamento feito.');
    await u.click(screen.getByRole('button', { name: 'Lançar no caixa' }));
    await preencher();
    await u.click(screen.getByRole('button', { name: 'Lançar' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.lancar')).toHaveLength(3));
    const ids = chamadasDe(m, 'caixa.lancar').map((c) => c.parametros?.id);
    expect(ids[1]).toBe(ids[0]);
    expect(ids[2]).not.toBe(ids[0]);
  });

  it('lançar: recusa antes de mandar valor, categoria, data e descrição que faltam', async () => {
    const m = servidor();
    const u = await abrirCaixa(m);
    await u.click(screen.getByRole('button', { name: 'Lançar no caixa' }));
    await u.click(screen.getByRole('button', { name: 'Lançar' }));
    expect(await screen.findByText('Escolha a categoria do lançamento.')).toBeInTheDocument();
    expect(screen.getByText('Escolha a data do lançamento.')).toBeInTheDocument();
    expect(screen.getByText('Descreva o lançamento.')).toBeInTheDocument();
    expect(screen.getAllByText(/Digite o valor em reais, maior que zero/).length).toBeGreaterThan(
      0,
    );
    expect(chamadasDe(m, 'caixa.lancar')).toHaveLength(0);
  });

  it('lançar: mostra a recusa do servidor em português', async () => {
    const m = servidor({
      'caixa.lancar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'A data do lançamento não pode ser no futuro.',
      }),
    });
    const u = await abrirCaixa(m);
    await u.click(screen.getByRole('button', { name: 'Lançar no caixa' }));
    await u.click(screen.getByLabelText('Saiu'));
    await u.selectOptions(screen.getByLabelText('Categoria'), ID_CATEGORIA);
    fireEvent.change(screen.getByLabelText('Data'), { target: { value: '2026-12-31' } });
    await u.type(screen.getByLabelText('Descrição'), 'Luz');
    await u.type(screen.getByLabelText('Valor'), '10');
    await u.click(screen.getByRole('button', { name: 'Lançar' }));
    expect(
      await screen.findByText('A data do lançamento não pode ser no futuro.'),
    ).toBeInTheDocument();
  });

  it('sem internet ao abrir: avisa e deixa tentar de novo', async () => {
    const m = servidor({ 'caixa.extrato': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m, 'Caixa');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();
  });
});

describe('Categorias do caixa', () => {
  async function abrirCategorias(m: ReturnType<typeof servidor>) {
    const u = await abrir(m, 'Categorias do caixa');
    await screen.findByRole('heading', { name: 'Categorias do caixa', level: 1 });
    await screen.findByText('Venda extra');
    return u;
  }

  it('lista com tipo e situação; a categoria da loja do sistema não tem ações', async () => {
    await abrirCategorias(servidor());
    expect(screen.getByText('Da loja (não muda)')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Renomear a categoria Aluguel' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Renomear a categoria Repasse do representante' }),
    ).toBeNull();
    expect(screen.getByRole('button', { name: 'Reativar a categoria Antiga' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Inativar a categoria Aluguel' }),
    ).toBeInTheDocument();
  });

  it('criar leva id novo, nome e tipo', async () => {
    const m = servidor({ 'caixa.criarCategoria': () => ({ ok: true, dados: {}, criado: true }) });
    const u = await abrirCategorias(m);
    await u.click(screen.getByRole('button', { name: 'Nova categoria' }));
    await u.type(screen.getByLabelText('Nome da categoria'), ' Luz ');
    await u.selectOptions(screen.getByLabelText('Tipo'), 'SAIDA');
    await u.click(screen.getByRole('button', { name: 'Criar categoria' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.criarCategoria')).toHaveLength(1));
    const p = ultima(m, 'caixa.criarCategoria');
    expect(p).toMatchObject({ nome: 'Luz', tipo: 'SAIDA' });
    expect(ehUuid(p?.id)).toBe(true);
    expect(await screen.findByText('Categoria criada.')).toBeInTheDocument();
  });

  it('criar sem nome avisa e não manda', async () => {
    const m = servidor();
    const u = await abrirCategorias(m);
    await u.click(screen.getByRole('button', { name: 'Nova categoria' }));
    await u.click(screen.getByRole('button', { name: 'Criar categoria' }));
    expect(await screen.findByText('Digite o nome da categoria.')).toBeInTheDocument();
    expect(chamadasDe(m, 'caixa.criarCategoria')).toHaveLength(0);
  });

  it('renomear manda só o nome; inativar e reativar mandam só o id', async () => {
    const m = servidor({
      'caixa.renomearCategoria': () => ({ ok: true, dados: {} }),
      'caixa.inativarCategoria': () => ({ ok: true, dados: {} }),
      'caixa.reativarCategoria': () => ({ ok: true, dados: {} }),
    });
    const u = await abrirCategorias(m);
    await u.click(screen.getByRole('button', { name: 'Renomear a categoria Aluguel' }));
    const campo = screen.getByLabelText('Nome da categoria');
    await u.clear(campo);
    await u.type(campo, 'Aluguel da loja');
    await u.click(screen.getByRole('button', { name: 'Salvar nome' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.renomearCategoria')).toHaveLength(1));
    expect(ultima(m, 'caixa.renomearCategoria')).toEqual({
      id: ID_CATEGORIA,
      nome: 'Aluguel da loja',
    });
    await u.click(screen.getByRole('button', { name: 'Inativar a categoria Aluguel' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.inativarCategoria')).toHaveLength(1));
    expect(ultima(m, 'caixa.inativarCategoria')).toEqual({ id: ID_CATEGORIA });
    await u.click(screen.getByRole('button', { name: 'Reativar a categoria Antiga' }));
    await waitFor(() => expect(chamadasDe(m, 'caixa.reativarCategoria')).toHaveLength(1));
    expect(ultima(m, 'caixa.reativarCategoria')).toEqual({ id: 'c3' });
  });

  it('mostra a recusa do servidor ao criar com nome repetido', async () => {
    const m = servidor({
      'caixa.criarCategoria': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'Já existe uma categoria com este nome. Use outro nome.',
      }),
    });
    const u = await abrirCategorias(m);
    await u.click(screen.getByRole('button', { name: 'Nova categoria' }));
    await u.type(screen.getByLabelText('Nome da categoria'), 'Aluguel');
    await u.click(screen.getByRole('button', { name: 'Criar categoria' }));
    expect(
      await screen.findByText('Já existe uma categoria com este nome. Use outro nome.'),
    ).toBeInTheDocument();
  });
});

// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  ID_ANEL,
  ID_BRINCO,
  ID_VENDEDOR,
  abrir,
  chamadasDe,
  ehUuid,
  montar,
  ultima,
  type Manipulador,
} from './apoio-de-teste';

const ID_P1 = 'b1b1b1b1-2fa1-41d3-b9a5-2c3a9a8f6b01';
const ID_P2 = 'b2b2b2b2-2fa1-41d3-b9a5-2c3a9a8f6b02';
const ID_P3 = 'b3b3b3b3-2fa1-41d3-b9a5-2c3a9a8f6b03';

const pendencia = (extra: Record<string, unknown> = {}) => ({
  id: ID_P1,
  vendedorId: ID_VENDEDOR,
  vendedorNome: 'Ana Vendedora',
  produtoId: ID_ANEL,
  produtoNome: 'Anel Solitário',
  quantidade: 1,
  valor: '70.00',
  tipo: 'FALTA',
  status: 'ABERTA',
  acertoId: ID_P3,
  ...extra,
});

const lista = () => [
  pendencia(),
  pendencia({
    id: ID_P2,
    produtoId: ID_BRINCO,
    produtoNome: 'Brinco de Argola',
    status: 'COBRADA',
  }),
  pendencia({
    id: ID_P3,
    produtoId: undefined,
    produtoNome: undefined,
    tipo: 'DEVOLUCAO_NAO_ACEITA',
    valor: '20.00',
  }),
];

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'pendencias.listar': () => ({ ok: true, dados: lista() }),
    ...extra,
  });
}

async function abrirPendencias(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, 'Pendências');
  await screen.findByRole('heading', { name: 'Pendências do acerto', level: 1 });
  await screen.findAllByText('Anel Solitário');
  return u;
}

describe('pendências do acerto', () => {
  it('lista vendedor, peça, quantidade, valor e a situação em palavras da loja', async () => {
    await abrirPendencias(servidor());
    const tabela = within(screen.getByRole('table'));
    expect(tabela.getAllByText('Ana Vendedora')).toHaveLength(3);
    expect(tabela.getByText('Anel Solitário')).toBeInTheDocument();
    expect(tabela.getAllByText('R$ 70,00', { selector: 'strong' })).toHaveLength(2);
    expect(tabela.getAllByText('Em aberto')).toHaveLength(2);
    expect(tabela.getByText('Cobrada')).toBeInTheDocument();
    expect(tabela.getAllByText('Peça que faltou')).toHaveLength(2);
    expect(screen.queryByText('ABERTA')).toBeNull();
  });

  it('explica que cobrar é no próximo acerto do vendedor', async () => {
    await abrirPendencias(servidor());
    expect(
      screen.getByText(
        /A pendência em aberto aparece no próximo acerto do vendedor, e é lá que ela é cobrada/,
      ),
    ).toBeInTheDocument();
  });

  it('só a pendência em aberto tem o botão Resolver', async () => {
    await abrirPendencias(servidor());
    expect(screen.getAllByRole('button', { name: /Resolver pendência/ })).toHaveLength(2);
    expect(
      screen.queryByRole('button', { name: 'Resolver pendência de Brinco de Argola' }),
    ).toBeNull();
  });

  it('"A peça apareceu" só existe para falta', async () => {
    const u = await abrirPendencias(servidor());
    await u.click(screen.getByRole('button', { name: 'Resolver pendência de Anel Solitário' }));
    expect(screen.getByRole('radio', { name: 'A loja assume' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'A peça apareceu' })).toBeInTheDocument();
    await u.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Voltar' }));
    await u.click(
      screen.getByRole('button', { name: 'Resolver pendência de Devolução não aceita' }),
    );
    expect(screen.getByRole('radio', { name: 'A loja assume' })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: 'A peça apareceu' })).toBeNull();
  });

  it('exige o motivo e manda o id do comando', async () => {
    let n = 0;
    const m = servidor({
      'pendencias.resolver': () => {
        n += 1;
        return { ok: true, dados: pendencia({ status: 'ASSUMIDA_PELA_LOJA' }), criado: true };
      },
      'pendencias.listar': () => ({
        ok: true,
        dados: n === 0 ? lista() : [pendencia({ status: 'ASSUMIDA_PELA_LOJA' })],
      }),
    });
    const u = await abrirPendencias(m);
    await u.click(screen.getByRole('button', { name: 'Resolver pendência de Anel Solitário' }));
    const janela = screen.getByRole('dialog');
    await u.click(within(janela).getByRole('radio', { name: 'A loja assume' }));
    await u.click(within(janela).getByRole('button', { name: 'Resolver pendência' }));
    expect(chamadasDe(m, 'pendencias.resolver')).toHaveLength(0);
    expect(await within(janela).findByText('Diga o motivo de resolver.')).toBeInTheDocument();
    await u.type(within(janela).getByLabelText('Motivo'), 'Peça perdida na viagem');
    await u.click(within(janela).getByRole('button', { name: 'Resolver pendência' }));
    await waitFor(() => expect(chamadasDe(m, 'pendencias.resolver')).toHaveLength(1));
    const p = ultima(m, 'pendencias.resolver') as Record<string, unknown>;
    expect(p).toMatchObject({ id: ID_P1, acao: 'LOJA_ASSUME', motivo: 'Peça perdida na viagem' });
    expect(ehUuid(p.comandoId)).toBe(true);
    expect(await screen.findByText('Pendência resolvida.')).toBeInTheDocument();
    expect(
      await within(screen.getByRole('table')).findByText('A loja assumiu'),
    ).toBeInTheDocument();
  });

  it('a peça apareceu manda a ação ENCONTRADA', async () => {
    const m = servidor({
      'pendencias.resolver': () => ({
        ok: true,
        dados: pendencia({ status: 'ENCONTRADA' }),
        criado: true,
      }),
    });
    const u = await abrirPendencias(m);
    await u.click(screen.getByRole('button', { name: 'Resolver pendência de Anel Solitário' }));
    const janela = screen.getByRole('dialog');
    await u.click(within(janela).getByRole('radio', { name: 'A peça apareceu' }));
    await u.type(within(janela).getByLabelText('Motivo'), 'Estava na gaveta');
    await u.click(within(janela).getByRole('button', { name: 'Resolver pendência' }));
    await waitFor(() => expect(chamadasDe(m, 'pendencias.resolver')).toHaveLength(1));
    expect(ultima(m, 'pendencias.resolver')).toMatchObject({ acao: 'ENCONTRADA' });
  });

  it('sem internet e nova tentativa: o mesmo id', async () => {
    let n = 0;
    const m = servidor({
      'pendencias.resolver': () =>
        ++n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: pendencia({ status: 'ASSUMIDA_PELA_LOJA' }), criado: false },
    });
    const u = await abrirPendencias(m);
    await u.click(screen.getByRole('button', { name: 'Resolver pendência de Anel Solitário' }));
    const janela = screen.getByRole('dialog');
    await u.click(within(janela).getByRole('radio', { name: 'A loja assume' }));
    await u.type(within(janela).getByLabelText('Motivo'), 'Perdida');
    await u.click(within(janela).getByRole('button', { name: 'Resolver pendência' }));
    expect(await within(janela).findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Resolver pendência' }));
    await waitFor(() => expect(chamadasDe(m, 'pendencias.resolver')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'pendencias.resolver').map(
      (c) => (c.parametros as { comandoId: string }).comandoId,
    );
    expect(a).toBe(b);
  });

  it('filtra por vendedor e por situação', async () => {
    const m = servidor();
    const u = await abrirPendencias(m);
    await u.selectOptions(screen.getByLabelText('Vendedor'), ID_VENDEDOR);
    await waitFor(() =>
      expect(ultima(m, 'pendencias.listar')).toMatchObject({ vendedorId: ID_VENDEDOR }),
    );
    await u.selectOptions(screen.getByLabelText('Situação'), 'ABERTA');
    await waitFor(() => expect(ultima(m, 'pendencias.listar')).toMatchObject({ status: 'ABERTA' }));
  });
});

// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { abrir, chamadasDe, montar, ultima, type Manipulador } from '../acertos/apoio-de-teste';

const ID_NOTA = '6a7b28ba-2fa1-41d3-b9a5-2c3a9a8f6b77';
const ID_VEND = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_SEMANA = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_CLIENTE = '5f6a28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';

const MENU = 'Comissão e meta do consignado';

const configuracao = () => ({
  metaGuarani: 700000,
  percentualAbaixoDaMeta: 30,
  percentualNaMeta: 40,
  diasDoRetorno: 45,
});

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'consignado.configuracao': () => ({ ok: true, dados: configuracao() }),
    'consignado.configuracaoAlterar': (p) => ({ ok: true, dados: p.parametros }),
    ...extra,
  });
}

async function abrirTela(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, MENU);
  await screen.findByRole('heading', { name: MENU, level: 1 });
  await screen.findByDisplayValue('700000');
  return u;
}

const campoMeta = () => screen.getByLabelText('Meta em Guarani');
const campoAbaixo = () => screen.getByLabelText('Comissão abaixo da meta, em %');
const campoNaMeta = () => screen.getByLabelText('Comissão ao bater a meta, em %');
const campoDias = () => screen.getByLabelText('Dias da saída até o retorno');
const salvar = () => screen.getByRole('button', { name: 'Salvar' });

describe('tela Comissão e meta do consignado', () => {
  it('mostra o que está valendo e a frase de que o que já foi feito não muda', async () => {
    await abrirTela(servidor());
    expect(campoAbaixo()).toHaveValue('30');
    expect(campoNaMeta()).toHaveValue('40');
    expect(campoDias()).toHaveValue('45');
    expect(screen.getByText('Gs. 700.000')).toBeInTheDocument();
    expect(
      screen.getByText('Vale para os próximos acertos e notas. O que já foi feito não muda.'),
    ).toBeInTheDocument();
  });

  it('salva os quatro valores e confirma em português', async () => {
    const m = servidor();
    const u = await abrirTela(m);
    await u.clear(campoMeta());
    await u.type(campoMeta(), '800000');
    await u.clear(campoAbaixo());
    await u.type(campoAbaixo(), '25');
    await u.clear(campoDias());
    await u.type(campoDias(), '30');
    await u.click(salvar());
    expect(await screen.findByText('Comissão e meta salvas.')).toBeInTheDocument();
    expect(ultima(m, 'consignado.configuracaoAlterar')).toEqual({
      metaGuarani: 800000,
      percentualAbaixoDaMeta: 25,
      percentualNaMeta: 40,
      diasDoRetorno: 30,
    });
  });

  it('não deixa passar de 40%, nem abaixo maior que na meta, nem dias fora de 1 a 365', async () => {
    const m = servidor();
    const u = await abrirTela(m);
    await u.clear(campoNaMeta());
    await u.type(campoNaMeta(), '41');
    await u.click(salvar());
    expect(await screen.findAllByText('Use um número de 0 a 40.')).not.toHaveLength(0);
    await u.clear(campoNaMeta());
    await u.type(campoNaMeta(), '20');
    await u.click(salvar());
    expect(
      await screen.findByText(
        'A comissão abaixo da meta não pode ser maior que a comissão ao bater a meta.',
      ),
    ).toBeInTheDocument();
    await u.clear(campoNaMeta());
    await u.type(campoNaMeta(), '40');
    await u.clear(campoDias());
    await u.type(campoDias(), '366');
    await u.click(salvar());
    expect(await screen.findByText('Use de 1 a 365 dias.')).toBeInTheDocument();
    expect(chamadasDe(m, 'consignado.configuracaoAlterar')).toHaveLength(0);
  });

  it('mostra o erro do servidor com o que fazer', async () => {
    const m = servidor({
      'consignado.configuracaoAlterar': () => ({
        ok: false,
        motivo: 'erro',
        status: 403,
        mensagem: 'Só a dona pode mudar a comissão.',
      }),
    });
    const u = await abrirTela(m);
    await u.click(salvar());
    expect(await screen.findByText(/Só a dona pode mudar a comissão/)).toBeInTheDocument();
  });
});

describe('coluna Meta nas notas', () => {
  const NOTA = {
    id: ID_NOTA,
    numero: '0000065-21',
    vendedorId: ID_VEND,
    semanaId: ID_SEMANA,
    semana: 65,
    sequencia: 21,
    clienteId: ID_CLIENTE,
    cliente: 'Maria Cliente',
    saidaEm: '2026-11-10T13:00:00Z',
    dataCobranca: '2026-12-25',
    situacao: 'FECHADO',
    itensDx: 5,
    valorDx: 500,
  };
  const outra = (numero: string, extra: Record<string, unknown>) => ({
    ...NOTA,
    id: numero,
    numero,
    cliente: `Cliente ${numero}`,
    ...extra,
  });

  it('diz Bateu, Não bateu, e deixa vazio quando a comissão foi informada ou não houve acerto', async () => {
    const m = servidor({
      'consignado.notas': () => ({
        ok: true,
        dados: [
          outra('A-1', {
            regraDaComissao: 'META',
            metaGuarani: 700000,
            vdGuarani: 700000,
            bateuMeta: true,
          }),
          outra('B-2', {
            regraDaComissao: 'META',
            metaGuarani: 700000,
            vdGuarani: 699989,
            bateuMeta: false,
          }),
          outra('C-3', { regraDaComissao: 'INFORMADA' }),
          outra('D-4', {}),
        ],
      }),
    });
    await abrir(m, 'Notas dos clientes');
    const linha = async (n: string) => (await screen.findByText(n)).closest('tr')!;
    expect(within(await linha('A-1')).getByText('Bateu')).toBeInTheDocument();
    expect(within(await linha('A-1')).getByText('Gs. 700.000')).toBeInTheDocument();
    expect(within(await linha('B-2')).getByText('Não bateu')).toBeInTheDocument();
    expect(within(await linha('B-2')).getByText('Gs. 699.989')).toBeInTheDocument();
    for (const n of ['C-3', 'D-4']) {
      const l = await linha(n);
      expect(within(l).queryByText('Bateu')).toBeNull();
      expect(within(l).queryByText('Não bateu')).toBeNull();
    }
  });

  it('na ficha do acerto mostra o vendido em Guarani, a meta e se bateu', async () => {
    const acerto = (extra: Record<string, unknown>) => ({
      id: ID_NOTA,
      semana: 65,
      acertadoEm: '2026-11-17T13:00:00Z',
      comissaoPercentual: 40,
      itensDx: 5,
      itensDv: 2,
      itensVd: 3,
      valorDx: 500,
      valorDv: 200,
      valorVd: 630,
      comissao: 252,
      bruto: 378,
      pendenciasAnteriores: 0,
      totalAReceber: 378,
      totalPago: 378,
      ficouDevendo: 0,
      saldoDoDebito: 0,
      estornado: false,
      ...extra,
    });
    const m = servidor({
      'consignado.notas': () => ({ ok: true, dados: [NOTA] }),
      'consignado.nota': () => ({
        ok: true,
        dados: {
          nota: NOTA,
          linhas: [],
          recibos: [],
          acertos: [
            acerto({
              regraDaComissao: 'META',
              metaGuarani: 700000,
              vdGuarani: 720000,
              bateuMeta: true,
            }),
            acerto({ id: 'x', regraDaComissao: 'INFORMADA' }),
          ],
        },
      }),
    });
    const u = await abrir(m, 'Notas dos clientes');
    await u.click(await screen.findByRole('button', { name: 'Ver a nota 0000065-21' }));
    await screen.findByRole('heading', { name: 'Nota 0000065-21' });
    await waitFor(() => expect(screen.getAllByText('Valor VD em Guarani')).toHaveLength(1));
    expect(screen.getByText('Gs. 720.000')).toBeInTheDocument();
    expect(screen.getByText('Gs. 700.000')).toBeInTheDocument();
    expect(screen.getByText('Bateu')).toBeInTheDocument();
  });
});

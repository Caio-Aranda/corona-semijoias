// @vitest-environment jsdom
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  abrir,
  chamadasDe,
  ehUuid,
  montar,
  ultima,
  type Manipulador,
} from '../acertos/apoio-de-teste';

/**
 * A dona muda a cotação do Dólar e do Guarani durante o dia (ADR-0006, item 15). A tela só digita e mostra: o sistema valida,
 * compara com o Banco Central (aviso a partir de 15%, sem travar) e grava.
 */
const ID_DONA = 'c1000000-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_BCB = 'c2000000-ceea-4672-a1b9-3a1a0f7c4d22';

const cotacaoDaLoja = {
  disponivel: true,
  cotacao: {
    id: ID_DONA,
    dataCotacao: '2026-11-23',
    dolar: '5.80000000',
    guarani: '0.00074074',
    guaranisPorReal: '1350.00000000',
    origem: 'DONA',
    vigenteDesde: '2026-11-23T13:15:00Z',
    deDiaAnterior: false,
  },
};

const historico = {
  data: '2026-11-23',
  cotacoes: [
    {
      id: ID_BCB,
      vigenteDesde: '2026-11-23T03:00:00Z',
      origem: 'BANCO_CENTRAL',
      dolar: '5.50130000',
      guarani: '0.00089500',
      valeAgora: false,
      lancadaPor: null,
      guaranisPorReal: null,
    },
    {
      id: ID_DONA,
      vigenteDesde: '2026-11-23T13:15:00Z',
      origem: 'DONA',
      dolar: '5.80000000',
      guarani: '0.00074074',
      guaranisPorReal: '1350.00000000',
      lancadaPor: 'Maria',
      valeAgora: true,
    },
  ],
};

const previaComAviso = {
  dolar: '6.40000000',
  guaranisPorReal: '1350.00000000',
  guarani: '0.00074074',
  bancoCentral: {
    dataCotacao: '2026-11-06',
    dolar: '5.50130000',
    guaranisPorReal: '1117.32000000',
  },
  diferencaDolarPorCento: '16.34',
  diferencaGuaraniPorCento: '20.83',
  avisoDeDiferenca: true,
  moedaDoAviso: 'DOLAR',
  aviso: 'x',
};

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'cotacao.hoje': () => ({ ok: true, dados: cotacaoDaLoja }),
    'cotacao.dia': () => ({ ok: true, dados: historico }),
    'cotacao.previa': () => ({
      ok: true,
      dados: { ...previaComAviso, avisoDeDiferenca: false, moedaDoAviso: null },
    }),
    'cotacao.gravar': () => ({
      ok: true,
      dados: { cotacao: cotacaoDaLoja.cotacao, avisoDeDiferenca: false },
      criado: true,
    }),
    ...extra,
  });
}

async function abrirACotacao(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, 'Cotação');
  await screen.findByRole('heading', { name: 'Cotação do Dólar e do Guarani', level: 1 });
  return u;
}

describe('Mudar a cotação', () => {
  it('abre já preenchida com a cotação que vale agora e mostra o histórico do dia', async () => {
    await abrirACotacao(servidor());
    expect(await screen.findByLabelText('1 dólar = R$')).toHaveValue('5,80');
    expect(screen.getByLabelText('1 real = Gs.')).toHaveValue('1.350');
    expect(
      screen.getByText('A cotação nova vale a partir de agora. O que já foi lançado não muda.'),
    ).toBeInTheDocument();
    const tabela = await screen.findByRole('table', { name: 'Cotações do dia' });
    expect(within(tabela).getByText('Banco Central')).toBeInTheDocument();
    expect(within(tabela).getByText('Loja (Maria)')).toBeInTheDocument();
    expect(within(tabela).getByText('10:15 · Vale agora')).toBeInTheDocument();
    expect(within(tabela).getByText('00:00')).toBeInTheDocument();
    expect(screen.getByText('Para corrigir, grave uma cotação nova.')).toBeInTheDocument();
  });

  it('o rodapé mostra "Cotação da loja de 23/11/2026 às 10:15" quando a vigente é dela', async () => {
    await abrirACotacao(servidor());
    expect(await screen.findByText('Cotação da loja de 23/11/2026 às 10:15.')).toBeInTheDocument();
  });

  it('o aviso de diferença vem do sistema, não trava e mostra o Banco Central ao lado', async () => {
    const m = servidor({ 'cotacao.previa': () => ({ ok: true, dados: previaComAviso }) });
    const u = await abrirACotacao(m);
    const dolar = await screen.findByLabelText('1 dólar = R$');
    await u.clear(dolar);
    await u.type(dolar, '6,40');
    expect(
      await screen.findByText(
        'Este valor está bem diferente do Banco Central (R$ 5,50). Confira antes de gravar.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Banco Central de 06/11/2026: 1 dólar = R$ 5,50 · 1 real = Gs. 1.117,32'),
    ).toBeInTheDocument();
    await waitFor(() => expect(ultima(m, 'cotacao.previa')).toEqual({ usd: '6.40' }));
    // O botão continua pronto: o aviso não trava.
    expect(screen.getByRole('button', { name: 'Gravar a cotação' })).toBeEnabled();
  });

  it('mudou só o Dólar: grava só o Dólar e um id da tela (o Guarani fica o que valia), confirma e busca de novo', async () => {
    const m = servidor();
    const u = await abrirACotacao(m);
    const dolar = await screen.findByLabelText('1 dólar = R$');
    await u.clear(dolar);
    await u.type(dolar, '5,85');
    const antes = chamadasDe(m, 'cotacao.hoje').length;
    await u.click(screen.getByRole('button', { name: 'Gravar a cotação' }));
    await waitFor(() => expect(chamadasDe(m, 'cotacao.gravar')).toHaveLength(1));
    const p = ultima(m, 'cotacao.gravar');
    expect(p).toMatchObject({ usd: '5.85' });
    expect(ehUuid(p?.id)).toBe(true);
    expect(Object.keys(p ?? {}).sort()).toEqual(['id', 'usd']);
    expect(
      await screen.findByText('Cotação da loja gravada. Vale desde 10:15.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(chamadasDe(m, 'cotacao.hoje').length).toBeGreaterThan(antes));
    // Outra cotação é outro lançamento: o id muda.
    const guarani = screen.getByLabelText('1 real = Gs.');
    await u.clear(guarani);
    await u.type(guarani, '1.400');
    await u.click(screen.getByRole('button', { name: 'Gravar a cotação' }));
    await waitFor(() => expect(chamadasDe(m, 'cotacao.gravar')).toHaveLength(2));
    const segunda = chamadasDe(m, 'cotacao.gravar')[1]?.parametros;
    expect(segunda?.id).not.toBe(p?.id);
    expect(Object.keys(segunda ?? {}).sort()).toEqual(['guaranisPorReal', 'id']);
    expect(segunda).toMatchObject({ guaranisPorReal: '1400' });
  });

  it('mudou as duas: grava as duas', async () => {
    const m = servidor();
    const u = await abrirACotacao(m);
    const dolar = await screen.findByLabelText('1 dólar = R$');
    await u.clear(dolar);
    await u.type(dolar, '5,85');
    const guarani = screen.getByLabelText('1 real = Gs.');
    await u.clear(guarani);
    await u.type(guarani, '1.360');
    await u.click(screen.getByRole('button', { name: 'Gravar a cotação' }));
    await waitFor(() =>
      expect(ultima(m, 'cotacao.gravar')).toMatchObject({ usd: '5.85', guaranisPorReal: '1360' }),
    );
  });

  it('sem mudar nada, não grava e diz o que fazer', async () => {
    const m = servidor();
    const u = await abrirACotacao(m);
    await screen.findByLabelText('1 dólar = R$');
    await u.click(screen.getByRole('button', { name: 'Gravar a cotação' }));
    expect(
      await screen.findByText('Mude o valor do dólar, do guarani ou dos dois.'),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'cotacao.gravar')).toHaveLength(0);
  });

  it('valor que não é número não é mandado; a mensagem diz o que fazer', async () => {
    const m = servidor();
    const u = await abrirACotacao(m);
    const dolar = await screen.findByLabelText('1 dólar = R$');
    await u.clear(dolar);
    await u.type(dolar, 'abc');
    await u.click(screen.getByRole('button', { name: 'Gravar a cotação' }));
    expect(
      screen.getAllByText('Confira os valores: digite só números, com vírgula nos centavos.')
        .length,
    ).toBeGreaterThan(0);
    expect(chamadasDe(m, 'cotacao.gravar')).toHaveLength(0);
  });

  it('ver outro dia pede o histórico daquele dia', async () => {
    const m = servidor();
    await abrirACotacao(m);
    fireEvent.change(await screen.findByLabelText('Ver outro dia'), {
      target: { value: '2026-11-20' },
    });
    await waitFor(() => expect(ultima(m, 'cotacao.dia')).toEqual({ data: '2026-11-20' }));
  });

  it('mostra a recusa do sistema (por exemplo, valor fora do limite) em português simples', async () => {
    const m = servidor({
      'cotacao.gravar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'O valor do dólar precisa estar entre R$ 0,01 e R$ 999,99.',
      }),
    });
    const u = await abrirACotacao(m);
    const dolar = await screen.findByLabelText('1 dólar = R$');
    await u.clear(dolar);
    await u.type(dolar, '0,0001');
    await u.click(screen.getByRole('button', { name: 'Gravar a cotação' }));
    expect(
      await screen.findByText('O valor do dólar precisa estar entre R$ 0,01 e R$ 999,99.'),
    ).toBeInTheDocument();
  });
});

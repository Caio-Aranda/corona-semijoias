// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { abrir, chamadasDe, montar, ultima, type Manipulador } from './apoio-de-teste';

const configuracao = (extra: Record<string, unknown> = {}) => ({
  metaGuarani: 700000,
  percentualAbaixoDaMeta: 30,
  percentualNaMeta: 40,
  ...extra,
});

function servidor(extra: Record<string, Manipulador> = {}) {
  return montar({
    'comissao.ler': () => ({ ok: true, dados: configuracao() }),
    'comissao.alterar': (p) => ({ ok: true, dados: p.parametros }),
    ...extra,
  });
}

async function abrirComissao(m: ReturnType<typeof servidor>) {
  const u = await abrir(m, 'Comissão e meta');
  await screen.findByRole('heading', { name: 'Comissão e meta', level: 1 });
  await screen.findByDisplayValue('700000');
  return u;
}

const campoMeta = () => screen.getByLabelText('Meta da semana, em Guarani');
const campoAbaixo = () => screen.getByLabelText('Comissão abaixo da meta, em %');
const campoNaMeta = () => screen.getByLabelText('Comissão a partir da meta, em %');
const salvar = () => screen.getByRole('button', { name: 'Salvar' });

describe('comissão e meta', () => {
  it('mostra o que está valendo e a frase de que só vale para os próximos acertos', async () => {
    await abrirComissao(servidor());
    expect(campoAbaixo()).toHaveValue('30');
    expect(campoNaMeta()).toHaveValue('40');
    expect(screen.getByText('Gs. 700.000')).toBeInTheDocument();
    expect(
      screen.getByText('Vale para os próximos acertos. Acerto já fechado não muda.'),
    ).toBeInTheDocument();
  });

  it('grava a meta e os dois percentuais como números inteiros', async () => {
    const m = servidor();
    const u = await abrirComissao(m);
    await u.clear(campoMeta());
    await u.type(campoMeta(), '800000');
    await u.clear(campoAbaixo());
    await u.type(campoAbaixo(), '25');
    await u.clear(campoNaMeta());
    await u.type(campoNaMeta(), '35');
    await u.click(salvar());
    await waitFor(() => expect(chamadasDe(m, 'comissao.alterar')).toHaveLength(1));
    expect(ultima(m, 'comissao.alterar')).toEqual({
      metaGuarani: 800000,
      percentualAbaixoDaMeta: 25,
      percentualNaMeta: 35,
    });
    expect(await screen.findByText('Comissão e meta salvas.')).toBeInTheDocument();
  });

  it('recusa "abaixo da meta" maior que "a partir da meta", antes de mandar', async () => {
    const m = servidor();
    const u = await abrirComissao(m);
    await u.clear(campoAbaixo());
    await u.type(campoAbaixo(), '50');
    await u.click(salvar());
    expect(
      await screen.findByText(
        'A comissão abaixo da meta não pode ser maior que a comissão a partir da meta.',
      ),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'comissao.alterar')).toHaveLength(0);
  });

  it('recusa percentual fora de 0 a 100 e meta que não é número, antes de mandar', async () => {
    const m = servidor();
    const u = await abrirComissao(m);
    await u.clear(campoNaMeta());
    await u.type(campoNaMeta(), '101');
    await u.click(salvar());
    expect(await screen.findByText('Use um número de 0 a 100.')).toBeInTheDocument();
    await u.clear(campoNaMeta());
    await u.type(campoNaMeta(), '40');
    await u.clear(campoMeta());
    await u.click(salvar());
    expect(
      await screen.findByText('Digite a meta em Guarani, só com números.'),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'comissao.alterar')).toHaveLength(0);
  });

  it('mostra o erro do servidor em português', async () => {
    const m = servidor({
      'comissao.alterar': () => ({
        ok: false,
        motivo: 'erro',
        status: 400,
        mensagem: 'Use uma porcentagem entre 0 e 100.',
      }),
    });
    const u = await abrirComissao(m);
    await u.click(salvar());
    expect(await screen.findByText('Use uma porcentagem entre 0 e 100.')).toBeInTheDocument();
    expect(screen.queryByText('Comissão e meta salvas.')).toBeNull();
  });

  it('sem internet ao abrir: avisa e deixa tentar de novo', async () => {
    const m = servidor({ 'comissao.ler': () => ({ ok: false, motivo: 'sem-conexao' }) });
    await abrir(m, 'Comissão e meta');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument();
  });
});

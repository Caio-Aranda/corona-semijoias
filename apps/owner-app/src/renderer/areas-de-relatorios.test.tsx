// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { EstadoDaSessao, PonteDoCorona } from '../shared/ponte';
import { App } from './App';
import { grupoDoMenuRelatorios } from './apoio-de-testes';

function ponte(): PonteDoCorona {
  return {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn(() => Promise.resolve({ ok: true as const, dados: {} })),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
}

beforeEach(() => {
  window.location.hash = '';
});

describe('páginas de relatórios por área', () => {
  it('o menu tem um item por área e a área abre uma página de cartões', async () => {
    const u = userEvent.setup();
    render(<App ponte={ponte()} />);
    const menu = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
    expect(within(menu).queryByRole('button', { name: 'Reposições' })).toBeNull();
    const grupo = grupoDoMenuRelatorios(menu);
    await u.click(within(grupo).getByRole('button', { name: 'Vendas' }));
    expect(window.location.hash).toBe('#/areas/vendas');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Relatórios de Vendas' }),
    ).toBeInTheDocument();
    expect(within(grupo).getByRole('button', { name: 'Vendas' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getAllByRole('button', { name: /Vendas feitas/ }).length).toBeGreaterThan(0);
  });

  it('abrir um relatório mostra "Voltar para Relatórios de <área>", e voltar reabre a área', async () => {
    const u = userEvent.setup();
    window.location.hash = '#/areas/maletas';
    render(<App ponte={ponte()} />);
    const pagina = await screen.findByRole('heading', { level: 1, name: /Relatórios de Maletas/ });
    expect(pagina).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: /Reposições/ }));
    expect(window.location.hash).toMatch(/^#\/relatorios\/maleta-/);
    const voltar = await screen.findByRole('button', {
      name: /Voltar para Relatórios de Maletas/,
    });
    // O item da área continua destacado no menu.
    expect(
      within(
        grupoDoMenuRelatorios(screen.getByRole('navigation', { name: 'Menu do Corona Gestão' })),
      ).getByRole('button', {
        name: 'Maletas',
      }),
    ).toHaveAttribute('aria-current', 'page');
    await u.click(voltar);
    expect(window.location.hash).toBe('#/areas/maletas');
    expect(
      await screen.findByRole('heading', { level: 1, name: /Relatórios de Maletas/ }),
    ).toBeInTheDocument();
  });
});

import { screen, within } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';
import {
  CHAVES_DAS_AREAS,
  cartoesDaArea,
  rotuloDaArea,
  type ChaveDeArea,
} from './nucleo/areas-de-relatorios';
import { TEXTOS } from './textos';
import { TEXTOS_DE_AREAS_DE_RELATORIOS } from './textos-areas-de-relatorios';

/** Só para os testes: abre um relatório (ou conferência) como a pessoa faz, pela área do menu "Relatórios" e pelo cartão. */
export async function abrirRelatorioPeloMenu(u: UserEvent, titulo: string): Promise<void> {
  for (const chave of CHAVES_DAS_AREAS) {
    if (!cartoesDaArea(chave).some((c) => c.titulo === titulo)) continue;
    const grupo = grupoDoMenuRelatorios(
      await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo }),
    );
    await u.click(within(grupo).getByRole('button', { name: rotuloDaArea(chave) }));
    await screen.findByRole('heading', {
      level: 1,
      name: TEXTOS_DE_AREAS_DE_RELATORIOS.tituloDaPagina(rotuloDaArea(chave)),
    });
    const cartoes = await screen.findByRole('group', {
      name: TEXTOS_DE_AREAS_DE_RELATORIOS.rotuloDosCartoes,
    });
    await u.click(within(cartoes).getByText(titulo));
    return;
  }
  throw new Error(`Nenhuma área tem o relatório "${titulo}".`);
}

/** Só para os testes: abre a página de uma área pelo menu e devolve o bloco dos cartões. */
export async function abrirAreaDoMenu(u: UserEvent, chave: ChaveDeArea): Promise<HTMLElement> {
  const grupo = grupoDoMenuRelatorios(
    await screen.findByRole('navigation', { name: TEXTOS.menu.rotulo }),
  );
  await u.click(within(grupo).getByRole('button', { name: rotuloDaArea(chave) }));
  return screen.findByRole('group', { name: TEXTOS_DE_AREAS_DE_RELATORIOS.rotuloDosCartoes });
}

/** O bloco "Relatórios" do menu lateral (o texto também aparece num atalho da tela inicial). */
export function grupoDoMenuRelatorios(menu: HTMLElement): HTMLElement {
  return within(menu)
    .getByText(TEXTOS_DE_AREAS_DE_RELATORIOS.grupoDoMenu)
    .closest('.cs-menu__grupo') as HTMLElement;
}

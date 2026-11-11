// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AvisoDeAtualizacao, PonteDoCorona } from '../shared/ponte';
import { FaixaDeAtualizacao } from './componentes/FaixaDeAtualizacao';

function ponteComAtualizacao(inicial: AvisoDeAtualizacao) {
  let ouvinte: ((a: AvisoDeAtualizacao) => void) | null = null;
  const ponte = {
    aoMudarAAtualizacao: (f: (a: AvisoDeAtualizacao) => void) => {
      ouvinte = f;
      f(inicial);
      return () => {
        ouvinte = null;
      };
    },
  } as unknown as PonteDoCorona;
  return { ponte, avisar: (a: AvisoDeAtualizacao) => ouvinte?.(a) };
}

describe('aviso de versão nova', () => {
  it('não aparece quando não há versão nova', () => {
    const { ponte } = ponteComAtualizacao({ estado: 'nenhuma' });
    const { container } = render(<FaixaDeAtualizacao ponte={ponte} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('aparece, em português simples e sem botão, quando a versão nova já foi baixada', () => {
    const { ponte } = ponteComAtualizacao({ estado: 'pronta', versao: '1.0.1' });
    render(<FaixaDeAtualizacao ponte={ponte} />);
    expect(
      screen.getByText('Há uma versão nova. Ela será instalada quando você fechar o programa.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });
});

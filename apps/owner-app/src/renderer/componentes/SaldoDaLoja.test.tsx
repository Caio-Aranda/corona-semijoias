// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SaldoDaLoja } from './SaldoDaLoja';

describe('SaldoDaLoja', () => {
  it('com estoque mostra só o número', () => {
    render(
      <SaldoDaLoja estoque={4} emMaletas={2} vendedores={[{ nome: 'Maria', quantidade: 2 }]} />,
    );
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.queryByText(/maleta/)).toBeNull();
    expect(screen.queryByText('Acabou')).toBeNull();
  });

  it('zero na loja e nenhuma em maleta: "Acabou"', () => {
    render(<SaldoDaLoja estoque={0} />);
    expect(screen.getByText('Acabou')).toBeInTheDocument();
  });

  it('zero na loja e em maleta: "0 na loja" e o aviso com os nomes', () => {
    render(
      <SaldoDaLoja
        estoque={0}
        emMaletas={2}
        vendedores={[
          { nome: 'Maria', quantidade: 1 },
          { nome: 'João', quantidade: 1 },
        ]}
      />,
    );
    expect(screen.getByText('0 na loja', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('Está em maleta: 2 (Maria, João)')).toBeInTheDocument();
    expect(screen.queryByText('Acabou')).toBeNull();
  });

  it('com muitos representantes mostra três nomes e "e mais N"', () => {
    const nomes = ['Ana', 'Bia', 'Cris', 'Dani', 'Eva'];
    render(
      <SaldoDaLoja
        estoque={0}
        emMaletas={5}
        vendedores={nomes.map((nome) => ({ nome, quantidade: 1 }))}
      />,
    );
    expect(screen.getByText('Está em maleta: 5 (Ana, Bia, Cris e mais 2)')).toBeInTheDocument();
  });
});

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EstadoDaLista } from './EstadoDaLista';
import { JanelaDeConfirmacao } from './JanelaDeConfirmacao';
import { MenuLateral } from './MenuLateral';
import { Paginacao } from './Paginacao';

describe('Paginacao', () => {
  it('mostra o total e as páginas, e muda de página', async () => {
    const aoMudar = vi.fn();
    render(
      <Paginacao
        pagina={1}
        tamanho={20}
        total={45}
        aoMudar={aoMudar}
        nomeDoItem={['usuário', 'usuários']}
      />,
    );
    expect(screen.getByText('45 usuários')).toBeInTheDocument();
    expect(screen.getByText('Página 2 de 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }));
    expect(aoMudar).toHaveBeenCalledWith(2);
    await userEvent.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(aoMudar).toHaveBeenCalledWith(0);
  });

  it('com uma página só, não mostra os botões; singular para 1', () => {
    render(
      <Paginacao
        pagina={0}
        tamanho={20}
        total={1}
        aoMudar={vi.fn()}
        nomeDoItem={['usuário', 'usuários']}
      />,
    );
    expect(screen.getByText('1 usuário')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('na primeira página o Anterior fica desligado', () => {
    render(<Paginacao pagina={0} tamanho={10} total={30} aoMudar={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled();
  });
});

describe('EstadoDaLista', () => {
  it('carregando, erro com nova tentativa e conteúdo', async () => {
    const tentar = vi.fn();
    const { rerender } = render(
      <EstadoDaLista carregando textoCarregando="Buscando...">
        conteúdo
      </EstadoDaLista>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Buscando...');
    rerender(
      <EstadoDaLista
        erro="Não foi possível buscar agora."
        textoCarregando="Buscando..."
        aoTentarDeNovo={tentar}
      >
        conteúdo
      </EstadoDaLista>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível buscar agora.');
    await userEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }));
    expect(tentar).toHaveBeenCalledOnce();
    rerender(<EstadoDaLista textoCarregando="Buscando...">conteúdo</EstadoDaLista>);
    expect(screen.getByText('conteúdo')).toBeInTheDocument();
  });
});

describe('JanelaDeConfirmacao', () => {
  it('o foco começa em Cancelar, Esc cancela e o botão principal tem o nome da ação', async () => {
    const aoCancelar = vi.fn();
    const aoConfirmar = vi.fn();
    render(
      <JanelaDeConfirmacao
        titulo="Desativar Maria?"
        rotuloConfirmar="Desativar usuário"
        rotuloCancelar="Voltar"
        perigosa
        aoCancelar={aoCancelar}
        aoConfirmar={aoConfirmar}
      >
        Ela sai do sistema agora.
      </JanelaDeConfirmacao>,
    );
    expect(screen.getByRole('dialog', { name: 'Desativar Maria?' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Voltar' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Desativar usuário' })).toHaveClass(
      'cs-botao--perigoso',
    );
    await userEvent.keyboard('{Escape}');
    expect(aoCancelar).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole('button', { name: 'Desativar usuário' }));
    expect(aoConfirmar).toHaveBeenCalledOnce();
  });

  it('ocupada, não deixa cancelar nem confirmar de novo', () => {
    render(
      <JanelaDeConfirmacao
        titulo="T"
        rotuloConfirmar="Fazer"
        ocupado
        aoCancelar={vi.fn()}
        aoConfirmar={vi.fn()}
      >
        x
      </JanelaDeConfirmacao>,
    );
    expect(screen.getByRole('button', { name: 'Fazer' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled();
  });
});

describe('MenuLateral', () => {
  it('marca o item aberto e avisa qual foi escolhido', async () => {
    const aoEscolher = vi.fn();
    render(
      <MenuLateral
        atual="b"
        aoEscolher={aoEscolher}
        grupos={[
          { itens: [{ chave: 'a', rotulo: 'Início' }] },
          { titulo: 'Pessoas', itens: [{ chave: 'b', rotulo: 'Usuários' }] },
        ]}
      />,
    );
    expect(screen.getByText('Pessoas')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Usuários' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('button', { name: 'Início' })).not.toHaveAttribute('aria-current');
    await userEvent.click(screen.getByRole('button', { name: 'Início' }));
    expect(aoEscolher).toHaveBeenCalledWith('a');
  });
});

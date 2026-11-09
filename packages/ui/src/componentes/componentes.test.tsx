import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Aviso } from './Aviso';
import { Botao, RodapeDeAcoes } from './Botao';
import { CabecalhoDeMarca } from './CabecalhoDeMarca';
import { Campo } from './Campo';
import { PainelDePecaLida } from './PainelDePecaLida';
import { Tabela } from './Tabela';
import { ValorTresMoedas } from './ValorTresMoedas';

describe('Botao', () => {
  it('é secundário e grande quando pedido, e responde ao clique', async () => {
    const aoClicar = vi.fn();
    render(
      <Botao variante="primario" grande onClick={aoClicar}>
        Salvar
      </Botao>,
    );
    const botao = screen.getByRole('button', { name: 'Salvar' });
    expect(botao).toHaveClass('cs-botao--primario', 'cs-botao--grande');
    await userEvent.click(botao);
    expect(aoClicar).toHaveBeenCalledOnce();
  });

  it('não envia formulário sem querer', () => {
    render(<Botao>Voltar</Botao>);
    expect(screen.getByRole('button', { name: 'Voltar' })).toHaveAttribute('type', 'button');
  });

  it('rodapé: Cancelar vem antes do botão principal', () => {
    render(<RodapeDeAcoes cancelar={<Botao>Cancelar</Botao>} principal={<Botao>Salvar</Botao>} />);
    const nomes = screen.getAllByRole('button').map((b) => b.textContent);
    expect(nomes).toEqual(['Cancelar', 'Salvar']);
  });
});

describe('Tabela', () => {
  it('mostra cabeçalhos e linhas', () => {
    render(
      <Tabela
        legenda="Peças da maleta"
        colunas={[
          { chave: 'nome', titulo: 'Peça' },
          { chave: 'qtd', titulo: 'Quantidade', numerica: true },
        ]}
        linhas={[{ nome: 'Brinco de gota', qtd: 3 }]}
      />,
    );
    expect(screen.getByRole('table', { name: 'Peças da maleta' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Quantidade' })).toHaveClass('cs-numero');
    expect(screen.getByRole('cell', { name: 'Brinco de gota' })).toBeInTheDocument();
  });

  it('explica quando está vazia', () => {
    render(
      <Tabela
        legenda="Peças"
        colunas={[{ chave: 'a', titulo: 'A' }]}
        linhas={[]}
        textoVazio="Nenhuma peça nesta maleta."
      />,
    );
    expect(screen.getByText('Nenhuma peça nesta maleta.')).toBeInTheDocument();
  });
});

describe('Campo', () => {
  it('liga rótulo, ajuda e erro ao campo', () => {
    render(
      <Campo
        rotulo="Nome do cliente"
        ajuda="Como no documento."
        erro="Escreva o nome do cliente."
      />,
    );
    const campo = screen.getByLabelText('Nome do cliente');
    expect(campo).toBeInvalid();
    expect(campo).toHaveAccessibleDescription('Como no documento. Escreva o nome do cliente.');
    expect(screen.getByRole('alert')).toHaveTextContent('Escreva o nome do cliente.');
  });

  it('sem erro, o campo não é marcado como inválido', () => {
    render(<Campo rotulo="Telefone" />);
    expect(screen.getByLabelText('Telefone')).toBeValid();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Aviso', () => {
  it('erro avisa na hora; informação avisa com calma', () => {
    const { rerender } = render(
      <Aviso tipo="erro" titulo="Esta peça não está na maleta.">
        Confira o código ou escolha outra peça.
      </Aviso>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Confira o código');
    rerender(<Aviso>Suas vendas estão salvas.</Aviso>);
    expect(screen.getByRole('status')).toHaveTextContent('Suas vendas estão salvas.');
  });
});

describe('CabecalhoDeMarca', () => {
  it('usa o texto Corona Semijoias no lugar do logotipo', () => {
    render(<CabecalhoDeMarca nomeDoApp="Corona Vendas" />);
    expect(screen.getByRole('banner')).toHaveTextContent('Corona Semijoias');
    expect(screen.getByText('Corona Vendas')).toBeInTheDocument();
  });
});

describe('ValorTresMoedas', () => {
  it('mostra Real em destaque e Dólar e Guarani abaixo, sem converter', () => {
    render(<ValorTresMoedas real="R$ 100,00" dolar="US$ 18,18" guarani="Gs. 111.732" />);
    expect(screen.getByText('R$ 100,00').tagName).toBe('STRONG');
    expect(screen.getByText('US$ 18,18 · Gs. 111.732')).toBeInTheDocument();
  });

  it('sem Dólar e Guarani, mostra só o Real', () => {
    render(<ValorTresMoedas real="R$ 10,00" />);
    expect(screen.getByText('R$ 10,00')).toBeInTheDocument();
    expect(document.querySelector('.cs-valor__outras')).toBeNull();
  });
});

describe('PainelDePecaLida', () => {
  const preco = <ValorTresMoedas real="R$ 59,90" />;

  it('mostra foto, nome, código e preço', () => {
    render(
      <PainelDePecaLida
        nome="Brinco de gota"
        codigo="A123"
        preco={preco}
        fotoUrl="/fotos/a123.jpg"
      />,
    );
    expect(screen.getByRole('img', { name: 'Foto de Brinco de gota' })).toHaveAttribute(
      'src',
      '/fotos/a123.jpg',
    );
    expect(screen.getByRole('heading', { name: 'Brinco de gota' })).toBeInTheDocument();
    expect(screen.getByText('Código A123')).toBeInTheDocument();
    expect(screen.getByText('R$ 59,90')).toBeInTheDocument();
  });

  it('sem foto mostra espaço reservado e continua mostrando os dados', () => {
    render(<PainelDePecaLida nome="Colar" codigo="B9" preco={preco} />);
    expect(screen.getByTestId('sem-foto')).toHaveTextContent('Sem foto');
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByText('R$ 59,90')).toBeInTheDocument();
  });

  it('foto que não abre cai no espaço reservado', () => {
    render(<PainelDePecaLida nome="Colar" codigo="B9" preco={preco} fotoUrl="/quebrada.jpg" />);
    screen.getByRole('img').dispatchEvent(new Event('error'));
    return screen.findByTestId('sem-foto');
  });

  it('sucesso e erro têm texto além da cor', () => {
    const { rerender } = render(
      <PainelDePecaLida
        nome="Colar"
        codigo="B9"
        preco={preco}
        resultado={{ tipo: 'sucesso', texto: 'Peça lida.' }}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Peça lida.');
    rerender(
      <PainelDePecaLida
        nome="Colar"
        codigo="B9"
        preco={preco}
        resultado={{ tipo: 'erro', texto: 'Esta peça não está na maleta. Leia o código de novo.' }}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Leia o código de novo.');
  });
});

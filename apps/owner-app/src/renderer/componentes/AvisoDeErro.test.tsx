// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { definirIdioma } from '../idioma/idioma';
import type { ErroDaTela } from '../nucleo/chamadas';
import { traduzirErro } from '../nucleo/chamadas';
import { AvisoDeErro } from './AvisoDeErro';

const GERAL_PT = 'Alguns dados não estão corretos. Revise o que preencheu e tente de novo.';
const GERAL_ES = 'Algunos datos no están correctos. Revise lo que completó e intente de nuevo.';

function recusa(campos: { campo: string; mensagem: string }[]): ErroDaTela {
  return traduzirErro({
    ok: false,
    motivo: 'erro',
    status: 400,
    codigo: 'DADOS_INVALIDOS',
    mensagem: GERAL_PT,
    campos,
  });
}

afterEach(() => definirIdioma('pt'));

describe('AvisoDeErro: o motivo da recusa (A1)', () => {
  it('em português mostra a frase geral e o motivo do campo que a tela não tem', () => {
    render(
      <AvisoDeErro
        erro={recusa([{ campo: 'data', mensagem: 'A data não pode ser no futuro.' }])}
      />,
    );
    expect(screen.getByText(GERAL_PT)).toBeInTheDocument();
    expect(screen.getByText('A data não pode ser no futuro.')).toBeInTheDocument();
  });

  it('em espanhol a frase geral sai em espanhol e o motivo como o servidor manda', () => {
    definirIdioma('es');
    render(
      <AvisoDeErro
        erro={recusa([{ campo: 'data', mensagem: 'A data não pode ser no futuro.' }])}
      />,
    );
    expect(screen.getByText(GERAL_ES)).toBeInTheDocument();
    expect(screen.getByText('A data não pode ser no futuro.')).toBeInTheDocument();
  });

  it('com mais de um campo recusado mostra todos os motivos em lista curta', () => {
    render(
      <AvisoDeErro
        erro={recusa([
          { campo: 'data', mensagem: 'A data não pode ser no futuro.' },
          { campo: 'valor', mensagem: 'O valor precisa ser maior que zero.' },
        ])}
      />,
    );
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('não repete o mesmo motivo duas vezes', () => {
    render(
      <AvisoDeErro
        erro={recusa([
          { campo: 'a', mensagem: 'Confira a data.' },
          { campo: 'b', mensagem: 'Confira a data.' },
        ])}
      />,
    );
    expect(screen.getAllByText('Confira a data.')).toHaveLength(1);
  });

  it('formulário que já mostra o campo não repete o motivo no aviso', () => {
    render(
      <AvisoDeErro
        erro={recusa([{ campo: 'descricao', mensagem: 'Escreva a descrição.' }])}
        camposJaMostrados={['descricao']}
      />,
    );
    expect(screen.getByText(GERAL_PT)).toBeInTheDocument();
    expect(screen.queryByText('Escreva a descrição.')).toBeNull();
  });

  it('mostra só o motivo dos campos que a tela não mostra', () => {
    render(
      <AvisoDeErro
        erro={recusa([
          { campo: 'descricao', mensagem: 'Escreva a descrição.' },
          { campo: 'data', mensagem: 'A data não pode ser no futuro.' },
        ])}
        camposJaMostrados={['descricao']}
      />,
    );
    expect(screen.queryByText('Escreva a descrição.')).toBeNull();
    expect(screen.getByText('A data não pode ser no futuro.')).toBeInTheDocument();
  });

  it("camposJaMostrados='todos' esconde todos os motivos", () => {
    render(
      <AvisoDeErro
        erro={recusa([{ campo: 'x', mensagem: 'Motivo qualquer.' }])}
        camposJaMostrados="todos"
      />,
    );
    expect(screen.queryByText('Motivo qualquer.')).toBeNull();
  });

  it('motivo igual à frase do servidor não aparece duas vezes', () => {
    const erro = traduzirErro({
      ok: false,
      motivo: 'erro',
      mensagem: 'Use um número de dias.',
      campos: [{ campo: 'dias', mensagem: 'Use um número de dias.' }],
    });
    render(<AvisoDeErro erro={erro} />);
    expect(screen.getAllByText('Use um número de dias.')).toHaveLength(1);
  });

  it('erro sem campos continua igual', () => {
    const erro = traduzirErro({ ok: false, motivo: 'erro', mensagem: 'Não deu.' });
    render(<AvisoDeErro erro={erro} />);
    expect(screen.getByText('Não deu.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });
});

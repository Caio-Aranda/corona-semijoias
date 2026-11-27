// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { PedidoDeChamada, PonteDoCorona, ResultadoDeChamada } from '../../shared/ponte';
import { definirIdioma } from '../idioma/idioma';
import { traduzirErro } from '../nucleo/chamadas';
import { NovaMaleta } from '../telas/maletas/NovaMaleta';
import { AvisoDeErro } from './AvisoDeErro';

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 50, total: itens.length },
});

function ponteCom(respostas: Record<string, ResultadoDeChamada>): PonteDoCorona {
  return {
    chamarApi: (p: PedidoDeChamada) => Promise.resolve(respostas[p.operacao] ?? pagina([])),
  } as unknown as PonteDoCorona;
}

afterEach(() => {
  definirIdioma('pt');
  window.location.hash = '';
});

describe('B1: tela que depende de cadastro feito antes', () => {
  it('sem semana aberta, a Nova maleta diz o que falta e o botão leva ao cadastro de semanas', async () => {
    definirIdioma('pt');
    const ponte = ponteCom({
      'vendedores.escolha': {
        ok: true,
        dados: { itens: [{ id: 'v1', nome: 'Joana', pracaId: null }] },
      },
      'semanas.listar': pagina([{ id: 's1', numero: 47, situacao: 'FECHADA' }]),
    });
    render(<NovaMaleta ponte={ponte} aoCriar={() => undefined} />);
    expect(
      await screen.findByText(
        'Ainda não há semana aberta. Cadastre a semana para montar a maleta.',
      ),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cadastrar semana' }));
    expect(window.location.hash).toBe('#/cadastros/semanas');
  });

  it('com semana aberta e representante, não mostra aviso de falta', async () => {
    definirIdioma('pt');
    const ponte = ponteCom({
      'vendedores.escolha': {
        ok: true,
        dados: { itens: [{ id: 'v1', nome: 'Joana', pracaId: null }] },
      },
      'semanas.listar': pagina([{ id: 's1', numero: 48, situacao: 'ABERTA' }]),
    });
    render(<NovaMaleta ponte={ponte} aoCriar={() => undefined} />);
    await waitFor(() =>
      expect(screen.getByRole('option', { name: 'Semana 48' })).toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: 'Cadastrar semana' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Cadastrar representante' }),
    ).not.toBeInTheDocument();
  });

  it('sem representante, em espanhol, o botão leva ao cadastro de representantes', async () => {
    definirIdioma('es');
    const ponte = ponteCom({
      'vendedores.escolha': { ok: true, dados: { itens: [] } },
      'semanas.listar': pagina([{ id: 's1', numero: 48, situacao: 'ABERTA' }]),
    });
    render(<NovaMaleta ponte={ponte} aoCriar={() => undefined} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Registrar representante' }));
    expect(window.location.hash).toBe('#/pessoas/vendedores');
  });
});

describe('Erro da API com botão que resolve', () => {
  it('limite do representante: mostra o texto do servidor e o botão para abrir os representantes', async () => {
    definirIdioma('pt');
    const erro = traduzirErro({
      ok: false,
      motivo: 'erro',
      status: 409,
      codigo: 'LIMITE_DE_CONSIGNACAO_EXCEDIDO',
      mensagem: 'O limite é R$ 700,00.',
    });
    render(<AvisoDeErro erro={erro} />);
    expect(screen.getByText('O limite é R$ 700,00.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Abrir representantes' }));
    expect(window.location.hash).toBe('#/pessoas/vendedores');
  });

  it('erro sem tela certa para resolver não ganha botão', () => {
    const erro = traduzirErro({
      ok: false,
      motivo: 'erro',
      status: 404,
      codigo: 'CADASTRO_NAO_ENCONTRADO',
      mensagem: 'Não encontramos este cadastro.',
    });
    render(<AvisoDeErro erro={erro} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

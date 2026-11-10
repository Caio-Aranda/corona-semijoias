// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { App } from './App';
import { CampoDeDinheiro } from './componentes/CampoDeDinheiro';
import { ProvedorDaCotacao, lerCotacao } from './nucleo/cotacao';

const COTACAO_06_11 = {
  disponivel: true,
  cotacao: {
    dataCotacao: '2026-11-06',
    dolar: '5.50130000',
    guarani: '0.00089500',
    deDiaAnterior: false,
  },
};

function ponteCom(respostas: Record<string, ResultadoDeChamada | (() => ResultadoDeChamada)>) {
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn((p: PedidoDeChamada) => {
      const r = respostas[p.operacao];
      const valor: ResultadoDeChamada =
        typeof r === 'function' ? r() : (r ?? { ok: true, dados: {} });
      return Promise.resolve(valor);
    }),
    aoMudarASessao: () => () => undefined,
  };
  return ponte;
}

beforeEach(() => {
  window.location.hash = '';
});

function ComCampo({ ponte }: { ponte: PonteDoCorona }) {
  const [valor, setValor] = useState('');
  return (
    <ProvedorDaCotacao ponte={ponte}>
      <CampoDeDinheiro valor={valor} aoMudar={(digitado) => setValor(digitado)} autoFocus />
    </ProvedorDaCotacao>
  );
}

describe('campo de dinheiro com as três moedas', () => {
  it('a pessoa digita em reais com vírgula e vê Real, Dólar e Guarani pela cotação do dia', async () => {
    const ponte = ponteCom({ 'cotacao.hoje': { ok: true, dados: COTACAO_06_11 } });
    const u = userEvent.setup();
    render(<ComCampo ponte={ponte} />);
    const campo = screen.getByLabelText('Valor em reais');
    expect(campo).toHaveFocus();
    await u.type(campo, '1.234,56');
    expect(await screen.findByText('R$ 1.234,56')).toBeInTheDocument();
    expect(await screen.findByText('US$ 224,41 · Gs. 1.379.397')).toBeInTheDocument();
  });

  it('valor errado mostra o que fazer e não converte nada', async () => {
    const ponte = ponteCom({ 'cotacao.hoje': { ok: true, dados: COTACAO_06_11 } });
    const u = userEvent.setup();
    render(<ComCampo ponte={ponte} />);
    await u.type(screen.getByLabelText('Valor em reais'), '10,555');
    expect(
      screen.getByText('Digite um valor em reais, com no máximo 2 números depois da vírgula.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/US\$/)).not.toBeInTheDocument();
  });

  it('sem cotação ainda, mostra só o Real', async () => {
    const ponte = ponteCom({ 'cotacao.hoje': { ok: true, dados: { disponivel: false } } });
    const u = userEvent.setup();
    render(<ComCampo ponte={ponte} />);
    await u.type(screen.getByLabelText('Valor em reais'), '100');
    expect(await screen.findByText('R$ 100,00')).toBeInTheDocument();
    expect(screen.queryByText(/US\$/)).not.toBeInTheDocument();
  });
});

describe('cotação do dia', () => {
  it('lê a resposta do servidor e monta a linha do glossário', () => {
    const c = lerCotacao(COTACAO_06_11);
    expect(c.linha).toBe('Cotação do Banco Central de 06/11/2026.');
    expect(c.taxas).toEqual({ dolar: '5.50130000', guarani: '0.00089500' });
    expect(c.deDiaAnterior).toBe(false);
  });

  it('marca o dia anterior', () => {
    const c = lerCotacao({
      disponivel: true,
      cotacao: { ...COTACAO_06_11.cotacao, deDiaAnterior: true },
    });
    expect(c.linha).toBe('Cotação do Banco Central de 06/11/2026, a última publicada.');
    expect(c.deDiaAnterior).toBe(true);
  });

  it('resposta sem cotação ou quebrada vira "ainda não há cotação"', () => {
    for (const ruim of [{ disponivel: false }, {}, null, 'texto', { cotacao: { dolar: '5' } }]) {
      const c = lerCotacao(ruim);
      expect(c.taxas).toBeNull();
      expect(c.linha).toBe(
        'Ainda não há cotação. Os valores em Dólar e Guarani aparecem quando ela chegar.',
      );
    }
  });

  it('o rodapé de todas as telas mostra a cotação em uso', async () => {
    const ponte = ponteCom({ 'cotacao.hoje': { ok: true, dados: COTACAO_06_11 } });
    render(<App ponte={ponte} />);
    expect(await screen.findByText('Cotação do Banco Central de 06/11/2026.')).toBeInTheDocument();
  });
});

describe('tela inicial: o que precisa da sua atenção', () => {
  it('mostra o aviso da cotação que o servidor mandar', async () => {
    const ponte = ponteCom({
      'avisos.listar': {
        ok: true,
        dados: {
          avisos: [
            {
              tipo: 'COTACAO',
              titulo: 'A cotação de hoje ainda não chegou.',
              texto: 'Os valores em Dólar e Guarani estão usando a cotação de 06/11/2026.',
              dias: ['2026-11-09'],
            },
          ],
        },
      },
    });
    render(<App ponte={ponte} />);
    expect(await screen.findByText('A cotação de hoje ainda não chegou.')).toBeInTheDocument();
    expect(screen.getByText(/usando a cotação de 06\/11\/2026/)).toBeInTheDocument();
    expect(screen.queryByText('Tudo em dia.')).not.toBeInTheDocument();
  });

  it('lista vazia: "Tudo em dia."', async () => {
    const ponte = ponteCom({ 'avisos.listar': { ok: true, dados: { avisos: [] } } });
    render(<App ponte={ponte} />);
    expect(await screen.findByText('Tudo em dia.')).toBeInTheDocument();
  });

  it('sem internet nos avisos: texto do glossário, sem inventar "tudo em dia"', async () => {
    const ponte = ponteCom({ 'avisos.listar': { ok: false, motivo: 'sem-conexao' } });
    render(<App ponte={ponte} />);
    await waitFor(() =>
      expect(screen.getByText('Sem conexão com a internet. Tente de novo.')).toBeInTheDocument(),
    );
    expect(screen.queryByText('Tudo em dia.')).not.toBeInTheDocument();
  });
});

describe('menu lateral', () => {
  it('tem Início, Cadastros e Pessoas, e leva à tela escolhida', async () => {
    const ponte = ponteCom({});
    const u = userEvent.setup();
    render(<App ponte={ponte} />);
    const menu = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
    for (const nome of ['Cadastros', 'Pessoas']) expect(menu).toHaveTextContent(nome);
    for (const nome of [
      'Início',
      'Produtos',
      'Fornecedores',
      'Vendedores',
      'Clientes',
      'Usuários',
    ]) {
      expect(screen.getByRole('button', { name: nome })).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Início' })).toHaveAttribute('aria-current', 'page');
    await u.click(screen.getByRole('button', { name: 'Produtos' }));
    expect(
      await screen.findByText(
        'Produtos ainda está sendo preparado. Logo você poderá usar por aqui.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Produtos' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(window.location.hash).toBe('#/cadastros/produtos');
  });
});

describe('sem internet com a sessão guardada', () => {
  it('a tela de espera tenta sozinha e entra quando a internet volta', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      const estados: EstadoDaSessao[] = [
        { estado: 'sem-conexao' },
        { estado: 'sem-conexao' },
        { estado: 'dentro', usuario: { nome: 'Maria' } },
      ];
      const ponte = ponteCom({});
      ponte.estouLogado = vi.fn(() =>
        Promise.resolve(
          estados.shift() ?? ({ estado: 'dentro', usuario: { nome: 'Maria' } } as const),
        ),
      );
      render(<App ponte={ponte} />);
      expect(
        await screen.findByText('Sem conexão com a internet. Tente de novo.'),
      ).toBeInTheDocument();
      expect(screen.getByText(/Você não precisa entrar de novo/)).toBeInTheDocument();
      await vi.advanceTimersByTimeAsync(16_000);
      await vi.advanceTimersByTimeAsync(16_000);
      expect(await screen.findByText('Olá, Maria.')).toBeInTheDocument();
      expect(ponte.estouLogado).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });
});

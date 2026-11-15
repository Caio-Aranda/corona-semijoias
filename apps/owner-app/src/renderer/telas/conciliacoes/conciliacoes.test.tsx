// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';
import { TEXTOS } from '../../textos';
import { TEXTOS_DE_CONCILIACOES } from '../../textos-conciliacoes';
import { lerConferenciaDoDinheiro, valorDaDiferenca } from './TelaConferenciaDoDinheiro';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const ID_VENDA = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_PRODUTO = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const NOVE = ['C1', 'C2', 'C3', 'C4', 'C5', 'C7', 'C8', 'C9', 'C10'];

function montar(conferir: Manipulador) {
  const chamadas: PedidoDeChamada[] = [];
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn(async (pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      if (pedido.operacao === 'conciliacoes.conferir') return conferir(pedido);
      if (pedido.operacao === 'avisos.listar') return { ok: true as const, dados: { avisos: [] } };
      return { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

async function abrir(m: ReturnType<typeof montar>) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await u.click(await screen.findByRole('button', { name: TEXTOS_DE_CONCILIACOES.menu }));
  return u;
}

const certas = () => ({
  ok: true as const,
  dados: {
    geradaEm: '2026-11-13T12:00:00Z',
    tudoCerto: true,
    conferencias: NOVE.map((chave) => ({ chave, conferidos: 12, divergencias: [] })),
  },
});

describe('Conferência do dinheiro', () => {
  it('chama a rota nova e mostra "Tudo certo" nas nove conferências, com quantos registros olhou', async () => {
    const m = montar(certas);
    await abrir(m);
    expect(await screen.findByText(/Tudo certo: o dinheiro/)).toBeInTheDocument();
    for (const chave of NOVE) {
      // Só na tela (o menu lateral também tem um item chamado Caixa).
      expect(
        within(screen.getByRole('main')).getByText(TEXTOS_DE_CONCILIACOES.titulos[chave]!),
      ).toBeInTheDocument();
    }
    expect(screen.getAllByText('Conferidos: 12').length).toBeGreaterThan(0);
    expect(screen.getAllByText(TEXTOS_DE_CONCILIACOES.semDiferenca)).toHaveLength(9);
    expect(m.chamadas.some((c) => c.operacao === 'conciliacoes.conferir')).toBe(true);
    expect(screen.getByText(/não da loja/)).toBeInTheDocument();
    // Nada de botão que corrige: só o de conferir de novo.
    expect(screen.queryByRole('button', { name: /corrigir|ajustar|estornar/i })).toBeNull();
  });

  it('lista o que não bate, em Real e em peças, sem esconder', async () => {
    const m = montar(() => ({
      ok: true,
      dados: {
        geradaEm: '2026-11-13T12:00:00Z',
        tudoCerto: false,
        conferencias: [
          {
            chave: 'C1',
            conferidos: 5,
            divergencias: [
              {
                tipo: 'VENDA',
                referencia: ID_VENDA,
                descricao: 'O que falta receber desta venda não bate com as parcelas em aberto.',
                esperado: '100.00',
                encontrado: '80.00',
                unidade: 'REAIS',
              },
            ],
          },
          {
            chave: 'C7',
            conferidos: 3,
            divergencias: [
              {
                tipo: 'PRODUTO',
                referencia: ID_PRODUTO,
                descricao: 'O estoque guardado desta peça não é a soma dos lançamentos do estoque.',
                esperado: '7',
                encontrado: '8',
                unidade: 'PECAS',
              },
            ],
          },
        ],
      },
    }));
    await abrir(m);
    expect(await screen.findByText(/Há diferenças/)).toBeInTheDocument();
    expect(screen.getByText(/O que falta receber desta venda não bate/)).toBeInTheDocument();
    expect(screen.getByText(/Devia ser R\$\s*100,00; está R\$\s*80,00\./)).toBeInTheDocument();
    expect(screen.getByText(/Devia ser 7 peças; está 8 peças\./)).toBeInTheDocument();
    expect(screen.getByText('Venda 8f14e45f')).toBeInTheDocument();
    expect(screen.getByText('Peça 2c4e28ba')).toBeInTheDocument();
    expect(screen.queryByText(/Tudo certo: o dinheiro/)).not.toBeInTheDocument();
    // Só Real: a conferência nunca mostra Dólar nem Guarani.
    for (const linha of screen.getAllByText(/Devia ser/)) {
      expect(linha.textContent).not.toMatch(/Dólar|Guarani/);
    }
  });

  it('sem conexão mostra o aviso em português e o botão de tentar de novo', async () => {
    const m = montar(() => ({ ok: false, motivo: 'sem-conexao' }));
    await abrir(m);
    expect(await screen.findByText(TEXTOS.semConexao.texto)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: TEXTOS.lista.tentarDeNovo })).toBeInTheDocument();
  });

  it('erro da API mostra o texto de erro, sem código nem termo técnico', async () => {
    const m = montar(() => ({ ok: false, motivo: 'erro' }));
    await abrir(m);
    expect(await screen.findByText(TEXTOS.algoDeuErrado)).toBeInTheDocument();
    expect(screen.queryByText(/Tudo certo: o dinheiro/)).not.toBeInTheDocument();
  });

  it('conferir de novo chama a rota outra vez', async () => {
    const m = montar(certas);
    const u = await abrir(m);
    await screen.findByText(/Tudo certo: o dinheiro/);
    await u.click(screen.getByRole('button', { name: TEXTOS_DE_CONCILIACOES.conferir }));
    await screen.findByText(/Tudo certo: o dinheiro/);
    expect(m.chamadas.filter((c) => c.operacao === 'conciliacoes.conferir').length).toBeGreaterThan(
      1,
    );
  });
});

describe('formatação da conferência', () => {
  it('Real com vírgula, peças e registros em número, porcentagem com vírgula', () => {
    expect(valorDaDiferenca('1234.50', 'REAIS')).toMatch(/R\$\s*1\.234,50/);
    expect(valorDaDiferenca('1', 'PECAS')).toBe('1 peça');
    expect(valorDaDiferenca('3', 'PECAS')).toBe('3 peças');
    expect(valorDaDiferenca('40.00', 'PERCENTUAL')).toBe('40,00%');
    expect(valorDaDiferenca('2', 'REGISTROS')).toBe('2 registros');
  });

  it('o que não tem o formato esperado é descartado e tudoCerto exige lista vazia', () => {
    const r = lerConferenciaDoDinheiro({
      tudoCerto: true,
      conferencias: [
        { chave: 'C1', conferidos: 1, divergencias: [{ tipo: 'VENDA', referencia: ID_VENDA }] },
        'lixo',
        { semChave: true },
      ],
    });
    expect(r.conferencias).toHaveLength(1);
    expect(r.tudoCerto).toBe(false);
    expect(lerConferenciaDoDinheiro(null).conferencias).toEqual([]);
  });
});

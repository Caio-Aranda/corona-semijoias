// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { abrirRelatorioPeloMenu } from '../../apoio-de-testes';
import { App } from '../../App';
import { TEXTOS_DE_CONCILIACOES } from '../../textos-conciliacoes';
import { situacaoEmPalavras } from './TelaConferenciaDiaria';

const D = TEXTOS_DE_CONCILIACOES.diaria;
const ID_A = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_B = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_PECA = '5d2a7c19-9b0e-4c61-8e57-1f6b3a4d9c22';

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const rodada = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  iniciadaEm: '2026-11-16T06:00:03Z',
  terminadaEm: '2026-11-16T06:00:09Z',
  origem: 'AUTOMATICA',
  pedidaPor: null,
  situacao: 'SEM_DIFERENCA',
  totalDiferencas: 0,
  chavesConferidas: ['C1'],
  chavesComFalha: [],
  ...extra,
});

function montar(respostas: Record<string, Manipulador>, hash = '') {
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
      const r = respostas[pedido.operacao];
      if (r) return r(pedido);
      if (pedido.operacao === 'avisos.listar') return { ok: true as const, dados: { avisos: [] } };
      return { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
  window.location.hash = hash;
  return { ponte, chamadas };
}

async function abrir(m: ReturnType<typeof montar>) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  await abrirRelatorioPeloMenu(u, D.menu);
  return u;
}

afterEach(() => {
  window.location.hash = '';
});

describe('Conferência de hoje', () => {
  it('mostra a última conferência e o histórico', async () => {
    const m = montar({
      'conferencias.listar': () => ({
        ok: true,
        dados: {
          itens: [
            rodada(ID_A, { situacao: 'COM_DIFERENCA', totalDiferencas: 2 }),
            rodada(ID_B, { origem: 'PEDIDA', pedidaPor: 'Maria' }),
          ],
          pagina: 0,
          tamanho: 30,
          total: 2,
        },
      }),
    });
    await abrir(m);
    const tela = within(screen.getByRole('main'));
    expect(
      await tela.findByText('Achou 2 diferenças', { selector: '[role], p, div' }),
    ).toBeTruthy();
    expect(tela.getByText(D.ultima)).toBeInTheDocument();
    expect(tela.getByText(/Feita sozinha pelo sistema/)).toBeInTheDocument();
    expect(tela.getByText(D.anteriores)).toBeInTheDocument();
    expect(tela.getAllByRole('button', { name: /Ver a conferência de/ })).toHaveLength(2);
    expect(tela.getByText(/Tudo certo/)).toBeInTheDocument();
  });

  it('sem nenhuma conferência, diz para tocar em Conferir agora', async () => {
    const m = montar({
      'conferencias.listar': () => ({
        ok: true,
        dados: { itens: [], pagina: 0, tamanho: 30, total: 0 },
      }),
    });
    await abrir(m);
    expect(await screen.findByText(D.nenhuma)).toBeInTheDocument();
  });

  it('Ver abre o detalhe com "Devia ser X; está Y" e a unidade de cada diferença', async () => {
    const m = montar({
      'conferencias.listar': () => ({
        ok: true,
        dados: {
          itens: [rodada(ID_A, { situacao: 'COM_DIFERENCA', totalDiferencas: 2 })],
          pagina: 0,
          tamanho: 30,
          total: 1,
        },
      }),
      'conferencias.ver': () => ({
        ok: true,
        dados: {
          rodada: rodada(ID_A, { situacao: 'COM_DIFERENCA', totalDiferencas: 2 }),
          grupos: [
            {
              chave: 'C11',
              diferencas: [
                {
                  item: null,
                  tipo: 'PRODUTO',
                  referencia: ID_PECA,
                  descricao: 'Anel de teste',
                  esperado: '11',
                  encontrado: '10',
                  unidade: 'PECAS',
                },
              ],
            },
            {
              chave: 'C4',
              diferencas: [
                {
                  item: null,
                  tipo: 'LANCAMENTO',
                  referencia: ID_A,
                  descricao: 'Caixa',
                  esperado: '100.50',
                  encontrado: '100.00',
                  unidade: 'REAIS',
                },
              ],
            },
          ],
        },
      }),
    });
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: /Ver a conferência de/ }));
    expect(await screen.findByText(/Devia ser 11 peças; está 10 peças\./)).toBeInTheDocument();
    expect(screen.getByText(/Devia ser R\$\s*100,50; está R\$\s*100,00\./)).toBeInTheDocument();
    expect(screen.getByText(TEXTOS_DE_CONCILIACOES.titulos.C11!)).toBeInTheDocument();
    // A conferência é só em Real: nenhuma diferença aparece em Dólar ou Guarani.
    expect(screen.queryByText(/US\$|Gs\./)).not.toBeInTheDocument();
    expect(m.chamadas.find((c) => c.operacao === 'conferencias.ver')?.parametros).toEqual({
      id: ID_A,
    });
  });

  it('Conferir agora manda o mesmo id no segundo toque depois de um erro de conexão e mostra o resultado', async () => {
    let tentativas = 0;
    const m = montar({
      'conferencias.listar': () => ({
        ok: true,
        dados: { itens: [], pagina: 0, tamanho: 30, total: 0 },
      }),
      'conferencias.conferirAgora': () => {
        tentativas += 1;
        return tentativas === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : {
              ok: true,
              dados: { rodada: rodada(ID_B, { origem: 'PEDIDA', pedidaPor: 'Maria' }), grupos: [] },
              criado: true,
            };
      },
      'conferencias.ver': () => ({
        ok: true,
        dados: { rodada: rodada(ID_B, { origem: 'PEDIDA', pedidaPor: 'Maria' }), grupos: [] },
      }),
    });
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: D.conferirAgora }));
    await waitFor(() => expect(tentativas).toBe(1));
    await u.click(await screen.findByRole('button', { name: D.conferirAgora }));
    await waitFor(() => expect(tentativas).toBe(2));
    const pedidas = m.chamadas.filter((c) => c.operacao === 'conferencias.conferirAgora');
    expect(pedidas).toHaveLength(2);
    const id1 = (pedidas[0]!.parametros as { id: string }).id;
    expect(id1).toMatch(/^[0-9a-f-]{36}$/);
    expect((pedidas[1]!.parametros as { id: string }).id).toBe(id1);
    expect(await screen.findByText(D.semDiferenca)).toBeInTheDocument();
  });

  it('409: mostra a mensagem de que já está rodando e deixa tentar de novo', async () => {
    const m = montar({
      'conferencias.listar': () => ({
        ok: true,
        dados: { itens: [], pagina: 0, tamanho: 30, total: 0 },
      }),
      'conferencias.conferirAgora': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'CONFERENCIA_EM_ANDAMENTO',
        mensagem: 'Uma conferência já está rodando. Espere um pouco e atualize a tela.',
      }),
    });
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: D.conferirAgora }));
    expect(
      await screen.findByText(
        'Uma conferência já está rodando. Espere um pouco e atualize a tela.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: D.conferirAgora })).toBeEnabled();
  });

  it('a situação em palavras: tudo certo, diferenças (singular e plural) e o que não terminou', () => {
    const base = {
      id: ID_A,
      iniciadaEm: '2026-11-16T06:00:00Z',
      origem: 'AUTOMATICA',
      pedidaPor: '',
      situacao: 'SEM_DIFERENCA',
      totalDiferencas: 0,
      chavesComFalha: [] as string[],
    };
    expect(situacaoEmPalavras(base)).toBe('Tudo certo');
    expect(situacaoEmPalavras({ ...base, situacao: 'COM_DIFERENCA', totalDiferencas: 1 })).toBe(
      'Achou 1 diferença',
    );
    expect(situacaoEmPalavras({ ...base, situacao: 'COM_DIFERENCA', totalDiferencas: 3 })).toBe(
      'Achou 3 diferenças',
    );
    expect(
      situacaoEmPalavras({ ...base, situacao: 'FALHOU', chavesComFalha: ['C3', 'ESTOQUE'] }),
    ).toBe(
      `Não terminou: ${TEXTOS_DE_CONCILIACOES.titulos.C3}, ${TEXTOS_DE_CONCILIACOES.titulosDaConferencia.ESTOQUE}`,
    );
  });

  it('o aviso novo da tela inicial leva à tela da conferência de hoje', async () => {
    const m = montar({
      'avisos.listar': () => ({
        ok: true,
        dados: {
          avisos: [
            {
              tipo: 'CONFERENCIA_COM_DIFERENCA',
              titulo: 'A conferência de hoje achou 2 diferenças',
              texto: 'Abra a conferência para ver o que não bate. Nada foi mudado sozinho.',
              dias: ['2026-11-16'],
            },
          ],
        },
      }),
      'conferencias.listar': () => ({
        ok: true,
        dados: { itens: [], pagina: 0, tamanho: 30, total: 0 },
      }),
    });
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    expect(await screen.findByText('A conferência de hoje achou 2 diferenças')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Abrir' }));
    expect(await screen.findByRole('heading', { name: D.titulo })).toBeInTheDocument();
    expect(m.chamadas.some((c) => c.operacao === 'conferencias.listar')).toBe(true);
  });
});

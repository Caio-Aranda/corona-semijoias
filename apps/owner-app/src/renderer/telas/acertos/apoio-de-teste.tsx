// Apoio dos testes das telas do acerto: a ponte de faz de conta e os dados de exemplo.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';

export type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

export const ID_VENDEDOR = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
export const ID_SEMANA = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
export const ID_ACERTO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
export const ID_ANEL = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
export const ID_BRINCO = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
export const ID_MALETA = '4e5e28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';
export const HASH = 'a'.repeat(64);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const ehUuid = (v: unknown) => typeof v === 'string' && UUID.test(v);

export const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

export const produtoDaFicha = (id: string, nome: string, codigo: string, fotoHash?: string) => ({
  id,
  nome,
  codigo,
  ...(fotoHash ? { fotoHash } : {}),
});

export const produtoCompleto = (id: string, nome: string, codigo: string) => ({
  id,
  version: 1,
  nome,
  referencia: 'R',
  categoriaId: ID_SEMANA,
  precoVenda: '70.00',
  precoCusto: '10.00',
  estoqueMinimo: 0,
  estoqueAtual: 5,
  ativo: true,
  fotoPrincipal: null,
  codigos: [{ id, codigo, tipo: 'LOJA', principal: true, ativo: true }],
});

/** A ficha do acerto como o servidor devolve (em andamento por padrão). */
export function ficha(extra: Record<string, unknown> = {}) {
  return {
    id: ID_ACERTO,
    status: 'EM_ACERTO',
    vendedorId: ID_VENDEDOR,
    vendedorNome: 'Ana Vendedora',
    semanaId: ID_SEMANA,
    semanaNumero: 45,
    iniciadoEm: '2026-11-10T12:00:00Z',
    pedidoPeloVendedor: false,
    enviosAguardando: 0,
    vendasQueChegaramDepoisDoInicio: 0,
    maletas: [
      { id: ID_MALETA, numero: 'M-1', status: 'EM_ACERTO', statusAntes: 'CONFERIDA', tipo: 'NOVA' },
    ],
    contagem: [],
    leituras: [],
    ...extra,
  };
}

export function montar(manipuladores: Record<string, Manipulador> = {}) {
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () =>
      pagina([{ id: ID_VENDEDOR, nome: 'Ana Vendedora', codigo: 7, ativo: true }]),
    'semanas.listar': () => pagina([{ id: ID_SEMANA, numero: 45, situacao: 'ABERTA' }]),
    'acertos.listar': () => ({ ok: true, dados: [] }),
    'produtos.fotos.imagem': () => ({
      ok: true,
      dados: { nome: 'x.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
    }),
  };
  const todos = { ...padrao, ...manipuladores };
  const ponte: PonteDoCorona = {
    entrar: vi.fn(),
    trocarSenha: vi.fn(),
    sair: vi.fn(() => Promise.resolve()),
    estouLogado: vi.fn(() =>
      Promise.resolve<EstadoDaSessao>({ estado: 'dentro', usuario: { nome: 'Maria' } }),
    ),
    chamarApi: vi.fn(async (pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = todos[pedido.operacao];
      return m ? m(pedido) : { ok: false as const, motivo: 'erro' as const };
    }),
    aoMudarASessao: () => () => undefined,
    aoMudarAAtualizacao: () => () => undefined,
  };
  return { ponte, chamadas };
}

export type Montagem = ReturnType<typeof montar>;
export const chamadasDe = (m: Montagem, op: string) => m.chamadas.filter((c) => c.operacao === op);
export const ultima = (m: Montagem, op: string) => chamadasDe(m, op).at(-1)?.parametros;

/** Abre o aplicativo e a tela pedida do menu (por padrão, "Acertos"). */
export async function abrir(m: Montagem, item = 'Acertos') {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  await u.click(within(nav).getByRole('button', { name: item }));
  return u;
}

/** Um item da lista de acertos como o servidor devolve. */
export const resumoDeAcerto = (extra: Record<string, unknown> = {}) => ({
  id: ID_ACERTO,
  status: 'EM_ACERTO',
  vendedorId: ID_VENDEDOR,
  vendedorNome: 'Ana Vendedora',
  semanaNumero: 45,
  maletas: 1,
  iniciadoEm: '2026-11-10T12:00:00Z',
  ...extra,
});

export const ID_VENDA = '5f6e28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';
export const ID_DEVOLUCAO = '6a7e28ba-2fa1-41d3-b9a5-2c3a9a8f6b77';
export const ID_PENDENCIA = '7b8e28ba-2fa1-41d3-b9a5-2c3a9a8f6b88';

/**
 * A prévia do fechamento como o servidor devolve. Os números são de propósito incoerentes entre si (o líquido não é
 * vendido menos comissão): a tela tem de mostrar o que veio, nunca calcular.
 */
export function previaDoServidor(extra: Record<string, unknown> = {}) {
  const anel = produtoDaFicha(ID_ANEL, 'Anel Solitário', '0012', HASH);
  const brinco = produtoDaFicha(ID_BRINCO, 'Brinco de Argola', 'B-7');
  return {
    acertoId: ID_ACERTO,
    itens: [
      {
        produto: anel,
        remessaId: ID_MALETA,
        esperado: 5,
        contadoBoa: 3,
        contadoDefeito: 1,
        falta: 1,
        valorDaFalta: '70.00',
      },
      {
        produto: brinco,
        remessaId: ID_MALETA,
        esperado: 2,
        contadoBoa: 0,
        contadoDefeito: 2,
        falta: 0,
        valorDaFalta: '0.00',
      },
    ],
    sobras: [],
    faltas: [
      { produto: anel, remessaId: ID_MALETA, quantidade: 1, valor: '70.00', status: 'COBRADA' },
    ],
    defeitos: [{ produto: brinco, contadoDefeito: 2 }],
    eventos: [{ tipo: 'VENDA', origemId: ID_VENDA, vendaId: ID_VENDA, valor: '100.00' }],
    revisoesPendentes: [],
    naoAceitas: [],
    pendenciasAntigas: [],
    comissoes: [
      {
        vendaId: ID_VENDA,
        eventoId: ID_VENDA,
        tipo: 'COMISSAO',
        base: '100.00',
        percentual: '40.00',
        valor: '40.00',
      },
    ],
    metaGuarani: 700000,
    vendidoParaMetaGuarani: 750000,
    percentualComissao: '40.00',
    vendidoBruto: '1000.00',
    descontos: '10.00',
    devolvido: '20.00',
    vendidoLiquido: '970.00',
    comissao: '388.00',
    faltasCobradas: '70.00',
    liquido: '777.77',
    creditoDisponivel: '0.00',
    creditoUsado: '0.00',
    aPagarNoAcerto: '555.55',
    recebidoNoAcerto: '0.00',
    boleto: '0.00',
    ficouDevendo: '0.00',
    pagoAoVendedor: '0.00',
    restanteSemData: false,
    parcelasDoRestante: [],
    contasAbertasDoVendedor: [],
    cotacaoId: ID_VENDA,
    cotacaoData: '2026-11-12',
    podeFechar: true,
    ...extra,
  };
}

/** Abre o acerto da lista e a tela de conferir (a prévia). */
export async function abrirPrevia(
  m: Montagem,
  { rotuloDoBotao = 'Conferir o acerto' }: { rotuloDoBotao?: string } = {},
) {
  const u = await abrir(m);
  await u.click(await screen.findByRole('button', { name: /Abrir acerto de Ana Vendedora/ }));
  await u.click(await screen.findByRole('button', { name: rotuloDoBotao }));
  return u;
}

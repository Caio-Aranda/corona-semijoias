// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  EstadoDaSessao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../../../shared/ponte';
import { App } from '../../App';
import { esquecerImagens } from '../../componentes/ImagemDoProduto';

const ID_VEND = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const ID_SEMANA = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const ID_PRACA = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';
const ID_ANEL = '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33';
const ID_BRINCO = '3d4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b44';
const ID_DESATIVADO = '4e5e28ba-2fa1-41d3-b9a5-2c3a9a8f6b55';
const ID_RASCUNHO = '5f6e28ba-2fa1-41d3-b9a5-2c3a9a8f6b66';
const HASH = 'a'.repeat(64);

type Manipulador = (p: PedidoDeChamada) => ResultadoDeChamada | Promise<ResultadoDeChamada>;

const pagina = (itens: unknown[]) => ({
  ok: true as const,
  dados: { itens, pagina: 0, tamanho: 20, total: itens.length },
});

const produto = (extra: Record<string, unknown>) => ({
  version: 1,
  referencia: 'R',
  categoriaId: ID_PRACA,
  precoVenda: 70,
  precoCusto: 10,
  estoqueMinimo: 0,
  estoqueAtual: 5,
  ativo: true,
  fotoPrincipal: { hash: HASH },
  ...extra,
});
const ANEL = produto({
  id: ID_ANEL,
  nome: 'Anel Solitário',
  codigos: [{ id: ID_ANEL, codigo: '0012', tipo: 'LOJA', principal: true, ativo: true }],
});
const BRINCO = produto({
  id: ID_BRINCO,
  nome: 'Brinco de Argola',
  precoVenda: 30,
  estoqueAtual: 1,
  fotoPrincipal: null,
  codigos: [{ id: ID_BRINCO, codigo: 'B-7', tipo: 'FORNECEDOR', principal: true, ativo: true }],
});
const DESATIVADO = produto({
  id: ID_DESATIVADO,
  nome: 'Colar Antigo',
  ativo: false,
  codigos: [{ id: ID_DESATIVADO, codigo: 'C-1', tipo: 'LOJA', principal: true, ativo: true }],
});
const SEM_ESTOQUE = produto({
  id: ID_DESATIVADO,
  nome: 'Pulseira Fina',
  estoqueAtual: 0,
  codigos: [{ id: ID_DESATIVADO, codigo: 'P-0', tipo: 'LOJA', principal: true, ativo: true }],
});
const PRODUTOS: Record<string, unknown> = {
  '0012': ANEL,
  'b-7': BRINCO,
  'c-1': DESATIVADO,
  'p-0': SEM_ESTOQUE,
};

/** Faz de conta do servidor: guarda a maleta em montagem e recalcula preço, totais e versão. */
function servidorDeMaletas() {
  const itens = new Map<string, { produto: Record<string, unknown>; quantidade: number }>();
  let version = 0;
  let status = 'RASCUNHO';
  const resposta = () => {
    const lista = [...itens.values()].map((i) => ({
      id: `item-${String(i.produto.id)}`,
      produto: {
        id: i.produto.id,
        nome: i.produto.nome,
        codigo: (i.produto.codigos as { codigo: string }[])[0]?.codigo,
        fotoPrincipal: i.produto.fotoPrincipal,
      },
      quantidade: i.quantidade,
      precoUnitario: i.produto.precoVenda,
      total: (i.produto.precoVenda as number) * i.quantidade,
    }));
    return {
      id: ID_RASCUNHO,
      version,
      vendedorId: ID_VEND,
      vendedorNome: 'Joana Representante',
      semanaId: ID_SEMANA,
      semanaNumero: 12,
      pracaId: ID_PRACA,
      pracaNome: 'Centro',
      tipo: 'NOVA',
      status,
      totalPecas: lista.reduce((s, i) => s + i.quantidade, 0),
      totalValor: lista.reduce((s, i) => s + i.total, 0),
      criadaEm: '2026-11-10T12:00:00Z',
      enviadaEm: status === 'ENVIADA' ? '2026-11-10T13:00:00Z' : null,
      itens: lista,
      avisos: [],
    };
  };
  return {
    itens,
    resposta,
    manipuladores: {
      'maletas.criar': (): ResultadoDeChamada => ({ ok: true, dados: resposta(), criado: true }),
      'maletas.ver': (): ResultadoDeChamada => ({ ok: true, dados: resposta() }),
      'maletas.colocarPeca': (p: PedidoDeChamada): ResultadoDeChamada => {
        const { produtoId, quantidade } = p.parametros as { produtoId: string; quantidade: number };
        const achado = Object.values(PRODUTOS).find(
          (x) => (x as { id: string }).id === produtoId,
        ) as Record<string, unknown>;
        itens.set(produtoId, { produto: achado, quantidade });
        version += 1;
        return { ok: true, dados: resposta() };
      },
      'maletas.tirarPeca': (p: PedidoDeChamada): ResultadoDeChamada => {
        itens.delete((p.parametros as { produtoId: string }).produtoId);
        version += 1;
        return { ok: true, dados: resposta() };
      },
      'maletas.enviar': (): ResultadoDeChamada => {
        status = 'ENVIADA';
        return { ok: true, dados: resposta(), criado: true };
      },
    } satisfies Record<string, Manipulador>,
  };
}

function montar(manipuladores: Record<string, Manipulador> = {}) {
  const servidor = servidorDeMaletas();
  const chamadas: PedidoDeChamada[] = [];
  const padrao: Record<string, Manipulador> = {
    'avisos.listar': () => ({ ok: true, dados: { avisos: [] } }),
    'cotacao.hoje': () => ({ ok: true, dados: { disponivel: false } }),
    'vendedores.escolha': () =>
      pagina([
        { id: ID_VEND, nome: 'Joana Representante', codigo: 7, pracaId: ID_PRACA, ativo: true },
      ]),
    'semanas.listar': () => pagina([{ id: ID_SEMANA, numero: 12, situacao: 'ABERTA' }]),
    'pracas.listar': () => pagina([{ id: ID_PRACA, nome: 'Centro', ativo: true }]),
    'produtos.listar': () => pagina([]),
    'produtos.porCodigo': (p) => {
      const achado = PRODUTOS[(p.parametros as { codigo: string }).codigo.toLowerCase()];
      return achado
        ? { ok: true, dados: achado }
        : { ok: false, motivo: 'erro', status: 404, mensagem: 'Não achou.' };
    },
    'produtos.fotos.imagem': () => ({
      ok: true,
      dados: { nome: 'x.jpg', tipo: 'image/jpeg', bytes: new Uint8Array([1]) },
    }),
    'maletas.emAberto': () => ({
      ok: true,
      dados: { limite: 500, valorEmAberto: 200, limiteRestante: 300, maletas: [] },
    }),
    'maletas.listar': () => pagina([]),
    ...servidor.manipuladores,
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
  return { ponte, chamadas, servidor };
}

type Montagem = ReturnType<typeof montar>;
const chamadasDe = (m: Montagem, op: string) => m.chamadas.filter((c) => c.operacao === op);
const ultima = (m: Montagem, op: string) => chamadasDe(m, op).at(-1)?.parametros;

async function abrirMontagem(m: Montagem) {
  const u = userEvent.setup();
  render(<App ponte={m.ponte} />);
  const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
  await u.click(within(nav).getByRole('button', { name: 'Montar maleta' }));
  await screen.findByRole('heading', { name: 'Montar maleta', level: 1 });
  return u;
}

async function comecarMaleta(m: Montagem) {
  const u = await abrirMontagem(m);
  await screen.findByRole('option', { name: 'Joana Representante' });
  await screen.findByRole('option', { name: 'Semana 12' });
  await u.selectOptions(screen.getByLabelText('Representante'), 'Joana Representante');
  await u.selectOptions(screen.getByLabelText('Semana'), 'Semana 12');
  await u.click(screen.getByRole('button', { name: 'Começar a montar' }));
  await screen.findByLabelText('Código da peça ou nome');
  return u;
}

async function ler(u: ReturnType<typeof userEvent.setup>, codigo: string) {
  await u.type(screen.getByLabelText('Código da peça ou nome'), `${codigo}{Enter}`);
}

beforeEach(() => {
  window.location.hash = '';
  esquecerImagens();
  URL.createObjectURL = vi.fn(() => 'blob:foto');
  URL.revokeObjectURL = vi.fn();
});

describe('Nova maleta', () => {
  it('cria o rascunho com representante, semana e tipo; o id é gerado na tela', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    const p = ultima(m, 'maletas.criar') as Record<string, unknown>;
    expect(p).toMatchObject({ vendedorId: ID_VEND, semanaId: ID_SEMANA, tipo: 'NOVA' });
    expect(p.id).toMatch(/^[0-9a-f-]{36}$/);
    expect(p).not.toHaveProperty('pracaId');
    expect(screen.getByLabelText('Código da peça ou nome')).toBe(document.activeElement);
    expect(u).toBeTruthy();
  });

  it('pede representante e semana antes de começar', async () => {
    const m = montar();
    const u = await abrirMontagem(m);
    await u.click(await screen.findByRole('button', { name: 'Começar a montar' }));
    expect(await screen.findByText('Escolha o representante.')).toBeTruthy();
    expect(screen.getByText('Escolha a semana.')).toBeTruthy();
    expect(chamadasDe(m, 'maletas.criar')).toHaveLength(0);
  });

  it('representante com restrição: mostra a mensagem do servidor e não segue', async () => {
    const m = montar({
      'maletas.criar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'Joana tem uma restrição que impede montar a maleta. Fale com a dona.',
      }),
    });
    const u = await abrirMontagem(m);
    await screen.findByRole('option', { name: 'Joana Representante' });
    await screen.findByRole('option', { name: 'Semana 12' });
    await u.selectOptions(screen.getByLabelText('Representante'), 'Joana Representante');
    await u.selectOptions(screen.getByLabelText('Semana'), 'Semana 12');
    await u.click(screen.getByRole('button', { name: 'Começar a montar' }));
    expect(await screen.findByText(/restrição que impede montar a maleta/)).toBeTruthy();
    expect(screen.queryByLabelText('Código da peça ou nome')).toBeNull();
  });
});

describe('Montagem pelo leitor', () => {
  it('ler a peça mostra foto, nome, código e estoque, e soma 1; ler de novo soma mais 1', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    const painel = await screen.findByRole('region', { name: 'Última peça lida' });
    expect(within(painel).getByText('Anel Solitário')).toBeTruthy();
    expect(within(painel).getByText('0012')).toBeTruthy();
    expect(within(painel).getByText('No estoque')).toBeTruthy();
    expect(await within(painel).findByRole('img')).toBeTruthy();
    expect(ultima(m, 'maletas.colocarPeca')).toMatchObject({ produtoId: ID_ANEL, quantidade: 1 });
    await ler(u, '0012');
    await waitFor(() =>
      expect(ultima(m, 'maletas.colocarPeca')).toMatchObject({ quantidade: 2, version: 1 }),
    );
    expect(await screen.findByText('Anel Solitário: agora são 2 na maleta.')).toBeTruthy();
    expect(screen.getByLabelText('Quantidade de Anel Solitário')).toHaveProperty('value', '2');
    expect(screen.getByText('Peças na maleta').nextSibling?.textContent).toBe('2');
  });

  it('código que não existe: "Peça não encontrada", sem cadastrar nada', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, '9999');
    expect(await screen.findByText('Peça não encontrada')).toBeTruthy();
    expect(screen.getByText(/Não encontramos nenhuma peça com o código 9999/)).toBeTruthy();
    expect(chamadasDe(m, 'maletas.colocarPeca')).toHaveLength(0);
    expect(chamadasDe(m, 'produtos.criar')).toHaveLength(0);
  });

  it('peça sem foto entra normalmente', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, 'B-7');
    expect(await screen.findByText('Brinco de Argola entrou na maleta.')).toBeTruthy();
    expect(ultima(m, 'maletas.colocarPeca')).toMatchObject({ produtoId: ID_BRINCO, quantidade: 1 });
  });

  it('peça desativada, sem estoque ou acima do estoque não entra, com aviso claro', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, 'C-1');
    expect(await screen.findByText(/Colar Antigo está inativa/)).toBeTruthy();
    await ler(u, 'P-0');
    expect(await screen.findByText(/Pulseira Fina está sem estoque/)).toBeTruthy();
    await ler(u, 'B-7');
    await screen.findByText('Brinco de Argola entrou na maleta.');
    await ler(u, 'B-7');
    expect(await screen.findByText(/Só há 1 peça de Brinco de Argola no estoque/)).toBeTruthy();
    expect(chamadasDe(m, 'maletas.colocarPeca')).toHaveLength(1);
  });

  it('peça sem estoque na loja mas em maleta de alguém diz de quem é, em vez da recusa genérica', async () => {
    const m = montar({
      'produtos.porCodigo': (p) => {
        const codigo = String((p.parametros as { codigo?: string }).codigo ?? '').toLowerCase();
        if (codigo === 'p-0') {
          return {
            ok: true,
            dados: {
              ...SEM_ESTOQUE,
              emMaletas: 1,
              vendedoresEmMaletas: [{ nome: 'Maria', quantidade: 1 }],
            },
          };
        }
        return { ok: true, dados: PRODUTOS[codigo] };
      },
    });
    const u = await comecarMaleta(m);
    await ler(u, 'P-0');
    expect(await screen.findByText('Sem estoque na loja. Está na maleta de Maria.')).toBeTruthy();
    expect(chamadasDe(m, 'maletas.colocarPeca')).toHaveLength(0);
  });

  it('muda a quantidade digitada e tira a peça', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    const campo = await screen.findByLabelText('Quantidade de Anel Solitário');
    await u.clear(campo);
    await u.type(campo, '0');
    await u.click(screen.getByRole('button', { name: 'Atualizar a quantidade de Anel Solitário' }));
    expect(await screen.findByText('Digite uma quantidade inteira, de 1 em diante.')).toBeTruthy();
    await u.clear(campo);
    await u.type(campo, '3{Enter}');
    await waitFor(() => expect(ultima(m, 'maletas.colocarPeca')).toMatchObject({ quantidade: 3 }));
    await u.click(screen.getByRole('button', { name: 'Tirar Anel Solitário da maleta' }));
    expect(await screen.findByText('A maleta está vazia. Leia a primeira peça.')).toBeTruthy();
    expect(ultima(m, 'maletas.tirarPeca')).toMatchObject({ produtoId: ID_ANEL });
  });

  it('mostra limite, já está com ele, esta maleta e sobra', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    await screen.findByText('Anel Solitário entrou na maleta.');
    const limite = screen.getByRole('region', { name: 'Limite do representante' });
    await within(limite).findByText('R$ 500,00');
    expect(within(limite).getByText('Já está com ele').nextSibling?.textContent).toBe('R$ 200,00');
    expect(within(limite).getByText('Esta maleta').nextSibling?.textContent).toBe('R$ 70,00');
    expect(within(limite).getByText('Sobra').nextSibling?.textContent).toBe('R$ 230,00');
  });

  it('representante com "Sem limite" marcado não mostra sobra', async () => {
    const m = montar({
      'maletas.emAberto': () => ({
        ok: true,
        dados: {
          limite: null,
          semLimite: true,
          limiteDefinido: true,
          valorEmAberto: 0,
          maletas: [],
        },
      }),
    });
    await comecarMaleta(m);
    expect((await screen.findAllByText('Sem limite')).length).toBeGreaterThan(0);
    expect(screen.queryByText('Sobra')).toBeNull();
  });

  it('limite ainda não definido avisa para definir antes de montar (zero não é "sem limite")', async () => {
    const m = montar({
      'maletas.emAberto': () => ({
        ok: true,
        dados: {
          limite: null,
          semLimite: false,
          limiteDefinido: false,
          valorEmAberto: 0,
          maletas: [],
        },
      }),
    });
    await comecarMaleta(m);
    expect(
      (await screen.findAllByText(/Defina o limite deste representante antes de montar a maleta/))
        .length,
    ).toBeGreaterThan(0);
    expect(screen.queryByText('Sem limite')).toBeNull();
  });

  it('com limite definido mostra "Limite, em mãos, pode levar mais"', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    await screen.findByText('Anel Solitário entrou na maleta.');
    expect(
      await screen.findByText('Limite R$ 500,00 — em mãos R$ 200,00 — pode levar mais R$ 230,00'),
    ).toBeTruthy();
  });
});

describe('Enviar a maleta', () => {
  it('pede confirmação com o resumo e depois mostra a ficha só para ler', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    await screen.findByText('Anel Solitário entrou na maleta.');
    await u.click(screen.getByRole('button', { name: 'Enviar maleta' }));
    const janela = await screen.findByRole('dialog');
    expect(
      within(janela).getByText(/1 peça para Joana Representante, num valor de R\$ 70,00/),
    ).toBeTruthy();
    expect(chamadasDe(m, 'maletas.enviar')).toHaveLength(0);
    await u.click(within(janela).getByRole('button', { name: 'Enviar maleta' }));
    expect(await screen.findByText('Maleta enviada', { selector: 'p' })).toBeTruthy();
    expect(ultima(m, 'maletas.enviar')).toEqual({ id: ID_RASCUNHO });
    expect(screen.queryByLabelText('Código da peça ou nome')).toBeNull();
    expect(screen.queryByLabelText('Quantidade de Anel Solitário')).toBeNull();
    expect(screen.getByText('Enviada em')).toBeTruthy();
    // Maleta enviada já tem o botão da nota (tarefa 3.7).
    expect(screen.getByRole('button', { name: 'Imprimir nota: Joana Representante' })).toBeTruthy();
  });

  it('maleta vazia não vai para a confirmação', async () => {
    const m = montar();
    const u = await comecarMaleta(m);
    await u.click(screen.getByRole('button', { name: 'Enviar maleta' }));
    expect(await screen.findByText(/Coloque pelo menos uma peça/)).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('mostra a recusa do servidor (limite) e deixa continuar montando', async () => {
    const m = montar({
      'maletas.enviar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem:
          'Esta maleta passa do limite de R$ 500,00: já estão com o representante R$ 200,00 e faltam R$ 20,00. Tire peças da maleta.',
      }),
    });
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    await screen.findByText('Anel Solitário entrou na maleta.');
    await u.click(screen.getByRole('button', { name: 'Enviar maleta' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Enviar maleta' }),
    );
    expect(await screen.findByText(/passa do limite de R\$ 500,00/)).toBeTruthy();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByLabelText('Código da peça ou nome')).toBeTruthy();
  });

  it('mostra a recusa de saldo insuficiente dizendo qual peça', async () => {
    const m = montar({
      'maletas.enviar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        mensagem: 'Não há peças suficientes no estoque de Anel Solitário.',
      }),
    });
    const u = await comecarMaleta(m);
    await ler(u, '0012');
    await screen.findByText('Anel Solitário entrou na maleta.');
    await u.click(screen.getByRole('button', { name: 'Enviar maleta' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Enviar maleta' }),
    );
    expect(await screen.findByText(/estoque de Anel Solitário/)).toBeTruthy();
  });

  it('resposta 200 do envio avisa que a maleta já estava enviada', async () => {
    const m2: Montagem = montar({
      'maletas.enviar': () => ({
        ok: true,
        dados: {
          ...m2.servidor.resposta(),
          status: 'ENVIADA',
          enviadaEm: '2026-11-10T13:00:00Z',
        },
        criado: false,
      }),
    });
    const u = await comecarMaleta(m2);
    await ler(u, '0012');
    await screen.findByText('Anel Solitário entrou na maleta.');
    await u.click(screen.getByRole('button', { name: 'Enviar maleta' }));
    await u.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Enviar maleta' }),
    );
    expect(await screen.findByText('Esta maleta já estava enviada')).toBeTruthy();
  });
});

describe('Rascunhos', () => {
  const rascunho = (extra: Record<string, unknown> = {}) => ({
    id: ID_RASCUNHO,
    version: 4,
    vendedorId: ID_VEND,
    vendedorNome: 'Joana Representante',
    semanaId: ID_SEMANA,
    semanaNumero: 12,
    pracaNome: 'Centro',
    tipo: 'NOVA',
    status: 'RASCUNHO',
    totalPecas: 3,
    totalValor: 210,
    criadaEm: '2026-11-10T12:00:00Z',
    ...extra,
  });

  it('lista os rascunhos e retoma um deles', async () => {
    const m = montar({ 'maletas.listar': () => pagina([rascunho()]) });
    const u = await abrirMontagem(m);
    expect(ultima(m, 'maletas.listar')).toMatchObject({ status: 'RASCUNHO' });
    await u.click(
      await screen.findByRole('button', {
        name: 'Continuar montando a maleta de Joana Representante',
      }),
    );
    expect(await screen.findByLabelText('Código da peça ou nome')).toBeTruthy();
    expect(ultima(m, 'maletas.ver')).toEqual({ id: ID_RASCUNHO });
  });

  it('exclui o rascunho só depois de confirmar', async () => {
    let existe = true;
    const m = montar({
      'maletas.listar': () => pagina(existe ? [rascunho()] : []),
      'maletas.excluir': () => {
        existe = false;
        return { ok: true, dados: null };
      },
    });
    const u = await abrirMontagem(m);
    await u.click(
      await screen.findByRole('button', { name: 'Excluir a maleta de Joana Representante' }),
    );
    const janela = await screen.findByRole('dialog');
    expect(within(janela).getByText(/As peças continuam no estoque/)).toBeTruthy();
    expect(chamadasDe(m, 'maletas.excluir')).toHaveLength(0);
    await u.click(within(janela).getByRole('button', { name: 'Excluir a maleta' }));
    await waitFor(() =>
      expect(ultima(m, 'maletas.excluir')).toEqual({ id: ID_RASCUNHO, version: 4 }),
    );
    expect(await screen.findByText(/Nenhuma maleta em montagem/)).toBeTruthy();
  });
});

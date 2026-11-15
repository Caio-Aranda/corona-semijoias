// @vitest-environment jsdom
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import { esquecerImagens } from '../../componentes/ImagemDoProduto';
import {
  HASH,
  ID_ACERTO,
  ID_ANEL,
  ID_BRINCO,
  ID_VENDEDOR,
  abrir,
  chamadasDe,
  ehUuid,
  ficha,
  montar,
  produtoCompleto,
  produtoDaFicha,
  ultima,
} from './apoio-de-teste';

beforeEach(() => esquecerImagens());

const resumo = (extra: Record<string, unknown> = {}) => ({
  id: ID_ACERTO,
  status: 'EM_ACERTO',
  vendedorId: ID_VENDEDOR,
  vendedorNome: 'Ana Vendedora',
  semanaNumero: 45,
  maletas: 1,
  iniciadoEm: '2026-11-10T12:00:00Z',
  ...extra,
});

describe('menu e tela inicial', () => {
  it('o menu tem o grupo Acerto com Acertos, Pendências e Comissão e meta', async () => {
    const m = montar();
    render(<App ponte={m.ponte} />);
    const nav = await screen.findByRole('navigation', { name: 'Menu do Corona Gestão' });
    expect(within(nav).getByText('Acerto')).toBeInTheDocument();
    for (const nome of ['Acertos', 'Pendências', 'Comissão e meta']) {
      expect(within(nav).getByRole('button', { name: nome })).toBeInTheDocument();
    }
  });

  it('o botão Fazer acerto da tela inicial leva à lista de acertos', async () => {
    const m = montar();
    const u = userEvent.setup();
    render(<App ponte={m.ponte} />);
    await u.click(await screen.findByRole('button', { name: 'Fazer acerto' }));
    expect(await screen.findByRole('heading', { name: 'Acertos', level: 1 })).toBeInTheDocument();
    expect(screen.queryByText('Em breve.')).toBeNull();
  });
});

describe('lista de acertos', () => {
  it('mostra vendedor, semana e a situação em palavras da loja', async () => {
    const m = montar({
      'acertos.listar': () => ({
        ok: true,
        dados: [
          resumo(),
          resumo({ id: ID_ANEL, status: 'ACERTADO' }),
          resumo({ id: ID_BRINCO, status: 'DESISTIDO' }),
        ],
      }),
    });
    await abrir(m);
    const tabela = within(await screen.findByRole('table'));
    expect(await tabela.findAllByText('Ana Vendedora')).toHaveLength(3);
    expect(tabela.getByText('Em acerto')).toBeInTheDocument();
    expect(tabela.getByText('Acertado')).toBeInTheDocument();
    expect(tabela.getByText('Desistido')).toBeInTheDocument();
    expect(screen.queryByText('EM_ACERTO')).toBeNull();
  });

  it('filtra pela situação mandando só valores conhecidos', async () => {
    const m = montar();
    const u = await abrir(m);
    await screen.findByRole('heading', { name: 'Acertos', level: 1 });
    await u.selectOptions(screen.getByLabelText('Situação'), 'ACERTADO');
    await waitFor(() =>
      expect(ultima(m, 'acertos.listar')).toMatchObject({ situacao: 'ACERTADO' }),
    );
  });
});

describe('começar acerto', () => {
  async function aoComecar(m: ReturnType<typeof montar>) {
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: 'Começar acerto' }));
    await u.selectOptions(await screen.findByLabelText('Vendedor'), ID_VENDEDOR);
    return u;
  }

  it('manda só o id (UUID da tela), o vendedor e a semana escolhida', async () => {
    const m = montar({ 'acertos.iniciar': () => ({ ok: true, dados: ficha(), criado: true }) });
    const u = await aoComecar(m);
    await u.click(screen.getByRole('button', { name: 'Mais opções' }));
    await u.selectOptions(screen.getByLabelText('Semana'), '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99');
    await u.click(screen.getByRole('button', { name: 'Começar' }));
    await screen.findByRole('heading', { name: /Contagem/ });
    const p = ultima(m, 'acertos.iniciar') as Record<string, unknown>;
    expect(Object.keys(p).sort()).toEqual(['id', 'semanaId', 'vendedorId']);
    expect(ehUuid(p.id)).toBe(true);
    expect(p.vendedorId).toBe(ID_VENDEDOR);
  });

  it('sem escolher a semana, não manda semana (o servidor escolhe)', async () => {
    const m = montar({ 'acertos.iniciar': () => ({ ok: true, dados: ficha(), criado: true }) });
    const u = await aoComecar(m);
    await u.click(screen.getByRole('button', { name: 'Começar' }));
    await screen.findByRole('heading', { name: /Contagem/ });
    expect(Object.keys(ultima(m, 'acertos.iniciar') as object).sort()).toEqual([
      'id',
      'vendedorId',
    ]);
  });

  it('falta de internet e nova tentativa reenviam o mesmo id', async () => {
    let n = 0;
    const m = montar({
      'acertos.iniciar': () =>
        ++n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: ficha(), criado: false },
    });
    const u = await aoComecar(m);
    await u.click(screen.getByRole('button', { name: 'Começar' }));
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Começar' }));
    await screen.findByRole('heading', { name: /Contagem/ });
    const [a, b] = chamadasDe(m, 'acertos.iniciar').map((c) => (c.parametros as { id: string }).id);
    expect(a).toBe(b);
  });

  it('vendedor que já está em acerto: diz o que fazer', async () => {
    const m = montar({
      'acertos.iniciar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'ACERTO_EM_ANDAMENTO',
        mensagem: 'Este vendedor já tem um acerto em andamento.',
      }),
    });
    const u = await aoComecar(m);
    await u.click(screen.getByRole('button', { name: 'Começar' }));
    expect(
      await screen.findByText('Este vendedor já está em acerto. Abra o acerto dele na lista.'),
    ).toBeInTheDocument();
  });

  it('exige escolher o vendedor antes de começar', async () => {
    const m = montar();
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: 'Começar acerto' }));
    await u.click(await screen.findByRole('button', { name: 'Começar' }));
    expect(await screen.findByText('Escolha o vendedor.')).toBeInTheDocument();
    expect(chamadasDe(m, 'acertos.iniciar')).toHaveLength(0);
  });

  it('avisa quando o vendedor não tem maleta com ele', async () => {
    const m = montar({
      'maletas.emAberto': () => ({
        ok: true,
        dados: { limite: 500, valorEmAberto: '0.00', limiteRestante: '500.00', maletas: [] },
      }),
    });
    await aoComecar(m);
    expect(
      await screen.findByText(
        /não tem maleta com ele: o acerto vai ter só vendas e devoluções antigas/,
      ),
    ).toBeInTheDocument();
  });
});

describe('contagem', () => {
  const codigos: Record<string, unknown> = {
    '0012': produtoCompleto(ID_ANEL, 'Anel Solitário', '0012'),
  };
  const comProdutos = {
    'produtos.porCodigo': (p: { parametros?: Record<string, unknown> }) => {
      const achado = codigos[String(p.parametros?.codigo).toLowerCase()];
      return achado
        ? { ok: true as const, dados: achado }
        : { ok: false as const, motivo: 'erro' as const, status: 404, mensagem: 'Não achou.' };
    },
  };

  async function abrirContagem(
    extra: Record<string, unknown> = {},
    manipuladores: Parameters<typeof montar>[0] = {},
  ) {
    const m = montar({
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
      'acertos.ver': () => ({ ok: true, dados: ficha(extra) }),
      ...comProdutos,
      ...manipuladores,
    });
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: /Abrir acerto de Ana Vendedora/ }));
    await screen.findByRole('heading', { name: /Contagem/ });
    return { m, u };
  }

  it('cada leitura leva um id novo e a contagem mostrada é a que a ficha devolveu', async () => {
    let soma = 0;
    const { m, u } = await abrirContagem(
      {},
      {
        'acertos.ler': () => {
          soma += 1;
          // De propósito, a ficha devolve 7 mesmo na primeira leitura: a tela mostra o que veio.
          return {
            ok: true,
            dados: ficha({
              contagem: [
                {
                  estadoPeca: 'BOA',
                  quantidade: soma === 1 ? 7 : 9,
                  produto: produtoDaFicha(ID_ANEL, 'Anel Solitário', '0012', HASH),
                },
              ],
            }),
            criado: true,
          };
        },
      },
    );
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    await screen.findByTestId('contagem-BOA-' + ID_ANEL);
    expect(screen.getByTestId('contagem-BOA-' + ID_ANEL)).toHaveTextContent('7');
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    await waitFor(() =>
      expect(screen.getByTestId('contagem-BOA-' + ID_ANEL)).toHaveTextContent('9'),
    );
    const ids = chamadasDe(m, 'acertos.ler').map(
      (c) => (c.parametros as { leituraId: string }).leituraId,
    );
    expect(ids).toHaveLength(2);
    expect(ids.every(ehUuid)).toBe(true);
    expect(ids[0]).not.toBe(ids[1]);
    expect(ultima(m, 'acertos.ler')).toMatchObject({
      id: ID_ACERTO,
      produtoId: ID_ANEL,
      estadoPeca: 'BOA',
      quantidade: 1,
    });
  });

  it('falha de rede e nova leitura da mesma peça reenviam o mesmo id', async () => {
    let n = 0;
    const { m, u } = await abrirContagem(
      {},
      {
        'acertos.ler': () =>
          ++n === 1
            ? { ok: false, motivo: 'sem-conexao' }
            : { ok: true, dados: ficha(), criado: false },
      },
    );
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    expect(await screen.findByText(/Sem conexão com a internet/)).toBeInTheDocument();
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    await waitFor(() => expect(chamadasDe(m, 'acertos.ler')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'acertos.ler').map(
      (c) => (c.parametros as { leituraId: string }).leituraId,
    );
    expect(a).toBe(b);
  });

  it('peça com defeito e quantidade escolhidas na tela vão como estão', async () => {
    const { m, u } = await abrirContagem(
      {},
      {
        'acertos.ler': () => ({ ok: true, dados: ficha(), criado: true }),
      },
    );
    await u.click(screen.getByRole('radio', { name: 'Com defeito' }));
    await u.clear(screen.getByLabelText('Quantidade'));
    await u.type(screen.getByLabelText('Quantidade'), '3');
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    await waitFor(() => expect(chamadasDe(m, 'acertos.ler')).toHaveLength(1));
    expect(ultima(m, 'acertos.ler')).toMatchObject({ estadoPeca: 'DEFEITO', quantidade: 3 });
  });

  it('desfazer leitura manda quantidade negativa com id novo', async () => {
    const { m, u } = await abrirContagem(
      {
        contagem: [
          {
            estadoPeca: 'BOA',
            quantidade: 2,
            produto: produtoDaFicha(ID_ANEL, 'Anel Solitário', '0012'),
          },
        ],
      },
      { 'acertos.ler': () => ({ ok: true, dados: ficha(), criado: true }) },
    );
    await u.click(screen.getByRole('button', { name: 'Desfazer leitura de Anel Solitário' }));
    await waitFor(() => expect(chamadasDe(m, 'acertos.ler')).toHaveLength(1));
    const p = ultima(m, 'acertos.ler') as {
      quantidade: number;
      leituraId: string;
      estadoPeca: string;
    };
    expect(p.quantidade).toBe(-1);
    expect(p.estadoPeca).toBe('BOA');
    expect(ehUuid(p.leituraId)).toBe(true);
  });

  it('peça de fora do acerto e contagem negativa mostram o que fazer', async () => {
    let n = 0;
    const { u } = await abrirContagem(
      {},
      {
        'acertos.ler': () =>
          ++n === 1
            ? {
                ok: false,
                motivo: 'erro',
                status: 404,
                codigo: 'PECA_NAO_ENCONTRADA',
                mensagem: 'x',
              }
            : {
                ok: false,
                motivo: 'erro',
                status: 409,
                codigo: 'LEITURA_DEIXA_CONTAGEM_NEGATIVA',
                mensagem: 'y',
              },
      },
    );
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    expect(
      await screen.findByText('Esta peça não é deste acerto. Confira o código.'),
    ).toBeInTheDocument();
    await u.type(screen.getByLabelText('Código da peça ou nome'), '0012{Enter}');
    expect(
      await screen.findByText('Não dá para tirar mais do que foi contado. Confira a contagem.'),
    ).toBeInTheDocument();
  });

  it('avisa de venda nova e de envios que o computador do vendedor ainda tem', async () => {
    await abrirContagem({ vendasQueChegaramDepoisDoInicio: 2, enviosAguardando: 3 });
    expect(await screen.findByText(/Chegou venda nova: confira a contagem/)).toBeInTheDocument();
    expect(
      screen.getByText(/O computador do vendedor ainda tem 3 envios para mandar/),
    ).toBeInTheDocument();
  });

  it('lista quem leu, quando e onde; peça sem foto mostra o quadro "Sem foto"', async () => {
    await abrirContagem({
      contagem: [
        {
          estadoPeca: 'BOA',
          quantidade: 1,
          produto: produtoDaFicha(ID_ANEL, 'Anel Solitário', '0012'),
        },
      ],
      leituras: [
        {
          id: ID_BRINCO,
          produtoId: ID_ANEL,
          estadoPeca: 'DEFEITO',
          quantidade: 1,
          lidaEm: '2026-11-10T15:00:00Z',
          lidaPorNome: 'Maria',
          peloVendedor: true,
        },
      ],
    });
    expect(screen.getByText('Sem foto')).toBeInTheDocument();
    expect(screen.getByText(/por Maria/)).toBeInTheDocument();
    expect(screen.getByText(/computador do vendedor/)).toBeInTheDocument();
  });
});

describe('desistir do acerto', () => {
  async function abrirParaDesistir(manipuladores: Parameters<typeof montar>[0] = {}) {
    const m = montar({
      'acertos.listar': () => ({ ok: true, dados: [resumo()] }),
      'acertos.ver': () => ({ ok: true, dados: ficha() }),
      ...manipuladores,
    });
    const u = await abrir(m);
    await u.click(await screen.findByRole('button', { name: /Abrir acerto de Ana Vendedora/ }));
    await u.click(await screen.findByRole('button', { name: 'Desistir do acerto' }));
    return { m, u };
  }

  it('exige o motivo e manda o id do comando', async () => {
    const { m, u } = await abrirParaDesistir({
      'acertos.desistir': () => ({ ok: true, dados: ficha({ status: 'DESISTIDO' }), criado: true }),
    });
    expect(
      screen.getByText(/As maletas voltam a ficar com o vendedor e nada de dinheiro é gravado/),
    ).toBeInTheDocument();
    const janela = screen.getByRole('dialog');
    await u.click(within(janela).getByRole('button', { name: 'Desistir do acerto' }));
    expect(chamadasDe(m, 'acertos.desistir')).toHaveLength(0);
    await u.type(within(janela).getByLabelText('Motivo'), 'O vendedor não veio');
    await u.click(within(janela).getByRole('button', { name: 'Desistir do acerto' }));
    await waitFor(() => expect(chamadasDe(m, 'acertos.desistir')).toHaveLength(1));
    const p = ultima(m, 'acertos.desistir') as Record<string, unknown>;
    expect(p).toMatchObject({ id: ID_ACERTO, motivo: 'O vendedor não veio' });
    expect(ehUuid(p.comandoId)).toBe(true);
  });

  it('depois de desistir volta à lista, onde aparece "Desistido"', async () => {
    let listas = 0;
    const { u } = await abrirParaDesistir({
      'acertos.listar': () => ({
        ok: true,
        dados: [resumo({ status: ++listas === 1 ? 'EM_ACERTO' : 'DESISTIDO' })],
      }),
      'acertos.desistir': () => ({ ok: true, dados: ficha({ status: 'DESISTIDO' }), criado: true }),
    });
    const janela = screen.getByRole('dialog');
    await u.type(within(janela).getByLabelText('Motivo'), 'Mudou de ideia');
    await u.click(within(janela).getByRole('button', { name: 'Desistir do acerto' }));
    expect(
      await within(await screen.findByRole('table')).findByText('Desistido'),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Acertos', level: 1 })).toBeInTheDocument();
  });
});

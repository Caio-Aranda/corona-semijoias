// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';
import type { Cliente, EnvioNaoAceito } from '../shared/dados';
import type {
  EstadoDaSincronizacao,
  PedidoDeChamada,
  PonteDoCorona,
  ResultadoDeChamada,
} from '../shared/ponte';
import { AvisoDosDados, IndicadorDeEnvio } from './IndicadorDeEnvio';
import { FormularioDeCliente } from './telas/FormularioDeCliente';
import { TEXTOS } from './textos';

const ESTADO: EstadoDaSincronizacao = {
  comInternet: true,
  enviando: false,
  aguardando: 0,
  vendasAguardando: 0,
  recusadas: 0,
  dadosAte: '2026-11-09T17:30:00Z', // 14:30 em Brasília
  naoConseguiuEnviar: false,
};

type Manipulador = (pedido: PedidoDeChamada) => ResultadoDeChamada;

/** Uma ponte em que o teste decide o estado do envio e o que o banco do computador responde. */
function criarPonte(manipuladores: Partial<Record<string, Manipulador>> = {}) {
  let ouvinte: ((e: EstadoDaSincronizacao) => void) | null = null;
  const chamadas: PedidoDeChamada[] = [];
  const ponte = {
    chamarApi: vi.fn((pedido: PedidoDeChamada) => {
      chamadas.push(pedido);
      const m = manipuladores[pedido.operacao];
      return Promise.resolve(m ? m(pedido) : { ok: false as const, motivo: 'erro' as const });
    }),
    aoMudarASincronizacao: (o: (e: EstadoDaSincronizacao) => void) => {
      ouvinte = o;
      return () => {
        ouvinte = null;
      };
    },
  } as unknown as PonteDoCorona;
  return {
    ponte,
    chamadas,
    estado: (e: Partial<EstadoDaSincronizacao>) => {
      act(() => ouvinte?.({ ...ESTADO, ...e }));
    },
  };
}

describe('o indicador de envio (glossário, parte 7)', () => {
  it('com internet e nada a enviar: "Com internet. Tudo enviado."', () => {
    const { ponte, estado } = criarPonte();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({});
    expect(screen.getByRole('status')).toHaveTextContent('Com internet. Tudo enviado.');
    expect(screen.queryByText(/aguardando envio/)).not.toBeInTheDocument();
  });

  it('com internet, enviando: "Enviando suas vendas…" e quantas esperam', () => {
    const { ponte, estado } = criarPonte();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ enviando: true, aguardando: 3, vendasAguardando: 3 });
    expect(screen.getByRole('status')).toHaveTextContent('Com internet. Enviando suas vendas…');
    expect(screen.getByText('3 vendas aguardando envio')).toBeInTheDocument();
  });

  it('sem internet: a venda está salva e será enviada quando conectar', () => {
    const { ponte, estado } = criarPonte();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ comInternet: false, aguardando: 1, vendasAguardando: 1 });
    expect(screen.getByRole('status')).toHaveTextContent(
      'Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar.',
    );
    expect(screen.getByText('1 venda aguardando envio')).toBeInTheDocument();
  });

  it('só vendas esperando diz "venda(s)"; com outros registros, diz "item(ns)"', () => {
    const { ponte, estado } = criarPonte();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ comInternet: false, aguardando: 4, vendasAguardando: 2 });
    expect(screen.getByText('4 itens aguardando envio')).toBeInTheDocument();
    estado({ comInternet: false, aguardando: 1, vendasAguardando: 0 });
    expect(screen.getByText('1 item aguardando envio')).toBeInTheDocument();
  });

  it('um envio que falhou 5 vezes: "Uma venda não conseguiu ser enviada. Avise a dona."', () => {
    const { ponte, estado } = criarPonte();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ aguardando: 1, vendasAguardando: 1, naoConseguiuEnviar: true });
    expect(
      screen.getByText('Uma venda não conseguiu ser enviada. Avise a dona.'),
    ).toBeInTheDocument();
  });

  it('antes de saber se há internet, não inventa nada', () => {
    const { ponte, estado } = criarPonte();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ comInternet: null });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

describe('envios que o servidor não aceitou', () => {
  const venda: EnvioNaoAceito = {
    id: '11111111-1111-4111-8111-111111111111',
    ehVenda: true,
    descricao: 'Venda para Ana',
    feitoEm: '2026-11-12T12:00:00.000Z',
    motivo: 'Esta peça já não estava na sua maleta. A dona já foi avisada.',
  };

  it('mostra o aviso, o motivo e o botão "Entendi", que tira da lista sem apagar nada', async () => {
    let lista = [venda];
    const { ponte, estado, chamadas } = criarPonte({
      'envios.naoAceitos': () => ({ ok: true, dados: lista }),
      'envios.entendi': () => {
        lista = [];
        return { ok: true, dados: null };
      },
    });
    const u = userEvent.setup();
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ recusadas: 1 });
    expect(
      await screen.findByText('1 venda não foi aceita. A dona já foi avisada.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Venda para Ana\./)).toBeInTheDocument();
    expect(screen.getByText(/Feito em 12\/11 às 09:00/)).toBeInTheDocument();
    expect(screen.getByText(/Esta peça já não estava na sua maleta/)).toBeInTheDocument();

    await u.click(screen.getByRole('button', { name: 'Entendi: Venda para Ana' }));
    expect(chamadas.filter((c) => c.operacao === 'envios.entendi')).toEqual([
      { operacao: 'envios.entendi', id: venda.id },
    ]);
    expect(screen.queryByText(/Venda para Ana/)).not.toBeInTheDocument();
    estado({ recusadas: 0 });
    expect(screen.queryByText(/não foi aceita/)).not.toBeInTheDocument();
  });

  it('com mais de uma, conta; com outro tipo de registro, diz "item"', async () => {
    const outro: EnvioNaoAceito = {
      ...venda,
      id: '22222222-2222-4222-8222-222222222222',
      ehVenda: false,
      descricao: 'Cliente novo: Carla',
    };
    const { ponte, estado } = criarPonte({
      'envios.naoAceitos': () => ({ ok: true, dados: [venda, outro] }),
    });
    render(<IndicadorDeEnvio ponte={ponte} />);
    estado({ recusadas: 2 });
    expect(
      await screen.findByText('2 itens não foram aceitos. A dona já foi avisada.'),
    ).toBeInTheDocument();
    expect(TEXTOS.envio.naoAceitas(3, true)).toBe(
      '3 vendas não foram aceitas. A dona já foi avisada.',
    );
  });
});

describe('"Dados até" nos relatórios', () => {
  it('mostra a data e a hora do último recebimento, a cotação com a data e o que espera envio', () => {
    const { ponte, estado } = criarPonte();
    render(<AvisoDosDados ponte={ponte} cotacao="Cotação de 06/11/2026." />);
    estado({ aguardando: 3, vendasAguardando: 3 });
    expect(screen.getByTestId('dados-ate')).toHaveTextContent(
      'Dados até 09/11 às 14:30. Cotação de 06/11/2026. 3 vendas aguardando envio',
    );
  });

  it('antes do primeiro recebimento, pede para conectar', () => {
    const { ponte, estado } = criarPonte();
    render(<AvisoDosDados ponte={ponte} />);
    estado({ dadosAte: null });
    expect(screen.getByTestId('dados-ate')).toHaveTextContent(
      'Ainda não recebemos os dados da loja neste computador. Conecte-se à internet.',
    );
  });
});

describe('editar cliente', () => {
  const cliente: Cliente = {
    id: '33333333-3333-4333-8333-333333333333',
    nome: 'Maria Souza',
    cpf: null,
    telefone: '111',
    cidade: null,
    meu: true,
    dataNascimento: null,
    whatsapp: null,
    observacoes: null,
    ativo: true,
  };

  it('manda só o que mudou', async () => {
    const { ponte, chamadas } = criarPonte({
      'clientes.alterar': () => ({ ok: true, dados: { ...cliente, telefone: '222' } }),
    });
    const aoSalvar = vi.fn();
    const u = userEvent.setup();
    render(
      <FormularioDeCliente
        ponte={ponte}
        editar={cliente}
        aoSalvar={aoSalvar}
        aoCancelar={() => undefined}
      />,
    );
    expect(screen.getByRole('heading', { name: 'Editar cliente' })).toBeInTheDocument();
    const telefone = screen.getByLabelText('Telefone');
    await u.clear(telefone);
    await u.type(telefone, '222');
    await u.click(screen.getByRole('button', { name: 'Salvar alterações' }));
    expect(chamadas).toEqual([
      { operacao: 'clientes.alterar', id: cliente.id, campos: { telefone: '222' } },
    ]);
    expect(aoSalvar).toHaveBeenCalledOnce();
  });

  it('sem mudança, avisa e não manda nada', async () => {
    const { ponte, chamadas } = criarPonte();
    const u = userEvent.setup();
    render(
      <FormularioDeCliente
        ponte={ponte}
        editar={cliente}
        aoSalvar={() => undefined}
        aoCancelar={() => undefined}
      />,
    );
    await u.click(screen.getByRole('button', { name: 'Salvar alterações' }));
    expect(await screen.findByText('Você não mudou nada neste cadastro.')).toBeInTheDocument();
    expect(chamadas).toEqual([]);
  });
});

describe('palavras proibidas na tela (glossário, parte 2)', () => {
  const raiz = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
  const glossario = readFileSync(join(raiz, 'docs', 'glossario-interface.md'), 'utf8');
  const bloco = (nome: string): string[] => {
    const m = new RegExp(`<!-- ${nome}:INICIO -->\\s*\`\`\`\\s*([\\s\\S]*?)\\s*\`\`\``).exec(
      glossario,
    );
    return (m?.[1] ?? '').split(/\r?\n/).filter(Boolean);
  };
  const blocoA = bloco('PROIBIDAS-A').map((p) => p.toLowerCase());
  const blocoB = bloco('PROIBIDAS-B');
  // Além do glossário: o que o ADR-0013 (item 18) proíbe, em qualquer forma.
  const extras = ['pendente', 'fila', 'sincroniz'];

  function textosDoIndicador(): string[] {
    const T = TEXTOS.envio;
    return [
      T.area,
      T.tudoEnviado,
      T.enviando,
      T.semInternet,
      T.vendasAguardando(1),
      T.vendasAguardando(3),
      T.itensAguardando(1),
      T.itensAguardando(4),
      T.naoConseguiu,
      T.naoAceitas(1, true),
      T.naoAceitas(3, true),
      T.naoAceitas(1, false),
      T.naoAceitas(3, false),
      T.tituloDaLista,
      T.feitoEm('12/11 às 09:00'),
      T.motivo,
      T.entendi,
      T.entendiDe('Venda para Ana'),
      T.dadosAte('09/11 às 14:30'),
      T.semDados,
      TEXTOS.cotacao.deHoje('06/11/2026'),
      TEXTOS.cotacao.ultima('05/11/2026'),
      TEXTOS.cotacao.doDia('06/11/2026'),
      TEXTOS.cliente.tituloDaEdicao,
      TEXTOS.cliente.salvarEdicao,
      TEXTOS.cliente.semMudanca,
      TEXTOS.clientes.editar,
      TEXTOS.clientes.atualizado,
      TEXTOS.clientes.soQuemAtende,
    ];
  }

  it('o glossário foi lido', () => {
    expect(blocoA.length).toBeGreaterThan(10);
    expect(blocoB).toContain('ENVIADA');
  });

  it.each(textosDoIndicador())('o texto "%s" não usa palavra proibida', (texto) => {
    const minusculo = texto.toLowerCase();
    for (const proibida of [...blocoA, ...extras]) {
      // Palavras curtas (null, sync, token) só valem inteiras, para não pegar "Entendi" nem "enviando".
      const regra = proibida.length <= 5 ? new RegExp(`\\b${proibida}\\b`) : null;
      expect(regra ? regra.test(minusculo) : minusculo.includes(proibida), proibida).toBe(false);
    }
    for (const proibida of blocoB) expect(texto.includes(proibida), proibida).toBe(false);
  });

  it('o indicador na tela, em todos os estados, também passa', async () => {
    const { ponte, estado } = criarPonte({
      'envios.naoAceitos': () => ({
        ok: true,
        dados: [
          {
            id: '11111111-1111-4111-8111-111111111111',
            ehVenda: true,
            descricao: 'Venda para Ana',
            feitoEm: '2026-11-12T12:00:00.000Z',
            motivo: 'Esta peça já não estava na sua maleta.',
          },
        ],
      }),
    });
    const { container } = render(<IndicadorDeEnvio ponte={ponte} />);
    const estados: Partial<EstadoDaSincronizacao>[] = [
      {},
      { enviando: true, aguardando: 2, vendasAguardando: 2 },
      { comInternet: false, aguardando: 5, vendasAguardando: 1 },
      { naoConseguiuEnviar: true, aguardando: 1, vendasAguardando: 1 },
      { recusadas: 1 },
    ];
    for (const e of estados) {
      estado(e);
      await screen.findByLabelText(TEXTOS.envio.area);
      const visivel = (container.textContent ?? '').toLowerCase();
      for (const proibida of [...blocoA, ...extras]) {
        const regra = proibida.length <= 5 ? new RegExp(`\\b${proibida}\\b`) : null;
        expect(regra ? regra.test(visivel) : visivel.includes(proibida), proibida).toBe(false);
      }
    }
  });
});

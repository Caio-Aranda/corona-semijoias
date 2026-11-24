// @vitest-environment jsdom
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { esquecerImagens } from '../../componentes/ImagemDoProduto';
import {
  ID_ACERTO,
  ID_ANEL,
  ID_BRINCO,
  abrirPrevia,
  chamadasDe,
  ehUuid,
  ficha,
  montar,
  previaDoServidor,
  resumoDeAcerto,
  ultima,
  type Manipulador,
} from './apoio-de-teste';

beforeEach(() => esquecerImagens());

const resposta = (dados: unknown) => ({ ok: true as const, dados });
const CONTA = 'aaaaaaaa-2fa1-41d3-b9a5-2c3a9a8f6b01';

function servidor(
  previaInicial: unknown = previaDoServidor(),
  extra: Record<string, Manipulador> = {},
) {
  return montar({
    'acertos.listar': () => resposta([resumoDeAcerto()]),
    'acertos.ver': () => resposta(ficha()),
    'acertos.previa': () => resposta(previaInicial),
    'acertos.previaComDecisoes': () => resposta(previaInicial),
    'acertos.fechar': () => ({
      ok: true,
      dados: ficha({ status: 'ACERTADO' }),
      criado: true,
    }),
    ...extra,
  });
}

const botaoFechar = () => screen.getByRole('button', { name: 'Fechar acerto' });
const valorDe = (rotulo: string) =>
  screen.getByText(rotulo, { selector: 'dt' }).nextElementSibling?.textContent ?? '';

async function aberta(m: ReturnType<typeof servidor>) {
  const u = await abrirPrevia(m);
  await screen.findByRole('button', { name: 'Fechar acerto' });
  return u;
}

async function confirmarFechar(u: ReturnType<typeof userEvent.setup>) {
  await u.click(botaoFechar());
  const janela = await screen.findByRole('dialog');
  await u.click(within(janela).getByRole('button', { name: 'Fechar acerto' }));
  return janela;
}

describe('quando dá para fechar', () => {
  it('com a prévia em dia o botão Fechar acerto está ligado', async () => {
    await aberta(servidor());
    expect(botaoFechar()).toBeEnabled();
  });

  it('sobra de peça desliga o botão', async () => {
    await aberta(
      servidor(
        previaDoServidor({
          podeFechar: false,
          sobras: [{ produtoId: ID_ANEL, esperado: 1, contado: 2 }],
        }),
      ),
    );
    expect(botaoFechar()).toBeDisabled();
  });

  it('devolução esperando revisão desliga o botão', async () => {
    await aberta(
      servidor(
        previaDoServidor({
          podeFechar: false,
          revisoesPendentes: [{ tipo: 'DEVOLUCAO', id: CONTA }],
        }),
      ),
    );
    expect(botaoFechar()).toBeDisabled();
  });

  it('enquanto a prévia nova é calculada o botão fica desligado', async () => {
    let liberar: (v: unknown) => void = () => undefined;
    const m = servidor(previaDoServidor(), {
      'acertos.previaComDecisoes': () =>
        new Promise((resolver) => {
          liberar = resolver as (v: unknown) => void;
        }),
    });
    const u = await aberta(m);
    await u.selectOptions(
      screen.getByLabelText('O que fazer com a falta de Anel Solitário'),
      'COBRAR_DEPOIS',
    );
    await waitFor(() => expect(botaoFechar()).toBeDisabled());
    liberar(resposta(previaDoServidor()));
    await waitFor(() => expect(botaoFechar()).toBeEnabled());
  });

  it('"A loja assume" sem motivo desliga o botão', async () => {
    const u = await aberta(servidor());
    await u.selectOptions(
      screen.getByLabelText('O que fazer com a falta de Anel Solitário'),
      'LOJA_ASSUME',
    );
    expect(botaoFechar()).toBeDisabled();
  });

  it('o que ficou devendo sem data avisa e desliga o botão; com data liga e mostra as parcelas', async () => {
    const m = servidor(
      previaDoServidor({ ficouDevendo: '300.00', restanteSemData: true, podeFechar: true }),
      {
        'acertos.previaComDecisoes': (p) => {
          const restante = (p.parametros as { pedido: { pagamento?: { restante?: unknown } } })
            .pedido.pagamento?.restante;
          return resposta(
            restante
              ? previaDoServidor({
                  ficouDevendo: '300.00',
                  restanteSemData: false,
                  parcelasDoRestante: [
                    { numero: 1, vencimento: '2026-12-15', valor: '150.00' },
                    { numero: 2, vencimento: '2027-01-15', valor: '150.00' },
                  ],
                })
              : previaDoServidor({ ficouDevendo: '300.00', restanteSemData: true }),
          );
        },
      },
    );
    const u = await aberta(m);
    expect(
      screen.getByText('Escolha a data de pagamento do que ficou devendo.'),
    ).toBeInTheDocument();
    expect(botaoFechar()).toBeDisabled();
    await u.selectOptions(screen.getByLabelText('Em quantas parcelas'), '2');
    fireEvent.change(screen.getByLabelText('Data da primeira parcela'), {
      target: { value: '2026-12-15' },
    });
    await waitFor(() =>
      expect(screen.getByText(/Parcela 2: R\$ 150,00, vence em 15\/01\/2027/)).toBeInTheDocument(),
    );
    expect(botaoFechar()).toBeEnabled();
    expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
      pedido: { pagamento: { restante: { parcelas: 2, primeiroVencimento: '2026-12-15' } } },
    });
  });
});

describe('o pagamento', () => {
  it('só tem Dinheiro, Transferência (PIX) e Boleto: nada de cheque, cartão nem entrada', async () => {
    await aberta(servidor());
    expect(screen.getByLabelText('Dinheiro')).toBeInTheDocument();
    expect(screen.getByLabelText('Transferência (PIX)')).toBeInTheDocument();
    expect(screen.getByLabelText('Boleto: valor')).toBeInTheDocument();
    // Só a tela do acerto (o menu lateral tem outras telas, como "Entradas e saídas" do estoque).
    const tudo = screen.getByRole('main').textContent ?? '';
    expect(tudo).not.toMatch(/cheque|cart[aã]o|entrada/i);
    expect(screen.queryByRole('option', { name: /cheque|cart[aã]o|entrada/i })).toBeNull();
  });

  it('cada valor digitado vai com a forma e um id próprio (UUID da tela)', async () => {
    const m = servidor();
    const u = await aberta(m);
    await u.type(screen.getByLabelText('Dinheiro'), '50,00');
    await u.type(screen.getByLabelText('Transferência (PIX)'), '30');
    await waitFor(() =>
      expect(
        (
          ultima(m, 'acertos.previaComDecisoes') as {
            pedido: { pagamento: { recebimentos: unknown[] } };
          }
        ).pedido.pagamento.recebimentos,
      ).toHaveLength(2),
    );
    const rec = (
      ultima(m, 'acertos.previaComDecisoes') as {
        pedido: { pagamento: { recebimentos: { id: string; forma: string; valor: string }[] } };
      }
    ).pedido.pagamento.recebimentos;
    expect(rec.map((r) => [r.forma, r.valor])).toEqual([
      ['DINHEIRO', '50.00'],
      ['PIX', '30.00'],
    ]);
    expect(rec.every((r) => ehUuid(r.id))).toBe(true);
    expect(rec[0]?.id).not.toBe(rec[1]?.id);
  });

  it('pagou agora em Guarani: mostra as três moedas e manda só o valor em Guarani (o sistema converte)', async () => {
    const m = servidor(previaDoServidor(), {
      'cotacao.hoje': () =>
        resposta({
          disponivel: true,
          cotacao: { dataCotacao: '2026-11-12', dolar: '5.00000000', guarani: '0.00090000' },
        }),
    });
    const u = await aberta(m);
    await u.selectOptions(screen.getByLabelText('Moeda (Dinheiro)'), 'GUARANI');
    await u.type(screen.getByLabelText('Dinheiro em Guarani'), '100.000');
    // Gs. 100.000 x 0,0009 = R$ 90,00; 90 / 5 = US$ 18,00.
    expect(await screen.findByText('US$ 18,00 · Gs. 100.000')).toBeInTheDocument();
    await waitFor(() =>
      expect(
        (
          ultima(m, 'acertos.previaComDecisoes') as {
            pedido: { pagamento: { recebimentos: unknown[] } };
          }
        ).pedido.pagamento.recebimentos,
      ).toEqual([
        expect.objectContaining({ forma: 'DINHEIRO', moeda: 'GUARANI', valorNaMoeda: '100000' }),
      ]),
    );
    const [rec] = (
      ultima(m, 'acertos.previaComDecisoes') as {
        pedido: { pagamento: { recebimentos: Record<string, unknown>[] } };
      }
    ).pedido.pagamento.recebimentos;
    expect(rec).not.toHaveProperty('valor');
    expect(ehUuid(rec?.id)).toBe(true);
  });

  it('sem cotação, o pagou agora fica só em reais', async () => {
    await aberta(servidor());
    expect(screen.queryByLabelText('Moeda (Dinheiro)')).toBeNull();
    expect(screen.getByLabelText('Dinheiro')).toBeInTheDocument();
  });

  it('boleto pede o vencimento e só então vai ao sistema', async () => {
    const m = servidor();
    const u = await aberta(m);
    await u.type(screen.getByLabelText('Boleto: valor'), '40,00');
    expect(await screen.findByText('Escolha o vencimento do boleto.')).toBeInTheDocument();
    expect(botaoFechar()).toBeDisabled();
    expect(chamadasDe(m, 'acertos.previaComDecisoes')).toHaveLength(0);
    fireEvent.change(screen.getByLabelText('Boleto: vencimento'), {
      target: { value: '2026-12-10' },
    });
    await waitFor(() =>
      expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
        pedido: { pagamento: { boleto: { valor: '40.00', vencimento: '2026-12-10' } } },
      }),
    );
    expect(botaoFechar()).toBeEnabled();
  });

  it('acerto a favor do representante: o padrão é guardar como crédito; "Pagar agora" pede a forma', async () => {
    const m = servidor(previaDoServidor({ aPagarNoAcerto: '-80.00', liquido: '-80.00' }));
    const u = await aberta(m);
    expect(screen.getByText('A loja deve R$ 80,00 ao representante.')).toBeInTheDocument();
    expect(
      screen.getByRole('radio', { name: 'Guardar como crédito para o próximo acerto' }),
    ).toBeChecked();
    await u.click(screen.getByRole('radio', { name: 'Pagar agora' }));
    await u.selectOptions(screen.getByLabelText('Como a loja paga'), 'PIX');
    await waitFor(() =>
      expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
        pedido: { pagamento: { aFavorDoVendedor: 'PAGAR_NA_HORA', formaDoPagamento: 'PIX' } },
      }),
    );
  });

  it('contas antigas do representante podem ser pagas junto, sem entrar no valor do acerto', async () => {
    const m = servidor(
      previaDoServidor({
        contasAbertasDoVendedor: [
          {
            id: CONTA,
            origem: 'ACERTO',
            numero: 1,
            totalDeParcelas: 2,
            vencimento: '2026-11-20',
            valor: '200.00',
            emAberto: '150.00',
            formaPrevista: 'PIX',
          },
        ],
      }),
    );
    const u = await aberta(m);
    expect(screen.getByText(/não entra no valor deste acerto/i)).toBeInTheDocument();
    await u.type(screen.getByLabelText('Pagar agora a parcela 1 de 2'), '100');
    await waitFor(() =>
      expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
        pedido: {
          pagamento: { contasAntigas: [{ contaId: CONTA, forma: 'DINHEIRO', valor: '100.00' }] },
        },
      }),
    );
  });

  it('mostra o que o sistema calculou do pagamento: recebido, boleto e o que ficou devendo', async () => {
    await aberta(
      servidor(
        previaDoServidor({ recebidoNoAcerto: '110.00', boleto: '40.00', ficouDevendo: '17.83' }),
      ),
    );
    expect(valorDe('Recebido agora')).toBe('R$ 110,00');
    expect(valorDe('Boleto')).toBe('R$ 40,00');
    expect(valorDe('Ficou devendo')).toBe('R$ 17,83');
  });
});

describe('fechar o acerto', () => {
  it('pergunta antes, com o resumo, e manda o id, as decisões, o pagamento e o líquido mostrado', async () => {
    const m = servidor();
    const u = await aberta(m);
    await u.click(botaoFechar());
    const janela = await screen.findByRole('dialog');
    expect(
      within(janela).getByText(
        'Fechar o acerto de Ana Representante? Depois de fechado, o acerto não muda mais.',
      ),
    ).toBeInTheDocument();
    expect(chamadasDe(m, 'acertos.fechar')).toHaveLength(0);
    await u.click(within(janela).getByRole('button', { name: 'Fechar acerto' }));
    await waitFor(() => expect(chamadasDe(m, 'acertos.fechar')).toHaveLength(1));
    const p = ultima(m, 'acertos.fechar') as { id: string; pedido: Record<string, unknown> };
    expect(p.id).toBe(ID_ACERTO);
    expect(ehUuid(p.pedido.id)).toBe(true);
    // O líquido é o da última prévia (777,77 de propósito): é só para o servidor conferir.
    expect(p.pedido.liquidoMostrado).toBe('777.77');
    expect(p.pedido).toMatchObject({
      faltas: [{ produtoId: ID_ANEL, decisao: 'COBRAR_NO_ACERTO' }],
      defeitos: [{ produtoId: ID_BRINCO, aprovadas: 0 }],
      cobrancas: [],
      pendenciasAntigas: [],
    });
    // Nunca vai comissão, total nem percentual.
    const chaves = Object.keys(p.pedido);
    for (const proibida of [
      'comissao',
      'liquido',
      'total',
      'percentualComissao',
      'vendidoLiquido',
    ]) {
      expect(chaves).not.toContain(proibida);
    }
    expect(await screen.findByText('Acerto fechado.')).toBeInTheDocument();
  });

  it('o botão desliga ao clicar: dois cliques seguidos não fecham duas vezes', async () => {
    let liberar: (v: unknown) => void = () => undefined;
    const m = servidor(previaDoServidor(), {
      'acertos.fechar': () =>
        new Promise((resolver) => {
          liberar = resolver as (v: unknown) => void;
        }),
    });
    const u = await aberta(m);
    await u.click(botaoFechar());
    const janela = await screen.findByRole('dialog');
    const confirmar = within(janela).getByRole('button', { name: 'Fechar acerto' });
    await u.click(confirmar);
    await u.click(confirmar);
    expect(chamadasDe(m, 'acertos.fechar')).toHaveLength(1);
    liberar({ ok: true, dados: ficha({ status: 'ACERTADO' }), criado: true });
    expect(await screen.findByText('Acerto fechado.')).toBeInTheDocument();
  });

  it('sem internet: diz que o acerto não foi fechado e a nova tentativa usa o mesmo id; resposta 200 mostra a ficha sem erro', async () => {
    let n = 0;
    const m = servidor(previaDoServidor(), {
      'acertos.fechar': () =>
        ++n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: ficha({ status: 'ACERTADO' }), criado: false },
    });
    const u = await aberta(m);
    const janela = await confirmarFechar(u);
    expect(
      await within(janela).findByText('Sem internet. O acerto não foi fechado. Tente de novo.'),
    ).toBeInTheDocument();
    await u.click(within(janela).getByRole('button', { name: 'Fechar acerto' }));
    await waitFor(() => expect(chamadasDe(m, 'acertos.fechar')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'acertos.fechar').map(
      (c) => (c.parametros as { pedido: { id: string } }).pedido.id,
    );
    expect(a).toBe(b);
    expect(await screen.findByText(/Acerto fechado/)).toBeInTheDocument();
    expect(screen.queryByText(/não foi fechado/)).toBeNull();
  });

  it('mudar uma decisão gera um id de fechamento novo', async () => {
    let n = 0;
    const m = servidor(previaDoServidor(), {
      'acertos.fechar': () =>
        ++n === 1
          ? { ok: false, motivo: 'sem-conexao' }
          : { ok: true, dados: ficha({ status: 'ACERTADO' }), criado: true },
    });
    const u = await aberta(m);
    const janela = await confirmarFechar(u);
    await within(janela).findByText(/não foi fechado/);
    await u.click(within(janela).getByRole('button', { name: 'Voltar e conferir' }));
    await u.selectOptions(
      screen.getByLabelText('O que fazer com a falta de Anel Solitário'),
      'COBRAR_DEPOIS',
    );
    await waitFor(() => expect(botaoFechar()).toBeEnabled());
    await confirmarFechar(u);
    await waitFor(() => expect(chamadasDe(m, 'acertos.fechar')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'acertos.fechar').map(
      (c) => (c.parametros as { pedido: { id: string } }).pedido.id,
    );
    expect(a).not.toBe(b);
  });

  it('números que mudaram (409): avisa, mostra a prévia nova e usa outro id na próxima vez', async () => {
    let previas = 0;
    let fechamentos = 0;
    const m = servidor(previaDoServidor(), {
      'acertos.previa': () =>
        resposta(previaDoServidor(++previas === 1 ? {} : { liquido: '999.99' })),
      'acertos.fechar': () =>
        ++fechamentos === 1
          ? {
              ok: false,
              motivo: 'erro',
              status: 409,
              codigo: 'FECHAMENTO_DESATUALIZADO',
              mensagem: 'x',
            }
          : { ok: true, dados: ficha({ status: 'ACERTADO' }), criado: true },
    });
    const u = await aberta(m);
    await confirmarFechar(u);
    expect(
      await screen.findByText('Os números mudaram desde que você abriu. Confira o acerto de novo.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(valorDe('Valor do acerto')).toBe('R$ 999,99'));
    await confirmarFechar(u);
    await waitFor(() => expect(chamadasDe(m, 'acertos.fechar')).toHaveLength(2));
    const [a, b] = chamadasDe(m, 'acertos.fechar').map(
      (c) => (c.parametros as { pedido: { id: string } }).pedido.id,
    );
    expect(a).not.toBe(b);
    expect(
      (ultima(m, 'acertos.fechar') as { pedido: { liquidoMostrado: string } }).pedido
        .liquidoMostrado,
    ).toBe('999.99');
  });

  it.each([
    [
      'PAGAMENTO_DO_ACERTO_PASSA_DO_DEVIDO',
      'O valor pago passa do que o representante deve. Confira os valores.',
    ],
    ['DATA_DO_RESTANTE_OBRIGATORIA', 'Escolha a data de pagamento do que ficou devendo.'],
  ])('erro %s diz o que fazer', async (codigo, texto) => {
    const m = servidor(previaDoServidor(), {
      'acertos.fechar': () => ({ ok: false, motivo: 'erro', status: 409, codigo, mensagem: 'x' }),
    });
    const u = await aberta(m);
    const janela = await confirmarFechar(u);
    expect(await within(janela).findByText(texto)).toBeInTheDocument();
  });

  it('semana fechada: diz que o acerto não pode ser fechado e para falar com o suporte', async () => {
    const m = servidor(previaDoServidor(), {
      'acertos.fechar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'SEMANA_FECHADA',
        mensagem: 'x',
      }),
    });
    const u = await aberta(m);
    const janela = await confirmarFechar(u);
    expect(
      await within(janela).findByText(/A semana deste acerto já está fechada.*suporte/),
    ).toBeInTheDocument();
  });

  it('sobra ou revisão que apareceu na hora de fechar (409) volta à prévia com o aviso', async () => {
    let previas = 0;
    const m = servidor(previaDoServidor(), {
      'acertos.previa': () =>
        resposta(
          previaDoServidor(
            ++previas === 1
              ? {}
              : { podeFechar: false, sobras: [{ produtoId: ID_ANEL, esperado: 1, contado: 2 }] },
          ),
        ),
      'acertos.fechar': () => ({
        ok: false,
        motivo: 'erro',
        status: 409,
        codigo: 'SOBRA_NO_ACERTO',
        mensagem: 'Contaram mais peças do que o esperado.',
      }),
    });
    const u = await aberta(m);
    await confirmarFechar(u);
    expect(
      await screen.findByText(/Sobrou peça: confira as vendas ou registre o ajuste/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => expect(botaoFechar()).toBeDisabled());
  });
});

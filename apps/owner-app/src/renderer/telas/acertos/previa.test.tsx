// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { esquecerImagens } from '../../componentes/ImagemDoProduto';
import {
  HASH,
  ID_ANEL,
  ID_BRINCO,
  ID_DEVOLUCAO,
  ID_PENDENCIA,
  abrirPrevia,
  chamadasDe,
  ficha,
  montar,
  previaDoServidor,
  produtoDaFicha,
  resumoDeAcerto,
  ultima,
  type Manipulador,
} from './apoio-de-teste';

beforeEach(() => esquecerImagens());

const resposta = (dados: unknown) => ({ ok: true as const, dados });

function servidor(
  previaInicial: unknown = previaDoServidor(),
  extra: Record<string, Manipulador> = {},
) {
  return montar({
    'acertos.listar': () => resposta([resumoDeAcerto()]),
    'acertos.ver': () => resposta(ficha()),
    'acertos.previa': () => resposta(previaInicial),
    'acertos.previaComDecisoes': () => resposta(previaInicial),
    ...extra,
  });
}

/** O valor que a tela mostra para um rótulo da lista de totais. */
const valorDe = (rotulo: string) =>
  screen.getByText(rotulo, { selector: 'dt' }).nextElementSibling?.textContent ?? '';

describe('prévia do acerto', () => {
  it('mostra exatamente os números da API, formatados, sem a tela calcular nada', async () => {
    const m = servidor();
    await abrirPrevia(m);
    await screen.findByRole('heading', { name: 'Conferência do acerto', level: 2 });
    expect(valorDe('Vendido')).toBe('R$ 1.000,00');
    expect(valorDe('Descontos')).toBe('R$ 10,00');
    expect(valorDe('Devolvido')).toBe('R$ 20,00');
    expect(valorDe('Vendido depois dos descontos')).toBe('R$ 970,00');
    expect(valorDe('Comissão do vendedor')).toBe('R$ 388,00');
    expect(valorDe('Faltas cobradas')).toBe('R$ 70,00');
    // A API devolveu 777,77 de propósito (não bate com a conta): é o que a tela mostra.
    expect(valorDe('Valor do acerto')).toBe('R$ 777,77');
    expect(valorDe('Crédito do vendedor usado')).toBe('R$ 0,00');
    expect(valorDe('A pagar agora')).toBe('R$ 555,55');
    expect(chamadasDe(m, 'acertos.previa')).toHaveLength(1);
    expect(Object.keys(ultima(m, 'acertos.previa') as object)).toEqual(['id']);
  });

  it('mostra esperado e contado por peça, com foto, e o valor da falta', async () => {
    await abrirPrevia(servidor());
    const tabela = await screen.findByRole('table', { name: 'Peças do acerto' });
    expect(tabela).toHaveTextContent('Anel Solitário');
    expect(tabela).toHaveTextContent('R$ 70,00');
    // O anel tem foto (a imagem carrega); o brinco não tem: mostra o quadro "Sem foto" e nada trava.
    expect((await screen.findAllByText('Sem foto')).length).toBeGreaterThan(0);
  });

  it('bateu a meta: mostra a soma em Guarani, a meta e o percentual que a API mandou', async () => {
    await abrirPrevia(servidor());
    expect(
      await screen.findByText(/Vendido na semana: Gs\. 750\.000 de Gs\. 700\.000 \(meta\)/),
    ).toBeInTheDocument();
    expect(screen.getByText('Bateu a meta: comissão de 40%.')).toBeInTheDocument();
  });

  it('abaixo da meta: mostra o percentual menor que a API mandou', async () => {
    await abrirPrevia(
      servidor(previaDoServidor({ vendidoParaMetaGuarani: 650000, percentualComissao: '30.00' })),
    );
    expect(await screen.findByText(/Gs\. 650\.000 de Gs\. 700\.000/)).toBeInTheDocument();
    expect(screen.getByText('Ainda não bateu a meta: comissão de 30%.')).toBeInTheDocument();
  });

  it('abaixo da meta: mostra quanto falta em Guarani e em Real, como a API mandou', async () => {
    await abrirPrevia(
      servidor(
        previaDoServidor({
          vendidoParaMetaGuarani: 650000,
          percentualComissao: '30.00',
          faltaParaMetaGuarani: 50000,
          faltaParaMetaReais: '45.00',
        }),
      ),
    );
    expect(
      await screen.findByText('Falta para a meta: Gs. 50.000 (R$ 45,00).'),
    ).toBeInTheDocument();
  });

  it('na meta ou acima dela não mostra a linha do que falta', async () => {
    await abrirPrevia(servidor());
    await screen.findByText('Bateu a meta: comissão de 40%.');
    expect(screen.queryByText(/Falta para a meta/)).not.toBeInTheDocument();
  });

  it('prévia sem os campos novos continua abrindo, só sem a linha do que falta', async () => {
    const velha: Record<string, unknown> = {
      ...previaDoServidor({ vendidoParaMetaGuarani: 650000 }),
    };
    delete velha.faltaParaMetaGuarani;
    delete velha.faltaParaMetaReais;
    await abrirPrevia(servidor(velha));
    expect(await screen.findByText(/Gs\. 650\.000 de Gs\. 700\.000/)).toBeInTheDocument();
    expect(screen.queryByText(/Falta para a meta/)).not.toBeInTheDocument();
  });

  it('campos do que falta fora da forma são ignorados, e o resto da prévia aparece', async () => {
    await abrirPrevia(
      servidor(
        previaDoServidor({
          vendidoParaMetaGuarani: 650000,
          faltaParaMetaGuarani: 'muito',
          faltaParaMetaReais: 45,
        }),
      ),
    );
    expect(await screen.findByText(/Gs\. 650\.000 de Gs\. 700\.000/)).toBeInTheDocument();
    expect(screen.queryByText(/Falta para a meta/)).not.toBeInTheDocument();
  });

  it('lista a comissão de cada venda como a API calculou', async () => {
    await abrirPrevia(servidor());
    const tabela = await screen.findByRole('table', { name: 'Comissão por venda' });
    expect(tabela).toHaveTextContent('R$ 100,00');
    expect(tabela).toHaveTextContent('40%');
    expect(tabela).toHaveTextContent('R$ 40,00');
  });

  it('sobra de peça avisa o que fazer', async () => {
    await abrirPrevia(
      servidor(
        previaDoServidor({
          podeFechar: false,
          sobras: [{ produtoId: ID_ANEL, esperado: 3, contado: 4 }],
        }),
      ),
    );
    expect(
      await screen.findByText(/Sobrou peça: confira as vendas ou registre o ajuste/),
    ).toBeInTheDocument();
    expect(screen.getByText(/Anel Solitário: eram 3, foram contadas 4/)).toBeInTheDocument();
  });

  it('devolução esperando revisão avisa e leva à revisão', async () => {
    const u = await abrirPrevia(
      servidor(
        previaDoServidor({
          podeFechar: false,
          revisoesPendentes: [{ tipo: 'DEVOLUCAO', id: ID_DEVOLUCAO }],
        }),
      ),
    );
    expect(await screen.findByText(/Tem devolução esperando sua revisão/)).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Revisar devoluções' }));
    expect(window.location.hash).toBe('#/devolucoes/revisar');
  });

  it('o resultado de uma decisão nova troca os números e, enquanto calcula, diz "Calculando..."', async () => {
    let liberar: (v: unknown) => void = () => undefined;
    const m = servidor(previaDoServidor(), {
      'acertos.previaComDecisoes': () =>
        new Promise((resolver) => {
          liberar = resolver as (v: unknown) => void;
        }),
    });
    const u = await abrirPrevia(m);
    await screen.findByText('Valor do acerto', { selector: 'dt' });
    await u.selectOptions(
      screen.getByLabelText('O que fazer com a falta de Anel Solitário'),
      'COBRAR_DEPOIS',
    );
    expect(await screen.findByText('Calculando...')).toBeInTheDocument();
    liberar(resposta(previaDoServidor({ liquido: '111.11', faltasCobradas: '0.00' })));
    await waitFor(() => expect(valorDe('Valor do acerto')).toBe('R$ 111,11'));
    expect(valorDe('Faltas cobradas')).toBe('R$ 0,00');
    expect(screen.queryByText('Calculando...')).toBeNull();
    // As decisões cobrem todas as peças com falta e todas com defeito (o servidor exige).
    expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
      pedido: {
        faltas: [{ produtoId: ID_ANEL, decisao: 'COBRAR_DEPOIS' }],
        defeitos: [{ produtoId: ID_BRINCO, aprovadas: 0 }],
        cobrancas: [],
        pendenciasAntigas: [],
      },
    });
  });

  it('só vale a prévia da última decisão (a resposta velha é descartada)', async () => {
    const liberar: ((v: unknown) => void)[] = [];
    const m = servidor(previaDoServidor(), {
      'acertos.previaComDecisoes': () =>
        new Promise((resolver) => {
          liberar.push(resolver as (v: unknown) => void);
        }),
    });
    const u = await abrirPrevia(m);
    const falta = await screen.findByLabelText('O que fazer com a falta de Anel Solitário');
    await u.selectOptions(falta, 'COBRAR_DEPOIS');
    await u.selectOptions(falta, 'COBRAR_NO_ACERTO');
    await waitFor(() => expect(liberar).toHaveLength(2));
    liberar[1]?.(resposta(previaDoServidor({ liquido: '222.22' })));
    await waitFor(() => expect(valorDe('Valor do acerto')).toBe('R$ 222,22'));
    liberar[0]?.(resposta(previaDoServidor({ liquido: '111.11' })));
    await new Promise((r) => setTimeout(r, 20));
    expect(valorDe('Valor do acerto')).toBe('R$ 222,22');
  });

  it('"A loja assume" pede o motivo e só chama o servidor com ele', async () => {
    const m = servidor();
    const u = await abrirPrevia(m);
    await u.selectOptions(
      await screen.findByLabelText('O que fazer com a falta de Anel Solitário'),
      'LOJA_ASSUME',
    );
    expect(await screen.findByText('Diga o motivo de a loja assumir.')).toBeInTheDocument();
    expect(chamadasDe(m, 'acertos.previaComDecisoes')).toHaveLength(0);
    await u.type(screen.getByLabelText('Motivo de a loja assumir: Anel Solitário'), 'Peça perdida');
    await waitFor(() => expect(chamadasDe(m, 'acertos.previaComDecisoes')).toHaveLength(1));
    expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
      pedido: {
        faltas: [{ produtoId: ID_ANEL, decisao: 'LOJA_ASSUME', motivo: 'Peça perdida' }],
      },
    });
  });

  it('aprovar peças com defeito vai na decisão', async () => {
    const m = servidor();
    const u = await abrirPrevia(m);
    const campo = await screen.findByLabelText('Aprovar como defeito: Brinco de Argola');
    await u.clear(campo);
    await u.type(campo, '1');
    await waitFor(() =>
      expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
        pedido: { defeitos: [{ produtoId: ID_BRINCO, aprovadas: 1 }] },
      }),
    );
  });

  it('o que não foi aceito e as pendências antigas também pedem decisão', async () => {
    const m = servidor(
      previaDoServidor({
        naoAceitas: [
          {
            tipo: 'DEVOLUCAO_NAO_ACEITA',
            origemTipo: 'DEVOLUCAO',
            origemId: ID_DEVOLUCAO,
            valor: '20.00',
          },
        ],
        pendenciasAntigas: [
          { id: ID_PENDENCIA, tipo: 'FALTA', produtoId: ID_ANEL, quantidade: 1, valor: '70.00' },
        ],
      }),
    );
    const u = await abrirPrevia(m);
    await u.selectOptions(
      await screen.findByLabelText('O que fazer com a devolução não aceita de R$ 20,00'),
      'COBRAR_DEPOIS',
    );
    await waitFor(() => expect(chamadasDe(m, 'acertos.previaComDecisoes')).toHaveLength(1));
    await u.selectOptions(
      screen.getByLabelText('O que fazer com a pendência antiga de Anel Solitário'),
      'COBRAR_NO_ACERTO',
    );
    await waitFor(() => expect(chamadasDe(m, 'acertos.previaComDecisoes')).toHaveLength(2));
    expect(ultima(m, 'acertos.previaComDecisoes')).toMatchObject({
      pedido: {
        cobrancas: [{ origemId: ID_DEVOLUCAO, decisao: 'COBRAR_DEPOIS' }],
        pendenciasAntigas: [{ pendenciaId: ID_PENDENCIA, decisao: 'COBRAR_NO_ACERTO' }],
      },
    });
  });

  it('Dólar e Guarani pela cotação da data da prévia, com a frase "Vale o valor em reais"', async () => {
    const m = servidor(previaDoServidor(), {
      'cotacao.hoje': () =>
        resposta({
          disponivel: true,
          cotacao: { dataCotacao: '2026-11-12', dolar: '5.00000000', guarani: '0.00080000' },
        }),
    });
    await abrirPrevia(m);
    expect(
      await screen.findByText(
        /Valores em Dólar e Guarani pela cotação de 12\/11\/2026\. Vale o valor em reais\./,
      ),
    ).toBeInTheDocument();
    expect(valorDe('Vendido')).toContain('US$ 200,00');
    expect(valorDe('Vendido')).toContain('Gs.');
  });

  it('sem a cotação da data da prévia, só Real e o aviso', async () => {
    const m = servidor(previaDoServidor(), {
      'cotacao.hoje': () =>
        resposta({
          disponivel: true,
          cotacao: { dataCotacao: '2026-11-13', dolar: '5.00000000', guarani: '0.00080000' },
        }),
    });
    await abrirPrevia(m);
    expect(
      await screen.findByText(/Sem cotação: os valores aparecem só em reais/),
    ).toBeInTheDocument();
    expect(valorDe('Vendido')).toBe('R$ 1.000,00');
  });

  it('resposta fora da forma vira aviso simples, nunca número inventado', async () => {
    await abrirPrevia(servidor({ itens: [], liquido: 'abc' }));
    expect(
      await screen.findByText('Não deu para ler o acerto. Tente de novo.'),
    ).toBeInTheDocument();
    expect(screen.queryByText('Valor do acerto', { selector: 'dt' })).toBeNull();
  });

  it('o produto da prévia aparece pelo nome e código da API', async () => {
    const m = servidor(
      previaDoServidor({
        itens: [
          {
            produto: produtoDaFicha(ID_ANEL, 'Anel Solitário', '0012', HASH),
            remessaId: ID_PENDENCIA,
            esperado: 1,
            contadoBoa: 1,
            contadoDefeito: 0,
            falta: 0,
            valorDaFalta: '0.00',
          },
        ],
        faltas: [],
        defeitos: [],
      }),
    );
    await abrirPrevia(m);
    expect(await screen.findByText(/Anel Solitário \(0012\)/)).toBeInTheDocument();
  });
});

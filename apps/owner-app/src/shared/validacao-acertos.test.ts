import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const OUTRO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const TERCEIRO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';

describe('conferência dos dados dos acertos', () => {
  it('iniciar leva só o id da tela, o representante e (se houver) a semana', () => {
    expect(conferirParametros('acertos.iniciar', { id: ID, vendedorId: OUTRO })).not.toBeNull();
    expect(
      conferirParametros('acertos.iniciar', { id: ID, vendedorId: OUTRO, semanaId: TERCEIRO }),
    ).not.toBeNull();
    expect(
      conferirParametros('acertos.iniciar', { id: ID, vendedorId: OUTRO, semanaId: '' }),
    ).not.toBeNull();
    for (const ruim of [
      { id: 'x', vendedorId: OUTRO },
      { id: ID },
      { id: ID, vendedorId: OUTRO, semanaId: 'x' },
      { id: ID, vendedorId: OUTRO, comissao: 10 },
    ]) {
      expect(conferirParametros('acertos.iniciar', ruim)).toBeNull();
    }
  });

  it('a lista aceita só representante, semana e as três situações', () => {
    expect(conferirParametros('acertos.listar', {})).not.toBeNull();
    expect(
      conferirParametros('acertos.listar', { vendedorId: ID, situacao: 'EM_ACERTO' }),
    ).not.toBeNull();
    expect(conferirParametros('acertos.listar', { situacao: 'ABERTO' })).toBeNull();
    expect(conferirParametros('acertos.listar', { vendedorId: 'x' })).toBeNull();
  });

  it('a leitura pede peça, estado boa ou com defeito e quantidade inteira diferente de zero', () => {
    const base = { id: ID, leituraId: OUTRO, produtoId: TERCEIRO, estadoPeca: 'BOA' };
    expect(conferirParametros('acertos.ler', { ...base, quantidade: 1 })).not.toBeNull();
    expect(conferirParametros('acertos.ler', { ...base, quantidade: -1 })).not.toBeNull();
    expect(
      conferirParametros('acertos.ler', { ...base, estadoPeca: 'DEFEITO', quantidade: 2 }),
    ).not.toBeNull();
    for (const quantidade of [0, 1.5, '1', 100_001, -100_001]) {
      expect(conferirParametros('acertos.ler', { ...base, quantidade })).toBeNull();
    }
    expect(
      conferirParametros('acertos.ler', { ...base, estadoPeca: 'RUIM', quantidade: 1 }),
    ).toBeNull();
    expect(conferirParametros('acertos.ler', { ...base, quantidade: 1, extra: 1 })).toBeNull();
  });

  it('desistir e resolver pedem motivo e o id do comando', () => {
    expect(
      conferirParametros('acertos.desistir', {
        id: ID,
        comandoId: OUTRO,
        motivo: 'Mudou de ideia',
      }),
    ).not.toBeNull();
    expect(
      conferirParametros('acertos.desistir', { id: ID, comandoId: OUTRO, motivo: '  ' }),
    ).toBeNull();
    expect(
      conferirParametros('pendencias.resolver', {
        id: ID,
        comandoId: OUTRO,
        acao: 'LOJA_ASSUME',
        motivo: 'Peça perdida',
      }),
    ).not.toBeNull();
    expect(
      conferirParametros('pendencias.resolver', {
        id: ID,
        comandoId: OUTRO,
        acao: 'COBRAR',
        motivo: 'x',
      }),
    ).toBeNull();
    expect(
      conferirParametros('pendencias.resolver', { id: ID, comandoId: OUTRO, acao: 'ENCONTRADA' }),
    ).toBeNull();
  });

  describe('prévia e fechamento', () => {
    const pedido = {
      id: OUTRO,
      faltas: [{ produtoId: TERCEIRO, decisao: 'COBRAR_NO_ACERTO' }],
      defeitos: [{ produtoId: TERCEIRO, aprovadas: 1 }],
      cobrancas: [{ origemId: ID, decisao: 'LOJA_ASSUME', motivo: 'Cliente de confiança' }],
      pendenciasAntigas: [{ pendenciaId: ID, decisao: 'MANTER' }],
      liquidoMostrado: '120.50',
      pagamento: {
        recebimentos: [
          { id: ID, forma: 'DINHEIRO', valor: '50.00' },
          { id: OUTRO, forma: 'PIX', valor: '30.00' },
        ],
        boleto: { valor: '10.00', vencimento: '2026-12-10' },
        restante: { parcelas: 3, primeiroVencimento: '2026-12-15' },
        contasAntigas: [{ id: TERCEIRO, contaId: ID, forma: 'PIX', valor: '5.00' }],
      },
    };

    it('aceita o pedido completo nas duas operações', () => {
      expect(conferirParametros('acertos.fechar', { id: ID, pedido })).not.toBeNull();
      const semLiquido = { ...pedido, liquidoMostrado: undefined };
      expect(
        conferirParametros('acertos.previaComDecisoes', { id: ID, pedido: semLiquido }),
      ).not.toBeNull();
    });

    it('a ponte só repassa as chaves conhecidas: total, comissão e percentual são recusados', () => {
      for (const extra of [
        { comissao: '10.00' },
        { liquido: '100.00' },
        { total: '1.00' },
        { percentualComissao: '40' },
        { vendidoLiquido: '1.00' },
      ]) {
        expect(
          conferirParametros('acertos.fechar', { id: ID, pedido: { ...pedido, ...extra } }),
        ).toBeNull();
      }
    });

    it('forma de pagamento só Dinheiro e Transferência (PIX); cheque, cartão e entrada não existem', () => {
      for (const forma of ['CHEQUE', 'CARTAO', 'ENTRADA', 'BOLETO']) {
        const ruim = {
          ...pedido,
          pagamento: { recebimentos: [{ id: ID, forma, valor: '1.00' }] },
        };
        expect(conferirParametros('acertos.fechar', { id: ID, pedido: ruim })).toBeNull();
      }
      expect(
        conferirParametros('acertos.fechar', {
          id: ID,
          pedido: {
            ...pedido,
            pagamento: { aFavorDoVendedor: 'PAGAR_NA_HORA', formaDoPagamento: 'CHEQUE' },
          },
        }),
      ).toBeNull();
      expect(
        conferirParametros('acertos.fechar', {
          id: ID,
          pedido: { ...pedido, pagamento: { aFavorDoVendedor: 'CREDITO' } },
        }),
      ).not.toBeNull();
    });

    it('parcelas de 1 a 24, datas AAAA-MM-DD, valores em texto com ponto', () => {
      const com = (pagamento: unknown) =>
        conferirParametros('acertos.fechar', { id: ID, pedido: { ...pedido, pagamento } });
      expect(com({ restante: { parcelas: 25, primeiroVencimento: '2026-12-15' } })).toBeNull();
      expect(com({ restante: { parcelas: 0, primeiroVencimento: '2026-12-15' } })).toBeNull();
      expect(com({ restante: { parcelas: 2, primeiroVencimento: '15/12/2026' } })).toBeNull();
      expect(com({ restante: { parcelas: 24, primeiroVencimento: '2026-12-15' } })).not.toBeNull();
      expect(com({ recebimentos: [{ id: ID, forma: 'PIX', valor: 50 }] })).toBeNull();
      expect(com({ recebimentos: [{ id: ID, forma: 'PIX', valor: '50,00' }] })).toBeNull();
      expect(com({ recebimentos: [{ id: ID, forma: 'PIX', valor: '0.00' }] })).toBeNull();
      // Em Dólar ou Guarani vai só o valor na moeda (o sistema converte pela cotação do acerto).
      expect(
        com({ recebimentos: [{ id: ID, forma: 'DINHEIRO', moeda: 'GUARANI', valorNaMoeda: '500000' }] }),
      ).not.toBeNull();
      expect(
        com({ recebimentos: [{ id: ID, forma: 'PIX', moeda: 'DOLAR', valorNaMoeda: '20.50' }] }),
      ).not.toBeNull();
      expect(
        com({
          recebimentos: [
            { id: ID, forma: 'DINHEIRO', moeda: 'GUARANI', valorNaMoeda: '500000', valor: '447.50' },
          ],
        }),
      ).toBeNull();
      expect(
        com({ recebimentos: [{ id: ID, forma: 'DINHEIRO', moeda: 'GUARANI', valorNaMoeda: '1.5' }] }),
      ).toBeNull();
      expect(com({ recebimentos: [{ id: ID, forma: 'DINHEIRO', moeda: 'GUARANI' }] })).toBeNull();
    });

    it('decisão fora da lista é recusada e "a loja assume" exige motivo', () => {
      const com = (parte: object) =>
        conferirParametros('acertos.fechar', { id: ID, pedido: { ...pedido, ...parte } });
      expect(com({ faltas: [{ produtoId: TERCEIRO, decisao: 'IGNORAR' }] })).toBeNull();
      expect(com({ faltas: [{ produtoId: TERCEIRO, decisao: 'LOJA_ASSUME' }] })).toBeNull();
      expect(
        com({ faltas: [{ produtoId: TERCEIRO, decisao: 'LOJA_ASSUME', motivo: 'Perdeu' }] }),
      ).not.toBeNull();
      expect(com({ defeitos: [{ produtoId: TERCEIRO, aprovadas: -1 }] })).toBeNull();
      expect(com({ pendenciasAntigas: [{ pendenciaId: ID, decisao: 'LOJA_ASSUME' }] })).toBeNull();
    });
  });

  it('comissão: meta inteira e percentuais de 0 a 100, abaixo da meta não maior que na meta', () => {
    const ok = { metaGuarani: 700000, percentualAbaixoDaMeta: 30, percentualNaMeta: 40 };
    expect(conferirParametros('comissao.alterar', ok)).not.toBeNull();
    expect(conferirParametros('comissao.ler', {})).not.toBeNull();
    for (const ruim of [
      { ...ok, percentualAbaixoDaMeta: 50 },
      { ...ok, percentualNaMeta: 101 },
      { ...ok, percentualAbaixoDaMeta: -1 },
      { ...ok, metaGuarani: 0.5 },
      { ...ok, metaGuarani: -1 },
      { ...ok, metaGuarani: '700000' },
    ]) {
      expect(conferirParametros('comissao.alterar', ruim)).toBeNull();
    }
  });
});

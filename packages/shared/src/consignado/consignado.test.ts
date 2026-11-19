import { describe, expect, it } from 'vitest';
import exemplos from '../../exemplos/consignado-acerto.json';
import { aplicarPagamento } from './aplicacao-de-pagamento';
import { calcularAcerto, comissaoValida } from './calculo-do-acerto';
import { exigirCentavos, textoDeCentavos } from './dinheiro-do-consignado';
import { situacaoDaNota } from './situacao-da-nota';

/** Os mesmos números que o servidor lê (`CalculoDoAcertoDaNotaTest`): o arquivo é um só. */
describe('cálculo do acerto da nota (casos do arquivo de exemplos)', () => {
  it('nota 0000064-21, número por número', () => {
    const n = exemplos.notaDeExemplo;
    const pago = Object.values(n.pago).reduce((s, v) => s + exigirCentavos(v), 0n);
    const r = calcularAcerto(
      n.linhas,
      n.comissaoPercentual,
      n.pendenciasAnteriores,
      textoDeCentavos(pago),
    );
    expect(r.itensDx).toBe(n.esperado.itensDx);
    expect(r.itensDv).toBe(n.esperado.itensDv);
    expect(r.itensVd).toBe(n.esperado.itensVd);
    expect(r.valorDx).toBe(n.esperado.valorDx);
    expect(r.valorDv).toBe(n.esperado.valorDv);
    expect(r.valorVd).toBe(n.esperado.valorVd);
    expect(r.vendaPercentual).toBe(n.esperado.vendaPercentual);
    expect(r.comissao).toBe(n.esperado.comissao);
    expect(r.bruto).toBe(n.esperado.bruto);
    expect(r.pendenciasAnteriores).toBe(n.pendenciasAnteriores);
    expect(r.totalAReceber).toBe(n.esperado.totalAReceber);
    expect(r.totalPago).toBe(n.esperado.totalPago);
    expect(r.ficouDevendo).toBe(n.esperado.ficouDevendo);
  });

  it.each(exemplos.casosDeComissao)('comissão: $nome', (c) => {
    const r = calcularAcerto(
      [{ dx: 1, dv: 0, precoUnitario: c.valorVd }],
      c.percentual,
      '0.00',
      '0.00',
    );
    expect(r.comissao).toBe(c.comissao);
    expect(r.bruto).toBe(c.bruto);
  });

  it.each(exemplos.comissoesRecusadas)('comissão %s é recusada', (p) => {
    expect(comissaoValida(p)).toBe(false);
    expect(() =>
      calcularAcerto([{ dx: 1, dv: 0, precoUnitario: '10.00' }], p, '0.00', '0.00'),
    ).toThrow();
  });

  it('DV maior que DX é recusado e "ficou devendo" nunca é negativo', () => {
    expect(() =>
      calcularAcerto([{ dx: 1, dv: 2, precoUnitario: '10.00' }], '30', '0.00', '0.00'),
    ).toThrow();
    const r = calcularAcerto([{ dx: 1, dv: 0, precoUnitario: '10.00' }], '30', '0.00', '50.00');
    expect(r.ficouDevendo).toBe('0.00');
  });

  it('venda % com DX zero mostra 0', () => {
    const r = calcularAcerto([], '30', '0.00', '0.00');
    expect(r.vendaPercentual).toBe(exemplos.vendaPercentualComDxZero.vendaPercentual);
  });
});

describe('aplicação do pagamento (mais antigo primeiro)', () => {
  it('três momentos: 400/300, 500+100/550, pagamento de 50', () => {
    const t = exemplos.tresMomentos;
    // Semana 1: bruto 400, pago 300 → deve 100.
    let a = aplicarPagamento([{ id: 'n1', saldo: t.semana1.bruto }], t.semana1.pago);
    expect(a.sobra).toBe('0.00');
    expect(exigirCentavos(t.semana1.bruto) - exigirCentavos(a.aplicacoes[0]?.valor ?? '0')).toBe(
      exigirCentavos(t.semana1.deviaDepois),
    );
    // Semana 2: o débito 1 (100) e o novo (500); pago 550.
    a = aplicarPagamento(
      [
        { id: 'n1', saldo: t.semana2.pendenciasAnteriores },
        { id: 'n2', saldo: t.semana2.bruto },
      ],
      t.semana2.pago,
    );
    const d1 =
      exigirCentavos(t.semana2.pendenciasAnteriores) -
      exigirCentavos(a.aplicacoes[0]?.valor ?? '0');
    const d2 = exigirCentavos(t.semana2.bruto) - exigirCentavos(a.aplicacoes[1]?.valor ?? '0');
    expect(textoDeCentavos(d1)).toBe(t.semana2.debito1DepoisDoPagamento);
    expect(textoDeCentavos(d2)).toBe(t.semana2.debito2DepoisDoPagamento);
    // Pagamento lançado de 50 → deve 0.
    a = aplicarPagamento(
      [{ id: 'n2', saldo: t.semana2.debito2DepoisDoPagamento }],
      t.pagamentoLancado.valor,
    );
    expect(
      textoDeCentavos(
        exigirCentavos(t.semana2.debito2DepoisDoPagamento) -
          exigirCentavos(a.aplicacoes[0]?.valor ?? '0'),
      ),
    ).toBe(t.pagamentoLancado.deviaDepois);
  });

  it('três débitos com pagamento parcial', () => {
    const t = exemplos.tresDebitos;
    const r = aplicarPagamento(
      t.saldos.map((saldo, i) => ({ id: `d${String(i)}`, saldo })),
      t.pago,
    );
    expect(r.aplicacoes.map((x) => x.valor)).toEqual(t.aplicacoes);
    expect(r.sobra).toBe(t.sobra);
  });

  it('pagamento acima do devido deixa sobra "a aplicar"', () => {
    const t = exemplos.pagamentoAcimaDoDevido;
    const r = aplicarPagamento(
      t.saldos.map((saldo, i) => ({ id: `d${String(i)}`, saldo })),
      t.pago,
    );
    expect(r.aplicacoes.map((x) => x.valor)).toEqual(t.aplicacoes);
    expect(r.sobra).toBe(t.sobra);
  });

  it('valor zero é recusado', () => {
    expect(() => aplicarPagamento([{ id: 'a', saldo: '10.00' }], '0.00')).toThrow();
  });
});

describe('situação da nota', () => {
  it('as quatro situações e as passagens', () => {
    expect(situacaoDaNota(false, false, null)).toBe('EM_ABERTO');
    expect(situacaoDaNota(false, true, null)).toBe('REMARCADO');
    expect(situacaoDaNota(true, false, '345.10')).toBe('PENDENTE');
    expect(situacaoDaNota(true, false, '0.00')).toBe('FECHADO');
    // Estorno do acerto: sem acerto ativo volta a Em aberto (ou Remarcado).
    expect(situacaoDaNota(false, false, '10.00')).toBe('EM_ABERTO');
  });
});

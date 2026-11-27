import { describe, expect, it } from 'vitest';
import { linhaDaMeta } from './comissao-pela-meta';

const PARAMETROS = { metaEmGuarani: '700000', percentualAbaixo: '30', percentualNaMeta: '40' };

describe('linha da meta do acerto (comprovante e relatórios)', () => {
  it('bateu a meta: vendido, meta e comissão de 40%', () => {
    expect(linhaDaMeta('812400', '40.00', PARAMETROS)).toEqual({
      vendidoGuarani: '812400',
      metaGuarani: '700000',
      percentual: '40',
      bateu: true,
      faltouGuarani: null,
    });
  });

  it('na meta exata conta como bateu', () => {
    expect(linhaDaMeta('700000', '40', PARAMETROS)?.bateu).toBe(true);
  });

  it('um guarani abaixo não bateu e diz quanto faltou', () => {
    expect(linhaDaMeta('699999', '30.00', PARAMETROS)).toEqual({
      vendidoGuarani: '699999',
      metaGuarani: '700000',
      percentual: '30',
      bateu: false,
      faltouGuarani: '1',
    });
  });

  it('sem cotação vale a porcentagem de abaixo da meta, sem valor vendido nem quanto faltou', () => {
    expect(linhaDaMeta(null, '30.00', PARAMETROS)).toEqual({
      vendidoGuarani: null,
      metaGuarani: '700000',
      percentual: '30',
      bateu: false,
      faltouGuarani: null,
    });
  });

  it('sem linha quando a porcentagem gravada não é a da meta de hoje (meta mudou depois)', () => {
    expect(linhaDaMeta('812400', '30.00', PARAMETROS)).toBeNull();
    expect(linhaDaMeta('100000', '40.00', PARAMETROS)).toBeNull();
  });

  it('sem linha com parâmetros inválidos', () => {
    expect(linhaDaMeta('812400', '40', { ...PARAMETROS, metaEmGuarani: '0' })).toBeNull();
  });
});

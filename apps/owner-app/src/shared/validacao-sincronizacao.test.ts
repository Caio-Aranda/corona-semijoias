import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';

describe('conferência dos dados de computadores e envios', () => {
  it('as listas não aceitam dado nenhum', () => {
    for (const op of [
      'sincronizacao.envios',
      'sincronizacao.quarentena',
      'sincronizacao.valoresSubstituidos',
    ] as const) {
      expect(conferirParametros(op, {})).toEqual({ operacao: op });
      expect(conferirParametros(op, { id: ID })).toBeNull();
    }
  });

  it('computadores do representante, trocar, tentar de novo e aprovar pedem só um id válido', () => {
    expect(conferirParametros('sincronizacao.aparelhos', { vendedorId: ID })).not.toBeNull();
    expect(conferirParametros('sincronizacao.aparelhos', { vendedorId: 'x' })).toBeNull();
    for (const op of [
      'sincronizacao.liberarAparelho',
      'sincronizacao.tentarDeNovo',
      'sincronizacao.aprovarQuarentena',
    ] as const) {
      expect(conferirParametros(op, { id: ID })).toEqual({ operacao: op, id: ID });
      expect(conferirParametros(op, { id: 'x' })).toBeNull();
      expect(conferirParametros(op, { id: ID, outro: 1 })).toBeNull();
    }
  });

  it('bloquear aceita motivo opcional de até 500 letras', () => {
    expect(conferirParametros('sincronizacao.bloquearAparelho', { id: ID })).toEqual({
      operacao: 'sincronizacao.bloquearAparelho',
      id: ID,
    });
    expect(
      conferirParametros('sincronizacao.bloquearAparelho', { id: ID, motivo: 'Roubado' }),
    ).toMatchObject({ motivo: 'Roubado' });
    expect(
      conferirParametros('sincronizacao.bloquearAparelho', { id: ID, motivo: 'x'.repeat(501) }),
    ).toBeNull();
  });

  it('descartar exige motivo escrito', () => {
    for (const op of [
      'sincronizacao.descartarEnvio',
      'sincronizacao.descartarQuarentena',
    ] as const) {
      expect(conferirParametros(op, { id: ID, motivo: 'Duplicado' })).toMatchObject({ id: ID });
      expect(conferirParametros(op, { id: ID, motivo: '   ' })).toBeNull();
      expect(conferirParametros(op, { id: ID })).toBeNull();
      expect(conferirParametros(op, { id: ID, motivo: 'x'.repeat(501) })).toBeNull();
    }
  });
});

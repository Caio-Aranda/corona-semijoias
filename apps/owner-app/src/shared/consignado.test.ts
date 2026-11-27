import { describe, expect, it } from 'vitest';
import { validarChamada } from './validacao';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const OUTRO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';

describe('conferência dos dados do consignado (a dona só lê e bipa o fechamento)', () => {
  it('telas.ler não leva dados', () => {
    expect(conferirParametros('telas.ler', {})).not.toBeNull();
    expect(conferirParametros('telas.ler', { x: 1 })).toBeNull();
  });

  it('as operações de um registro aceitam só o id, em formato de UUID', () => {
    for (const op of [
      'fechamento.iniciar',
      'fechamento.ler',
      'fechamento.concluir',
      'consignado.semanas',
      'consignado.semana',
      'consignado.nota',
      'consignado.cliente',
      'consignado.alertaVisto',
    ] as const) {
      expect(conferirParametros(op, { id: ID })).toEqual({ operacao: op, id: ID });
      expect(conferirParametros(op, { id: 'x' })).toBeNull();
      expect(conferirParametros(op, { id: ID, extra: 1 })).toBeNull();
      expect(conferirParametros(op, {})).toBeNull();
    }
  });

  it('as leituras do fechamento: um id novo por leitura, peça em UUID, quantidade inteira de 1 em diante', () => {
    const boa = { id: ID, leituras: [{ id: OUTRO, produtoId: ID, quantidade: 3 }] };
    expect(conferirParametros('fechamento.leituras', boa)).not.toBeNull();
    for (const ruim of [
      { id: ID, leituras: [] },
      { id: ID },
      { id: ID, leituras: [{ id: OUTRO, produtoId: ID, quantidade: 0 }] },
      { id: ID, leituras: [{ id: OUTRO, produtoId: ID, quantidade: 1.5 }] },
      { id: ID, leituras: [{ id: OUTRO, produtoId: ID, quantidade: 100_001 }] },
      { id: ID, leituras: [{ id: 'x', produtoId: ID, quantidade: 1 }] },
      { id: ID, leituras: [{ id: OUTRO, produtoId: 'x', quantidade: 1 }] },
      { id: ID, leituras: [{ id: OUTRO, produtoId: ID, quantidade: 1, custo: 9 }] },
      { id: ID, leituras: [{ id: OUTRO, produtoId: ID, quantidade: 1 }], motivo: 'x' },
    ]) {
      expect(conferirParametros('fechamento.leituras', ruim)).toBeNull();
    }
    const muitas = Array.from({ length: 201 }, () => ({ id: OUTRO, produtoId: ID, quantidade: 1 }));
    expect(conferirParametros('fechamento.leituras', { id: ID, leituras: muitas })).toBeNull();
  });

  it('o filtro das notas aceita só os campos conhecidos e a situação das notas', () => {
    expect(conferirParametros('consignado.notas', {})).toEqual({
      operacao: 'consignado.notas',
      filtros: {},
    });
    expect(
      conferirParametros('consignado.notas', {
        vendedorId: ID,
        semanaId: OUTRO,
        situacao: 'PENDENTE',
        clienteId: ID,
        texto: 'Maria',
      }),
    ).not.toBeNull();
    for (const ruim of [
      { situacao: 'PAGO' },
      { vendedorId: 'x' },
      { texto: 'a'.repeat(101) },
      { outro: 1 },
    ]) {
      expect(conferirParametros('consignado.notas', ruim)).toBeNull();
    }
  });

  it('a foto do cliente só aceita os três tipos que o servidor conhece', () => {
    for (const tipo of ['cliente', 'documento-frente', 'documento-verso']) {
      expect(conferirParametros('consignado.fotoDoCliente', { id: ID, tipo })).not.toBeNull();
    }
    expect(conferirParametros('consignado.fotoDoCliente', { id: ID, tipo: '../x' })).toBeNull();
    expect(conferirParametros('consignado.fotoDoCliente', { id: ID })).toBeNull();
  });

  it('os avisos aceitam só "só não vistos"', () => {
    expect(conferirParametros('consignado.alertas', { soNaoVistos: true })).toEqual({
      operacao: 'consignado.alertas',
      soNaoVistos: true,
    });
    expect(conferirParametros('consignado.alertas', {})).toEqual({
      operacao: 'consignado.alertas',
      soNaoVistos: false,
    });
    expect(conferirParametros('consignado.alertas', { soNaoVistos: 'sim' })).toBeNull();
  });

  it('o fechamento da maleta como relatório só existe para uma maleta', () => {
    expect(
      validarChamada({
        operacao: 'relatorios.cadastros',
        parametros: { relatorio: 'maleta-fechamento', remessaId: ID },
      }),
    ).not.toBeNull();
    expect(
      validarChamada({
        operacao: 'relatorios.cadastros',
        parametros: { relatorio: 'maleta-fechamento' },
      }),
    ).toBeNull();
  });

  it('a configuração do consignado: lê sem dados e só grava valores dentro dos limites', () => {
    expect(conferirParametros('consignado.configuracao', {})).not.toBeNull();
    expect(conferirParametros('consignado.configuracao', { x: 1 })).toBeNull();
    const ok = {
      metaGuarani: 700000,
      percentualAbaixoDaMeta: 30,
      percentualNaMeta: 40,
      diasDoRetorno: 45,
    };
    expect(conferirParametros('consignado.configuracaoAlterar', ok)).toEqual({
      operacao: 'consignado.configuracaoAlterar',
      corpo: ok,
    });
    for (const ruim of [
      { ...ok, metaGuarani: 0 },
      { ...ok, metaGuarani: 1_000_000_001 },
      { ...ok, percentualNaMeta: 41 },
      { ...ok, percentualAbaixoDaMeta: 41 },
      { ...ok, percentualAbaixoDaMeta: 35, percentualNaMeta: 30 },
      { ...ok, diasDoRetorno: 0 },
      { ...ok, diasDoRetorno: 366 },
      { ...ok, diasDoRetorno: 1.5 },
      { ...ok, extra: 1 },
    ]) {
      expect(conferirParametros('consignado.configuracaoAlterar', ruim)).toBeNull();
    }
  });
});

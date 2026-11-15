import { describe, expect, it } from 'vitest';
import { conferirParametros } from './validacao-parametros';

const ID = '8f14e45f-ceea-4672-a1b9-3a1a0f7c4d11';
const OUTRO = '9a14e45f-ceea-4672-a1b9-3a1a0f7c4d99';
const TERCEIRO = '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22';

describe('conferência dos dados do financeiro', () => {
  it('o extrato aceita só datas AAAA-MM-DD', () => {
    expect(conferirParametros('caixa.extrato', {})).not.toBeNull();
    expect(
      conferirParametros('caixa.extrato', { de: '2026-11-01', ate: '2026-11-15' }),
    ).not.toBeNull();
    expect(conferirParametros('caixa.extrato', { de: '01/11/2026' })).toBeNull();
    expect(conferirParametros('caixa.extrato', { saldo: '10.00' })).toBeNull();
  });

  it('lançar no caixa pede id, tipo, forma, categoria, data, descrição e valor em texto com ponto', () => {
    const ok = {
      id: ID,
      tipo: 'SAIDA',
      forma: 'PIX',
      categoriaId: OUTRO,
      data: '2026-11-10',
      descricao: ' Aluguel ',
      valor: '150.50',
    };
    expect(conferirParametros('caixa.lancar', ok)).toEqual({
      operacao: 'caixa.lancar',
      corpo: { ...ok, descricao: 'Aluguel' },
    });
    for (const ruim of [
      { ...ok, forma: 'CHEQUE' },
      { ...ok, forma: 'CARTAO' },
      { ...ok, tipo: 'ENTRADA_ACERTO' },
      { ...ok, valor: '0.00' },
      { ...ok, valor: '1,50' },
      { ...ok, valor: '-5.00' },
      { ...ok, descricao: '   ' },
      { ...ok, descricao: 'x'.repeat(201) },
      { ...ok, data: '10/11/2026' },
      { ...ok, id: 'abc' },
      { ...ok, saldoDepois: '1.00' },
    ]) {
      expect(conferirParametros('caixa.lancar', ruim)).toBeNull();
    }
  });

  it('estornar lançamento, pagamento ou recebimento exige o id do comando e o motivo', () => {
    expect(
      conferirParametros('caixa.estornar', { id: ID, comandoId: OUTRO, motivo: ' Errei ' }),
    ).toEqual({
      operacao: 'caixa.estornar',
      id: ID,
      corpo: { id: OUTRO, motivo: 'Errei' },
    });
    expect(
      conferirParametros('caixa.estornar', { id: ID, comandoId: OUTRO, motivo: '' }),
    ).toBeNull();
    expect(conferirParametros('caixa.estornar', { id: ID, motivo: 'x' })).toBeNull();
    expect(
      conferirParametros('contasAPagar.estornar', { baixaId: ID, comandoId: OUTRO, motivo: 'x' }),
    ).toEqual({
      operacao: 'contasAPagar.estornar',
      id: ID,
      corpo: { id: OUTRO, motivo: 'x' },
    });
    expect(
      conferirParametros('recebimentos.estornar', {
        id: ID,
        comandoId: OUTRO,
        motivo: 'x',
        vencimento: '2026-12-01',
      }),
    ).toEqual({
      operacao: 'recebimentos.estornar',
      id: ID,
      corpo: { id: OUTRO, motivo: 'x', vencimento: '2026-12-01' },
    });
    expect(
      conferirParametros('recebimentos.estornar', {
        id: ID,
        comandoId: OUTRO,
        motivo: 'x',
        vencimento: 'amanhã',
      }),
    ).toBeNull();
  });

  it('categorias: criar leva id, nome e tipo; renomear só o nome', () => {
    expect(conferirParametros('caixa.categorias', {})).not.toBeNull();
    expect(
      conferirParametros('caixa.criarCategoria', { id: ID, nome: ' Aluguel ', tipo: 'SAIDA' }),
    ).toEqual({
      operacao: 'caixa.criarCategoria',
      corpo: { id: ID, nome: 'Aluguel', tipo: 'SAIDA' },
    });
    expect(
      conferirParametros('caixa.criarCategoria', { id: ID, nome: '', tipo: 'SAIDA' }),
    ).toBeNull();
    expect(
      conferirParametros('caixa.criarCategoria', { id: ID, nome: 'x', tipo: 'OUTRO' }),
    ).toBeNull();
    expect(conferirParametros('caixa.renomearCategoria', { id: ID, nome: 'Luz' })).toEqual({
      operacao: 'caixa.renomearCategoria',
      id: ID,
      corpo: { nome: 'Luz' },
    });
    expect(
      conferirParametros('caixa.renomearCategoria', { id: ID, nome: 'Luz', tipo: 'ENTRADA' }),
    ).toBeNull();
    expect(conferirParametros('caixa.inativarCategoria', { id: ID })).not.toBeNull();
    expect(conferirParametros('caixa.reativarCategoria', { id: 'x' })).toBeNull();
  });

  it('contas a pagar: filtros conhecidos e página', () => {
    expect(
      conferirParametros('contasAPagar.listar', {
        fornecedorId: ID,
        situacao: 'VENCIDA',
        vencimentoDe: '2026-11-01',
        vencimentoAte: '2026-11-30',
        pagina: 0,
        tamanho: 20,
      }),
    ).not.toBeNull();
    expect(conferirParametros('contasAPagar.listar', { situacao: 'ATRASADA' })).toBeNull();
    expect(conferirParametros('contasAPagar.ver', { id: ID })).toEqual({
      operacao: 'contasAPagar.ver',
      id: ID,
    });
    expect(conferirParametros('contasAPagar.ver', { id: 'x' })).toBeNull();
    expect(conferirParametros('contasAPagar.listar', { tamanho: 1000 })).toBeNull();
  });

  it('pagar conta: forma dinheiro, Transferência (PIX) ou boleto; a data é opcional', () => {
    const ok = { id: ID, comandoId: OUTRO, forma: 'BOLETO', valor: '30.00' };
    expect(conferirParametros('contasAPagar.pagar', ok)).toEqual({
      operacao: 'contasAPagar.pagar',
      id: ID,
      corpo: { id: OUTRO, forma: 'BOLETO', valor: '30.00' },
    });
    expect(conferirParametros('contasAPagar.pagar', { ...ok, data: '2026-11-10' })).not.toBeNull();
    expect(conferirParametros('contasAPagar.pagar', { ...ok, forma: 'CHEQUE' })).toBeNull();
    expect(conferirParametros('contasAPagar.pagar', { ...ok, valor: '0' })).toBeNull();
    expect(conferirParametros('contasAPagar.pagar', { ...ok, data: 'hoje' })).toBeNull();
  });

  it('contas a receber: filtros conhecidos', () => {
    expect(
      conferirParametros('contasAReceber.listar', {
        vendedorId: ID,
        clienteId: OUTRO,
        situacao: 'EM_ABERTO',
        nivel: 'VENDEDOR',
      }),
    ).not.toBeNull();
    expect(conferirParametros('contasAReceber.listar', { nivel: 'CHEQUE' })).toBeNull();
    expect(conferirParametros('contasAReceber.listar', { situacao: 'x' })).toBeNull();
  });

  it('recebimento do vendedor: id, vendedor, forma e valor; a conta é opcional', () => {
    const ok = { id: ID, vendedorId: OUTRO, forma: 'DINHEIRO', valor: '50.00' };
    expect(conferirParametros('recebimentos.receber', ok)).toEqual({
      operacao: 'recebimentos.receber',
      corpo: ok,
    });
    expect(conferirParametros('recebimentos.receber', { ...ok, contaId: TERCEIRO })).not.toBeNull();
    expect(conferirParametros('recebimentos.receber', { ...ok, forma: 'BOLETO' })).not.toBeNull();
    expect(conferirParametros('recebimentos.receber', { ...ok, forma: 'CARTAO' })).toBeNull();
    expect(conferirParametros('recebimentos.receber', { ...ok, contaId: 'x' })).toBeNull();
    expect(conferirParametros('recebimentos.receber', { ...ok, entrada: '1.00' })).toBeNull();
    expect(
      conferirParametros('recebimentos.listar', { vendedorId: ID, de: '2026-11-01' }),
    ).not.toBeNull();
  });

  it('fechar semana leva só o id da semana e o id do comando', () => {
    expect(conferirParametros('semanas.fechar', { id: ID, comandoId: OUTRO })).toEqual({
      operacao: 'semanas.fechar',
      id: ID,
      corpo: { id: OUTRO },
    });
    expect(conferirParametros('semanas.fechar', { id: ID })).toBeNull();
  });
});

import { describe, expect, it } from 'vitest';
import { OPERACOES_DA_API } from './operacoes';
import { OPERACOES_DO_CONSIGNADO } from './operacoes-do-consignado';
import { validarChamada } from './validacao';

/** A ponte só deixa passar o formato certo dos pedidos do consignado (tarefa 9.2); o resto vira `null`. */
const ID = '11111111-1111-4111-8111-111111111111';
const OUTRO = '22222222-2222-4222-8222-222222222222';

describe('pedidos do consignado ao cliente na ponte', () => {
  it('toda operação do consignado está na lista da ponte', () => {
    for (const o of OPERACOES_DO_CONSIGNADO) expect(OPERACOES_DA_API).toContain(o);
  });

  it('aceita o acerto com os fatos e descarta o que sobra (nunca leva total nem preço)', () => {
    const v = validarChamada({
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: ID,
        reciboId: OUTRO,
        notaId: ID,
        comissaoPercentual: '30',
        devolvidos: [{ produtoId: ID, remessaId: OUTRO, quantidade: 5, preco: '9.99' }],
        formas: [{ forma: 'PIX', valor: '300.00' }],
        totalAReceber: '1.00',
      },
    });
    expect(v).toEqual({
      operacao: 'consignado.acertoSalvar',
      acerto: {
        id: ID,
        reciboId: OUTRO,
        notaId: ID,
        comissaoPercentual: '30',
        devolvidos: [{ produtoId: ID, remessaId: OUTRO, quantidade: 5 }],
        formas: [{ forma: 'PIX', valor: '300.00' }],
      },
    });
  });

  it.each([
    ['cheque', { forma: 'CHEQUE', valor: '1.00' }],
    ['boleto', { forma: 'BOLETO', valor: '1.00' }],
    ['valor com três casas', { forma: 'PIX', valor: '1.005' }],
    ['valor negativo', { forma: 'PIX', valor: '-1.00' }],
    ['valor em número', { forma: 'PIX', valor: 1 }],
  ])('recusa pagamento com %s', (_nome, forma) => {
    expect(
      validarChamada({
        operacao: 'consignado.pagamento',
        pagamento: { id: ID, clienteId: OUTRO, formas: [forma] },
      }),
    ).toBeNull();
  });

  it('recusa id que não é UUID, comissão em formato errado e foto que não é JPEG', () => {
    expect(validarChamada({ operacao: 'consignado.nota', id: 'abc' })).toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.acertoPrevia',
        acerto: { notaId: ID, devolvidos: [], comissaoPercentual: '30%', formas: [] },
      }),
    ).toBeNull();
    expect(
      validarChamada({ operacao: 'consignado.fotoGuardar', dataUrl: 'data:image/png;base64,AAAA' }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.fotoGuardar',
        dataUrl: 'data:image/jpeg;base64,/9j/4AAQ',
      }),
    ).toEqual({
      operacao: 'consignado.fotoGuardar',
      dataUrl: 'data:image/jpeg;base64,/9j/4AAQ',
    });
  });

  it('o estorno exige o motivo; a remarcação exige uma data no formato AAAA-MM-DD', () => {
    expect(
      validarChamada({
        operacao: 'consignado.estornarAcerto',
        id: ID,
        acertoId: OUTRO,
        motivo: '  ',
      }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.estornarAcerto',
        id: ID,
        acertoId: OUTRO,
        motivo: 'Contei errado',
      }),
    ).not.toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.remarcar',
        id: ID,
        notaId: OUTRO,
        dataCobranca: '31/12/2026',
      }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.remarcar',
        id: ID,
        notaId: OUTRO,
        dataCobranca: '2026-12-31',
      }),
    ).not.toBeNull();
  });

  it('o cadastro exige as três fotos (hash) e o endereço, e a comissão de 0 a 40 é conferida adiante', () => {
    const base = {
      id: ID,
      nome: 'Maria',
      enderecoLogradouro: 'Rua A',
      enderecoNumero: '1',
      enderecoBairro: 'Centro',
      enderecoCidade: 'Ponta Porã',
      enderecoUf: 'MS',
      fotoHash: 'a'.repeat(64),
      fotoDocumentoFrenteHash: 'b'.repeat(64),
      fotoDocumentoVersoHash: 'c'.repeat(64),
    };
    expect(
      validarChamada({ operacao: 'consignado.clienteCadastrar', cliente: base }),
    ).not.toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.clienteCadastrar',
        cliente: { ...base, fotoHash: 'x' },
      }),
    ).toBeNull();
    expect(
      validarChamada({
        operacao: 'consignado.clienteCadastrar',
        cliente: { ...base, documentoTipo: 'RUC' },
      }),
    ).toBeNull();
  });
});

import { describe, expect, it } from 'vitest';
import {
  analisarPlanilhaDeCompra,
  contarPreviaDeCompra,
  modeloDaPlanilha,
  montarPreviaDeCompra,
  podeGravarAPrevia,
  type PecaDaPrevia,
} from './planilha-compra';
import { lerCsv, type Tabela } from './planilha';

const CAB = ['Código', 'Quantidade', 'Custo'];

function ler(...linhas: string[][]) {
  const r = analisarPlanilhaDeCompra([CAB, ...linhas] as Tabela);
  if (r.tipo !== 'ok') throw new Error(r.mensagem);
  return r.linhas;
}

describe('leitura da planilha de compra', () => {
  it('linha boa vira quantidade inteira e custo em centavos', () => {
    const [l] = ler(['0012', '10', '12,50']);
    expect(l).toMatchObject({ linha: 2, codigo: '0012', quantidade: 10, custo: 1250n });
    expect(l?.motivo).toBeUndefined();
  });

  it('aceita apelidos das colunas, sem acento e em qualquer ordem', () => {
    const r = analisarPlanilhaDeCompra([
      ['QTD', 'Preço de custo', 'Cód'],
      ['3', '1,5', 'A1'],
    ]);
    expect(r).toMatchObject({ tipo: 'ok', linhas: [{ codigo: 'A1', quantidade: 3, custo: 150n }] });
  });

  it('custo com 3 casas é arredondado como o servidor faz', () => {
    expect(ler(['A', '1', '12,345'])[0]?.custo).toBe(1235n);
  });

  it('cada problema recusa só a própria linha, com texto simples', () => {
    const l = ler(
      ['', '1', '1,00'],
      ['B', '0', '1,00'],
      ['C', '1,5', '1,00'],
      ['D', '1', 'abc'],
      ['x'.repeat(61), '1', '1,00'],
      ['OK', '2', '3,00'],
    );
    expect(l.map((x) => x.motivo)).toEqual([
      'Falta o código.',
      'A quantidade precisa ser um número inteiro, maior que zero.',
      'A quantidade precisa ser um número inteiro, maior que zero.',
      'O custo não é um valor válido. Use números, com vírgula para os centavos.',
      'O código é longo demais (até 60 letras ou números).',
      undefined,
    ]);
  });

  it('código repetido na planilha (sem diferenciar maiúsculas) recusa a segunda linha', () => {
    const l = ler(['ab-1', '1', '1,00'], [' AB-1 ', '2', '1,00']);
    expect(l[0]?.motivo).toBeUndefined();
    expect(l[1]?.motivo).toBe(
      'Este código já aparece na linha 2. Some as quantidades numa linha só.',
    );
  });

  it('planilha sem coluna, vazia ou grande demais não é lida', () => {
    expect(
      analisarPlanilhaDeCompra([
        ['Código', 'Quantidade'],
        ['A', '1'],
      ]),
    ).toMatchObject({
      tipo: 'erro',
      mensagem: expect.stringContaining('custo') as string,
    });
    expect(analisarPlanilhaDeCompra([])).toMatchObject({ tipo: 'erro' });
    expect(analisarPlanilhaDeCompra([CAB])).toMatchObject({ tipo: 'erro' });
    const muitas = Array.from({ length: 501 }, (_, i) => [`C${i}`, '1', '1,00']);
    expect(analisarPlanilhaDeCompra([CAB, ...muitas])).toMatchObject({ tipo: 'erro' });
  });
});

describe('prévia da planilha de compra', () => {
  const pecas = new Map<string, PecaDaPrevia>([
    ['a-1', { id: 'p1', nome: 'Anel' }],
    ['a-2', { id: 'p1', nome: 'Anel' }],
    ['b-1', { id: 'p2', nome: 'Brinco' }],
  ]);

  it('código conhecido entra; desconhecido espera cadastro ou correção; erro de leitura não entra', () => {
    const l = montarPreviaDeCompra(
      ler(['A-1', '1', '1,00'], ['ZZ', '1', '1,00'], ['B-1', 'x', '1,00']),
      pecas,
    );
    expect(l.map((x) => x.situacao)).toEqual(['ok', 'desconhecido', 'erro']);
    expect(l[0]?.peca?.nome).toBe('Anel');
    expect(l[1]?.motivo).toBe('Não existe peça com este código.');
    expect(contarPreviaDeCompra(l)).toEqual({ ok: 1, desconhecido: 1, erro: 1 });
    expect(podeGravarAPrevia(l)).toBe(false);
  });

  it('a mesma peça com dois códigos diferentes recusa a segunda linha', () => {
    const l = montarPreviaDeCompra(ler(['A-1', '1', '1,00'], ['A-2', '1', '1,00']), pecas);
    expect(l[1]).toMatchObject({ situacao: 'erro' });
    expect(l[1]?.motivo).toContain('linha 2');
  });

  it('só dá para gravar quando todas as linhas estão boas', () => {
    const boas = montarPreviaDeCompra(ler(['A-1', '1', '1,00'], ['B-1', '2', '2,00']), pecas);
    expect(podeGravarAPrevia(boas)).toBe(true);
    expect(podeGravarAPrevia([])).toBe(false);
  });
});

describe('modelo da planilha', () => {
  it('é um CSV que o próprio sistema lê de volta', () => {
    const bytes = modeloDaPlanilha();
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
    const tabela = lerCsv(new TextDecoder().decode(bytes));
    expect(tabela[0]).toEqual(['código', 'quantidade', 'custo']);
    expect(analisarPlanilhaDeCompra(tabela)).toMatchObject({ tipo: 'ok' });
  });
});

import { describe, expect, it } from 'vitest';
import {
  adicionarOuSomar,
  centavosParaApi,
  centavosParaCampo,
  custoDaPlanilhaEmCentavos,
  custoEmCentavos,
  itensDoPedido,
  lerQuantidade,
  problemasDasLinhas,
  separarErrosDoServidor,
  totalDaCompra,
  totalDaLinha,
  totalDePecas,
  type LinhaDaCompra,
} from './compra';

const linha = (extra: Partial<LinhaDaCompra> = {}): LinhaDaCompra => ({
  produtoId: 'p1',
  nome: 'Anel',
  codigo: '0012',
  fotoHash: null,
  quantidade: '1',
  custo: '10,00',
  ...extra,
});

describe('quantidade e custo', () => {
  it('quantidade é inteira, de 1 até 1.000.000', () => {
    expect(lerQuantidade('3')).toBe(3);
    expect(lerQuantidade(' 12 ')).toBe(12);
    expect(lerQuantidade('1000000')).toBe(1_000_000);
    for (const ruim of ['', '0', '-1', '1,5', '2.5', 'abc', '1000001', '99999999']) {
      expect(lerQuantidade(ruim)).toBeNull();
    }
  });

  it('custo digitado com vírgula vira centavos; mais de 2 casas ou lixo não passa', () => {
    expect(custoEmCentavos('12,50')).toBe(1250n);
    expect(custoEmCentavos('1.234,5')).toBe(123450n);
    expect(custoEmCentavos('0,05')).toBe(5n);
    expect(custoEmCentavos('10')).toBe(1000n);
    for (const ruim of ['', 'abc', '-1', '1,234']) expect(custoEmCentavos(ruim)).toBeNull();
  });

  it('custo da planilha com 3 ou 4 casas arredonda para cima na metade, como o servidor', () => {
    expect(custoDaPlanilhaEmCentavos('12,345')).toBe(1235n);
    expect(custoDaPlanilhaEmCentavos('12,344')).toBe(1234n);
    expect(custoDaPlanilhaEmCentavos('0,005')).toBe(1n);
    expect(custoDaPlanilhaEmCentavos('0,0049')).toBe(0n);
    expect(custoDaPlanilhaEmCentavos('12.345')).toBe(1235n);
    expect(custoDaPlanilhaEmCentavos('R$ 1.234,56')).toBe(123456n);
    expect(custoDaPlanilhaEmCentavos('7')).toBe(700n);
    for (const ruim of ['', 'x', '1,23456', '-3']) {
      expect(custoDaPlanilhaEmCentavos(ruim)).toBeNull();
    }
  });

  it('texto de centavos para a API e para o campo', () => {
    expect(centavosParaApi(1250n)).toBe('12.50');
    expect(centavosParaApi(5n)).toBe('0.05');
    expect(centavosParaCampo(123450n)).toBe('1234,50');
  });
});

describe('totais', () => {
  it('total da linha é custo vezes quantidade, em centavos exatos', () => {
    expect(totalDaLinha(linha({ quantidade: '3', custo: '12,50' }))).toBe(3750n);
    // 0,10 três vezes não vira 0,30000000000000004
    expect(totalDaLinha(linha({ quantidade: '3', custo: '0,10' }))).toBe(30n);
    expect(totalDaLinha(linha({ quantidade: '', custo: '1,00' }))).toBeNull();
    expect(totalDaLinha(linha({ quantidade: '1', custo: 'x' }))).toBeNull();
  });

  it('total da compra soma as linhas boas e conta as peças', () => {
    const linhas = [
      linha({ produtoId: 'a', quantidade: '2', custo: '10,00' }),
      linha({ produtoId: 'b', quantidade: '3', custo: '0,10' }),
      linha({ produtoId: 'c', quantidade: '', custo: '5,00' }),
    ];
    expect(totalDaCompra(linhas)).toBe(2030n);
    expect(totalDePecas(linhas)).toBe(5);
  });

  it('soma de muitas linhas não perde centavo', () => {
    const linhas = Array.from({ length: 500 }, (_, i) =>
      linha({ produtoId: `p${i}`, quantidade: '7', custo: '0,33' }),
    );
    expect(totalDaCompra(linhas)).toBe(500n * 7n * 33n);
  });
});

describe('bipar a mesma peça', () => {
  it('soma 1 na linha que já existe e mantém o custo e a ordem', () => {
    const a = linha({ produtoId: 'a', quantidade: '2', custo: '9,00' });
    const b = linha({ produtoId: 'b' });
    const r = adicionarOuSomar([a, b], linha({ produtoId: 'a', custo: '1,00' }));
    expect(r).toHaveLength(2);
    expect(r[0]).toMatchObject({ produtoId: 'a', quantidade: '3', custo: '9,00' });
    expect(r[1]).toBe(b);
  });

  it('peça nova entra no fim; quantidade apagada conta como a primeira leitura', () => {
    expect(adicionarOuSomar([linha({ produtoId: 'a' })], linha({ produtoId: 'b' }))).toHaveLength(
      2,
    );
    const r = adicionarOuSomar(
      [linha({ produtoId: 'a', quantidade: '' })],
      linha({ produtoId: 'a' }),
    );
    expect(r[0]?.quantidade).toBe('1');
  });

  it('não passa do limite de quantidade', () => {
    const r = adicionarOuSomar([linha({ quantidade: '1000000' })], linha());
    expect(r[0]?.quantidade).toBe('1000000');
  });
});

describe('pedido e erros', () => {
  it('itens no formato da API, sem total; null se a linha não está boa', () => {
    expect(itensDoPedido([linha({ quantidade: '2', custo: '12,5' })])).toEqual([
      { produtoId: 'p1', quantidade: 2, custoUnitario: '12.50' },
    ]);
    expect(itensDoPedido([linha({ custo: '' })])).toBeNull();
  });

  it('problemas por linha usam os textos recebidos', () => {
    const p = problemasDasLinhas([linha(), linha({ quantidade: '0', custo: '' })], {
      quantidade: 'Q',
      custo: 'C',
    });
    expect(p).toEqual([{}, { quantidade: 'Q', custo: 'C' }]);
  });

  it('erros do servidor: itens[i] vai para a linha i; o resto fica no cabeçalho', () => {
    const r = separarErrosDoServidor({
      'itens[2].codigo': 'Não encontramos nenhuma peça com o código X.',
      'itens[2].quantidade': 'Informe uma quantidade maior que zero.',
      'itens[0].produtoId': 'Esta peça não existe mais.',
      fornecedorId: 'Escolha o fornecedor da compra.',
    });
    expect(r.porItem.get(2)).toHaveLength(2);
    expect(r.porItem.get(0)).toEqual(['Esta peça não existe mais.']);
    expect(r.gerais).toEqual({ fornecedorId: 'Escolha o fornecedor da compra.' });
  });
});

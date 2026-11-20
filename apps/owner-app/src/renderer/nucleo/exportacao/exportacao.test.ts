import { describe, expect, it } from 'vitest';
import { lerCsv, lerXlsx } from '../planilha';
import { gerarCsv } from './csv';
import { montarGrade, type PedidoDeExportacao } from './grade';
import { gerarPdf } from './pdf';
import { gerarXlsx } from './xlsx';

const COTACAO = { dolar: '5.50130000', guarani: '0.00089500' };

function pedido(sobre: Partial<PedidoDeExportacao> = {}): PedidoDeExportacao {
  return {
    loja: 'Corona Semijoias',
    titulo: 'Produtos e custos',
    filtros: ['Situação: Ativos', 'Busca: anel'],
    geradoEm: '10/11/2026 às 09:30',
    geradoPor: 'Maria',
    linhaDaCotacao: 'Cotação do Banco Central de 06/11/2026.',
    dataDaCotacao: '2026-11-06',
    cotacao: COTACAO,
    colunas: [
      { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
      { chave: 'estoque', titulo: 'Estoque', tipo: 'inteiro' },
      { chave: 'precoVenda', titulo: 'Preço de venda', tipo: 'dinheiro' },
      { chave: 'ativo', titulo: 'Situação', tipo: 'simNao' },
    ],
    linhas: [
      { nome: 'Anel; "folheado"', estoque: 3, precoVenda: '1234.56', ativo: true },
      { nome: '=SOMA(A1)', estoque: 0, precoVenda: '1.00', ativo: false },
      { nome: 'Colar', estoque: 10, precoVenda: '0.01', ativo: true },
    ],
    totais: [
      { chave: 'todos', rotulo: 'Produtos', quantidade: 3, valorEmReais: null },
      {
        chave: 'preco-venda',
        rotulo: 'Soma dos preços de venda',
        quantidade: 3,
        valorEmReais: '1235.57',
      },
    ],
    totaisDeValor: { 'preco-venda': 'precoVenda' },
    ...sobre,
  };
}

describe('grade de exportação: três moedas e data da cotação', () => {
  it('cada valor em dinheiro vira Real, Dólar e Guarani, mais a data da cotação da linha', () => {
    const g = montarGrade(pedido());
    expect(g.titulos).toEqual([
      'Produto',
      'Estoque',
      'Preço de venda (R$)',
      'Preço de venda (US$)',
      'Preço de venda (Gs.)',
      'Situação',
      'Data da cotação',
    ]);
    expect(g.linhas[0]!.map((c) => c.v)).toEqual([
      'Anel; "folheado"',
      '3',
      '1234.56',
      '224.41',
      '1379397',
      'Ativo',
      '06/11/2026',
    ]);
    // R$ 0,01: US$ 0,00 e Gs. 11 (mesma conta da tela).
    expect(g.linhas[2]!.map((c) => c.v).slice(2, 5)).toEqual(['0.01', '0.00', '11']);
  });

  it('o total em Dólar e em Guarani é a soma das linhas já convertidas (o detalhamento fecha)', () => {
    const g = montarGrade(pedido());
    const soma = g.rodape.find((l) => l[0]!.v === 'Soma dos preços de venda')!;
    const dolares = g.linhas.map((l) => Number(l[3]!.v));
    const guaranis = g.linhas.map((l) => Number(l[4]!.v));
    expect(soma[2]!.v).toBe('1235.57');
    expect(Number(soma[3]!.v)).toBeCloseTo(
      dolares.reduce((a, b) => a + b, 0),
      2,
    );
    expect(Number(soma[4]!.v)).toBe(guaranis.reduce((a, b) => a + b, 0));
  });

  it('o total de quantidade aparece na segunda coluna', () => {
    const g = montarGrade(pedido());
    expect(g.rodape[0]!.map((c) => c.v).slice(0, 2)).toEqual(['Produtos', '3']);
  });

  it('sem cotação: só o Real, Dólar e Guarani em branco e sem data', () => {
    const g = montarGrade(pedido({ cotacao: null, dataDaCotacao: null, linhaDaCotacao: null }));
    expect(g.linhas[0]!.map((c) => c.v).slice(2)).toEqual(['1234.56', '', '', 'Ativo', '']);
  });

  it('sem coluna de dinheiro não há colunas de moeda nem data da cotação', () => {
    const g = montarGrade(
      pedido({
        colunas: [{ chave: 'nome', titulo: 'Produto', tipo: 'texto' }],
        totais: [{ chave: 'todos', rotulo: 'Produtos', quantidade: 3, valorEmReais: null }],
        totaisDeValor: {},
      }),
    );
    expect(g.titulos).toEqual(['Produto']);
  });

  it('datas e horas em português, no horário de Brasília', () => {
    const g = montarGrade(
      pedido({
        colunas: [
          { chave: 'd', titulo: 'Dia', tipo: 'data' },
          { chave: 'h', titulo: 'Quando', tipo: 'dataHora' },
        ],
        linhas: [{ d: '1990-03-31', h: '2026-11-10T12:30:00Z' }],
        totais: [],
        totaisDeValor: {},
      }),
    );
    expect(g.linhas[0]!.map((c) => c.v)).toEqual(['31/03/1990', '10/11/2026 às 09:30']);
  });
});

describe('CSV', () => {
  it('tem o cabeçalho (loja, filtros, data, usuário) e os totais no fim', () => {
    const texto = gerarCsv(montarGrade(pedido()));
    const linhas = lerCsv(texto);
    expect(linhas[0]).toEqual(['Corona Semijoias']);
    expect(linhas[1]).toEqual(['Produtos e custos']);
    expect(linhas[2]).toEqual(['Filtros: Situação: Ativos; Busca: anel']);
    expect(linhas[3]).toEqual(['Gerado em 10/11/2026 às 09:30 por Maria']);
    expect(linhas[4]).toEqual(['Cotação do Banco Central de 06/11/2026.']);
    expect(linhas.at(-1)![0]).toBe('Soma dos preços de venda');
  });

  it('usa ponto e vírgula e vírgula decimal, começa com marca de UTF-8 e protege aspas e fórmulas', () => {
    const texto = gerarCsv(montarGrade(pedido()));
    expect(texto.startsWith('﻿')).toBe(true);
    expect(texto).toContain('"Anel; ""folheado""";3;1234,56;224,41;1379397;Ativo;06/11/2026');
    // Um nome que começa com "=" não pode virar fórmula no Excel.
    expect(texto).toContain("'=SOMA(A1)");
  });
});

describe('Excel (.xlsx)', () => {
  it('abre de volta com as mesmas linhas, os números como números e as três moedas', async () => {
    const bytes = gerarXlsx(montarGrade(pedido()));
    const tabela = await lerXlsx(bytes);
    const cabecalho = tabela.findIndex((l) => l[0] === 'Produto');
    expect(cabecalho).toBeGreaterThan(0);
    expect(tabela.slice(0, 5).map((l) => l[0])).toEqual([
      'Corona Semijoias',
      'Produtos e custos',
      'Filtros: Situação: Ativos; Busca: anel',
      'Gerado em 10/11/2026 às 09:30 por Maria',
      'Cotação do Banco Central de 06/11/2026.',
    ]);
    expect(tabela[cabecalho]).toContain('Preço de venda (US$)');
    expect(tabela[cabecalho + 1]).toEqual([
      'Anel; "folheado"',
      '3',
      '1234.56',
      '224.41',
      '1379397',
      'Ativo',
      '06/11/2026',
    ]);
    // Texto com "=" fica como texto, nunca como fórmula.
    const xml = new TextDecoder('latin1').decode(bytes);
    expect(xml.includes('<f>')).toBe(false);
    expect(tabela[cabecalho + 2]![0]).toBe('=SOMA(A1)');
  });

  it('é um zip válido (assinatura e fim do diretório)', () => {
    const bytes = gerarXlsx(montarGrade(pedido()));
    expect([...bytes.slice(0, 4)]).toEqual([0x50, 0x4b, 0x03, 0x04]);
    const fim = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(
      bytes.length - 22,
      true,
    );
    expect(fim).toBe(0x06054b50);
  });
});

describe('PDF', () => {
  const texto = (b: Uint8Array) => new TextDecoder('latin1').decode(b);

  it('é um PDF com cabeçalho, filtros, cotação, linhas e totais', () => {
    const pdf = texto(gerarPdf(montarGrade(pedido())));
    expect(pdf.startsWith('%PDF-1.4')).toBe(true);
    expect(pdf.trimEnd().endsWith('%%EOF')).toBe(true);
    for (const trecho of [
      'Corona Semijoias',
      'Produtos e custos',
      'Filtros: Situação: Ativos; Busca: anel',
      'Gerado em 10/11/2026',
      'por Maria',
      'Cotação do Banco Central de 06/11/2026.',
      'Soma dos preços de venda',
    ]) {
      // As letras acentuadas saem em Windows-1252, que o decodificador "latin1" do Node lê igual.
      expect(pdf).toContain(trecho);
    }
  });

  it('quebra em várias páginas quando há muitas linhas, e cada página repete os títulos', () => {
    const muitas = Array.from({ length: 120 }, (_, i) => ({
      nome: `Produto ${i}`,
      estoque: i,
      precoVenda: '10.00',
      ativo: true,
    }));
    const pdf = texto(gerarPdf(montarGrade(pedido({ linhas: muitas }))));
    const paginas = (pdf.match(/\/Type \/Page\b/g) ?? []).length;
    expect(paginas).toBeGreaterThan(2);
    expect((pdf.match(/Pre.o de venda \\\(R\$\\\)/g) ?? []).length).toBeGreaterThanOrEqual(paginas);
    expect(pdf).toContain(`/Count ${paginas}`);
  });

  it('não deixa parênteses e barras do texto quebrarem o arquivo', () => {
    const pdf = texto(
      gerarPdf(
        montarGrade(
          pedido({
            linhas: [{ nome: 'Anel (ouro) \\ 18k', estoque: 1, precoVenda: '1.00', ativo: true }],
          }),
        ),
      ),
    );
    expect(pdf).toContain('Anel \\(ouro\\) \\\\ 18k');
  });
});

describe('grade: foto, quantidade com sinal e totais em peças (tarefa 2.8)', () => {
  const base = {
    loja: 'Corona Semijoias',
    titulo: 'Estoque',
    filtros: [],
    geradoEm: '10/11/2026 às 09:30',
    geradoPor: 'Maria',
    linhaDaCotacao: null,
    dataDaCotacao: null,
    cotacao: null,
    totaisDeValor: {},
  };

  it('a foto não vai para os arquivos e a quantidade com sinal sai como número', () => {
    const grade = montarGrade({
      ...base,
      colunas: [
        { chave: 'fotoPrincipalHash', titulo: 'Foto', tipo: 'foto' },
        { chave: 'nome', titulo: 'Produto', tipo: 'texto' },
        { chave: 'quantidade', titulo: 'Quantidade', tipo: 'sinal' },
      ],
      linhas: [{ fotoPrincipalHash: 'abc', nome: 'Anel', quantidade: -2 }],
      totais: [{ chave: 'saidas', rotulo: 'Peças que saíram', quantidade: 1, pecas: 2 }],
    });
    expect(grade.titulos).toEqual(['Produto', 'Quantidade']);
    expect(grade.linhas[0]!.map((c) => c.v)).toEqual(['Anel', '-2']);
    expect(grade.linhas[0]![1]!.numero).toBe(true);
    expect(grade.rodape[0]!.map((c) => c.v)).toEqual(['Peças que saíram', '2']);
  });

  it('a soma em Dólar e Guarani pode deixar de fora as linhas que não valem no total', () => {
    const taxas = { dataCotacao: '2026-11-06', dolar: '5.50130000', guarani: '0.00089500' };
    const grade = montarGrade({
      ...base,
      cotacao: taxas,
      dataDaCotacao: '2026-11-06',
      colunas: [
        { chave: 'nome', titulo: 'Compra', tipo: 'texto' },
        { chave: 'total', titulo: 'Total', tipo: 'dinheiro' },
      ],
      linhas: [
        { nome: 'A', total: '100.00', estornada: false },
        { nome: 'B', total: '50.00', estornada: true },
      ],
      totais: [
        { chave: 'registradas', rotulo: 'Registradas', quantidade: 1, valorEmReais: '100.00' },
      ],
      totaisDeValor: { registradas: 'total' },
      linhasDoTotal: { registradas: (l) => l.estornada !== true },
    });
    // 100,00 ÷ 5,5013 = US$ 18,18 (a compra desfeita de 50,00 não entra).
    expect(grade.rodape[0]!.map((c) => c.v)).toContain('18.18');
  });
});

describe('relatório em partes (secoes)', () => {
  const comPartes = () =>
    montarGrade(
      pedido({
        colunas: [{ chave: 'nome', titulo: 'Peça', tipo: 'texto' }],
        linhas: [{ nome: 'Anel' }],
        totais: [
          { chave: 'enviadas', rotulo: 'Peças enviadas', quantidade: 1, pecas: 10 },
          { chave: 'vd', rotulo: 'Valor vendido', quantidade: 1, valorEmReais: '300.00' },
        ],
        totaisDeValor: {},
        secoes: [
          { titulo: 'Peças da maleta', totais: ['enviadas'] },
          { titulo: 'Dinheiro', totais: ['vd'] },
        ],
      }),
    );

  it('cada parte ganha título, e há uma linha em branco entre elas', () => {
    const rodape = comPartes().rodape.map((l) => l[0]?.v);
    expect(rodape).toEqual(['Peças da maleta', 'Peças enviadas', '', 'Dinheiro', 'Valor vendido']);
  });

  it('o CSV traz as duas seções, a das peças antes da do dinheiro', () => {
    const csv = gerarCsv(comPartes());
    expect(csv.indexOf('Peças da maleta')).toBeLessThan(csv.indexOf('Dinheiro'));
    expect(csv.indexOf('Peças enviadas')).toBeLessThan(csv.indexOf('Dinheiro'));
    expect(csv.indexOf('Valor vendido')).toBeGreaterThan(csv.indexOf('Dinheiro'));
  });

  it('sem partes, o rodapé é só a lista de totais, como sempre', () => {
    expect(montarGrade(pedido()).rodape.map((l) => l[0]?.v)).toEqual([
      'Produtos',
      'Soma dos preços de venda',
    ]);
  });
});

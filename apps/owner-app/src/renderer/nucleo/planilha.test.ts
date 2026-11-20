import { deflateRawSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';
import {
  analisarPlanilha,
  contarPrevia,
  lerCsv,
  lerDinheiroDaPlanilha,
  lerPlanilha,
  lerXlsx,
  montarPrevia,
  type Tabela,
} from './planilha';

const CAT = [
  { id: '1b4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b22', nome: 'Anéis' },
  { id: '2c4e28ba-2fa1-41d3-b9a5-2c3a9a8f6b33', nome: 'Brincos' },
];
const CABECALHO = ['Código', 'Nome', 'Categoria', 'Custo', 'Preço'];

describe('leitura de CSV', () => {
  it('detecta ponto e vírgula, aspas e vírgula dentro do texto', () => {
    const t = lerCsv('﻿código;nome;preço\n001;"Anel, ouro";"12,50"\n\n002;Brinco;3\n');
    expect(t).toEqual([
      ['código', 'nome', 'preço'],
      ['001', 'Anel, ouro', '12,50'],
      ['002', 'Brinco', '3'],
    ]);
  });
  it('detecta vírgula como separador', () => {
    expect(lerCsv('a,b\n1,2')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ]);
  });
});

/** Monta um .xlsx pequeno (zip com uma folha) para testar a leitura. */
function montarXlsx(folha: string, textos: string[], metodo: 0 | 8): Uint8Array {
  const arquivos: [string, string][] = [
    ['xl/worksheets/sheet1.xml', folha],
    ['xl/sharedStrings.xml', `<sst>${textos.map((t) => `<si><t>${t}</t></si>`).join('')}</sst>`],
  ];
  const partes: Buffer[] = [];
  const central: Buffer[] = [];
  let deslocamento = 0;
  for (const [nome, conteudo] of arquivos) {
    const bruto = Buffer.from(conteudo, 'utf8');
    const dados = metodo === 8 ? deflateRawSync(bruto) : bruto;
    const nomeB = Buffer.from(nome);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(metodo, 8);
    local.writeUInt32LE(dados.length, 18);
    local.writeUInt32LE(bruto.length, 22);
    local.writeUInt16LE(nomeB.length, 26);
    partes.push(local, nomeB, dados);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0);
    c.writeUInt16LE(metodo, 10);
    c.writeUInt32LE(dados.length, 20);
    c.writeUInt32LE(bruto.length, 24);
    c.writeUInt16LE(nomeB.length, 28);
    c.writeUInt32LE(deslocamento, 42);
    central.push(c, nomeB);
    deslocamento += 30 + nomeB.length + dados.length;
  }
  const dirCentral = Buffer.concat(central);
  const fim = Buffer.alloc(22);
  fim.writeUInt32LE(0x06054b50, 0);
  fim.writeUInt16LE(arquivos.length, 10);
  fim.writeUInt32LE(dirCentral.length, 12);
  fim.writeUInt32LE(deslocamento, 16);
  return new Uint8Array(Buffer.concat([...partes, dirCentral, fim]));
}

describe('leitura de Excel (.xlsx)', () => {
  const folha =
    '<worksheet><sheetData>' +
    '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="D1" t="inlineStr"><is><t>Custo</t></is></c></row>' +
    '<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" t="s"><v>3</v></c><c r="D2"><v>12.5</v></c></row>' +
    '</sheetData></worksheet>';
  const textos = ['Código', 'Nome', '0012', 'Anel &amp; Cia'];

  it.each([8, 0] as const)('lê textos, números e colunas vazias (método %i)', async (metodo) => {
    const t = await lerXlsx(montarXlsx(folha, textos, metodo));
    expect(t).toEqual([
      ['Código', 'Nome', '', 'Custo'],
      ['0012', 'Anel & Cia', '', '12.5'],
    ]);
  });

  it('escolhe a leitura pelo nome do arquivo e recusa arquivo que não é planilha', async () => {
    expect((await lerPlanilha('a.xlsx', montarXlsx(folha, textos, 8)))[0]).toHaveLength(4);
    expect(await lerPlanilha('a.csv', new TextEncoder().encode('a;b\n1;2'))).toHaveLength(2);
    await expect(lerXlsx(new Uint8Array([1, 2, 3]))).rejects.toThrow();
  });
});

describe('valores em dinheiro da planilha', () => {
  it.each([
    ['1.234,56', '1234.56'],
    ['12,5', '12.50'],
    ['12.5', '12.50'],
    ['R$ 10', '10.00'],
    ['1.000', '1000.00'],
    ['0', '0.00'],
  ])('%s vira %s', (entrada, saida) => {
    expect(lerDinheiroDaPlanilha(entrada)).toBe(saida);
  });
  it.each(['', 'abc', '-3', '1,234', '12,345', '1e3'])('%s é recusado', (entrada) => {
    expect(lerDinheiroDaPlanilha(entrada)).toBeNull();
  });
});

describe('prévia da importação', () => {
  const tabela = (...linhas: string[][]): Tabela => [CABECALHO, ...linhas];

  it('aceita as colunas em qualquer ordem e com acento', () => {
    const r = analisarPlanilha(
      [
        ['Preço', 'Custo', 'Categoria', 'Nome', 'Código'],
        ['70,00', '10', 'anéis', 'Anel', '0012'],
      ],
      CAT,
    );
    expect(r).toMatchObject({
      tipo: 'ok',
      linhas: [{ codigo: '0012', preco: '70.00', custo: '10.00', categoriaId: CAT[0]!.id }],
    });
  });

  it('coluna que falta é erro de planilha, dizendo qual', () => {
    const r = analisarPlanilha(
      [
        ['Código', 'Nome'],
        ['1', 'x'],
      ],
      CAT,
    );
    expect(r.tipo).toBe('erro');
    expect((r as { mensagem: string }).mensagem).toContain('categoria');
  });

  it('cada linha ruim traz o motivo e as outras seguem', () => {
    const r = analisarPlanilha(
      tabela(
        ['', 'Anel', 'Anéis', '1', '2'],
        ['A1', '', 'Anéis', '1', '2'],
        ['A2', 'Anel', 'Colares', '1', '2'],
        ['A3', 'Anel', 'Anéis', 'x', '2'],
        ['A4', 'Anel', 'Anéis', '1', '2,345'],
        ['A5', 'Anel', 'Anéis', '1', '2'],
        ['a5 ', 'Outro', 'Anéis', '1', '2'],
      ),
      CAT,
    );
    if (r.tipo !== 'ok') throw new Error('esperava ok');
    expect(r.linhas.map((l) => l.motivo)).toEqual([
      'Falta o código.',
      'Falta o nome.',
      'A categoria "Colares" não existe. Cadastre a categoria antes.',
      expect.stringContaining('O custo não é um valor válido'),
      expect.stringContaining('O preço não é um valor válido'),
      undefined,
      'O código a5 já aparece na linha 7.',
    ]);
    expect(r.linhas.map((l) => l.linha)).toEqual([2, 3, 4, 5, 6, 7, 8]);
  });

  it('código que já existe atualiza (sem diferenciar maiúsculas); novo cria; ruim recusa', () => {
    const r = analisarPlanilha(
      tabela(
        ['A1', 'Anel', 'Anéis', '1', '2'],
        ['B2', 'Brinco', 'Brincos', '1', '2'],
        ['', 'x', 'Brincos', '1', '2'],
      ),
      CAT,
    );
    if (r.tipo !== 'ok') throw new Error('esperava ok');
    const previa = montarPrevia(r.linhas, new Map([['a1', { id: 'p1', version: 2 }]]));
    expect(previa.map((l) => l.situacao)).toEqual(['atualizar', 'criar', 'recusar']);
    expect(previa[0]!.existente).toEqual({ id: 'p1', version: 2 });
    expect(contarPrevia(previa)).toEqual({ criar: 1, atualizar: 1, recusar: 1 });
  });

  it('planilha sem produtos ou grande demais é erro', () => {
    expect(analisarPlanilha([CABECALHO], CAT)).toMatchObject({ tipo: 'erro' });
    expect(analisarPlanilha([], CAT)).toMatchObject({ tipo: 'erro' });
  });
});

describe('coluna opcional Tam./Ton. (tarefa 9.8)', () => {
  const comTamTon = [...CABECALHO, 'Tam./Ton.'];

  it('sem a coluna a planilha continua valendo e a linha fica sem Tam./Ton.', () => {
    const r = analisarPlanilha([CABECALHO, ['001', 'Anel', 'Anéis', '10,00', '30,00']], CAT);
    expect(r.tipo).toBe('ok');
    if (r.tipo === 'ok') expect(r.linhas[0]?.tamTon).toBeUndefined();
  });

  it('com a coluna (nos dois idiomas), lê o texto de cada linha; vazio fica sem valor', () => {
    for (const titulo of ['Tam./Ton.', 'Tam Ton', 'Tamanho/Tom', 'Talle/Tono']) {
      const r = analisarPlanilha(
        [
          [...CABECALHO, titulo],
          ['001', 'Anel', 'Anéis', '10,00', '30,00', ' 17 '],
          ['002', 'Brinco', 'Brincos', '5,00', '12,00', ''],
        ],
        CAT,
      );
      expect(r.tipo, titulo).toBe('ok');
      if (r.tipo === 'ok') {
        expect(r.linhas[0]?.tamTon).toBe('17');
        expect(r.linhas[1]?.tamTon).toBeUndefined();
        expect(r.linhas[0]?.motivo).toBeUndefined();
      }
    }
  });

  it('mais de 30 letras recusa só aquela linha', () => {
    const r = analisarPlanilha(
      [comTamTon, ['001', 'Anel', 'Anéis', '10,00', '30,00', 'x'.repeat(31)]],
      CAT,
    );
    expect(r.tipo).toBe('ok');
    if (r.tipo === 'ok') expect(r.linhas[0]?.motivo).toMatch(/Tam\.\/Ton\./);
  });
});

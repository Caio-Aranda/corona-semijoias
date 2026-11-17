/**
 * Planilha Excel (.xlsx) sem biblioteca: um .xlsx é um zip de arquivos XML. Aqui o zip é gravado sem
 * compressão (o arquivo é pequeno) e o texto vai como texto — nunca como fórmula.
 */
import type { Celula, Grade } from './grade';
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';

const CODIFICAR = new TextEncoder();

const TABELA_CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff;
  for (const b of bytes) c = TABELA_CRC[(c ^ b) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** Zip sem compressão (método 0), nomes em UTF-8. */
export function montarZip(arquivos: { nome: string; conteudo: Uint8Array }[]): Uint8Array {
  const partes: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let deslocamento = 0;
  for (const a of arquivos) {
    const nome = CODIFICAR.encode(a.nome);
    const crc = crc32(a.conteudo);
    const local = new Uint8Array(30 + nome.length);
    const v = new DataView(local.buffer);
    v.setUint32(0, 0x04034b50, true);
    v.setUint16(4, 20, true);
    v.setUint16(6, 0x0800, true); // nomes em UTF-8
    v.setUint16(8, 0, true); // sem compressão
    v.setUint32(14, crc, true);
    v.setUint32(18, a.conteudo.length, true);
    v.setUint32(22, a.conteudo.length, true);
    v.setUint16(26, nome.length, true);
    local.set(nome, 30);
    partes.push(local, a.conteudo);

    const entrada = new Uint8Array(46 + nome.length);
    const e = new DataView(entrada.buffer);
    e.setUint32(0, 0x02014b50, true);
    e.setUint16(4, 20, true);
    e.setUint16(6, 20, true);
    e.setUint16(8, 0x0800, true);
    e.setUint32(16, crc, true);
    e.setUint32(20, a.conteudo.length, true);
    e.setUint32(24, a.conteudo.length, true);
    e.setUint16(28, nome.length, true);
    e.setUint32(42, deslocamento, true);
    entrada.set(nome, 46);
    central.push(entrada);
    deslocamento += local.length + a.conteudo.length;
  }
  const tamanhoCentral = central.reduce((s, c) => s + c.length, 0);
  const fim = new Uint8Array(22);
  const f = new DataView(fim.buffer);
  f.setUint32(0, 0x06054b50, true);
  f.setUint16(8, arquivos.length, true);
  f.setUint16(10, arquivos.length, true);
  f.setUint32(12, tamanhoCentral, true);
  f.setUint32(16, deslocamento, true);
  const todos = [...partes, ...central, fim];
  const saida = new Uint8Array(todos.reduce((s, p) => s + p.length, 0));
  let posicao = 0;
  for (const p of todos) {
    saida.set(p, posicao);
    posicao += p.length;
  }
  return saida;
}

function xml(texto: string): string {
  // Caracteres de controle não existem em XML 1.0; o resto precisa de escape.
  const semControle = Array.from(texto)
    .filter((c) => {
      const n = c.charCodeAt(0);
      return n >= 0x20 || n === 0x09 || n === 0x0a || n === 0x0d;
    })
    .join('');
  return semControle
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function nomeDaColuna(indice: number): string {
  let n = indice + 1;
  let nome = '';
  while (n > 0) {
    const resto = (n - 1) % 26;
    nome = String.fromCharCode(65 + resto) + nome;
    n = Math.floor((n - 1) / 26);
  }
  return nome;
}

// Estilos: 0 normal · 1 negrito · 2 número com 2 casas · 3 número inteiro · 4 negrito com 2 casas.
function celulaXml(
  c: Celula,
  coluna: number,
  linha: number,
  negrito: boolean,
  casas: 'fixo2' | 'inteiro',
): string {
  const ref = `${nomeDaColuna(coluna)}${linha}`;
  if (c.v === '') return '';
  if (c.numero && /^-?\d+(\.\d+)?$/.test(c.v)) {
    const estilo = casas === 'inteiro' ? 3 : negrito ? 4 : 2;
    return `<c r="${ref}" s="${estilo}"><v>${c.v}</v></c>`;
  }
  return `<c r="${ref}" s="${negrito ? 1 : 0}" t="inlineStr"><is><t xml:space="preserve">${xml(c.v)}</t></is></c>`;
}

function linhaXml(
  celulas: Celula[],
  numeroDaLinha: number,
  negrito: boolean,
  grade: Grade,
): string {
  const partes = celulas.map((c, i) => {
    // Só os valores em dinheiro e a porcentagem têm 2 casas; contagens e Guarani são inteiros.
    const titulo = grade.titulos[i] ?? '';
    const inteiro = !/\((R\$|US\$)\)|%/i.test(titulo) && !/Comiss/.test(titulo);
    return celulaXml(c, i, numeroDaLinha, negrito, inteiro ? 'inteiro' : 'fixo2');
  });
  return `<row r="${numeroDaLinha}">${partes.join('')}</row>`;
}

export function gerarXlsx(grade: Grade): Uint8Array {
  const linhas: string[] = [];
  let n = 1;
  for (const [i, texto] of grade.cabecalho.entries()) {
    linhas.push(linhaXml([{ v: texto }], n++, i < 2, grade));
  }
  n++;
  linhas.push(
    linhaXml(
      grade.titulos.map((t) => ({ v: t })),
      n++,
      true,
      grade,
    ),
  );
  for (const l of grade.linhas) linhas.push(linhaXml(l, n++, false, grade));
  if (grade.rodape.length > 0) {
    n++;
    for (const l of grade.rodape) linhas.push(linhaXml(l, n++, true, grade));
  }
  const colunas = grade.titulos
    .map((t, i) => {
      const maior = Math.max(t.length, ...grade.linhas.map((l) => l[i]?.v.length ?? 0));
      return `<col min="${i + 1}" max="${i + 1}" width="${Math.min(50, Math.max(10, maior + 2))}" customWidth="1"/>`;
    })
    .join('');

  const cabecalhoXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
  const arquivos = [
    {
      nome: '[Content_Types].xml',
      texto: `${cabecalhoXml}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`,
    },
    {
      nome: '_rels/.rels',
      texto: `${cabecalhoXml}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
    },
    {
      nome: 'xl/workbook.xml',
      texto: `${cabecalhoXml}<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${SOLTOS.arquivo.planilha}" sheetId="1" r:id="rId1"/></sheets></workbook>`,
    },
    {
      nome: 'xl/_rels/workbook.xml.rels',
      texto: `${cabecalhoXml}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`,
    },
    {
      nome: 'xl/styles.xml',
      texto: `${cabecalhoXml}<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="4" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/></cellXfs></styleSheet>`,
    },
    {
      nome: 'xl/worksheets/sheet1.xml',
      texto: `${cabecalhoXml}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>${colunas}</cols><sheetData>${linhas.join('')}</sheetData></worksheet>`,
    },
  ];
  return montarZip(arquivos.map((a) => ({ nome: a.nome, conteudo: CODIFICAR.encode(a.texto) })));
}

/**
 * PDF sem biblioteca: texto em Helvetica (Windows-1252, que tem todas as letras do português), página
 * A4 deitada, os títulos das colunas repetidos em cada página e "Página x de y" no pé. Texto grande
 * demais para a coluna é cortado com "..." (o Excel e o CSV trazem o texto inteiro).
 */
import type { Celula, Grade } from './grade';
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';

const LARGURA = 842;
const ALTURA = 595;
const MARGEM = 28;
const TAMANHO = 7; // fonte padrão; encolhe até TAMANHO_MINIMO quando há muitas colunas
const TAMANHO_MINIMO = 5;
const FOLGA = 4; // pontos de respiro dentro de cada coluna
const ALTURA_DA_LINHA = 11;
const LARGURA_DO_CARACTERE = 0.55; // em corpos de fonte; cobre números e maiúsculas sem estourar a coluna

/**
 * Sinais que o Windows-1252 guarda entre 0x80 e 0x9F (travessão, reticências, aspas curvas, euro...).
 * Sem esta tabela, "Compra de 05/11 — Ouro" saía como "Compra de 05/11 ? Ouro" no PDF.
 */
const WINDOWS_1252: Record<string, number> = {
  '€': 0x80,
  '‚': 0x82,
  ƒ: 0x83,
  '„': 0x84,
  '…': 0x85,
  '†': 0x86,
  '‡': 0x87,
  ˆ: 0x88,
  '‰': 0x89,
  Š: 0x8a,
  '‹': 0x8b,
  Œ: 0x8c,
  Ž: 0x8e,
  '‘': 0x91,
  '’': 0x92,
  '“': 0x93,
  '”': 0x94,
  '•': 0x95,
  '–': 0x96,
  '—': 0x97,
  '˜': 0x98,
  '™': 0x99,
  š: 0x9a,
  '›': 0x9b,
  œ: 0x9c,
  ž: 0x9e,
  Ÿ: 0x9f,
};

/** Letras do texto em Windows-1252; o que não existe vira "?". Parênteses e barras são protegidos. */
export function paraPdf(texto: string): string {
  let saida = '';
  for (const c of texto) {
    const codigo = c.codePointAt(0)!;
    const doWindows = WINDOWS_1252[c];
    const ch =
      doWindows !== undefined
        ? String.fromCharCode(doWindows)
        : codigo <= 0xff && (codigo < 0x80 || codigo > 0x9f)
          ? c
          : '?';
    saida += ch === '(' || ch === ')' || ch === '\\' ? `\\${ch}` : ch;
  }
  return saida;
}

function caber(texto: string, largura: number, tamanho = TAMANHO): string {
  const maximo = Math.max(1, Math.floor(largura / (tamanho * LARGURA_DO_CARACTERE)));
  return texto.length <= maximo ? texto : `${texto.slice(0, Math.max(1, maximo - 3))}...`;
}

function larguraDoTexto(texto: string, tamanho = TAMANHO): number {
  return texto.length * tamanho * LARGURA_DO_CARACTERE;
}

/** Título em até duas linhas, quebrando no espaço que deixa as duas metades mais parecidas. */
export function quebrarTitulo(titulo: string): string[] {
  const palavras = titulo.split(' ');
  if (palavras.length < 2) return [titulo];
  let melhor: string[] = [titulo];
  let menor = titulo.length;
  for (let k = 1; k < palavras.length; k++) {
    const a = palavras.slice(0, k).join(' ');
    const b = palavras.slice(k).join(' ');
    const maior = Math.max(a.length, b.length);
    if (maior < menor) {
      menor = maior;
      melhor = [a, b];
    }
  }
  return melhor;
}

function textoDaCelula(celula: Celula): string {
  const n = celula.numero ? formatarNumero(celula.v) : celula.v;
  return celula.moeda ? `${celula.moeda} ${n}` : n;
}

export interface PlanoDeColunas {
  tamanho: number;
  colunas: { x: number; largura: number }[];
}

/**
 * Larguras e fonte das colunas. Números e títulos nunca são cortados: se não couberem na fonte
 * padrão, a fonte encolhe (até o mínimo). Só o texto livre (nome, fornecedor...) pode ser cortado
 * com "...", e só quando mesmo a fonte mínima não basta.
 */
export function planejarColunas(grade: Grade): PlanoDeColunas {
  const disponivel = LARGURA - 2 * MARGEM;
  const todas = [...grade.linhas, ...grade.rodape];
  // Quantos caracteres cada coluna precisa; texto livre fica limitado a 24 (24 no primeiro, 20 nos outros).
  const precisa = grade.titulos.map((titulo, i) => {
    const doTitulo = Math.max(...quebrarTitulo(titulo).map((l) => l.length));
    const dosValores = Math.max(0, ...todas.map((l) => (l[i] ? textoDaCelula(l[i]).length : 0)));
    const livre = !grade.numericas[i];
    const valores = livre ? Math.min(dosValores, i === 0 ? 28 : 22) : dosValores;
    return Math.max(doTitulo, valores, 3);
  });
  const totalEmCaracteres = precisa.reduce((s, n) => s + n, 0);
  // Largura = n*0.55*t + FOLGA por coluna; cabe quando soma <= disponivel.
  const folgaTotal = FOLGA * precisa.length;
  const ideal = (disponivel - folgaTotal) / (totalEmCaracteres * LARGURA_DO_CARACTERE);
  const tamanho = Math.max(TAMANHO_MINIMO, Math.min(TAMANHO, Math.floor(ideal * 10) / 10));
  const necessarias = precisa.map((n) => n * LARGURA_DO_CARACTERE * tamanho + FOLGA);
  const soma = necessarias.reduce((s, n) => s + n, 0);
  let larguras: number[];
  if (soma <= disponivel) {
    // Sobra: reparte proporcionalmente ao que cada coluna precisa.
    larguras = necessarias.map((n) => (n / soma) * disponivel);
  } else {
    // Nem a fonte mínima basta: só as colunas de texto livre cedem espaço (e ficam com "...").
    const fixas = necessarias.reduce((s, n, i) => s + (grade.numericas[i] ? n : 0), 0);
    const livres = necessarias.reduce((s, n, i) => s + (grade.numericas[i] ? 0 : n), 0);
    const fator = Math.max(0.2, (disponivel - fixas) / livres);
    larguras = necessarias.map((n, i) => (grade.numericas[i] ? n : n * fator));
    const total = larguras.reduce((s, n) => s + n, 0);
    larguras = larguras.map((n) => (n / total) * disponivel);
  }
  let x = MARGEM;
  return {
    tamanho,
    colunas: larguras.map((largura) => {
      const coluna = { x, largura };
      x += largura;
      return coluna;
    }),
  };
}

/** Retângulo cheio em cinza claro (fundo do cabeçalho da tabela). */
export function fundoCinza(x: number, yBaixo: number, largura: number, altura: number): string {
  return `q 0.92 g ${x.toFixed(2)} ${yBaixo.toFixed(2)} ${largura.toFixed(2)} ${altura.toFixed(2)} re f Q`;
}

/** Linhas finas da tabela: horizontais nos `ys` e verticais nos `xs`, entre o topo e a base. */
export function linhasDaTabela(
  xs: number[],
  ys: number[],
  topo: number,
  base: number,
  xEsq: number,
  xDir: number,
): string {
  const o: string[] = ['q 0.45 G 0.4 w'];
  for (const y of ys)
    o.push(`${xEsq.toFixed(2)} ${y.toFixed(2)} m ${xDir.toFixed(2)} ${y.toFixed(2)} l S`);
  for (const x of xs)
    o.push(`${x.toFixed(2)} ${base.toFixed(2)} m ${x.toFixed(2)} ${topo.toFixed(2)} l S`);
  o.push('Q');
  return o.join('\n');
}

type Item = { celulas: Celula[]; negrito: boolean } | { espaco: true };

/**
 * O PDF do relatório e, se houver, tabelas a mais depois dele (ex.: as faltas do Fechamento da maleta), cada uma com as suas
 * colunas e começando numa página nova. "Página x de y" conta todas as páginas do arquivo.
 */
export function gerarPdf(grade: Grade, anexos: readonly Grade[] = []): Uint8Array {
  const planos = [grade, ...anexos].map((g) => ({ grade: g, paginas: planejarPaginas(g) }));
  const total = planos.reduce((soma, p) => soma + p.paginas.paginas.length, 0);
  let antes = 0;
  const conteudos = planos.flatMap((p) => {
    const desenhadas = desenharPaginas(p.grade, p.paginas, antes, total);
    antes += p.paginas.paginas.length;
    return desenhadas;
  });
  return montarArquivoPdf(conteudos, LARGURA, ALTURA);
}

interface PaginasPlanejadas {
  colunas: PlanoDeColunas['colunas'];
  corpo: number;
  titulosEmLinhas: string[][];
  linhasDoTitulo: number;
  paginas: Item[][];
}

function planejarPaginas(grade: Grade): PaginasPlanejadas {
  const { colunas, tamanho: corpo } = planejarColunas(grade);
  const titulosEmLinhas = grade.titulos.map(quebrarTitulo);
  const linhasDoTitulo = Math.max(...titulosEmLinhas.map((t) => t.length));
  const itens: Item[] = grade.linhas.map((celulas) => ({ celulas, negrito: false }));
  if (grade.rodape.length > 0) {
    itens.push({ espaco: true });
    for (const celulas of grade.rodape) itens.push({ celulas, negrito: true });
  }

  const alturaDoCabecalho = 16 + (grade.cabecalho.length - 1) * 11 + 8;
  const area = ALTURA - 2 * MARGEM - 14; // 14 para o "Página x de y"
  const paginas: Item[][] = [[]];
  let usado = alturaDoCabecalho + ALTURA_DA_LINHA * linhasDoTitulo + 3; // cabeçalho do relatório + títulos das colunas
  for (const item of itens) {
    const altura = 'espaco' in item ? ALTURA_DA_LINHA / 2 : ALTURA_DA_LINHA;
    if (usado + altura > area) {
      paginas.push([]);
      usado = ALTURA_DA_LINHA * linhasDoTitulo + 3;
    }
    paginas[paginas.length - 1]!.push(item);
    usado += altura;
  }
  return { colunas, corpo, titulosEmLinhas, linhasDoTitulo, paginas };
}

function desenharPaginas(
  grade: Grade,
  plano: PaginasPlanejadas,
  paginasAntes: number,
  totalDePaginas: number,
): string[] {
  const { colunas, corpo, titulosEmLinhas, linhasDoTitulo, paginas } = plano;
  return paginas.map((itensDaPagina, numero) => {
    const o: string[] = [];
    const texto = (x: number, y: number, s: string, negrito: boolean, tamanho = corpo) =>
      o.push(
        `BT /${negrito ? 'F2' : 'F1'} ${tamanho} Tf ${x.toFixed(2)} ${y.toFixed(2)} Td (${paraPdf(s)}) Tj ET`,
      );
    let y = ALTURA - MARGEM;
    if (numero === 0) {
      grade.cabecalho.forEach((linha, i) => {
        y -= i === 0 ? 14 : 11;
        texto(MARGEM, y, linha, i < 2, i === 0 ? 13 : i === 1 ? 10 : 8);
      });
      y -= 8;
    }
    // Tabela de verdade: cabeçalho com fundo cinza, linhas finas entre linhas e colunas.
    const bordasX = [
      ...colunas.map((c) => c.x),
      colunas[colunas.length - 1]!.x + colunas[colunas.length - 1]!.largura,
    ];
    const topoDaTabela = y;
    const baseDoCabecalho = y - linhasDoTitulo * ALTURA_DA_LINHA - 3;
    o.push(
      fundoCinza(MARGEM, baseDoCabecalho, LARGURA - 2 * MARGEM, topoDaTabela - baseDoCabecalho),
    );
    // Títulos das colunas (em até duas linhas), repetidos em toda página.
    for (let l = 0; l < linhasDoTitulo; l++) {
      y -= ALTURA_DA_LINHA;
      grade.titulos.forEach((_, i) => {
        const partes = titulosEmLinhas[i]!;
        // Título de uma linha só fica na linha de baixo, junto dos valores.
        const parte = partes[l - (linhasDoTitulo - partes.length)];
        if (parte === undefined) return;
        const c = colunas[i]!;
        const s = caber(parte, c.largura - FOLGA, corpo);
        texto(
          grade.numericas[i] ? c.x + c.largura - 2 - larguraDoTexto(s, corpo) : c.x + 2,
          y,
          s,
          true,
        );
      });
    }
    y -= 3;
    const horizontais = [topoDaTabela, y];
    const trechos: { topo: number; base: number }[] = [];
    let inicioDoTrecho = topoDaTabela;
    for (const item of itensDaPagina) {
      if ('espaco' in item) {
        trechos.push({ topo: inicioDoTrecho, base: y });
        y -= ALTURA_DA_LINHA / 2;
        inicioDoTrecho = y;
        horizontais.push(y);
        continue;
      }
      y -= ALTURA_DA_LINHA;
      horizontais.push(y);
      item.celulas.forEach((celula, i) => {
        if (celula.v === '') return;
        const c = colunas[i]!;
        const s = caber(textoDaCelula(celula), c.largura - FOLGA, corpo);
        texto(
          grade.numericas[i] ? c.x + c.largura - 2 - larguraDoTexto(s, corpo) : c.x + 2,
          y + 3,
          s,
          item.negrito,
        );
      });
    }
    trechos.push({ topo: inicioDoTrecho, base: y });
    for (const t of trechos) {
      const ys = horizontais.filter((h) => h <= t.topo && h >= t.base);
      o.push(linhasDaTabela(bordasX, ys, t.topo, t.base, MARGEM, LARGURA - MARGEM));
    }
    texto(
      LARGURA - MARGEM - 60,
      MARGEM - 6,
      SOLTOS.arquivo.pagina(paginasAntes + numero + 1, totalDePaginas),
      false,
      8,
    );
    return o.join('\n');
  });
}

/** Monta o arquivo PDF a partir do conteúdo de cada página (Helvetica e Helvetica-Bold como F1 e F2). */
export function montarArquivoPdf(conteudos: string[], largura: number, altura: number): Uint8Array {
  // Objetos: 1 catálogo, 2 páginas, 3 e 4 as fontes; depois, para cada página, a página e o conteúdo.
  const objetos: string[] = [];
  const idsDasPaginas = conteudos.map((_, i) => 5 + i * 2);
  objetos[1] = '<< /Type /Catalog /Pages 2 0 R >>';
  objetos[2] = `<< /Type /Pages /Kids [${idsDasPaginas.map((id) => `${id} 0 R`).join(' ')}] /Count ${conteudos.length} >>`;
  objetos[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>';
  objetos[4] =
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>';
  conteudos.forEach((conteudo, i) => {
    const id = 5 + i * 2;
    objetos[id] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${largura} ${altura}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${id + 1} 0 R >>`;
    objetos[id + 1] = `<< /Length ${conteudo.length} >>\nstream\n${conteudo}\nendstream`;
  });

  let arquivo = '%PDF-1.4\n';
  const posicoes: number[] = [];
  for (let id = 1; id < objetos.length; id++) {
    posicoes[id] = arquivo.length;
    arquivo += `${id} 0 obj\n${objetos[id]}\nendobj\n`;
  }
  const inicioDaTabela = arquivo.length;
  arquivo += `xref\n0 ${objetos.length}\n0000000000 65535 f \n`;
  for (let id = 1; id < objetos.length; id++) {
    arquivo += `${String(posicoes[id]).padStart(10, '0')} 00000 n \n`;
  }
  arquivo += `trailer\n<< /Size ${objetos.length} /Root 1 0 R >>\nstartxref\n${inicioDaTabela}\n%%EOF\n`;

  const bytes = new Uint8Array(arquivo.length);
  for (let i = 0; i < arquivo.length; i++) bytes[i] = arquivo.charCodeAt(i) & 0xff;
  return bytes;
}

/**
 * "1234.56" -> "1.234,56" no PDF. Quem decide as casas é o próprio valor (o Real, o Dólar e a
 * porcentagem trazem centavos; o Guarani e as contagens são inteiros), não o título da coluna:
 * no Fechamento da maleta os valores em Real ficam sob títulos de peças e perdiam os centavos.
 */
function formatarNumero(v: string): string {
  const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(v);
  if (!m) return v;
  const inteiro = (m[2] ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${m[1]}${inteiro}${m[3] ? `,${m[3]}` : ''}`;
}

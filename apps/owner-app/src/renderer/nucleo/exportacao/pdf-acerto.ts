/**
 * PDF do acerto fechado (tarefa 7.9): A4 em pé, no mesmo gerador à mão dos relatórios e da nota (Helvetica, sem biblioteca).
 * O PDF só desenha o texto que recebe: os valores e as frases vêm prontos da ficha do acerto (nada de dinheiro é calculado
 * aqui). O título repete em toda página e o rodapé (a cotação) fica só na última.
 */
import { paraPdf, montarArquivoPdf } from './pdf';
import { quebrar } from './pdf-nota';

const LARGURA = 595;
const ALTURA = 842;
const MARGEM = 40;
const LINHA = 13;
const CORPO = 9;
const LARGURA_DO_CARACTERE = 0.52;
const UTIL = LARGURA - 2 * MARGEM;

export type LinhaDoPdf =
  { texto: string; negrito?: boolean } | { rotulo: string; valor: string; negrito?: boolean };

export interface TabelaDoPdf {
  titulos: string[];
  /** Largura relativa de cada coluna. */
  pesos?: number[];
  linhas: string[][];
}

export interface SecaoDoPdf {
  titulo: string;
  linhas?: LinhaDoPdf[];
  tabela?: TabelaDoPdf;
}

export interface AcertoParaPdf {
  loja: string;
  titulo: string;
  cabecalho: string[];
  secoes: SecaoDoPdf[];
  /** Frase final (a cotação). */
  rodape: string;
}

type Item =
  | { tipo: 'secao'; texto: string; altura: number }
  | { tipo: 'texto'; linhas: string[]; negrito: boolean; altura: number }
  | { tipo: 'par'; rotulo: string[]; valor: string[]; negrito: boolean; altura: number }
  | { tipo: 'titulos-da-tabela'; tabela: TabelaDoPdf; larguras: number[]; altura: number }
  | { tipo: 'linha-da-tabela'; celulas: string[][]; larguras: number[]; altura: number };

const larguraDe = (s: string, tamanho: number) => s.length * tamanho * LARGURA_DO_CARACTERE;

function larguras(t: TabelaDoPdf): number[] {
  const pesos = t.titulos.map((_, i) => t.pesos?.[i] ?? 1);
  const soma = pesos.reduce((a, b) => a + b, 0);
  return pesos.map((p) => (p / soma) * UTIL);
}

/** Posições do que será desenhado: itens em ordem, cada um com a altura que ocupa. */
function itensDo(a: AcertoParaPdf): { itens: Item[]; fim: string[] } {
  const itens: Item[] = [];
  for (const s of a.secoes) {
    itens.push({ tipo: 'secao', texto: s.titulo, altura: LINHA + 14 });
    for (const l of s.linhas ?? []) {
      if ('rotulo' in l) {
        const rotulo = quebrar(l.rotulo, 250, CORPO);
        const valor = quebrar(l.valor, UTIL - 260, CORPO);
        itens.push({
          tipo: 'par',
          rotulo,
          valor,
          negrito: l.negrito === true,
          altura: Math.max(rotulo.length, valor.length) * LINHA,
        });
      } else {
        const linhas = quebrar(l.texto, UTIL, CORPO);
        itens.push({
          tipo: 'texto',
          linhas,
          negrito: l.negrito === true,
          altura: linhas.length * LINHA,
        });
      }
    }
    if (s.tabela) {
      const larg = larguras(s.tabela);
      itens.push({
        tipo: 'titulos-da-tabela',
        tabela: s.tabela,
        larguras: larg,
        altura: LINHA + 6,
      });
      for (const linha of s.tabela.linhas) {
        const celulas = linha.map((c, i) => quebrar(c, (larg[i] ?? 60) - 6, CORPO));
        itens.push({
          tipo: 'linha-da-tabela',
          celulas,
          larguras: larg,
          altura: Math.max(1, ...celulas.map((c) => c.length)) * LINHA,
        });
      }
    }
    itens.push({ tipo: 'texto', linhas: [''], negrito: false, altura: 6 });
  }
  return { itens, fim: quebrar(a.rodape, UTIL, 8) };
}

export function gerarPdfDoAcerto(acerto: AcertoParaPdf): Uint8Array {
  const { itens, fim } = itensDo(acerto);
  const cabecalhoCompleto = 18 + 20 + acerto.cabecalho.length * 14 + 14;
  const cabecalhoCurto = 34;
  const limite = ALTURA - MARGEM - 24; // 24 para o "Página x de y"
  const alturaDoFim = fim.length * 11 + 10;

  // Reparte os itens em páginas; numa página nova dentro de uma tabela, os títulos dela se repetem.
  const paginas: Item[][] = [[]];
  let usado = cabecalhoCompleto;
  let tituloAtual: Extract<Item, { tipo: 'titulos-da-tabela' }> | null = null;
  for (const item of itens) {
    if (item.tipo === 'titulos-da-tabela') tituloAtual = item;
    else if (item.tipo === 'secao') tituloAtual = null;
    if (usado + item.altura > limite) {
      paginas.push([]);
      usado = cabecalhoCurto;
      if (item.tipo === 'linha-da-tabela' && tituloAtual) {
        paginas[paginas.length - 1]!.push(tituloAtual);
        usado += tituloAtual.altura;
      }
    }
    paginas[paginas.length - 1]!.push(item);
    usado += item.altura;
  }
  if (usado + alturaDoFim > limite) paginas.push([]);

  const conteudos = paginas.map((itensDaPagina, numero) => {
    const o: string[] = [];
    const texto = (x: number, y: number, s: string, negrito = false, tamanho = CORPO) =>
      o.push(
        `BT /${negrito ? 'F2' : 'F1'} ${tamanho} Tf ${x.toFixed(2)} ${y.toFixed(2)} Td (${paraPdf(s)}) Tj ET`,
      );
    const traco = (y: number, x1 = MARGEM, x2 = LARGURA - MARGEM) =>
      o.push(`0.5 w ${x1} ${y.toFixed(2)} m ${x2} ${y.toFixed(2)} l S`);

    let y = ALTURA - MARGEM;
    if (numero === 0) {
      y -= 18;
      texto(MARGEM, y, acerto.loja, true, 18);
      y -= 20;
      texto(MARGEM, y, acerto.titulo, true, 12);
      for (const linha of acerto.cabecalho) {
        y -= 14;
        texto(MARGEM, y, linha);
      }
      y -= 14;
    } else {
      y -= 12;
      texto(MARGEM, y, `${acerto.loja} - ${acerto.titulo}`, true, 10);
      y -= 22;
    }

    for (const item of itensDaPagina) {
      const topo = y - LINHA;
      switch (item.tipo) {
        case 'secao':
          texto(MARGEM, topo - 4, item.texto, true, 11);
          traco(topo - 8);
          break;
        case 'texto':
          item.linhas.forEach((l, k) => texto(MARGEM, topo - k * LINHA, l, item.negrito));
          break;
        case 'par':
          item.rotulo.forEach((l, k) => texto(MARGEM, topo - k * LINHA, l, item.negrito));
          item.valor.forEach((l, k) => texto(MARGEM + 260, topo - k * LINHA, l, item.negrito));
          break;
        case 'titulos-da-tabela': {
          let x = MARGEM;
          item.tabela.titulos.forEach((t, i) => {
            texto(x + 2, topo, t, true);
            x += item.larguras[i] ?? 0;
          });
          traco(topo - 4);
          break;
        }
        case 'linha-da-tabela': {
          let x = MARGEM;
          item.celulas.forEach((linhasDaCelula, i) => {
            linhasDaCelula.forEach((l, k) => texto(x + 2, topo - k * LINHA, l));
            x += item.larguras[i] ?? 0;
          });
          break;
        }
      }
      y -= item.altura;
    }
    if (numero === paginas.length - 1) {
      y -= 10;
      fim.forEach((l, k) => texto(MARGEM, y - k * 11, l, false, 8));
    }
    texto(
      LARGURA - MARGEM - larguraDe(`Página ${numero + 1} de ${paginas.length}`, 8),
      MARGEM - 8,
      `Página ${numero + 1} de ${paginas.length}`,
      false,
      8,
    );
    return o.join('\n');
  });

  return montarArquivoPdf(conteudos, LARGURA, ALTURA);
}

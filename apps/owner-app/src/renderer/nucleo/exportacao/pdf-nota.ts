/**
 * PDF da "Nota de remessa" (tarefa 3.7): A4 em pé, no mesmo gerador à mão dos relatórios (Helvetica,
 * sem biblioteca). Cabeçalho da loja e a tabela de peças se repetem em cada página; os totais, o aviso
 * da cotação e as linhas de assinatura ficam só na última. Sem foto (decisão da tarefa 3.7). Os valores
 * vêm prontos do servidor: nada de dinheiro é calculado aqui.
 */
import {
  dataParaTela,
  formatarDolar,
  formatarGuarani,
  formatarReais,
  centavosDeTexto,
} from '../moedas';
import { TEXTOS_DE_MALETAS } from '../../textos-maletas';
import { dataEHoraParaTela } from './grade';
import { montarArquivoPdf, paraPdf } from './pdf';
import { TEXTOS_SOLTOS as SOLTOS } from '../../textos-soltos';

const T = TEXTOS_DE_MALETAS.nota;

const LARGURA = 595;
const ALTURA = 842;
const MARGEM = 40;
const LINHA = 13;
const CORPO = 9;
const LARGURA_DO_CARACTERE = 0.52;
/** Colunas: código, peça, quantidade, preço, subtotal (x inicial e largura). */
const COLUNAS = [
  { x: MARGEM, largura: 85 },
  { x: MARGEM + 85, largura: 215 },
  { x: MARGEM + 300, largura: 40 },
  { x: MARGEM + 340, largura: 90 },
  { x: MARGEM + 430, largura: 85 },
];
/** Espaço que a última página precisa para totais, aviso e as duas assinaturas. */
const ALTURA_DO_FIM = 190;

export interface NotaParaPdf {
  loja: string;
  numero: string;
  vendedor: string;
  praca: string;
  semana: string;
  tipo: string;
  enviadaEm: string;
  cancelada: boolean;
  motivoDoCancelamento: string;
  itens: { codigo: string; nome: string; quantidade: string; preco: string; subtotal: string }[];
  totalPecas: string;
  totalValor: string;
  cotacao: { data: string; totalEmDolar: string; totalEmGuarani: string } | null;
}

function reais(valor: string): string {
  const c = centavosDeTexto(valor);
  return c === null ? valor : formatarReais(c);
}

/** Quebra o texto em linhas que cabem na largura (por palavras; palavra enorme é cortada). */
export function quebrar(texto: string, largura: number, tamanho: number): string[] {
  const maximo = Math.max(4, Math.floor(largura / (tamanho * LARGURA_DO_CARACTERE)));
  const linhas: string[] = [];
  let atual = '';
  for (let palavra of texto.split(/\s+/).filter(Boolean)) {
    while (palavra.length > maximo) {
      if (atual) {
        linhas.push(atual);
        atual = '';
      }
      linhas.push(palavra.slice(0, maximo));
      palavra = palavra.slice(maximo);
    }
    if (!atual) atual = palavra;
    else if (`${atual} ${palavra}`.length <= maximo) atual = `${atual} ${palavra}`;
    else {
      linhas.push(atual);
      atual = palavra;
    }
  }
  if (atual) linhas.push(atual);
  return linhas.length > 0 ? linhas : [''];
}

const larguraDe = (s: string, tamanho: number) => s.length * tamanho * LARGURA_DO_CARACTERE;

type Linha = { celulas: string[]; altura: number };

export function gerarPdfDaNota(nota: NotaParaPdf): Uint8Array {
  // Linhas da tabela, já quebradas; a altura de cada uma depende do nome.
  const linhas: Linha[] = nota.itens.map((i) => {
    const nome = quebrar(i.nome, COLUNAS[1]!.largura - 6, CORPO);
    return {
      celulas: [i.codigo, nome.join('\n'), i.quantidade, i.preco, i.subtotal],
      altura: nome.length * LINHA,
    };
  });

  const alturaDoCabecalhoCompleto = 150 + (nota.cancelada ? 44 : 0);
  const alturaDoCabecalhoCurto = 50;
  const alturaDosTitulos = LINHA + 6;
  const limite = ALTURA - MARGEM - 24; // 24 para o "Página x de y"

  const paginas: Linha[][] = [[]];
  let usado = alturaDoCabecalhoCompleto + alturaDosTitulos;
  for (const l of linhas) {
    if (usado + l.altura > limite) {
      paginas.push([]);
      usado = alturaDoCabecalhoCurto + alturaDosTitulos;
    }
    paginas[paginas.length - 1]!.push(l);
    usado += l.altura;
  }
  // A assinatura fica na última página: se não couber o fim, ele ganha uma página só para ele.
  const fimNaUltima = usado + ALTURA_DO_FIM <= limite;
  if (!fimNaUltima) paginas.push([]);

  const conteudos = paginas.map((itens, numero) => {
    const o: string[] = [];
    const texto = (x: number, y: number, s: string, negrito = false, tamanho = CORPO) =>
      o.push(
        `BT /${negrito ? 'F2' : 'F1'} ${tamanho} Tf ${x.toFixed(2)} ${y.toFixed(2)} Td (${paraPdf(s)}) Tj ET`,
      );
    const aDireita = (xFim: number, y: number, s: string, negrito = false, tamanho = CORPO) =>
      texto(xFim - larguraDe(s, tamanho), y, s, negrito, tamanho);
    const traco = (y: number, x1 = MARGEM, x2 = LARGURA - MARGEM) =>
      o.push(`0.5 w ${x1} ${y.toFixed(2)} m ${x2} ${y.toFixed(2)} l S`);

    let y = ALTURA - MARGEM;
    if (numero === 0) {
      y -= 18;
      texto(MARGEM, y, nota.loja, true, 18);
      y -= 18;
      texto(MARGEM, y, T.titulo, true, 12);
      if (nota.cancelada) {
        y -= 26;
        texto(MARGEM, y, T.cancelada, true, 22);
        if (nota.motivoDoCancelamento) {
          y -= 14;
          texto(MARGEM, y, quebrar(nota.motivoDoCancelamento, 480, CORPO)[0] ?? '', false, CORPO);
        }
      }
      y -= 22;
      texto(MARGEM, y, `${T.maleta}: ${nota.numero}`, true, 11);
      y -= 16;
      texto(MARGEM, y, `${T.vendedor}: ${nota.vendedor}`);
      texto(MARGEM + 300, y, `${T.praca}: ${nota.praca}`);
      y -= 14;
      texto(MARGEM, y, `${T.semana}: ${nota.semana}`);
      texto(MARGEM + 300, y, `${T.tipo}: ${nota.tipo}`);
      y -= 14;
      texto(MARGEM, y, `${T.enviadaEm}: ${nota.enviadaEm}`);
      y -= 20;
    } else {
      y -= 12;
      texto(MARGEM, y, `${nota.loja} - ${T.titulo}`, true, 10);
      aDireita(LARGURA - MARGEM, y, `${T.maleta}: ${nota.numero}`, true, 10);
      if (nota.cancelada) texto(MARGEM + 250, y, T.cancelada, true, 10);
      y -= 24;
    }

    // Títulos da tabela, repetidos em toda página.
    y -= LINHA;
    T.colunas.forEach((titulo, i) => {
      const c = COLUNAS[i]!;
      if (i >= 2) aDireita(c.x + c.largura - 2, y, titulo, true);
      else texto(c.x + 2, y, titulo, true);
    });
    traco(y - 4);
    y -= 6;

    for (const l of itens) {
      const nomeLinhas = l.celulas[1]!.split('\n');
      const topo = y - LINHA;
      texto(
        COLUNAS[0]!.x + 2,
        topo,
        quebrar(l.celulas[0]!, COLUNAS[0]!.largura - 4, CORPO)[0] ?? '',
      );
      nomeLinhas.forEach((nl, k) => texto(COLUNAS[1]!.x + 2, topo - k * LINHA, nl));
      for (const i of [2, 3, 4]) {
        const c = COLUNAS[i]!;
        aDireita(c.x + c.largura - 2, topo, l.celulas[i]!);
      }
      y -= l.altura;
    }

    const ultima = numero === paginas.length - 1;
    if (ultima) {
      y -= 6;
      traco(y);
      y -= 18;
      texto(MARGEM, y, `${T.pecas}: ${nota.totalPecas}`, true, 10);
      y -= 15;
      texto(MARGEM, y, `${T.valor}: ${reais(nota.totalValor)}`, true, 10);
      if (nota.cotacao) {
        const d = centavosDeTexto(nota.cotacao.totalEmDolar);
        const g = centavosDeTexto(nota.cotacao.totalEmGuarani);
        y -= 14;
        const partes = [
          d !== null ? formatarDolar(d) : '',
          g !== null ? formatarGuarani(g / 100n) : '',
        ].filter(Boolean);
        if (partes.length > 0) texto(MARGEM, y, partes.join('   '), false, 10);
        y -= 13;
        texto(MARGEM, y, T.cotacao(dataParaTela(nota.cotacao.data)), false, 8);
      }
      // Assinaturas: dona e vendedor.
      y -= 70;
      traco(y, MARGEM, MARGEM + 220);
      traco(y, MARGEM + 295, LARGURA - MARGEM);
      texto(MARGEM, y - 12, T.assinaturaDona);
      texto(MARGEM + 295, y - 12, `${T.assinaturaVendedor}: ${nota.vendedor}`);
    }
    texto(
      LARGURA - MARGEM - 60,
      MARGEM - 8,
      SOLTOS.arquivo.pagina(numero + 1, paginas.length),
      false,
      8,
    );
    return o.join('\n');
  });

  return montarArquivoPdf(conteudos, LARGURA, ALTURA);
}

/** Lê a resposta da rota da nota (dados congelados no envio) para o gerador; `null` se vier faltando algo. */
export function lerNota(bruto: unknown): NotaParaPdf | null {
  const n = (typeof bruto === 'object' && bruto !== null ? bruto : null) as Record<
    string,
    unknown
  > | null;
  if (!n || !Array.isArray(n.itens)) return null;
  const txt = (v: unknown) => (typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');
  const obj = (v: unknown) =>
    typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : {};
  const itens = n.itens.map((i) => {
    const x = obj(i);
    return {
      codigo: txt(x.codigo),
      nome: txt(x.nome),
      quantidade: txt(x.quantidade),
      preco: reais(txt(x.precoRemessa)),
      subtotal: reais(txt(x.subtotal)),
    };
  });
  const cot = obj(n.cotacao);
  const enviada = txt(n.enviadaEm);
  const tipo = txt(n.tipo);
  return {
    loja: txt(n.loja) || 'Corona Semijoias',
    numero: txt(n.numero),
    vendedor: txt(obj(n.vendedor).nome),
    praca: txt(n.praca),
    semana: txt(obj(n.semana).numero),
    tipo:
      tipo === 'REPOSICAO'
        ? SOLTOS.arquivo.nota.reposicao
        : tipo === 'NOVA'
          ? SOLTOS.arquivo.nota.nova
          : tipo,
    enviadaEm: enviada ? dataEHoraParaTela(enviada) : '',
    cancelada: n.cancelada === true,
    motivoDoCancelamento: txt(n.motivoDoCancelamento),
    itens,
    totalPecas: txt(n.totalPecas),
    totalValor: txt(n.totalValor),
    cotacao:
      txt(cot.data) && txt(cot.totalEmDolar) && txt(cot.totalEmGuarani)
        ? {
            data: txt(cot.data),
            totalEmDolar: txt(cot.totalEmDolar),
            totalEmGuarani: txt(cot.totalEmGuarani),
          }
        : null,
  };
}

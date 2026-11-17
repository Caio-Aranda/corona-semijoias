/**
 * Planilha de produtos (Excel .xlsx ou CSV): leitura do arquivo e prévia linha a linha. Nada aqui
 * grava: a tela só grava depois que a pessoa confirma a prévia.
 */
import { TAMANHO_MAXIMO_DO_CODIGO } from '../../shared/produtos';
import { chaveDoCodigo, normalizarCodigo } from './codigos';
import { TEXTOS_SOLTOS as SOLTOS } from '../textos-soltos';

export const TAMANHO_MAXIMO_DA_PLANILHA = 20 * 1024 * 1024;
export const MAXIMO_DE_LINHAS = 5000;

export type Tabela = string[][];

// ---------------------------------------------------------------- CSV

function dividirCsv(texto: string, separador: string): Tabela {
  const linhas: Tabela = [];
  let linha: string[] = [];
  let campo = '';
  let entreAspas = false;
  for (let i = 0; i < texto.length; i++) {
    const c = texto[i]!;
    if (entreAspas) {
      if (c === '"') {
        if (texto[i + 1] === '"') {
          campo += '"';
          i++;
        } else entreAspas = false;
      } else campo += c;
    } else if (c === '"') entreAspas = true;
    else if (c === separador) {
      linha.push(campo);
      campo = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && texto[i + 1] === '\n') i++;
      linha.push(campo);
      campo = '';
      linhas.push(linha);
      linha = [];
    } else campo += c;
  }
  if (campo !== '' || linha.length > 0) {
    linha.push(campo);
    linhas.push(linha);
  }
  return linhas.filter((l) => l.some((x) => x.trim() !== ''));
}

/** CSV com vírgula, ponto e vírgula ou tabulação (o que a primeira linha mais usar). */
export function lerCsv(texto: string): Tabela {
  const limpo = texto.replace(/^\uFEFF/, '');
  const primeira = limpo.split(/\r?\n/, 1)[0] ?? '';
  const contar = (s: string) => primeira.split(s).length - 1;
  const separador = [';', ',', '\t'].sort((a, b) => contar(b) - contar(a))[0] ?? ';';
  return dividirCsv(limpo, separador);
}

export function decodificarCsv(bytes: Uint8Array): string {
  const utf8 = new TextDecoder('utf-8').decode(bytes);
  // Planilha salva no Excel em português costuma ser Windows-1252.
  return utf8.includes('�') ? new TextDecoder('windows-1252').decode(bytes) : utf8;
}

// ---------------------------------------------------------------- XLSX

async function descomprimir(bytes: Uint8Array): Promise<Uint8Array> {
  const fluxo = new Blob([bytes as BlobPart])
    .stream()
    .pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(fluxo).arrayBuffer());
}

/** Lê os arquivos de dentro do .xlsx (que é um zip): só o que a leitura precisa. */
async function lerZip(bytes: Uint8Array): Promise<Map<string, Uint8Array>> {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let fim = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65_557); i--) {
    if (v.getUint32(i, true) === 0x06054b50) {
      fim = i;
      break;
    }
  }
  if (fim < 0) throw new Error('zip');
  const total = v.getUint16(fim + 10, true);
  let p = v.getUint32(fim + 16, true);
  const arquivos = new Map<string, Uint8Array>();
  const nomes = new TextDecoder();
  for (let n = 0; n < total; n++) {
    if (v.getUint32(p, true) !== 0x02014b50) throw new Error('zip');
    const metodo = v.getUint16(p + 10, true);
    const tamanhoComprimido = v.getUint32(p + 20, true);
    const tamNome = v.getUint16(p + 28, true);
    const tamExtra = v.getUint16(p + 30, true);
    const tamComentario = v.getUint16(p + 32, true);
    const inicio = v.getUint32(p + 42, true);
    const nome = nomes.decode(bytes.subarray(p + 46, p + 46 + tamNome));
    p += 46 + tamNome + tamExtra + tamComentario;
    if (!/^xl\/(sharedStrings\.xml|worksheets\/[^/]+\.xml)$/.test(nome)) continue;
    const dados = inicio + 30 + v.getUint16(inicio + 26, true) + v.getUint16(inicio + 28, true);
    const conteudo = bytes.subarray(dados, dados + tamanhoComprimido);
    if (metodo === 0) arquivos.set(nome, conteudo);
    else if (metodo === 8) arquivos.set(nome, await descomprimir(conteudo));
    else throw new Error('zip');
  }
  return arquivos;
}

const entidades: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
function textoXml(s: string): string {
  return s.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (_, e: string) => {
    if (e[0] === '#') {
      const n = e[1]?.toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(n) ? String.fromCodePoint(n) : '';
    }
    return entidades[e.toLowerCase()] ?? '';
  });
}

function juntarTextos(xml: string): string {
  let saida = '';
  for (const m of xml.matchAll(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g)) saida += textoXml(m[1] ?? '');
  return saida;
}

function colunaDaCelula(ref: string): number {
  let n = 0;
  for (const c of /^[A-Z]+/i.exec(ref)?.[0] ?? '')
    n = n * 26 + (c.toUpperCase().charCodeAt(0) - 64);
  return n - 1;
}

export async function lerXlsx(bytes: Uint8Array): Promise<Tabela> {
  const arquivos = await lerZip(bytes);
  const decodificar = new TextDecoder();
  const compartilhadas: string[] = [];
  const cs = arquivos.get('xl/sharedStrings.xml');
  if (cs) {
    for (const m of decodificar.decode(cs).matchAll(/<si(?:\s[^>]*)?>([\s\S]*?)<\/si>/g)) {
      compartilhadas.push(juntarTextos(m[1] ?? ''));
    }
  }
  const nomeDaFolha = arquivos.has('xl/worksheets/sheet1.xml')
    ? 'xl/worksheets/sheet1.xml'
    : [...arquivos.keys()].filter((k) => k.startsWith('xl/worksheets/')).sort()[0];
  const folha = nomeDaFolha ? arquivos.get(nomeDaFolha) : undefined;
  if (!folha) throw new Error('planilha');
  const tabela: Tabela = [];
  for (const linha of decodificar.decode(folha).matchAll(/<row(?:\s[^>]*)?>([\s\S]*?)<\/row>/g)) {
    const celulas: string[] = [];
    for (const c of (linha[1] ?? '').matchAll(/<c\s([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g)) {
      const atributos = c[1] ?? '';
      const ref = /\br="([A-Z]+\d+)"/i.exec(atributos)?.[1];
      const tipo = /\bt="([^"]+)"/.exec(atributos)?.[1];
      const corpo = c[2] ?? '';
      const v = /<v>([\s\S]*?)<\/v>/.exec(corpo)?.[1] ?? '';
      const valor =
        tipo === 'inlineStr'
          ? juntarTextos(corpo)
          : tipo === 's'
            ? (compartilhadas[Number(v)] ?? '')
            : textoXml(v);
      const coluna = ref ? colunaDaCelula(ref) : celulas.length;
      while (celulas.length < coluna) celulas.push('');
      celulas[coluna] = valor;
    }
    if (celulas.some((x) => x.trim() !== '')) tabela.push(celulas);
  }
  return tabela;
}

export async function lerPlanilha(nome: string, bytes: Uint8Array): Promise<Tabela> {
  return /\.xlsx$/i.test(nome) ? lerXlsx(bytes) : lerCsv(decodificarCsv(bytes));
}

// ---------------------------------------------------------------- Prévia

export const semAcento = (s: string): string =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').trim().toLowerCase().replace(/\s+/g, ' ');

export type ColunaDaPlanilha = 'codigo' | 'nome' | 'categoria' | 'custo' | 'preco';

const APELIDOS: Record<ColunaDaPlanilha, string[]> = {
  codigo: ['codigo', 'cod', 'codigo da loja', 'codigo de barras', 'codigo de la tienda'],
  nome: ['nome', 'produto', 'descricao', 'nombre', 'producto'],
  categoria: ['categoria'],
  custo: [
    'custo',
    'preco de custo',
    'preco custo',
    'valor de custo',
    'costo',
    'precio de costo',
    'precio costo',
    'valor de costo',
  ],
  preco: [
    'preco',
    'preco de venda',
    'preco venda',
    'venda',
    'valor de venda',
    'precio',
    'precio de venta',
    'precio venta',
    'valor de venta',
  ],
};

export const COLUNAS_OBRIGATORIAS: ColunaDaPlanilha[] = [
  'codigo',
  'nome',
  'categoria',
  'custo',
  'preco',
];

export function achar(cabecalho: string[]): Partial<Record<ColunaDaPlanilha, number>> {
  const mapa: Partial<Record<ColunaDaPlanilha, number>> = {};
  cabecalho.forEach((titulo, i) => {
    const t = semAcento(titulo);
    for (const [coluna, apelidos] of Object.entries(APELIDOS) as [ColunaDaPlanilha, string[]][]) {
      if (mapa[coluna] === undefined && apelidos.includes(t)) mapa[coluna] = i;
    }
  });
  return mapa;
}

/** "1.234,56", "12,5", "12.5" e "R$ 10" viram "1234.56", "12.50", "12.50" e "10.00". */
export function lerDinheiroDaPlanilha(bruto: string): string | null {
  const t = bruto.trim().replace(/^R\$\s*/, '');
  let inteiro: string;
  let centavos = '';
  let m: RegExpExecArray | null;
  if ((m = /^(\d{1,3}(?:\.\d{3})+|\d+),(\d{1,2})$/.exec(t))) {
    inteiro = m[1]!.replace(/\./g, '');
    centavos = m[2]!;
  } else if ((m = /^(\d+)\.(\d{1,2})$/.exec(t))) {
    inteiro = m[1]!;
    centavos = m[2]!;
  } else if ((m = /^(\d{1,3}(?:\.\d{3})+|\d+)$/.exec(t))) {
    inteiro = m[1]!.replace(/\./g, '');
  } else return null;
  if (inteiro.length > 12) return null;
  return `${inteiro}.${centavos.padEnd(2, '0') || '00'}`;
}

export interface CategoriaConhecida {
  id: string;
  nome: string;
}

export interface LinhaLida {
  /** Número da linha na planilha (a primeira de dados é a 2). */
  linha: number;
  codigo: string;
  nome: string;
  categoria: string;
  categoriaId?: string;
  custo?: string;
  preco?: string;
  /** Motivo da recusa, em português; vazio quando a linha está boa. */
  motivo?: string;
}

export type ResultadoDaLeitura =
  { tipo: 'erro'; mensagem: string } | { tipo: 'ok'; linhas: LinhaLida[] };

/** Confere a planilha linha a linha, sem falar com o servidor. */
export function analisarPlanilha(
  tabela: Tabela,
  categorias: readonly CategoriaConhecida[],
): ResultadoDaLeitura {
  const [cabecalho, ...dados] = tabela;
  if (!cabecalho) return { tipo: 'erro', mensagem: SOLTOS.planilha.vazia };
  const colunas = achar(cabecalho);
  const faltam = COLUNAS_OBRIGATORIAS.filter((c) => colunas[c] === undefined);
  if (faltam.length > 0) {
    return {
      tipo: 'erro',
      mensagem: SOLTOS.planilha.faltamColunas(
        faltam.map((c) => SOLTOS.planilha.colunas[c] ?? c).join(', '),
      ),
    };
  }
  if (dados.length === 0) return { tipo: 'erro', mensagem: SOLTOS.planilha.semLinhasDeProdutos };
  if (dados.length > MAXIMO_DE_LINHAS) {
    return {
      tipo: 'erro',
      mensagem: SOLTOS.planilha.linhasDemais(MAXIMO_DE_LINHAS),
    };
  }
  const porNome = new Map(categorias.map((c) => [semAcento(c.nome), c]));
  const codigosVistos = new Map<string, number>();
  const linhas = dados.map((celulas, i): LinhaLida => {
    const pegar = (c: ColunaDaPlanilha) => (celulas[colunas[c]!] ?? '').trim();
    const l: LinhaLida = {
      linha: i + 2,
      codigo: normalizarCodigo(pegar('codigo')),
      nome: pegar('nome'),
      categoria: pegar('categoria'),
    };
    const recusar = (motivo: string) => {
      l.motivo ??= motivo;
    };
    if (l.codigo === '') recusar(SOLTOS.planilha.faltaCodigo);
    else if (l.codigo.length > TAMANHO_MAXIMO_DO_CODIGO) recusar(SOLTOS.planilha.codigoLongo);
    else {
      const chave = chaveDoCodigo(l.codigo);
      const antes = codigosVistos.get(chave);
      if (antes !== undefined) recusar(SOLTOS.planilha.codigoRepetido(l.codigo, antes));
      else codigosVistos.set(chave, l.linha);
    }
    if (l.nome === '') recusar(SOLTOS.planilha.faltaNome);
    else if (l.nome.length > 200) recusar(SOLTOS.planilha.nomeLongo);
    const categoria = porNome.get(semAcento(l.categoria));
    if (l.categoria === '') recusar(SOLTOS.planilha.faltaCategoria);
    else if (!categoria) recusar(SOLTOS.planilha.categoriaNaoExiste(l.categoria));
    else l.categoriaId = categoria.id;
    const custo = lerDinheiroDaPlanilha(pegar('custo'));
    if (custo === null) recusar(SOLTOS.planilha.custoInvalido);
    else l.custo = custo;
    const preco = lerDinheiroDaPlanilha(pegar('preco'));
    if (preco === null) recusar(SOLTOS.planilha.precoInvalido);
    else l.preco = preco;
    return l;
  });
  return { tipo: 'ok', linhas };
}

export interface ProdutoExistente {
  id: string;
  version: number;
  nome?: string;
  referencia?: string;
  fornecedorId?: string;
  estoqueMinimo?: number;
}

export interface LinhaDaPrevia extends LinhaLida {
  situacao: 'criar' | 'atualizar' | 'recusar';
  existente?: ProdutoExistente;
}

/** Junta a leitura com o que já existe: código conhecido atualiza, desconhecido cria. */
export function montarPrevia(
  linhas: readonly LinhaLida[],
  existentes: ReadonlyMap<string, ProdutoExistente>,
): LinhaDaPrevia[] {
  return linhas.map((l) => {
    if (l.motivo) return { ...l, situacao: 'recusar' };
    const existente = existentes.get(chaveDoCodigo(l.codigo));
    return existente ? { ...l, situacao: 'atualizar', existente } : { ...l, situacao: 'criar' };
  });
}

export function contarPrevia(linhas: readonly LinhaDaPrevia[]): {
  criar: number;
  atualizar: number;
  recusar: number;
} {
  const n = { criar: 0, atualizar: 0, recusar: 0 };
  for (const l of linhas) n[l.situacao]++;
  return n;
}

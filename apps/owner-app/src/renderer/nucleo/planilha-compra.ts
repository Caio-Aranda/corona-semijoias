/**
 * Planilha da compra (Excel .xlsx ou CSV, colunas código, quantidade e custo): conferência linha a
 * linha e prévia. Nada aqui grava: a tela só grava depois que a pessoa confirma a prévia, e a
 * planilha inteira vira uma compra só.
 */
import { MAXIMO_DE_ITENS_DA_COMPRA } from '../../shared/compras';
import { TAMANHO_MAXIMO_DO_CODIGO } from '../../shared/produtos';
import { TEXTOS_DE_COMPRAS } from '../textos-compras';
import { custoDaPlanilhaEmCentavos, lerQuantidade } from './compra';
import { chaveDoCodigo, normalizarCodigo } from './codigos';
import { semAcento, type Tabela } from './planilha';
import { TEXTOS_SOLTOS as SOLTOS } from '../textos-soltos';

const P = TEXTOS_DE_COMPRAS.planilha.problemas;

export type ColunaDaCompra = 'codigo' | 'quantidade' | 'custo';

const APELIDOS: Record<ColunaDaCompra, string[]> = {
  codigo: ['codigo', 'cod', 'codigo da loja', 'codigo de barras', 'codigo de la tienda'],
  quantidade: ['quantidade', 'qtd', 'qtde', 'quant', 'cantidad', 'cant'],
  custo: [
    'custo',
    'preco de custo',
    'preco custo',
    'valor de custo',
    'custo unitario',
    'costo',
    'precio de costo',
    'costo unitario',
  ],
};
const OBRIGATORIAS: ColunaDaCompra[] = ['codigo', 'quantidade', 'custo'];
const NOME_NA_TELA = SOLTOS.planilha.colunasDaCompra;

export interface LinhaLidaDaCompra {
  /** Número da linha na planilha (a primeira de dados é a 2). */
  linha: number;
  codigo: string;
  /** Texto como estava na planilha, para mostrar. */
  quantidadeLida: string;
  quantidade?: number;
  /** Custo de cada peça, já arredondado para centavos. */
  custo?: bigint;
  /** Motivo da recusa, em português; vazio quando a linha está boa. */
  motivo?: string;
}

export type ResultadoDaLeituraDaCompra =
  { tipo: 'erro'; mensagem: string } | { tipo: 'ok'; linhas: LinhaLidaDaCompra[] };

function acharColunas(cabecalho: string[]): Partial<Record<ColunaDaCompra, number>> {
  const mapa: Partial<Record<ColunaDaCompra, number>> = {};
  cabecalho.forEach((titulo, i) => {
    const t = semAcento(titulo);
    for (const [coluna, apelidos] of Object.entries(APELIDOS) as [ColunaDaCompra, string[]][]) {
      if (mapa[coluna] === undefined && apelidos.includes(t)) mapa[coluna] = i;
    }
  });
  return mapa;
}

/** Confere a planilha linha a linha, sem falar com o servidor. */
export function analisarPlanilhaDeCompra(tabela: Tabela): ResultadoDaLeituraDaCompra {
  const [cabecalho, ...dados] = tabela;
  if (!cabecalho) return { tipo: 'erro', mensagem: SOLTOS.planilha.vazia };
  const colunas = acharColunas(cabecalho);
  const faltam = OBRIGATORIAS.filter((c) => colunas[c] === undefined);
  if (faltam.length > 0) {
    return {
      tipo: 'erro',
      mensagem: TEXTOS_DE_COMPRAS.planilha.colunasFaltam(
        faltam.map((c) => NOME_NA_TELA[c]).join(', '),
      ),
    };
  }
  if (dados.length === 0) {
    return { tipo: 'erro', mensagem: SOLTOS.planilha.semLinhasDePecas };
  }
  if (dados.length > MAXIMO_DE_ITENS_DA_COMPRA) {
    return {
      tipo: 'erro',
      mensagem: TEXTOS_DE_COMPRAS.planilha.linhasDemais(MAXIMO_DE_ITENS_DA_COMPRA),
    };
  }
  const vistos = new Map<string, number>();
  const linhas = dados.map((celulas, i): LinhaLidaDaCompra => {
    const pegar = (c: ColunaDaCompra) => (celulas[colunas[c]!] ?? '').trim();
    const l: LinhaLidaDaCompra = {
      linha: i + 2,
      codigo: normalizarCodigo(pegar('codigo')),
      quantidadeLida: pegar('quantidade'),
    };
    const recusar = (motivo: string) => {
      l.motivo ??= motivo;
    };
    if (l.codigo === '') recusar(P.semCodigo);
    else if (l.codigo.length > TAMANHO_MAXIMO_DO_CODIGO) recusar(P.codigoLongo);
    else {
      const chave = chaveDoCodigo(l.codigo);
      const antes = vistos.get(chave);
      if (antes !== undefined) recusar(P.repetido(antes));
      else vistos.set(chave, l.linha);
    }
    const quantidade = lerQuantidade(l.quantidadeLida);
    if (quantidade === null) recusar(P.quantidade);
    else l.quantidade = quantidade;
    const custo = custoDaPlanilhaEmCentavos(pegar('custo'));
    if (custo === null) recusar(P.custo);
    else l.custo = custo;
    return l;
  });
  return { tipo: 'ok', linhas };
}

/** A peça que o servidor achou para um código (só o que a prévia precisa). */
export interface PecaDaPrevia {
  id: string;
  nome: string;
}

export interface LinhaDaPreviaDeCompra extends LinhaLidaDaCompra {
  situacao: 'ok' | 'desconhecido' | 'erro';
  peca?: PecaDaPrevia;
}

/**
 * Junta a leitura com o que o servidor achou. Código sem peça fica "desconhecido" (a pessoa cadastra
 * ou corrige); a mesma peça em duas linhas, mesmo com códigos diferentes, recusa a segunda (o
 * servidor também recusa: a quantidade vai numa linha só).
 */
export function montarPreviaDeCompra(
  linhas: readonly LinhaLidaDaCompra[],
  achadas: ReadonlyMap<string, PecaDaPrevia>,
): LinhaDaPreviaDeCompra[] {
  const pecaNaLinha = new Map<string, number>();
  return linhas.map((l): LinhaDaPreviaDeCompra => {
    if (l.motivo) return { ...l, situacao: 'erro' };
    const peca = achadas.get(chaveDoCodigo(l.codigo));
    if (!peca) return { ...l, situacao: 'desconhecido', motivo: P.naoEncontrada };
    const antes = pecaNaLinha.get(peca.id);
    if (antes !== undefined) {
      return { ...l, peca, situacao: 'erro', motivo: P.pecaRepetida(antes) };
    }
    pecaNaLinha.set(peca.id, l.linha);
    return { ...l, peca, situacao: 'ok' };
  });
}

export function contarPreviaDeCompra(linhas: readonly LinhaDaPreviaDeCompra[]): {
  ok: number;
  desconhecido: number;
  erro: number;
} {
  const n = { ok: 0, desconhecido: 0, erro: 0 };
  for (const l of linhas) n[l.situacao]++;
  return n;
}

/** A compra só pode ser gravada quando todas as linhas estão boas (quem não serve, a pessoa tira). */
export const podeGravarAPrevia = (linhas: readonly LinhaDaPreviaDeCompra[]): boolean =>
  linhas.length > 0 && linhas.every((l) => l.situacao === 'ok');

/** Arquivo modelo (CSV com ponto e vírgula e BOM, que o Excel em português abre direito). */
export function modeloDaPlanilha(): Uint8Array {
  const texto =
    TEXTOS_DE_COMPRAS.planilha.modeloLinhas.map((l) => l.join(';')).join('\r\n') + '\r\n';
  const corpo = new TextEncoder().encode(texto);
  const saida = new Uint8Array(corpo.length + 3);
  saida.set([0xef, 0xbb, 0xbf], 0);
  saida.set(corpo, 3);
  return saida;
}

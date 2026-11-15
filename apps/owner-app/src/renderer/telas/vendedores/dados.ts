/** Formatos que a API de vendedores devolve, lidos com cuidado, e as conversões entre tela e API. */
import type { CampoDaApi } from '../../../shared/cadastros';
import { centavosDeTexto, formatarReais, lerReaisDigitados } from '../../nucleo/moedas';
import { TEXTOS } from '../../textos';

const T = TEXTOS.vendedores;

export interface Registro {
  id?: string;
  version: number;
  [campo: string]: unknown;
}

export interface Vendedor extends Registro {
  id: string;
  codigo: number;
  nome: string;
  ativo: boolean;
}

export interface PaginaDeVendedores {
  itens: Vendedor[];
  pagina: number;
  tamanho: number;
  total: number;
}

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};

export function lerRegistro(bruto: unknown): Registro {
  const o = objeto(bruto);
  return { ...o, version: typeof o.version === 'number' ? o.version : 0 };
}

export function lerVendedor(bruto: unknown): Vendedor | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string' || typeof o.nome !== 'string') return null;
  return {
    ...o,
    id: o.id,
    nome: o.nome,
    codigo: typeof o.codigo === 'number' ? o.codigo : 0,
    ativo: o.ativo !== false,
    version: typeof o.version === 'number' ? o.version : 0,
  };
}

export function lerPaginaDeVendedores(bruto: unknown): PaginaDeVendedores {
  const p = objeto(bruto);
  const itens = (Array.isArray(p.itens) ? p.itens : [])
    .map(lerVendedor)
    .filter((v): v is Vendedor => v !== null);
  return {
    itens,
    pagina: typeof p.pagina === 'number' ? p.pagina : 0,
    tamanho: typeof p.tamanho === 'number' && p.tamanho > 0 ? p.tamanho : 20,
    total: typeof p.total === 'number' ? p.total : itens.length,
  };
}

export function lerLista(bruto: unknown): Registro[] {
  return Array.isArray(bruto) ? bruto.map(lerRegistro) : [];
}

export function texto(r: Registro, campo: string): string {
  const v = r[campo];
  return typeof v === 'string' || typeof v === 'number' ? String(v) : '';
}

/** Um campo de tela da ficha. */
export interface CampoDaFicha {
  nome: string;
  /** Rótulo próprio da aba (quando o nome do campo da API se repete em outra aba). */
  rotulo?: string;
  tipo: 'texto' | 'data' | 'area' | 'selecao' | 'sim-nao' | 'dinheiro';
  obrigatorio?: boolean;
  max?: number;
  ajuda?: string;
  /** Nome da lista de opções (vem de fora, por exemplo as praças). */
  lista?: string;
  opcoes?: readonly { valor: string; rotulo: string }[];
  /** Texto da primeira opção, a que significa "nenhuma". */
  semOpcao?: string;
  /** Campo "sim ou não" que, marcado, dispensa este campo obrigatório (ex.: "Sem limite"). */
  obrigatorioExcetoSe?: string;
  /** Texto próprio de campo obrigatório vazio. */
  mensagemObrigatorio?: string;
  /** Campo "sim ou não" que, marcado, desabilita e esvazia este campo. */
  desabilitaSe?: string;
}

/** O mesmo campo pelo que a API conhece, para o tipo e o tamanho. */
export function daApi(campos: readonly CampoDaApi[]): CampoDaFicha[] {
  return campos.map((c): CampoDaFicha => {
    const base = { nome: c.nome, ...(c.max ? { max: c.max } : {}) };
    switch (c.tipo) {
      case 'data':
        return { ...base, tipo: 'data' };
      case 'dinheiro':
        return { ...base, tipo: 'dinheiro' };
      case 'booleano':
        return { ...base, tipo: 'sim-nao' };
      case 'sexo':
        return { ...base, tipo: 'selecao' };
      case 'uuid':
        return { ...base, tipo: 'selecao' };
      default:
        return { ...base, tipo: 'texto' };
    }
  });
}

/** "1500.00" → "1500,00" (o que a pessoa vê e edita). */
export function reaisParaTela(v: unknown): string {
  return typeof v === 'string' || typeof v === 'number' ? String(v).replace('.', ',') : '';
}

export function valoresIniciais(
  campos: readonly CampoDaFicha[],
  registro: Registro,
): Record<string, string> {
  const saida: Record<string, string> = {};
  for (const c of campos) {
    const v = registro[c.nome];
    switch (c.tipo) {
      case 'sim-nao':
        saida[c.nome] = v === true ? 'sim' : 'nao';
        break;
      case 'dinheiro':
        saida[c.nome] = reaisParaTela(v);
        break;
      default:
        saida[c.nome] = typeof v === 'string' ? v : '';
    }
  }
  return saida;
}

/**
 * Converte o que está na tela no corpo da API. Campo vazio não vai (a API entende como "limpar").
 * Devolve também as mensagens dos campos que a pessoa digitou errado, antes de falar com o servidor.
 */
export function montarCorpo(
  campos: readonly CampoDaFicha[],
  valores: Record<string, string>,
): { corpo: Record<string, unknown>; erros: Record<string, string> } {
  const corpo: Record<string, unknown> = {};
  const erros: Record<string, string> = {};
  for (const c of campos) {
    const bruto = valores[c.nome] ?? '';
    const v = bruto.trim();
    if (c.tipo === 'sim-nao') {
      corpo[c.nome] = v === 'sim';
      continue;
    }
    if (c.desabilitaSe && valores[c.desabilitaSe] === 'sim') continue;
    if (v === '') {
      const dispensado = c.obrigatorioExcetoSe && valores[c.obrigatorioExcetoSe] === 'sim';
      if (c.obrigatorio && !dispensado) {
        erros[c.nome] = c.mensagemObrigatorio ?? TEXTOS.formulario.obrigatorio;
      }
      continue;
    }
    if (c.tipo === 'dinheiro') {
      const reais = lerReaisDigitados(v);
      if (reais === null) erros[c.nome] = TEXTOS.dinheiro.invalido;
      else corpo[c.nome] = reais;
    } else {
      corpo[c.nome] = v;
    }
  }
  return { corpo, erros };
}

/** Telefone para a lista: o primeiro que houver. */
/**
 * O limite de consignação na lista: o valor em reais, "Sem limite" (escolha da dona) ou "Limite não
 * definido" (cadastro antigo: não recebe maleta até a dona escolher). Zero vale zero, nunca "Sem limite".
 */
export function limiteDaLista(v: Vendedor): string {
  if (v.semLimite === true) return T.limiteDaLista.semLimite;
  const centavos =
    typeof v.limiteConsignacao === 'string' ? centavosDeTexto(v.limiteConsignacao) : null;
  return centavos === null ? T.limiteDaLista.naoDefinido : formatarReais(centavos);
}

export function telefoneDaLista(v: Vendedor): string {
  return texto(v, 'telefone1') || texto(v, 'whatsapp') || texto(v, 'telefone2');
}

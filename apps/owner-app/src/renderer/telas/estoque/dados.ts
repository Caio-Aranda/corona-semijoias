/** Formatos que a API de estoque e de contagem devolve, lidos com cuidado (nada aqui confia no formato). */

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');
const inteiro = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

export interface PecaDoEstoque {
  id: string;
  nome: string;
  codigo: string;
  referencia: string;
  minimo: number;
  ativo: boolean;
  fotoHash: string | null;
}

export interface Pagina<T> {
  itens: T[];
  pagina: number;
  tamanho: number;
  total: number;
}

export function lerPeca(bruto: unknown): PecaDoEstoque | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  const foto = objeto(o.fotoPrincipal);
  return {
    id: o.id,
    nome: texto(o.nome),
    codigo: texto(o.codigo),
    referencia: texto(o.referencia),
    minimo: inteiro(o.estoqueMinimo),
    ativo: o.ativo !== false,
    fotoHash: typeof foto.hash === 'string' ? foto.hash : null,
  };
}

export function lerPagina<T>(bruto: unknown, ler: (item: unknown) => T | null): Pagina<T> {
  const o = objeto(bruto);
  const itens = (Array.isArray(o.itens) ? o.itens : []).map(ler).filter((i): i is T => i !== null);
  return {
    itens,
    pagina: inteiro(o.pagina),
    tamanho: typeof o.tamanho === 'number' && o.tamanho > 0 ? o.tamanho : 20,
    total: typeof o.total === 'number' ? o.total : itens.length,
  };
}

/** Um lançamento do histórico. `quantidade` tem sinal (positivo entra, negativo sai). */
export interface Lancamento {
  id: string;
  peca: PecaDoEstoque;
  tipo: string;
  quantidade: number;
  saldoDepois: number;
  motivo: string;
  usuarioNome: string;
  ocorridaEm: string;
  /** Se este lançamento desfaz outro, o id do outro. */
  desfazId: string | null;
  /** Se este lançamento já foi desfeito, o id de quem desfez. */
  desfeitoPorId: string | null;
}

export function lerLancamento(bruto: unknown): Lancamento | null {
  const o = objeto(bruto);
  const peca = lerPeca(o.produto);
  if (typeof o.id !== 'string' || !peca) return null;
  return {
    id: o.id,
    peca,
    tipo: texto(o.tipo),
    quantidade: inteiro(o.quantidade),
    saldoDepois: inteiro(o.saldoDepois),
    motivo: texto(o.motivo),
    usuarioNome: texto(o.usuarioNome),
    ocorridaEm: texto(o.ocorridaEm),
    desfazId: typeof o.estornaId === 'string' ? o.estornaId : null,
    desfeitoPorId: typeof o.desfeitoPorId === 'string' ? o.desfeitoPorId : null,
  };
}

export interface FichaDoEstoque {
  peca: PecaDoEstoque;
  saldo: number;
  historico: Pagina<Lancamento>;
}

export function lerFicha(bruto: unknown): FichaDoEstoque | null {
  const o = objeto(bruto);
  const peca = lerPeca(o.produto);
  if (!peca) return null;
  return { peca, saldo: inteiro(o.saldo), historico: lerPagina(o.historico, lerLancamento) };
}

export interface PecaAcabando {
  peca: PecaDoEstoque;
  saldo: number;
  minimo: number;
  falta: number;
}

export function lerAcabando(bruto: unknown): PecaAcabando | null {
  const o = objeto(bruto);
  const peca = lerPeca(o.produto);
  if (!peca) return null;
  return { peca, saldo: inteiro(o.saldo), minimo: inteiro(o.minimo), falta: inteiro(o.falta) };
}

export interface ResumoDaContagem {
  totalDePecas: number;
  lidos: number;
  naoLidos: number;
  comDiferenca: number;
  faltam: number;
  sobram: number;
}

export function lerResumo(bruto: unknown): ResumoDaContagem {
  const o = objeto(bruto);
  return {
    totalDePecas: inteiro(o.totalDePecas),
    lidos: inteiro(o.lidos),
    naoLidos: inteiro(o.naoLidos),
    comDiferenca: inteiro(o.comDiferenca),
    faltam: inteiro(o.faltam),
    sobram: inteiro(o.sobram),
  };
}

export interface Contagem {
  id: string;
  escopo: string;
  escopoNome: string;
  situacao: string;
  iniciadoEm: string;
  iniciadoPorNome: string;
  fechadoEm: string;
  fechadoPorNome: string;
  canceladoEm: string;
  motivoDoCancelamento: string;
  zerouNaoLidos: boolean;
  ajustes: number;
  /** Peças com diferença que não foram ajustadas porque foram excluídas durante a contagem. */
  naoAjustadas: { nome: string; diferenca: number }[];
  resumo: ResumoDaContagem;
}

export function lerContagem(bruto: unknown): Contagem | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  return {
    id: o.id,
    escopo: texto(o.escopo),
    escopoNome: texto(o.escopoNome),
    situacao: texto(o.situacao),
    iniciadoEm: texto(o.iniciadoEm),
    iniciadoPorNome: texto(o.iniciadoPorNome),
    fechadoEm: texto(o.fechadoEm),
    fechadoPorNome: texto(o.fechadoPorNome),
    canceladoEm: texto(o.canceladoEm),
    motivoDoCancelamento: texto(o.motivoDoCancelamento),
    zerouNaoLidos: o.zerouNaoLidos === true,
    ajustes: inteiro(o.ajustes),
    naoAjustadas: (Array.isArray(o.naoAjustadas) ? o.naoAjustadas : []).map((x) => ({
      nome: texto(objeto(x).nome),
      diferenca: inteiro(objeto(x).diferenca),
    })),
    resumo: lerResumo(o.resumo),
  };
}

export interface LinhaDeContagem {
  id: string;
  escopo: string;
  escopoNome: string;
  situacao: string;
  iniciadoEm: string;
  fechadoEm: string;
  lidos: number;
  totalDePecas: number;
}

export function lerLinhaDeContagem(bruto: unknown): LinhaDeContagem | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  return {
    id: o.id,
    escopo: texto(o.escopo),
    escopoNome: texto(o.escopoNome),
    situacao: texto(o.situacao),
    iniciadoEm: texto(o.iniciadoEm),
    fechadoEm: texto(o.fechadoEm),
    lidos: inteiro(o.lidos),
    totalDePecas: inteiro(o.totalDePecas),
  };
}

export interface ItemDaContagem {
  peca: PecaDoEstoque;
  esperado: number;
  /** Sem leitura ainda: nada contado. */
  contado: number | null;
  diferenca: number | null;
  saldoAtual: number;
}

export function lerItem(bruto: unknown): ItemDaContagem | null {
  const o = objeto(bruto);
  const peca = lerPeca(o.produto);
  if (!peca) return null;
  return {
    peca,
    esperado: inteiro(o.esperado),
    contado: typeof o.contado === 'number' ? o.contado : null,
    diferenca: typeof o.diferenca === 'number' ? o.diferenca : null,
    saldoAtual: inteiro(o.saldoAtual),
  };
}

export function lerLeitura(
  bruto: unknown,
): { item: ItemDaContagem; resumo: ResumoDaContagem } | null {
  const o = objeto(bruto);
  const item = lerItem(o.item);
  return item ? { item, resumo: lerResumo(o.resumo) } : null;
}

/** Data e hora na tela: dd/mm/aaaa hh:mm, no horário do computador. */
export function dataEHoraParaTela(iso: string): string {
  const d = new Date(iso);
  if (iso === '' || Number.isNaN(d.getTime())) return '';
  const dois = (n: number) => String(n).padStart(2, '0');
  return `${dois(d.getDate())}/${dois(d.getMonth() + 1)}/${d.getFullYear()} ${dois(d.getHours())}:${dois(d.getMinutes())}`;
}

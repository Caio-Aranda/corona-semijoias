/** Formatos que a API do consignado devolve, lidos com cuidado (nada aqui confia no formato). */

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');
const inteiro = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const lista = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
/** Percentual: o servidor manda em texto ("30.00"); número também vale. Sem casas inúteis: "30", "32.5". */
function percentual(v: unknown): string {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : Number.NaN;
  return Number.isFinite(n) ? String(n) : '';
}
/** Dinheiro do servidor chega em texto ("12.50") ou número; a tela usa sempre texto com 2 casas. */
export function dinheiro(v: unknown): string {
  const n = typeof v === 'number' ? v : typeof v === 'string' ? Number(v) : Number.NaN;
  return Number.isFinite(n) ? n.toFixed(2) : '0.00';
}

// --- Fechamento ---

export interface LeituraDoFechamentoLida {
  situacao: 'EM_ANDAMENTO' | 'CONCLUIDO' | '';
  iniciadoEm: string;
  concluidoEm: string;
  pecas: {
    produtoId: string;
    nome: string;
    comClientes: number;
    diferenca: number;
  }[];
}

export function lerLeituraDoFechamento(bruto: unknown): LeituraDoFechamentoLida | null {
  const o = objeto(bruto);
  if (typeof o.remessaId !== 'string') return null;
  const situacao = o.situacaoDoFechamento;
  return {
    situacao: situacao === 'EM_ANDAMENTO' || situacao === 'CONCLUIDO' ? situacao : '',
    iniciadoEm: texto(o.iniciadoEm),
    concluidoEm: texto(o.concluidoEm),
    pecas: lista(o.pecas).map((p) => {
      const l = objeto(p);
      return {
        produtoId: texto(l.produtoId),
        nome: texto(l.nome),
        comClientes: inteiro(l.comClientes),
        diferenca: inteiro(l.diferenca),
      };
    }),
  };
}

// --- Notas ---

export interface NotaLida {
  id: string;
  numero: string;
  vendedorId: string;
  semanaId: string;
  semana: number;
  sequencia: number;
  clienteId: string;
  cliente: string;
  saidaEm: string;
  dataCobranca: string;
  situacao: string;
  itensDx: number;
  valorDx: string;
  meta: MetaLida;
}

/** A meta do acerto da nota: `regra` vazia quando não há acerto; `bateu` só existe na regra META. */
export interface MetaLida {
  regra: 'META' | 'INFORMADA' | '';
  metaGuarani: number | null;
  vdGuarani: number | null;
  bateu: boolean | null;
}

const inteiroOuNulo = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null;

function lerMeta(o: Record<string, unknown>): MetaLida {
  const regra =
    o.regraDaComissao === 'META' || o.regraDaComissao === 'INFORMADA' ? o.regraDaComissao : '';
  return {
    regra,
    metaGuarani: inteiroOuNulo(o.metaGuarani),
    vdGuarani: inteiroOuNulo(o.vdGuarani),
    bateu: regra === 'META' ? o.bateuMeta === true : null,
  };
}

export function lerNota(bruto: unknown): NotaLida | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  return {
    id: o.id,
    numero: texto(o.numero),
    vendedorId: texto(o.vendedorId),
    semanaId: texto(o.semanaId),
    semana: inteiro(o.semana),
    sequencia: inteiro(o.sequencia),
    clienteId: texto(o.clienteId),
    cliente: texto(o.cliente),
    saidaEm: texto(o.saidaEm),
    dataCobranca: texto(o.dataCobranca),
    situacao: texto(o.situacao),
    itensDx: inteiro(o.itensDx),
    valorDx: dinheiro(o.valorDx),
    meta: lerMeta(o),
  };
}

export function lerNotas(bruto: unknown): NotaLida[] {
  return lista(bruto).flatMap((n) => {
    const nota = lerNota(n);
    return nota ? [nota] : [];
  });
}

export interface AcertoLidoDaNota {
  id: string;
  semana: number;
  acertadoEm: string;
  comissaoPercentual: string;
  itensDx: number;
  itensDv: number;
  itensVd: number;
  valorDx: string;
  valorDv: string;
  valorVd: string;
  comissao: string;
  bruto: string;
  pendenciasAnteriores: string;
  totalAReceber: string;
  totalPago: string;
  ficouDevendo: string;
  saldoDoDebito: string;
  estornado: boolean;
  motivoDoEstorno: string;
  meta: MetaLida;
}

export interface ReciboLidoDaNota {
  id: string;
  semana: number;
  sequencia: number;
  pagoEm: string;
  total: string;
  aAplicar: string;
  estornado: boolean;
  formas: { forma: string; valor: string }[];
}

export interface DetalheDaNotaLido {
  nota: NotaLida;
  linhas: { produto: string; dx: number; precoUnitario: string }[];
  acertos: AcertoLidoDaNota[];
  recibos: ReciboLidoDaNota[];
}

export function lerDetalheDaNota(bruto: unknown): DetalheDaNotaLido | null {
  const o = objeto(bruto);
  const nota = lerNota(o.nota);
  if (!nota) return null;
  return {
    nota,
    linhas: lista(o.linhas).map((b) => {
      const l = objeto(b);
      return {
        produto: texto(l.produto),
        dx: inteiro(l.dx),
        precoUnitario: dinheiro(l.precoUnitario),
      };
    }),
    acertos: lista(o.acertos).map((b) => {
      const a = objeto(b);
      return {
        id: texto(a.id),
        semana: inteiro(a.semana),
        acertadoEm: texto(a.acertadoEm),
        comissaoPercentual: percentual(a.comissaoPercentual) || '0',
        itensDx: inteiro(a.itensDx),
        itensDv: inteiro(a.itensDv),
        itensVd: inteiro(a.itensVd),
        valorDx: dinheiro(a.valorDx),
        valorDv: dinheiro(a.valorDv),
        valorVd: dinheiro(a.valorVd),
        comissao: dinheiro(a.comissao),
        bruto: dinheiro(a.bruto),
        pendenciasAnteriores: dinheiro(a.pendenciasAnteriores),
        totalAReceber: dinheiro(a.totalAReceber),
        totalPago: dinheiro(a.totalPago),
        ficouDevendo: dinheiro(a.ficouDevendo),
        saldoDoDebito: dinheiro(a.saldoDoDebito),
        estornado: a.estornado === true,
        motivoDoEstorno: texto(a.motivoDoEstorno),
        meta: lerMeta(a),
      };
    }),
    recibos: lista(o.recibos).map((b) => {
      const r = objeto(b);
      return {
        id: texto(r.id),
        semana: inteiro(r.semana),
        sequencia: inteiro(r.sequencia),
        pagoEm: texto(r.pagoEm),
        total: dinheiro(r.total),
        aAplicar: dinheiro(r.aAplicar),
        estornado: r.estornado === true,
        formas: lista(r.formas).map((f) => {
          const k = objeto(f);
          return { forma: texto(k.forma), valor: dinheiro(k.valor) };
        }),
      };
    }),
  };
}

// --- Semanas ---

export interface SemanaLida {
  id: string;
  numero: number;
  situacao: string;
  abertaEm: string;
  notas: number;
  valorDx: string;
  valorVd: string;
  comissao: string;
  recebido: string;
}

export function lerSemanas(bruto: unknown): SemanaLida[] {
  return lista(bruto).flatMap((b) => {
    const s = objeto(b);
    if (typeof s.id !== 'string') return [];
    return [
      {
        id: s.id,
        numero: inteiro(s.numero),
        situacao: texto(s.situacao),
        abertaEm: texto(s.abertaEm),
        notas: inteiro(s.notas),
        valorDx: dinheiro(s.valorDx),
        valorVd: dinheiro(s.valorVd),
        comissao: dinheiro(s.comissao),
        recebido: dinheiro(s.recebido),
      },
    ];
  });
}

// --- Cliente ---

export interface FichaDoCliente {
  clienteId: string;
  nome: string;
  pendencia: string;
  notas: NotaLida[];
  pecasComEle: { produto: string; quantidade: number }[];
  cadastro: {
    telefone: string;
    whatsapp: string;
    endereco: string;
    documento: string;
    documentoTipo: string;
    indicadoPor: string;
    comoConheceu: string;
    comissaoPercentual: string;
    representante: string;
    temFoto: boolean;
    temFotoDoDocumentoFrente: boolean;
    temFotoDoDocumentoVerso: boolean;
  };
}

export function lerFichaDoCliente(bruto: unknown): FichaDoCliente | null {
  const o = objeto(bruto);
  if (typeof o.clienteId !== 'string') return null;
  const c = objeto(o.cadastro);
  return {
    clienteId: o.clienteId,
    nome: texto(o.nome),
    pendencia: dinheiro(o.pendencia),
    notas: lerNotas(o.notas),
    pecasComEle: lista(o.pecasComEle).map((b) => {
      const p = objeto(b);
      return { produto: texto(p.produto), quantidade: inteiro(p.quantidade) };
    }),
    cadastro: {
      telefone: texto(c.telefone),
      whatsapp: texto(c.whatsapp),
      endereco: texto(c.endereco),
      documento: texto(c.documento),
      documentoTipo: texto(c.documentoTipo),
      indicadoPor: texto(c.indicadoPor),
      comoConheceu: texto(c.comoConheceu),
      comissaoPercentual: percentual(c.comissaoPercentual) || '30',
      representante: texto(c.representante),
      temFoto: c.temFoto === true,
      temFotoDoDocumentoFrente: c.temFotoDoDocumentoFrente === true,
      temFotoDoDocumentoVerso: c.temFotoDoDocumentoVerso === true,
    },
  };
}

// --- Avisos ---

export interface AvisoLido {
  id: string;
  tipo: string;
  criadoEm: string;
  vistoEm: string;
}

export function lerAvisos(bruto: unknown): AvisoLido[] {
  return lista(bruto).flatMap((b) => {
    const a = objeto(b);
    if (typeof a.id !== 'string') return [];
    return [
      { id: a.id, tipo: texto(a.tipo), criadoEm: texto(a.criadoEm), vistoEm: texto(a.vistoEm) },
    ];
  });
}

/** Representantes para escolher (a lista de vendedores ativos que a ponte já oferece). */
export function lerRepresentantes(bruto: unknown): { id: string; nome: string }[] {
  return lista(objeto(bruto).itens).flatMap((b) => {
    const v = objeto(b);
    return typeof v.id === 'string' ? [{ id: v.id, nome: texto(v.nome) }] : [];
  });
}

// --- Comissão e meta do consignado ---

export interface ConfiguracaoLida {
  metaGuarani: number;
  percentualAbaixoDaMeta: number;
  percentualNaMeta: number;
  diasDoRetorno: number;
}

export function lerConfiguracaoDoConsignado(bruto: unknown): ConfiguracaoLida {
  const o = objeto(bruto);
  return {
    metaGuarani: inteiro(o.metaGuarani),
    percentualAbaixoDaMeta: inteiro(o.percentualAbaixoDaMeta),
    percentualNaMeta: inteiro(o.percentualNaMeta),
    diasDoRetorno: inteiro(o.diasDoRetorno),
  };
}

/** Formatos que a API de acertos devolve, lidos com cuidado (nada aqui confia no formato nem calcula dinheiro). */
import type { ErroDaTela } from '../../nucleo/chamadas';
import { TEXTOS_DE_ACERTOS } from '../../textos-acertos';

export const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
export const texto = (v: unknown): string => (typeof v === 'string' ? v : '');
export const textoOuNulo = (v: unknown): string | null => (typeof v === 'string' ? v : null);
export const inteiro = (v: unknown): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : 0;
export const lista = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Troca a mensagem dos erros mais comuns pelo texto da loja que diz o que fazer; os outros ficam como o servidor mandou. */
export function erroDoAcerto(erro: ErroDaTela): ErroDaTela {
  const texto = erro.codigo ? TEXTOS_DE_ACERTOS.erros[erro.codigo] : undefined;
  return texto ? { ...erro, mensagem: texto } : erro;
}

export interface ResumoDoAcerto {
  id: string;
  status: string;
  vendedorId: string;
  vendedorNome: string;
  semanaNumero: number;
  maletas: number;
  iniciadoEm: string;
  pedidoPeloVendedor: boolean;
}

export function lerListaDeAcertos(bruto: unknown): ResumoDoAcerto[] {
  return lista(bruto).flatMap((b) => {
    const o = objeto(b);
    if (typeof o.id !== 'string') return [];
    return [
      {
        id: o.id,
        status: texto(o.status),
        vendedorId: texto(o.vendedorId),
        vendedorNome: texto(o.vendedorNome),
        semanaNumero: inteiro(o.semanaNumero),
        maletas: inteiro(o.maletas),
        iniciadoEm: texto(o.iniciadoEm),
        pedidoPeloVendedor: o.pedidoPeloVendedor === true,
      },
    ];
  });
}

export interface ProdutoDoAcerto {
  id: string;
  nome: string;
  codigo: string;
  fotoHash: string | null;
}

export function lerProdutoDoAcerto(bruto: unknown): ProdutoDoAcerto | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  return {
    id: o.id,
    nome: texto(o.nome),
    codigo: texto(o.codigo),
    fotoHash: textoOuNulo(o.fotoHash),
  };
}

export interface LinhaDaContagem {
  estadoPeca: string;
  quantidade: number;
  produto: ProdutoDoAcerto;
}

export interface LeituraDoAcerto {
  id: string;
  produtoId: string;
  estadoPeca: string;
  quantidade: number;
  lidaEm: string;
  lidaPorNome: string;
  peloVendedor: boolean;
}

export interface MaletaDoAcerto {
  id: string;
  numero: string;
  status: string;
  tipo: string;
}

export interface FichaDoAcerto {
  id: string;
  status: string;
  vendedorId: string;
  vendedorNome: string;
  semanaId: string;
  semanaNumero: number;
  iniciadoEm: string;
  desistidoEm: string;
  motivoDesistencia: string;
  pedidoPeloVendedor: boolean;
  enviosAguardando: number;
  vendasQueChegaramDepoisDoInicio: number;
  maletas: MaletaDoAcerto[];
  contagem: LinhaDaContagem[];
  leituras: LeituraDoAcerto[];
  /** O fechamento gravado, só quando o acerto está acertado (lido na ficha do acerto fechado). */
  fechamento: unknown;
}

export function lerFicha(bruto: unknown): FichaDoAcerto | null {
  const o = objeto(bruto);
  if (typeof o.id !== 'string') return null;
  const contagem: LinhaDaContagem[] = [];
  for (const b of lista(o.contagem)) {
    const c = objeto(b);
    const produto = lerProdutoDoAcerto(c.produto);
    if (produto) {
      contagem.push({
        estadoPeca: texto(c.estadoPeca),
        quantidade: inteiro(c.quantidade),
        produto,
      });
    }
  }
  const leituras: LeituraDoAcerto[] = [];
  for (const b of lista(o.leituras)) {
    const l = objeto(b);
    if (typeof l.id !== 'string') continue;
    leituras.push({
      id: l.id,
      produtoId: texto(l.produtoId),
      estadoPeca: texto(l.estadoPeca),
      quantidade: inteiro(l.quantidade),
      lidaEm: texto(l.lidaEm),
      lidaPorNome: texto(l.lidaPorNome),
      peloVendedor: l.peloVendedor === true,
    });
  }
  const maletas: MaletaDoAcerto[] = [];
  for (const b of lista(o.maletas)) {
    const m = objeto(b);
    if (typeof m.id === 'string') {
      maletas.push({
        id: m.id,
        numero: texto(m.numero),
        status: texto(m.status),
        tipo: texto(m.tipo),
      });
    }
  }
  return {
    id: o.id,
    status: texto(o.status),
    vendedorId: texto(o.vendedorId),
    vendedorNome: texto(o.vendedorNome),
    semanaId: texto(o.semanaId),
    semanaNumero: inteiro(o.semanaNumero),
    iniciadoEm: texto(o.iniciadoEm),
    desistidoEm: texto(o.desistidoEm),
    motivoDesistencia: texto(o.motivoDesistencia),
    pedidoPeloVendedor: o.pedidoPeloVendedor === true,
    enviosAguardando: inteiro(o.enviosAguardando),
    vendasQueChegaramDepoisDoInicio: inteiro(o.vendasQueChegaramDepoisDoInicio),
    maletas,
    contagem,
    leituras,
    fechamento: o.fechamento ?? null,
  };
}

/** Leitura (sem confiar no formato) do que a API devolve para as telas de devoluções, trocas, créditos e garantia. */
import { dinheiroEmTexto } from '../produtos/dados';

const objeto = (v: unknown): Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
const lista = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const texto = (v: unknown): string => (typeof v === 'string' ? v : '');
const textoOuNulo = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null);
const inteiro = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

/** Um valor do lançamento: o Real e, quando há cotação do dia dele, o Dólar e o Guarani que o servidor calculou. */
export interface ValorDoLancamento {
  real: string | null;
  dolar: string | null;
  guarani: string | null;
}

/** Como `dinheiroEmTexto`, mas aceita o sinal (a diferença de uma troca pode ser negativa). */
function dinheiroComSinal(v: unknown): string | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v.toFixed(2);
  if (typeof v !== 'string') return null;
  const m = /^(-?)(\d+(?:\.\d+)?)$/.exec(v);
  const sem = m?.[2] === undefined ? null : dinheiroEmTexto(m[2]);
  return m && sem !== null ? `${m[1]}${sem}` : null;
}

function valor(real: unknown, dolar?: unknown, guarani?: unknown): ValorDoLancamento {
  return {
    real: dinheiroComSinal(real),
    dolar: dolar === undefined ? null : dinheiroComSinal(dolar),
    guarani: dolar === undefined || guarani === undefined ? null : dinheiroComSinal(guarani),
  };
}

export interface PecaLida {
  nome: string;
  quantidade: number;
}

function pecas(bruto: unknown): PecaLida[] {
  return lista(bruto).map((i) => {
    const o = objeto(i);
    return { nome: texto(objeto(o.produto).nome), quantidade: inteiro(o.quantidade) };
  });
}

export function resumoDasPecas(itens: readonly PecaLida[]): string {
  return itens.map((i) => `${i.quantidade} × ${i.nome}`).join(', ');
}

// --- pedidos de autorização ---

export interface Autorizacao {
  id: string;
  vendedorNome: string;
  clienteNome: string;
  motivo: string;
  tipo: string;
  situacao: string;
  valorPedido: ValorDoLancamento;
  pedidaEm: string | null;
  validaAte: string | null;
  prazoDaGarantiaAte: string | null;
  itens: PecaLida[];
}

export function lerAutorizacoes(bruto: unknown): Autorizacao[] {
  return lista(bruto).flatMap((a) => {
    const o = objeto(a);
    const id = textoOuNulo(o.id);
    if (!id) return [];
    return [
      {
        id,
        vendedorNome: texto(o.vendedorNome),
        clienteNome: texto(o.clienteNome),
        motivo: texto(o.motivo),
        tipo: texto(o.tipo),
        situacao: texto(o.situacao),
        valorPedido: valor(o.valorPedido, o.valorPedidoDolar, o.valorPedidoGuarani),
        pedidaEm: textoOuNulo(o.pedidaEm),
        validaAte: textoOuNulo(o.validaAte),
        prazoDaGarantiaAte: textoOuNulo(o.prazoDaGarantiaAte),
        itens: pecas(o.itens),
      },
    ];
  });
}

// --- devoluções ---

export interface Devolucao {
  id: string;
  vendedorId: string;
  clienteNome: string;
  motivo: string;
  situacao: string;
  destino: string;
  valorDevolvido: ValorDoLancamento;
  valorReembolsado: string | null;
  valorEmCredito: string | null;
  devolvidaEm: string | null;
  motivosRevisao: string[];
  itens: PecaLida[];
}

export function lerDevolucoes(bruto: unknown): Devolucao[] {
  return lista(bruto).flatMap((d) => {
    const o = objeto(d);
    const id = textoOuNulo(o.id);
    if (!id) return [];
    return [
      {
        id,
        vendedorId: texto(o.vendedorId),
        clienteNome: texto(o.clienteNome),
        motivo: texto(o.motivo),
        situacao: texto(o.situacao),
        destino: texto(o.destino),
        valorDevolvido: valor(o.valorDevolvido, o.valorDevolvidoDolar, o.valorDevolvidoGuarani),
        valorReembolsado: dinheiroEmTexto(o.valorReembolsado),
        valorEmCredito: dinheiroEmTexto(o.valorEmCredito),
        devolvidaEm: textoOuNulo(o.devolvidaEm),
        motivosRevisao: lista(o.motivosRevisao).filter((m): m is string => typeof m === 'string'),
        itens: pecas(o.itens),
      },
    ];
  });
}

/** A devolução recém-registrada (uma só, não uma lista). */
export function lerDevolucao(bruto: unknown): Devolucao | null {
  return lerDevolucoes([bruto])[0] ?? null;
}

// --- trocas ---

export interface Troca {
  id: string;
  vendedorId: string;
  clienteNome: string;
  situacao: string;
  diferenca: ValorDoLancamento;
  trocadaEm: string | null;
  voltaram: PecaLida[];
  sairam: PecaLida[];
}

export function lerTrocas(bruto: unknown): Troca[] {
  return lista(bruto).flatMap((t) => {
    const o = objeto(t);
    const id = textoOuNulo(o.id);
    if (!id) return [];
    return [
      {
        id,
        vendedorId: texto(o.vendedorId),
        clienteNome: texto(o.clienteNome),
        situacao: texto(o.situacao),
        diferenca: valor(o.diferenca, o.diferencaDolar, o.diferencaGuarani),
        trocadaEm: textoOuNulo(o.trocadaEm),
        voltaram: pecas(o.voltaram),
        sairam: pecas(o.sairam),
      },
    ];
  });
}

// --- créditos ---

export interface CreditoDoCliente {
  id: string;
  vendedorId: string;
  clienteNome: string;
  origem: string;
  situacao: string;
  valor: string | null;
  valorUtilizado: string | null;
  saldo: ValorDoLancamento;
  geradoEm: string | null;
}

export function lerCreditos(bruto: unknown): CreditoDoCliente[] {
  return lista(bruto).flatMap((c) => {
    const o = objeto(c);
    const id = textoOuNulo(o.id);
    if (!id) return [];
    return [
      {
        id,
        vendedorId: texto(o.vendedorId),
        clienteNome: texto(o.clienteNome),
        origem: texto(o.origem),
        situacao: texto(o.situacao),
        valor: dinheiroEmTexto(o.valor),
        valorUtilizado: dinheiroEmTexto(o.valorUtilizado),
        saldo: valor(o.saldo, o.saldoDolar, o.saldoGuarani),
        geradoEm: textoOuNulo(o.geradoEm),
      },
    ];
  });
}

// --- garantia ---

export interface Garantia {
  prazoArrependimentoDias: number;
  prazoDefeitoDias: number;
  limiteReembolsoSemanal: string | null;
  valorAprovacaoReembolso: string | null;
  percentualAlertaDevolucao: number;
}

export function lerGarantia(bruto: unknown): Garantia | null {
  const o = objeto(bruto);
  if (typeof o.prazoArrependimentoDias !== 'number' || typeof o.prazoDefeitoDias !== 'number') {
    return null;
  }
  return {
    prazoArrependimentoDias: o.prazoArrependimentoDias,
    prazoDefeitoDias: o.prazoDefeitoDias,
    limiteReembolsoSemanal: dinheiroEmTexto(o.limiteReembolsoSemanal),
    valorAprovacaoReembolso: dinheiroEmTexto(o.valorAprovacaoReembolso),
    percentualAlertaDevolucao: inteiro(o.percentualAlertaDevolucao),
  };
}

// --- a venda a devolver ---

export interface LinhaDaVenda {
  itemVendaId: string;
  produtoId: string;
  nome: string;
  vendidas: number;
  devolvidas: number;
  podemVoltar: number;
}

export interface VendaComAPeca {
  vendaId: string;
  clienteNome: string;
  vendidaEm: string | null;
  total: string | null;
  aPrazo: boolean;
  prazoArrependimentoAte: string | null;
  prazoDefeitoAte: string | null;
  itens: LinhaDaVenda[];
}

/** `peca` é nula quando o código não é de nenhuma peça. */
export function lerVendasComAPeca(bruto: unknown): {
  achouAPeca: boolean;
  vendas: VendaComAPeca[];
} {
  const o = objeto(bruto);
  const vendas = lista(o.vendas).flatMap((v) => {
    const x = objeto(v);
    const vendaId = textoOuNulo(x.vendaId);
    if (!vendaId) return [];
    return [
      {
        vendaId,
        clienteNome: texto(x.clienteNome),
        vendidaEm: textoOuNulo(x.vendidaEm),
        total: dinheiroEmTexto(x.total),
        aPrazo: x.aPrazo === true,
        prazoArrependimentoAte: textoOuNulo(x.prazoArrependimentoAte),
        prazoDefeitoAte: textoOuNulo(x.prazoDefeitoAte),
        itens: lista(x.itens).flatMap((l) => {
          const y = objeto(l);
          const itemVendaId = textoOuNulo(y.itemVendaId);
          const produtoId = textoOuNulo(objeto(y.produto).id);
          if (!itemVendaId || !produtoId) return [];
          return [
            {
              itemVendaId,
              produtoId,
              nome: texto(objeto(y.produto).nome),
              vendidas: inteiro(y.vendidas),
              devolvidas: inteiro(y.devolvidas),
              podemVoltar: inteiro(y.podemVoltar),
            },
          ];
        }),
      },
    ];
  });
  return { achouAPeca: textoOuNulo(objeto(o.peca).id) !== null, vendas };
}

// --- vendedores (nomes e escolha) ---

export interface VendedorParaNome {
  id: string;
  nome: string;
}

export function lerNomesDosVendedores(bruto: unknown): VendedorParaNome[] {
  return lista(objeto(bruto).itens).flatMap((v) => {
    const o = objeto(v);
    const id = textoOuNulo(o.id);
    return id ? [{ id, nome: texto(o.nome) }] : [];
  });
}

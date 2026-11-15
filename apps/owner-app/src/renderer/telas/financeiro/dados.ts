/** Formatos que a API do financeiro devolve, lidos com cuidado (nada aqui confia no formato nem calcula dinheiro). */
import type { ErroDaTela } from '../../nucleo/chamadas';
import { centavosDeTexto } from '../../nucleo/moedas';
import { TEXTOS_DE_FINANCEIRO } from '../../textos-financeiro';
import { inteiro, lista, objeto, texto, textoOuNulo } from '../acertos/dados';

export { inteiro, lista, objeto, texto, textoOuNulo };

/** Valor em Real como a API manda ("12.50", "-3.00"); qualquer outra coisa vira `null` (a tela nunca inventa número). */
export const valorEmReais = (v: unknown): string | null =>
  typeof v === 'string' && centavosDeTexto(v) !== null ? v : null;

/** Troca a mensagem dos erros mais comuns pela frase da loja que diz o que fazer; os outros ficam como o servidor mandou. */
export function erroDoFinanceiro(erro: ErroDaTela): ErroDaTela {
  const frase = erro.codigo ? TEXTOS_DE_FINANCEIRO.erros[erro.codigo] : undefined;
  return frase ? { ...erro, mensagem: frase } : erro;
}

export interface LancamentoDoCaixa {
  id: string;
  tipo: string;
  valor: string;
  forma: string;
  categoria: string;
  origemTipo: string;
  estornaId: string;
  dataLancamento: string;
  descricao: string;
  vendedorNome: string;
  fornecedorNome: string;
  motivo: string;
  saldoDepois: string;
}

export interface ExtratoDoCaixa {
  saldoAnterior: string;
  entradas: string;
  saidas: string;
  saldoFinal: string;
  lancamentos: LancamentoDoCaixa[];
}

export function lerExtrato(bruto: unknown): ExtratoDoCaixa | null {
  const o = objeto(bruto);
  const saldoAnterior = valorEmReais(o.saldoAnterior);
  const entradas = valorEmReais(o.entradas);
  const saidas = valorEmReais(o.saidas);
  const saldoFinal = valorEmReais(o.saldoFinal);
  if (saldoAnterior === null || entradas === null || saidas === null || saldoFinal === null) {
    return null;
  }
  const lancamentos = lista(o.lancamentos).flatMap((b): LancamentoDoCaixa[] => {
    const l = objeto(b);
    const valor = valorEmReais(l.valor);
    if (typeof l.id !== 'string' || valor === null) return [];
    return [
      {
        id: l.id,
        tipo: texto(l.tipo),
        valor,
        forma: texto(l.forma),
        categoria: texto(l.categoria),
        origemTipo: texto(l.origemTipo),
        estornaId: texto(l.estornaId),
        dataLancamento: texto(l.dataLancamento),
        descricao: texto(l.descricao),
        vendedorNome: texto(l.vendedorNome),
        fornecedorNome: texto(l.fornecedorNome),
        motivo: texto(l.motivo),
        saldoDepois: valorEmReais(l.saldoDepois) ?? '',
      },
    ];
  });
  return { saldoAnterior, entradas, saidas, saldoFinal, lancamentos };
}

export interface SaldoDoCaixa {
  saldo: string;
  negativo: boolean;
}

export function lerSaldo(bruto: unknown): SaldoDoCaixa | null {
  const o = objeto(bruto);
  const saldo = valorEmReais(o.saldo);
  return saldo === null ? null : { saldo, negativo: o.negativo === true };
}

export interface CategoriaDoCaixa {
  id: string;
  nome: string;
  tipo: string;
  ativa: boolean;
  sistema: boolean;
}

export function lerCategorias(bruto: unknown): CategoriaDoCaixa[] {
  return lista(bruto).flatMap((b): CategoriaDoCaixa[] => {
    const c = objeto(b);
    if (typeof c.id !== 'string') return [];
    return [
      {
        id: c.id,
        nome: texto(c.nome),
        tipo: texto(c.tipo),
        ativa: c.ativa === true,
        sistema: c.sistema === true,
      },
    ];
  });
}

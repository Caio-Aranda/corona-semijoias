/** Regras da tela de estoque (sinais, rótulos, validações). Texto da pessoa, nunca da API. */
import { MAXIMO_DA_QUANTIDADE_DO_ESTOQUE } from '../../shared/estoque';
import { TEXTOS_SOLTOS as SOLTOS } from '../textos-soltos';

/** Nome do tipo na tela (glossário, parte 12): nunca ENTRADA, SAIDA, AVARIA nem AJUSTE. */
const ROTULOS = SOLTOS.estoque.rotulos;

export function rotuloDoTipo(tipo: string): string {
  return ROTULOS[tipo] ?? SOLTOS.estoque.outro;
}

/** Quantidade com sinal na tela: +3 entra, -2 sai. Zero não leva sinal. */
export function quantidadeComSinal(n: number): string {
  if (n > 0) return `+${n}`;
  if (n < 0) return `-${Math.abs(n)}`;
  return '0';
}

export interface LinhaParaDesfazer {
  /** O lançamento é o "desfazer" de outro. */
  desfazDeOutro: boolean;
  /** Já foi desfeito por outro lançamento. */
  desfeito: boolean;
}

/** Só se desfaz o que ainda vale: não se desfaz o que já foi desfeito nem um "desfazer". */
export function podeDesfazer(l: LinhaParaDesfazer): boolean {
  return !l.desfazDeOutro && !l.desfeito;
}

/** A peça está acabando quando há estoque mínimo e o estoque chegou nele ou ficou abaixo. */
export function estaAcabando(saldo: number, minimo: number): boolean {
  return minimo > 0 && saldo <= minimo;
}

const SO_NUMEROS = /^\d+$/;
const COM_SINAL = /^[-+]?\d+$/;

export type Conferido<T> = { ok: true; valor: T } | { ok: false; erro: string };

const falha = (erro: string): { ok: false; erro: string } => ({ ok: false, erro });

export const MENSAGENS = SOLTOS.estoque.mensagens;

/** Entrada, saída ou perda: a quantidade é sempre positiva (o sinal é do tipo). */
export function conferirQuantidade(texto: string): Conferido<number> {
  const t = texto.trim();
  if (!SO_NUMEROS.test(t)) return falha(MENSAGENS.quantidade);
  const n = Number.parseInt(t, 10);
  if (n < 1) return falha(MENSAGENS.quantidade);
  if (n > MAXIMO_DA_QUANTIDADE_DO_ESTOQUE) return falha(MENSAGENS.quantidadeGrande);
  return { ok: true, valor: n };
}

export type FormaDoAjuste = 'CONTEI' | 'DIFERENCA';

export type AjusteConferido = { novaQuantidade: number } | { diferenca: number };

/**
 * Ajuste: "contei X" (a nova quantidade) ou a diferença com sinal. O estoque nunca fica abaixo de zero.
 * O servidor confere de novo; aqui a pessoa já vê o que fazer antes de enviar.
 */
export function conferirAjuste(
  forma: FormaDoAjuste,
  texto: string,
  saldo: number,
): Conferido<AjusteConferido> {
  const t = texto.trim();
  if (forma === 'CONTEI') {
    if (!SO_NUMEROS.test(t)) return falha(MENSAGENS.contei);
    const n = Number.parseInt(t, 10);
    if (n > MAXIMO_DA_QUANTIDADE_DO_ESTOQUE) return falha(MENSAGENS.quantidadeGrande);
    if (n === saldo) return falha(MENSAGENS.contouIgual);
    return { ok: true, valor: { novaQuantidade: n } };
  }
  if (!COM_SINAL.test(t)) return falha(MENSAGENS.diferenca);
  const d = Number.parseInt(t, 10);
  if (d === 0) return falha(MENSAGENS.diferenca);
  if (Math.abs(d) > MAXIMO_DA_QUANTIDADE_DO_ESTOQUE) return falha(MENSAGENS.quantidadeGrande);
  if (saldo + d < 0) return falha(MENSAGENS.diferencaPassa(saldo));
  return { ok: true, valor: { diferenca: d } };
}

export function conferirMotivo(texto: string, mensagem: string): Conferido<string> {
  const t = texto.trim();
  return t === '' ? falha(mensagem) : { ok: true, valor: t };
}

export function conferirMinimo(texto: string): Conferido<number> {
  const t = texto.trim();
  if (!SO_NUMEROS.test(t)) return falha(MENSAGENS.minimo);
  const n = Number.parseInt(t, 10);
  return n > 1_000_000 ? falha(MENSAGENS.minimo) : { ok: true, valor: n };
}

/** O que o fechamento da contagem mostra e exige antes de enviar. */
export interface ResumoDoFechamento {
  comDiferenca: number;
  naoLidos: number;
}

/**
 * Se sobraram peças não lidas, a pessoa precisa dizer o que fazer com elas (zero ou como estão).
 * Sem escolha o botão de fechar fica desligado.
 */
export function precisaEscolherONaoLido(r: ResumoDoFechamento, escolha: boolean | null): boolean {
  return r.naoLidos > 0 && escolha === null;
}

/** Quantos produtos o fechamento vai ajustar: os que têm diferença; os não lidos só se virarem zero. */
export function quantosAjustes(r: ResumoDoFechamento, zerarNaoLidos: boolean): number {
  return r.comDiferenca + (zerarNaoLidos ? r.naoLidos : 0);
}

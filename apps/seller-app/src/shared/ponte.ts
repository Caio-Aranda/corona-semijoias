/**
 * Contrato da ponte entre a tela (React) e o processo principal. É a ÚNICA coisa que a tela
 * conhece do processo principal. Nada aqui carrega token: a tela só recebe o resultado.
 */

import type { PedidoDeChamada } from './operacoes';

export { OPERACOES_DA_API } from './operacoes';
export type { OperacaoDaApi, PedidoDeChamada } from './operacoes';

/** Nomes dos canais. Só o processo principal e a ponte (preload) os usam. */
export const CANAIS = {
  entrar: 'corona:entrar',
  trocarSenha: 'corona:trocar-senha',
  sair: 'corona:sair',
  estouLogado: 'corona:estou-logado',
  chamarApi: 'corona:chamar-api',
  sessaoMudou: 'corona:sessao-mudou',
  atualizacaoEstado: 'corona:atualizacao-estado',
  atualizacaoMudou: 'corona:atualizacao-mudou',
} as const;

/** A lista fixa de funções que a ponte oferece à tela. Acrescentar uma função exige mexer aqui. */
export const FUNCOES_DA_PONTE = [
  'entrar',
  'trocarSenha',
  'sair',
  'estouLogado',
  'chamarApi',
  'aoMudarASessao',
  'aoMudarAAtualizacao',
] as const;

export interface DadosDeEntrada {
  usuario: string;
  senha: string;
}

export interface DadosDeNovaSenha {
  senhaNova: string;
}

export interface UsuarioLogado {
  nome: string;
}

/**
 * Resultado de entrar ou de trocar a senha. As mensagens (`mensagem`) vêm da API:
 * o aplicativo nunca monta texto de erro.
 */
export type ResultadoDeEntrada =
  | { tipo: 'entrou' }
  | { tipo: 'trocar-senha' }
  | { tipo: 'recusado'; mensagem: string }
  | { tipo: 'app-desatualizado'; mensagem: string }
  | { tipo: 'sem-conexao' }
  | { tipo: 'erro-inesperado' };

export type EstadoDaSessao =
  | { estado: 'fora' }
  | { estado: 'trocar-senha' }
  | { estado: 'sem-conexao' }
  | { estado: 'dentro'; usuario: UsuarioLogado | null }
  | { estado: 'app-desatualizado'; mensagem: string };

export type ResultadoDeChamada =
  | { ok: true; dados: unknown }
  | {
      ok: false;
      motivo: 'sessao-terminou' | 'sem-conexao' | 'app-desatualizado' | 'erro';
      mensagem?: string;
    };

/** Aviso do processo principal de que a pessoa precisa entrar de novo. */
export interface AvisoDeSessao {
  estado: 'fora' | 'app-desatualizado';
  mensagem?: string;
}

/** Estado da atualização automática do aplicativo (ADR-0009). Só o processo principal decide. */
export type AvisoDeAtualizacao = { estado: 'nenhuma' } | { estado: 'pronta'; versao: string };

/** O que a tela enxerga em `window.corona`. */
export interface PonteDoCorona {
  entrar(dados: DadosDeEntrada): Promise<ResultadoDeEntrada>;
  trocarSenha(dados: DadosDeNovaSenha): Promise<ResultadoDeEntrada>;
  sair(): Promise<void>;
  estouLogado(): Promise<EstadoDaSessao>;
  chamarApi(pedido: PedidoDeChamada): Promise<ResultadoDeChamada>;
  /** Avisa quando a sessão terminar sozinha. Devolve a função que cancela o aviso. */
  aoMudarASessao(ouvinte: (aviso: AvisoDeSessao) => void): () => void;
  /** Avisa o estado da atualização automática, já ao assinar e sempre que mudar. Devolve o cancelamento. */
  aoMudarAAtualizacao(ouvinte: (aviso: AvisoDeAtualizacao) => void): () => void;
}

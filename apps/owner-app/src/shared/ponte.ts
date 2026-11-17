/**
 * Contrato da ponte entre a tela (React) e o processo principal. É a ÚNICA coisa que a tela
 * conhece do processo principal. Nada aqui carrega token: a tela só recebe o resultado.
 */

import { OPERACOES_DE_ACERTOS } from './acertos';
import { OPERACOES_DE_CADASTRO } from './cadastros';
import { OPERACOES_DE_COMPRAS } from './compras';
import { OPERACOES_DE_DEVOLUCOES } from './devolucoes';
import { OPERACOES_DE_ESTOQUE } from './estoque';
import { OPERACOES_DE_FINANCEIRO } from './financeiro';
import { OPERACOES_DE_MALETAS } from './maletas';
import { OPERACOES_DE_PRODUTOS } from './produtos';
import { OPERACOES_DE_RELATORIOS } from './relatorios';
import { OPERACOES_DE_SINCRONIZACAO } from './sincronizacao';
import { OPERACOES_DE_VENDEDORES } from './vendedores';

/** Operações de consulta que o processo principal aceita fazer em nome da tela. */
export const OPERACOES_DA_API = [
  'eu',
  'usuarios.listar',
  'usuarios.criar',
  'usuarios.editar',
  'usuarios.desativar',
  'usuarios.reativar',
  'usuarios.redefinirSenha',
  'cotacao.hoje',
  'cotacao.converter',
  'avisos.listar',
  'conciliacoes.conferir',
  'conferencias.listar',
  'conferencias.ver',
  'conferencias.conferirAgora',
  'painel.semanal',
  'vendedores.escolha',
  ...OPERACOES_DE_CADASTRO,
  ...OPERACOES_DE_VENDEDORES,
  ...OPERACOES_DE_PRODUTOS,
  ...OPERACOES_DE_COMPRAS,
  ...OPERACOES_DE_ESTOQUE,
  ...OPERACOES_DE_MALETAS,
  ...OPERACOES_DE_ACERTOS,
  ...OPERACOES_DE_FINANCEIRO,
  ...OPERACOES_DE_RELATORIOS,
  ...OPERACOES_DE_SINCRONIZACAO,
  ...OPERACOES_DE_DEVOLUCOES,
] as const;
export type OperacaoDaApi = (typeof OPERACOES_DA_API)[number];

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

/** Uma operação com os dados que ela precisa. Os dados são conferidos de novo no processo principal. */
export interface PedidoDeChamada {
  operacao: OperacaoDaApi;
  parametros?: Record<string, unknown>;
}

/** Um campo que o servidor recusou, com o texto em português que ele devolveu. */
export interface CampoRecusado {
  campo: string;
  mensagem: string;
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
  | { ok: true; dados: unknown; criado?: boolean }
  | {
      ok: false;
      motivo: 'sessao-terminou' | 'sem-conexao' | 'app-desatualizado' | 'erro';
      mensagem?: string;
      /** Código HTTP da recusa (400, 403, 409...), quando o servidor respondeu. */
      status?: number;
      /** Código do erro (ex.: CADASTRO_ALTERADO), só para a tela decidir o que mostrar. */
      codigo?: string;
      campos?: CampoRecusado[];
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
  /**
   * Avisa o estado da atualização automática: já na hora de assinar (se uma versão nova já foi baixada) e
   * sempre que mudar. Devolve a função que cancela o aviso.
   */
  aoMudarAAtualizacao(ouvinte: (aviso: AvisoDeAtualizacao) => void): () => void;
}

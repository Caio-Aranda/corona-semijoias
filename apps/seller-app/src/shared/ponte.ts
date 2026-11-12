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
  sincronizacaoEstado: 'corona:sincronizacao-estado',
  sincronizacaoMudou: 'corona:sincronizacao-mudou',
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
  'aoMudarASincronizacao',
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
      /** CPF repetido no cadastro de cliente: o cliente que já existe (diretório da loja). */
      clienteExistente?: { id: string; nome: string; telefone?: string; cidade?: string };
    };

/** Aviso do processo principal de que a pessoa precisa entrar de novo. */
export interface AvisoDeSessao {
  estado: 'fora' | 'app-desatualizado';
  mensagem?: string;
}

/** Estado da atualização automática do aplicativo (ADR-0009). Só o processo principal decide. */
export type AvisoDeAtualizacao = { estado: 'nenhuma' } | { estado: 'pronta'; versao: string };

/**
 * O estado do envio e do recebimento, o ÚNICO dado do motor que vai para a tela (tarefa 5.6; ADR-0013, item 18). Só
 * contadores, sim/não e datas: nada de banco, token, id ou dado de cliente. A tela (tarefa 5.7) monta os textos do
 * glossário a partir dele ("Com internet. Tudo enviado.", "3 vendas aguardando envio"...).
 */
export interface EstadoDaSincronizacao {
  /** A rota de saúde respondeu no último ciclo; `null` antes do primeiro. */
  comInternet: boolean | null;
  /** Um ciclo está em andamento. */
  enviando: boolean;
  /** Operações guardadas que aguardam envio. */
  aguardando: number;
  /** Destas, quantas são vendas (para "N vendas aguardando envio"). */
  vendasAguardando: number;
  /** Recusas e conflitos que o vendedor ainda não marcou como "Entendi". */
  recusadas: number;
  /** "Dados até": hora do servidor (ISO, UTC) do último recebimento completo. */
  dadosAte: string | null;
  /** Uma operação falhou 5 vezes seguidas: "Uma venda não conseguiu ser enviada. Avise a dona." */
  naoConseguiuEnviar: boolean;
}

const DATA_ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
const contador = (v: unknown): number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : 0;

/**
 * Copia só os campos documentados, com o tipo certo. Roda no processo principal antes de mandar e na ponte antes de
 * entregar à tela: o que não for contador, sim/não ou data some.
 */
export function limparEstadoDaSincronizacao(v: unknown): EstadoDaSincronizacao {
  const o = (typeof v === 'object' && v !== null ? v : {}) as Record<string, unknown>;
  const dadosAte = o['dadosAte'];
  return {
    comInternet: typeof o['comInternet'] === 'boolean' ? o['comInternet'] : null,
    enviando: o['enviando'] === true,
    aguardando: contador(o['aguardando']),
    vendasAguardando: contador(o['vendasAguardando']),
    recusadas: contador(o['recusadas']),
    dadosAte: typeof dadosAte === 'string' && DATA_ISO.test(dadosAte) ? dadosAte : null,
    naoConseguiuEnviar: o['naoConseguiuEnviar'] === true,
  };
}

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
  /** Avisa o estado do envio e do recebimento, já ao assinar e sempre que mudar. Devolve o cancelamento. */
  aoMudarASincronizacao(ouvinte: (estado: EstadoDaSincronizacao) => void): () => void;
}

/**
 * Contrato da ponte entre a tela (React) e o processo principal. É a ÚNICA coisa que a tela
 * conhece do processo principal. Nada aqui carrega token: a tela só recebe o resultado.
 */

import type { PedidoDeChamada } from './operacoes';
import { limparPrazo, type PrazoOffline } from './prazo';
import type { ResultadoDeSaidaDoRelatorio } from './relatorios-do-consignado';

export { OPERACOES_DA_API } from './operacoes';
export type { OperacaoDaApi, PedidoDeChamada } from './operacoes';

/** Nomes dos canais. Só o processo principal e a ponte (preload) os usam. */
export const CANAIS = {
  entrar: 'corona:entrar',
  trocarSenha: 'corona:trocar-senha',
  sair: 'corona:sair',
  liberarComputador: 'corona:liberar-computador',
  estouLogado: 'corona:estou-logado',
  chamarApi: 'corona:chamar-api',
  sessaoMudou: 'corona:sessao-mudou',
  atualizacaoEstado: 'corona:atualizacao-estado',
  atualizacaoMudou: 'corona:atualizacao-mudou',
  sincronizacaoEstado: 'corona:sincronizacao-estado',
  sincronizacaoMudou: 'corona:sincronizacao-mudou',
  idiomaLer: 'corona:idioma-ler',
  idiomaGravar: 'corona:idioma-gravar',
  relatorioImprimir: 'corona:relatorio-imprimir',
  relatorioSalvarPdf: 'corona:relatorio-salvar-pdf',
  relatorioWhatsapp: 'corona:relatorio-whatsapp',
} as const;

/** A lista fixa de funções que a ponte oferece à tela. Acrescentar uma função exige mexer aqui. */
export const FUNCOES_DA_PONTE = [
  'entrar',
  'trocarSenha',
  'sair',
  'liberarComputador',
  'estouLogado',
  'chamarApi',
  'aoMudarASessao',
  'aoMudarAAtualizacao',
  'aoMudarASincronizacao',
  'lerIdioma',
  'gravarIdioma',
  'imprimirRelatorio',
  'salvarRelatorioEmPdf',
  'prepararRelatorioNoWhatsapp',
] as const;

/** Os dois idiomas do Corona Vendas (a tela guarda a escolha pela ponte, nunca em armazenamento do navegador). */
export type IdiomaDaTela = 'es' | 'pt';

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
  | { tipo: 'recusado'; mensagem: string; codigo?: string }
  | { tipo: 'app-desatualizado'; mensagem: string }
  | { tipo: 'sem-conexao' }
  | { tipo: 'erro-inesperado' };

export type EstadoDaSessao =
  | { estado: 'fora' }
  | { estado: 'trocar-senha' }
  | { estado: 'sem-conexao' }
  | { estado: 'dentro'; usuario: UsuarioLogado | null }
  /** Há sessão guardada e dados neste computador, mas sem internet: entra só com a senha (tarefa 5.8). */
  | { estado: 'entrar-sem-internet' }
  /** Aplicativo reaberto com a sessão ainda valida, mas o banco local fechado: pede a senha antes de tudo. */
  | { estado: 'reabrir'; usuario: string | null }
  /** A dona desligou este computador: as vendas estão indo para a dona antes de apagar os dados (tarefa 5.8). */
  | { estado: 'desligando' }
  /** Os dados deste computador foram apagados (tarefa 5.8). */
  | { estado: 'desligado' }
  | { estado: 'app-desatualizado'; mensagem: string };

export type ResultadoDeChamada =
  | { ok: true; dados: unknown }
  | {
      ok: false;
      motivo: 'sessao-terminou' | 'sem-conexao' | 'app-desatualizado' | 'erro';
      mensagem?: string;
      /** O código do erro (`CodigoErro` da API ou do computador): a tela escolhe a frase no idioma da pessoa. */
      codigo?: string;
      /** O motivo de cada campo recusado, como o servidor mandou (português). */
      campos?: { campo: string; mensagem: string }[];
      /** CPF repetido no cadastro de cliente: o cliente que já existe (diretório da loja). */
      clienteExistente?: { id: string; nome: string; telefone?: string; cidade?: string };
    };

/** Aviso do processo principal de que a pessoa precisa entrar de novo. */
export interface AvisoDeSessao {
  estado: 'fora' | 'app-desatualizado' | 'desligando' | 'desligado';
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
  /** O prazo máximo sem internet (tarefa 5.8): aviso 3 dias antes, e esgotado bloqueia o que grava. */
  prazo: PrazoOffline;
  /** Fotos das maletas abertas que já estão no computador (tarefa 5.9). */
  fotosBaixadas: number;
  /** Fotos das maletas abertas. "Baixando fotos: 45 de 120" enquanto baixadas < total. */
  fotosTotal: number;
  /**
   * O servidor aceitou o envio, mas este Corona Vendas está abaixo da versão mínima (tarefa 5.13): as vendas continuam
   * indo, os dados novos só depois de atualizar. "Seu aplicativo está desatualizado."
   */
  appDesatualizado: boolean;
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
    prazo: limparPrazo(o['prazo']),
    fotosBaixadas: contador(o['fotosBaixadas']),
    fotosTotal: contador(o['fotosTotal']),
    appDesatualizado: o['appDesatualizado'] === true,
  };
}

/** O que a tela enxerga em `window.corona`. */
export interface PonteDoCorona {
  entrar(dados: DadosDeEntrada): Promise<ResultadoDeEntrada>;
  trocarSenha(dados: DadosDeNovaSenha): Promise<ResultadoDeEntrada>;
  sair(): Promise<void>;
  /** "Este computador é de outra pessoa": guarda o banco antigo à parte e deixa o computador livre (nada é apagado). */
  liberarComputador(): Promise<void>;
  estouLogado(): Promise<EstadoDaSessao>;
  chamarApi(pedido: PedidoDeChamada): Promise<ResultadoDeChamada>;
  /** Avisa quando a sessão terminar sozinha. Devolve a função que cancela o aviso. */
  aoMudarASessao(ouvinte: (aviso: AvisoDeSessao) => void): () => void;
  /** Avisa o estado da atualização automática, já ao assinar e sempre que mudar. Devolve o cancelamento. */
  aoMudarAAtualizacao(ouvinte: (aviso: AvisoDeAtualizacao) => void): () => void;
  /** Avisa o estado do envio e do recebimento, já ao assinar e sempre que mudar. Devolve o cancelamento. */
  aoMudarASincronizacao(ouvinte: (estado: EstadoDaSincronizacao) => void): () => void;
  /** O idioma que a pessoa escolheu neste computador; `null` se nunca escolheu (vale o padrão, espanhol). */
  lerIdioma(): Promise<IdiomaDaTela | null>;
  /** Guarda neste computador o idioma escolhido. */
  gravarIdioma(idioma: IdiomaDaTela): Promise<void>;
  /** Imprime o relatório que está na tela (tarefa 9.3). Só a folha do relatório sai no papel. */
  imprimirRelatorio(): Promise<ResultadoDeSaidaDoRelatorio>;
  /** Salva em PDF o relatório que está na tela; a pessoa escolhe onde guardar. */
  salvarRelatorioEmPdf(dados: { nome: string }): Promise<ResultadoDeSaidaDoRelatorio>;
  /** Guarda o PDF, mostra a pasta e abre a conversa do WhatsApp com o cliente e a mensagem pronta. Nada é enviado sozinho. */
  prepararRelatorioNoWhatsapp(dados: {
    telefone: string | null;
    mensagem: string;
    nome: string;
    /** Relatório do próprio representante: abre o WhatsApp sem número e ele escolhe a conversa. */
    semNumero?: boolean;
  }): Promise<ResultadoDeSaidaDoRelatorio>;
}

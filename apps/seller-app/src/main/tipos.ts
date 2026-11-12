/** Por que uma chamada à API não deu certo. Só o processo principal enxerga isto. */
/** O cliente que já tem o CPF digitado (409 do cadastro): só o que o servidor mostra do diretório da loja. */
export interface ClienteExistente {
  id: string;
  nome: string;
  telefone?: string;
  cidade?: string;
}

export type Falha =
  | {
      tipo: 'recusada';
      status: number;
      codigo?: string;
      /** A ordem que o servidor manda junto (`APAGAR_DADOS`: computador desligado pela dona; tarefa 5.8). */
      acao?: string;
      mensagem?: string;
      clienteExistente?: ClienteExistente;
    }
  | { tipo: 'sem-conexao' }
  | { tipo: 'app-desatualizado'; mensagem: string }
  | { tipo: 'resposta-invalida' };

export type Resultado<T> = { ok: true; dados: T } | { ok: false; falha: Falha };

/** Resposta de entrar, renovar ou trocar a senha, já conferida pelo adaptador da API. */
export interface Credenciais {
  tokenDeAcesso: string;
  /** Ausente no token restrito da troca obrigatória de senha. */
  tokenDeRenovacao?: string;
  validadeEmSegundos: number;
  trocaDeSenhaObrigatoria: boolean;
  /** Hora do servidor (UTC, texto ISO) na resposta. É a referência do prazo offline, não o relógio do computador. */
  horaDoServidor?: string;
  /** Prazo máximo sem internet, em dias (ADR-0004, item 5). Guardado, ainda não aplicado. */
  tempoMaximoOfflineDias?: number;
}

export interface ApiDeAutenticacao {
  entrar(usuario: string, senha: string): Promise<Resultado<Credenciais>>;
  renovar(tokenDeRenovacao: string): Promise<Resultado<Credenciais>>;
  trocarSenha(tokenDeAcesso: string, senhaNova: string): Promise<Resultado<Credenciais>>;
  sair(tokenDeAcesso: string): Promise<Resultado<null>>;
}

/** O cofre do sistema (no Windows, o safeStorage do Electron). */
export interface Cofre {
  disponivel(): Promise<boolean>;
  cifrar(texto: string): Promise<Uint8Array>;
  decifrar(dados: Uint8Array): Promise<string>;
}

/** Arquivo onde só o token de renovação, já cifrado, fica guardado. */
export interface ArquivoDoToken {
  ler(): Promise<Uint8Array | null>;
  gravar(dados: Uint8Array): Promise<void>;
  apagar(): Promise<void>;
}

export interface Relogio {
  agora(): number;
  /** Agenda `fn` daqui a `ms` milissegundos e devolve a função que cancela. */
  agendar(ms: number, fn: () => void): () => void;
}

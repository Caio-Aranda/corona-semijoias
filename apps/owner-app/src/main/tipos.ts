/** Por que uma chamada à API não deu certo. Só o processo principal enxerga isto. */
export type Falha =
  | {
      tipo: 'recusada';
      status: number;
      codigo?: string;
      mensagem?: string;
      campos?: { campo: string; mensagem: string }[];
    }
  | { tipo: 'sem-conexao' }
  | { tipo: 'app-desatualizado'; mensagem: string }
  | { tipo: 'resposta-invalida' };

/** {@code criado} só vem nas chamadas que distinguem 201 (criado) de 200 (já estava registrado). */
export type Resultado<T> = { ok: true; dados: T; criado?: boolean } | { ok: false; falha: Falha };

/** Resposta de entrar, renovar ou trocar a senha, já conferida pelo adaptador da API. */
export interface Credenciais {
  tokenDeAcesso: string;
  /** Ausente no token restrito da troca obrigatória de senha. */
  tokenDeRenovacao?: string;
  validadeEmSegundos: number;
  trocaDeSenhaObrigatoria: boolean;
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

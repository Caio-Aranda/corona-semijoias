/** Erro do servidor que o aplicativo precisa reconhecer. O texto é sempre o que veio da API. */
export class ErroDaApi extends Error {
  readonly status: number;
  readonly codigo: string;

  constructor(status: number, codigo: string, mensagem: string) {
    super(mensagem);
    this.name = new.target.name;
    this.status = status;
    this.codigo = codigo;
  }
}

/** HTTP 426 `APP_DESATUALIZADO`: a tela mostra a mensagem da API e não deixa continuar. */
export class ErroAppDesatualizado extends ErroDaApi {
  readonly versaoMinima: string | undefined;
  readonly versaoAtual: string | undefined;

  constructor(mensagem: string, versaoMinima?: string, versaoAtual?: string) {
    super(426, 'APP_DESATUALIZADO', mensagem);
    this.versaoMinima = versaoMinima;
    this.versaoAtual = versaoAtual;
  }
}

/** HTTP 400 `APP_TIPO_INVALIDO`: o aplicativo mandou um tipo que a API não conhece (erro de instalação). */
export class ErroTipoDeAppInvalido extends ErroDaApi {
  constructor(mensagem: string) {
    super(400, 'APP_TIPO_INVALIDO', mensagem);
  }
}

export const ehAppDesatualizado = (e: unknown): e is ErroAppDesatualizado =>
  e instanceof ErroAppDesatualizado;

export const ehTipoDeAppInvalido = (e: unknown): e is ErroTipoDeAppInvalido =>
  e instanceof ErroTipoDeAppInvalido;

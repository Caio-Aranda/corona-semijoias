package br.com.coronasemijoias.api.acessos;

/**
 * Resultado de uma tentativa de login (coluna {@code tentativas_login.resultado}; ADR-0004, item 7).
 * Só {@link #SENHA_INCORRETA} conta para o bloqueio; só {@link #SUCESSO} zera a contagem de login + IP.
 */
public enum ResultadoDaTentativa {

    SUCESSO,

    /** Senha errada, login que não existe ou login que nem pode existir: a mesma coisa para o bloqueio. */
    SENHA_INCORRETA,

    /** Recusada sem conferir a senha: login + IP ou IP bloqueado, ou excesso de pedidos ao mesmo tempo. */
    BLOQUEADO,

    /** Senha certa de usuário desativado. */
    USUARIO_DESATIVADO,

    /** Perfil incompatível com o aplicativo (tarefa 12). */
    APP_INCOMPATIVEL,

    /** Senha temporária certa depois do prazo: não conta como erro e não zera a contagem. */
    SENHA_TEMPORARIA_VENCIDA,

    /** Senha certa, mas o identificador de aparelho não combina com o perfil: não conta como erro. */
    PEDIDO_INVALIDO,

    /** A senha foi redefinida e o bloqueio daquele login foi retirado (sem IP). */
    BLOQUEIO_LIMPO,

    /** Senha certa, mas o computador foi bloqueado pela dona (tarefa 5.3): não entra e recebe a ordem de apagar. */
    APARELHO_BLOQUEADO,

    /** Senha certa, mas o identificador do computador já é de outra pessoa (tarefa 5.3). */
    APARELHO_DE_OUTRA_PESSOA
}

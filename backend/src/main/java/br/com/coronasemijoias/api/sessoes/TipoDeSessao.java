package br.com.coronasemijoias.api.sessoes;

/** Tipo da família da sessão (coluna {@code sessoes.tipo}). */
public enum TipoDeSessao {

    /** Sessão comum: token de acesso e token de renovação. */
    NORMAL,

    /** Sessão do token restrito da troca de senha obrigatória: sem token de renovação. */
    TROCA_SENHA
}

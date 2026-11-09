package br.com.coronasemijoias.api.configuracoes;

/**
 * As chaves da tabela {@code configuracoes} que a aplicação usa (especificação §9). Chave nova: uma
 * linha numa migração nova e uma constante aqui.
 */
public enum ChaveDeConfiguracao {

    /** Prazo máximo sem internet do Corona Vendas, em dias (padrão 10; ADR-0004, item 5). */
    TEMPO_MAXIMO_OFFLINE_DIAS("tempo_maximo_offline_dias"),

    /** Erros seguidos de login + IP antes do bloqueio (padrão 5; ADR-0004, item 7). */
    TENTATIVAS_LOGIN_ANTES_DO_BLOQUEIO("tentativas_login_antes_do_bloqueio"),

    /** Duração do bloqueio de login + IP, em minutos (padrão 15; ADR-0004, item 7). */
    TEMPO_BLOQUEIO_LOGIN_MINUTOS("tempo_bloqueio_login_minutos");

    private final String chave;

    ChaveDeConfiguracao(String chave) {
        this.chave = chave;
    }

    /** O nome da chave na tabela. */
    public String chave() {
        return chave;
    }
}

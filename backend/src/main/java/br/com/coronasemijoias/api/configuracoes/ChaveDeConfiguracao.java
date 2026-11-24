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
    TEMPO_BLOQUEIO_LOGIN_MINUTOS("tempo_bloqueio_login_minutos"),

    /** Desconto máximo de uma venda, em % do subtotal (padrão 0 até a dona definir; ADR-0011, item 5). */
    LIMITE_DESCONTO_PERCENTUAL("limite_desconto_percentual"),

    /** Dias para o cliente desistir da peça, também "não serviu" e "outro" (padrão 7; ADR-0014, item 4; dúvidas D1 e D2). */
    PRAZO_ARREPENDIMENTO_DIAS("prazo_arrependimento_dias"),

    /** Dias para devolver por defeito (padrão 30; ADR-0014, item 4; dúvida D1). */
    PRAZO_DEFEITO_DIAS("prazo_defeito_dias"),

    /** Reembolso máximo por vendedor na semana, em Real; vazio = sem limite (ADR-0014, item 10; dúvida D3). */
    LIMITE_REEMBOLSO_SEMANAL("limite_reembolso_semanal"),

    /** Valor de reembolso a partir do qual a dona aprova antes, em Real; vazio = sem valor (ADR-0014, item 10; dúvida D3). */
    VALOR_APROVACAO_REEMBOLSO("valor_aprovacao_reembolso"),

    /** Porcentagem de devolução (últimas 4 semanas) que avisa a dona sobre o vendedor (padrão 20; dúvida D9). */
    PERCENTUAL_ALERTA_DEVOLUCAO("percentual_alerta_devolucao"),

    /** Meta da semana para a comissão, em Guarani (padrão 700.000; ADR-0012, item 5). */
    COMISSAO_META_GUARANI("comissao_meta_guarani"),

    /** Percentual da comissão quando a soma fica abaixo da meta (padrão 30). */
    COMISSAO_PERCENTUAL_ABAIXO_DA_META("comissao_percentual_abaixo_da_meta"),

    /** Percentual da comissão quando a soma chega à meta (padrão 40). */
    COMISSAO_PERCENTUAL_NA_META("comissao_percentual_na_meta"),

    /** 1 mostra no Corona Gestão o acerto antigo com a dona (§7.4); 0 (padrão) esconde. Nada é apagado (ADR-0016, item 15). */
    ACERTO_DO_VENDEDOR_VISIVEL("acerto_do_vendedor_visivel");

    private final String chave;

    ChaveDeConfiguracao(String chave) {
        this.chave = chave;
    }

    /** O nome da chave na tabela. */
    public String chave() {
        return chave;
    }
}

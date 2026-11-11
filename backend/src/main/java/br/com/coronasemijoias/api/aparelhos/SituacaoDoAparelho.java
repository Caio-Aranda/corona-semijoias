package br.com.coronasemijoias.api.aparelhos;

/** Situação de um computador do Corona Vendas (coluna {@code dispositivos.situacao}; ADR-0013, item 13). */
public enum SituacaoDoAparelho {

    /** O computador em uso do vendedor: vende, confere e recebe dados. Um só por pessoa. */
    ATIVO,

    /** Segundo computador: o login funciona, mas nada além disso até a dona liberar ("Trocar computador"). */
    AGUARDANDO_LIBERACAO,

    /** Trocado por outro: ainda envia o que guardou (as vendas são reais), não vende nem recebe dados. */
    SUBSTITUIDO,

    /** Perdido ou roubado: as sessões caem e a próxima chamada recebe a ordem de apagar os dados. */
    BLOQUEADO
}

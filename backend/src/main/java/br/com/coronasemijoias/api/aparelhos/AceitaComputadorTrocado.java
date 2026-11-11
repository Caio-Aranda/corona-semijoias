package br.com.coronasemijoias.api.aparelhos;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rota do vendedor que o computador trocado ({@code SUBSTITUIDO}) ainda pode usar: só o envio das vendas que ele guardou
 * (ADR-0013, item 13). Todas as outras recusam com {@code APARELHO_SUBSTITUIDO}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AceitaComputadorTrocado {
}

package br.com.coronasemijoias.api.fotos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites das fotos de produto.
 *
 * @param maximoDePontos maior foto aceita, em pontos (largura x altura); protege o servidor de imagem-bomba
 *                       (25 milhões cobrem os celulares comuns, por exemplo 5000 x 5000)
 */
@ConfigurationProperties("corona.fotos")
public record PropriedadesDeFotos(@DefaultValue("25000000") long maximoDePontos) {
}

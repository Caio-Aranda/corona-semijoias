package br.com.coronasemijoias.api.sessoes;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros da sessão longa na configuração do servidor (ADR-0004, item 2, e "Parâmetros técnicos e
 * onde ficam"). Mudar é mudança de configuração e nova subida da API.
 *
 * @param validade             quanto vale cada token de renovação, contado da renovação (30 dias)
 * @param janelaDeTolerancia   quanto tempo o token anterior ainda devolve o mesmo sucessor (2 minutos)
 * @param segredoDeRenovacao   segredo do HMAC que deriva o sucessor, em Base64 (do {@code .env})
 */
@ConfigurationProperties("corona.seguranca.sessao")
public record PropriedadesDaSessao(Duration validade, Duration janelaDeTolerancia, String segredoDeRenovacao) {

    public PropriedadesDaSessao {
        if (validade == null || validade.isNegative() || validade.isZero()) {
            validade = Duration.ofDays(30);
        }
        if (janelaDeTolerancia == null || janelaDeTolerancia.isNegative()) {
            janelaDeTolerancia = Duration.ofMinutes(2);
        }
    }

    @Override
    public String toString() {
        return "PropriedadesDaSessao[validade=" + validade + ", janelaDeTolerancia=" + janelaDeTolerancia
                + ", segredoDeRenovacao=(oculto)]";
    }
}

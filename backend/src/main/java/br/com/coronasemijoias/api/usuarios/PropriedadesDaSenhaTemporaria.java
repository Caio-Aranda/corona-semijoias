package br.com.coronasemijoias.api.usuarios;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da senha temporária (ADR-0004, item 6): quanto tempo ela vale, contado da criação do
 * usuário ou de cada redefinição. Mudar é mudança de configuração do servidor, sem tela.
 *
 * @param validade padrão 7 dias
 */
@ConfigurationProperties("corona.seguranca.senha-temporaria")
public record PropriedadesDaSenhaTemporaria(Duration validade) {

    public PropriedadesDaSenhaTemporaria {
        if (validade == null || validade.isNegative() || validade.isZero()) {
            validade = Duration.ofDays(7);
        }
    }
}

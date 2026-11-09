package br.com.coronasemijoias.api.comum.persistencia;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Informa ao banco, só para a transação atual, de qual aparelho vem o que está sendo gravado.
 * O gatilho {@code corona.controle_sincronizavel} copia esse valor para {@code origem_dispositivo}
 * na inserção e ignora o que vier no corpo (ADR-0002, item 3).
 *
 * <p>Quem chama é o código que já conhece a sessão autenticada (o envio do Corona Vendas, Fase 5),
 * nunca com um valor vindo da requisição. O valor some no fim da transação, então uma conexão do
 * pool nunca o leva para outra requisição.
 */
@Component
public class OrigemDoDispositivo {

    private final JdbcTemplate jdbc;

    public OrigemDoDispositivo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void definirNaTransacaoAtual(UUID dispositivo) {
        Objects.requireNonNull(dispositivo, "dispositivo");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A origem do aparelho só pode ser definida dentro de uma transação.");
        }
        jdbc.queryForObject("SELECT set_config('corona.origem_dispositivo', ?, true)", String.class,
                dispositivo.toString());
    }
}

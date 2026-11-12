package br.com.coronasemijoias.api.sync;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * O sync no painel de saúde (tarefa 5.11; ADR-0013, item 21). Atraso do limite seguro e computador parado são
 * <strong>alerta, não queda</strong>: o estado continua {@code UP} (senão o monitor da VPS reiniciaria a API por causa
 * de uma venda lenta) e os números vão nos detalhes.
 */
@Component("sync")
public class SaudeDoSync extends AbstractHealthIndicator {

    private final MetricasDoSync metricas;
    private final ComputadoresParados computadores;

    public SaudeDoSync(MetricasDoSync metricas, ComputadoresParados computadores) {
        super("Não foi possível conferir o sync");
        this.metricas = metricas;
        this.computadores = computadores;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("idadeDaTransacaoMaisAntigaSegundos", metricas.idadeDaTransacaoMaisAntigaSegundos())
                .withDetail("computadoresSemEnviar", computadores.semEnviar().size())
                .withDetail("computadoresAguardandoLiberacao", computadores.aguardandoLiberacao().size());
    }
}

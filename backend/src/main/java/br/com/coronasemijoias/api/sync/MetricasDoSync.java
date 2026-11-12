package br.com.coronasemijoias.api.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * As métricas do sync (tarefa 5.11; ADR-0013, item 21). Todas são leitura e sem etiqueta: nada de id, nome ou dado de
 * vendedor aparece nelas.
 *
 * <ul>
 *   <li>{@code corona.sync.xmin.idade.segundos}: a idade da transação de escrita mais antiga em andamento. É ela que
 *       segura o limite seguro do pull (ADR-0002, item 5): enquanto uma escrita demora, o pull de todos espera.
 *   <li>{@code corona.sync.pull.duracao} e {@code corona.sync.push.duracao}: quanto cada recebimento e cada envio levou.
 *   <li>{@code corona.sync.conferencia.defeitos}: tabelas em que um computador achou diferença na conferência por
 *       resumo (furo no filtro).
 *   <li>{@code corona.sync.computadores.sem.enviar}: computadores ativos parados além do prazo.
 * </ul>
 */
@Component
public class MetricasDoSync {

    public static final String XMIN_IDADE = "corona.sync.xmin.idade.segundos";
    public static final String PULL_DURACAO = "corona.sync.pull.duracao";
    public static final String PUSH_DURACAO = "corona.sync.push.duracao";
    public static final String DEFEITOS = "corona.sync.conferencia.defeitos";
    public static final String COMPUTADORES_PARADOS = "corona.sync.computadores.sem.enviar";

    private final JdbcTemplate jdbc;
    private final Timer pull;
    private final Timer push;
    private final Counter defeitos;

    public MetricasDoSync(JdbcTemplate jdbc, MeterRegistry registro, ComputadoresParados parados) {
        this.jdbc = jdbc;
        this.pull = Timer.builder(PULL_DURACAO).description("Duração de cada recebimento de dados do vendedor")
                .register(registro);
        this.push = Timer.builder(PUSH_DURACAO).description("Duração de cada envio de operações do vendedor")
                .register(registro);
        this.defeitos = Counter.builder(DEFEITOS)
                .description("Tabelas em que o computador achou diferença na conferência por resumo")
                .register(registro);
        Gauge.builder(XMIN_IDADE, this, MetricasDoSync::idadeDaTransacaoMaisAntigaSegundos)
                .description("Idade da transação de escrita mais antiga em andamento (segura o limite seguro do pull)")
                .baseUnit("seconds").register(registro);
        Gauge.builder(COMPUTADORES_PARADOS, parados, p -> p.semEnviar().size())
                .description("Computadores ativos parados além do prazo").register(registro);
    }

    /**
     * A idade, em segundos, da transação com identificador (escrita) mais antiga em andamento nesta base; zero se não
     * houver. Só enxerga as conexões do mesmo papel do banco, que são as da API.
     */
    public double idadeDaTransacaoMaisAntigaSegundos() {
        Double idade = jdbc.queryForObject("""
                SELECT coalesce(extract(epoch FROM max(clock_timestamp() - xact_start)), 0)::float8
                  FROM pg_stat_activity
                 WHERE datname = current_database() AND backend_xid IS NOT NULL AND pid <> pg_backend_pid()""",
                Double.class);
        return idade == null ? 0 : Math.max(0, idade);
    }

    public <T> T medirPull(Supplier<T> trabalho) {
        return pull.record(trabalho);
    }

    public <T> T medirPush(Supplier<T> trabalho) {
        return push.record(trabalho);
    }

    public void registrarDefeito(int tabelas) {
        if (tabelas > 0) {
            defeitos.increment(tabelas);
        }
    }
}

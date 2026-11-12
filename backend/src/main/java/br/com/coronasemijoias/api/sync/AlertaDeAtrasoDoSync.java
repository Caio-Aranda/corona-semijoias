package br.com.coronasemijoias.api.sync;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Os alertas de atraso do sync no log (tarefa 5.11; ADR-0013, item 21): a transação de escrita mais antiga passou de
 * 30 s (ADR-0002, item 5) e computador parado além do prazo. Cada episódio alerta uma vez só: o alerta repete depois
 * que o problema some e volta. Só leitura; nunca interfere no sync.
 */
@Component
@EnableScheduling
public class AlertaDeAtrasoDoSync {

    private static final Logger LOG = LoggerFactory.getLogger(AlertaDeAtrasoDoSync.class);

    private final MetricasDoSync metricas;
    private final ComputadoresParados computadores;
    private final Duration limiteDoXmin;
    private final boolean agendado;

    private boolean emAtraso;
    private Set<UUID> jaAvisados = new HashSet<>();

    public AlertaDeAtrasoDoSync(MetricasDoSync metricas, ComputadoresParados computadores,
            @Value("${corona.sync.alerta-xmin-segundos:30}") long limiteDoXminEmSegundos,
            @Value("${corona.sync.alerta-agendado:true}") boolean agendado) {
        this.metricas = metricas;
        this.computadores = computadores;
        this.limiteDoXmin = Duration.ofSeconds(limiteDoXminEmSegundos);
        this.agendado = agendado;
    }

    @Scheduled(fixedDelayString = "${corona.sync.alerta-intervalo:PT15S}", initialDelayString = "PT30S")
    void verificarAgendado() {
        if (!agendado) {
            return;
        }
        try {
            verificar(limiteDoXmin);
            verificarComputadores();
        } catch (RuntimeException e) {
            LOG.warn("Não foi possível conferir o atraso do sync agora: {}", e.toString());
        }
    }

    /**
     * Confere a idade da transação mais antiga contra o limite. Devolve {@code true} se está em atraso. O alerta sai uma
     * vez por episódio.
     */
    public synchronized boolean verificar(Duration limite) {
        double idade = metricas.idadeDaTransacaoMaisAntigaSegundos();
        boolean atrasado = idade > limite.toMillis() / 1000.0;
        if (atrasado && !emAtraso) {
            LOG.error("ATRASO DO LIMITE SEGURO DO SYNC: há uma transação de escrita aberta há {} s (limite {} s). "
                    + "O recebimento de dados de todos os vendedores espera por ela (ADR-0002, item 5).",
                    Math.round(idade), limite.toSeconds());
        } else if (!atrasado && emAtraso) {
            LOG.info("O limite seguro do sync voltou ao normal.");
        }
        emAtraso = atrasado;
        return atrasado;
    }

    /** Um aviso no log por computador parado, uma vez por episódio. Devolve quantos estão parados. */
    public synchronized int verificarComputadores() {
        var parados = computadores.semEnviar();
        Set<UUID> agora = new HashSet<>();
        for (ComputadoresParados.Parado p : parados) {
            agora.add(p.computadorId());
            if (!jaAvisados.contains(p.computadorId())) {
                LOG.warn("COMPUTADOR DO VENDEDOR SEM ENVIAR: o computador {} está parado há {} dia(s) (prazo {} dias).",
                        p.computadorId(), p.dias(), computadores.prazoEmDias());
            }
        }
        jaAvisados = agora;
        return parados.size();
    }
}

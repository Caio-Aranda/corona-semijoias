package br.com.coronasemijoias.api.cotacoes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara a busca das cotações (ADR-0006, item 3): segunda a sexta, a cada 30 minutos, em UTC. A agenda
 * vem de {@code corona.cotacoes.agenda}; quem decide se a hora vale é a {@link BuscaDeCotacoes}. Os
 * testes desligam a agenda com {@code corona.cotacoes.busca-agendada=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "corona.cotacoes.busca-agendada", havingValue = "true", matchIfMissing = true)
public class AgendaDaBuscaDeCotacoes {

    private static final Logger LOG = LoggerFactory.getLogger(AgendaDaBuscaDeCotacoes.class);

    private final BuscaDeCotacoes busca;

    public AgendaDaBuscaDeCotacoes(BuscaDeCotacoes busca) {
        this.busca = busca;
    }

    @Scheduled(cron = "${corona.cotacoes.agenda}", zone = "UTC")
    void buscar() {
        try {
            busca.rodar();
        } catch (RuntimeException e) {
            // Falha de banco, por exemplo: a próxima rodada tenta de novo. Nunca derruba a agenda.
            LOG.error("Cotações: a rodada de busca falhou.", e);
        }
    }
}

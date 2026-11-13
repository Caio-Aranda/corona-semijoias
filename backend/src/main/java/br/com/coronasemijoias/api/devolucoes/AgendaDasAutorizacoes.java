package br.com.coronasemijoias.api.devolucoes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Marca as autorizações vencidas todo dia às 03:10 UTC (00:10 de Brasília; tarefa 6.4, dúvida P8). É só a situação: a
 * regra do uso olha a data e nunca depende desta agenda ter rodado. Desligável com
 * {@code corona.devolucoes.agenda-das-autorizacoes=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "corona.devolucoes.agenda-das-autorizacoes", havingValue = "true", matchIfMissing = true)
public class AgendaDasAutorizacoes {

    private static final Logger LOG = LoggerFactory.getLogger(AgendaDasAutorizacoes.class);

    private final ServicoDeAutorizacoes autorizacoes;

    public AgendaDasAutorizacoes(ServicoDeAutorizacoes autorizacoes) {
        this.autorizacoes = autorizacoes;
    }

    @Scheduled(cron = "0 10 3 * * *", zone = "UTC")
    void marcarVencidas() {
        try {
            int quantas = autorizacoes.marcarVencidas();
            if (quantas > 0) {
                LOG.info("Autorizações de devolução: {} marcada(s) como vencida(s).", quantas);
            }
        } catch (RuntimeException e) {
            // Falha de banco, por exemplo: amanhã tenta de novo. Nunca derruba a agenda.
            LOG.error("Autorizações de devolução: a marcação das vencidas falhou.", e);
        }
    }
}

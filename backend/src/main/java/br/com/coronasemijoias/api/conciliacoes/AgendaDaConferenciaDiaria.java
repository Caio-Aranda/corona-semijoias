package br.com.coronasemijoias.api.conciliacoes;

import br.com.coronasemijoias.api.conciliacoes.ServicoDaConferenciaDiaria.Origem;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara a conferência diária todo dia às 03:00 de Brasília (tarefa 8.4; padrão R2 do ADR-0015), depois da limpeza técnica. Os
 * testes desligam a agenda. Se der erro, só registra: o aviso de "nenhuma conferência nas últimas 26 horas" avisa a dona, e a rodada
 * de amanhã tenta de novo. Se outra rodada já estiver rodando, esta desiste (uma só por vez, mesmo com mais de uma API no ar).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "corona.conferencia-diaria.agendada", havingValue = "true", matchIfMissing = true)
public class AgendaDaConferenciaDiaria {

    private static final Logger LOG = LoggerFactory.getLogger(AgendaDaConferenciaDiaria.class);

    private final ServicoDaConferenciaDiaria servico;

    public AgendaDaConferenciaDiaria(ServicoDaConferenciaDiaria servico) {
        this.servico = servico;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "America/Sao_Paulo")
    void conferir() {
        try {
            servico.rodar(UUID.randomUUID(), Origem.AUTOMATICA, null);
        } catch (RuntimeException e) {
            LOG.error("Conferência diária: a rodada automática não terminou; tenta de novo amanhã.", e);
        }
    }
}

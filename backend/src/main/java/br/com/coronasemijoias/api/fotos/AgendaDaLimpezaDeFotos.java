package br.com.coronasemijoias.api.fotos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Dispara a {@link LimpezaDeArquivosDeFotos} todo dia, de madrugada (UTC). Os testes desligam a agenda. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "corona.fotos.limpeza-agendada", havingValue = "true", matchIfMissing = true)
public class AgendaDaLimpezaDeFotos {

    private static final Logger LOG = LoggerFactory.getLogger(AgendaDaLimpezaDeFotos.class);

    private final LimpezaDeArquivosDeFotos limpeza;

    public AgendaDaLimpezaDeFotos(LimpezaDeArquivosDeFotos limpeza) {
        this.limpeza = limpeza;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    void limpar() {
        try {
            limpeza.limpar();
        } catch (RuntimeException e) {
            LOG.error("Fotos: a limpeza diária falhou; tenta de novo amanhã.", e);
        }
    }
}

package br.com.coronasemijoias.api.acessos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Dispara a {@link LimpezaDeDadosTecnicos} todo dia, de madrugada (UTC). Os testes desligam a agenda. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "corona.limpeza-tecnica.agendada", havingValue = "true", matchIfMissing = true)
public class AgendaDaLimpezaTecnica {

    private static final Logger LOG = LoggerFactory.getLogger(AgendaDaLimpezaTecnica.class);

    private final LimpezaDeDadosTecnicos limpeza;
    private final br.com.coronasemijoias.api.sync.EsvaziamentoDoRegistroDeEnvios registroDeEnvios;

    public AgendaDaLimpezaTecnica(LimpezaDeDadosTecnicos limpeza,
            br.com.coronasemijoias.api.sync.EsvaziamentoDoRegistroDeEnvios registroDeEnvios) {
        this.limpeza = limpeza;
        this.registroDeEnvios = registroDeEnvios;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "UTC")
    void limpar() {
        try {
            limpeza.limpar();
        } catch (RuntimeException e) {
            LOG.error("Limpeza técnica: falhou; tenta de novo amanhã.", e);
        }
        try {
            // O JSON completo dos envios do Corona Vendas fica 90 dias; depois, só o resumo (tarefa 5.5).
            registroDeEnvios.esvaziar();
        } catch (RuntimeException e) {
            LOG.error("Registro dos envios: o esvaziamento falhou; tenta de novo amanhã.", e);
        }
    }
}
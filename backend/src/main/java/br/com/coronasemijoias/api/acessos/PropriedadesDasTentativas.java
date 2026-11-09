package br.com.coronasemijoias.api.acessos;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros técnicos do limite de tentativas na configuração do servidor (ADR-0004, item 7, e
 * "Parâmetros técnicos e onde ficam"). Os dois números do bloqueio de login + IP ficam na tabela
 * {@code configuracoes}, não aqui.
 *
 * @param limitePorIp        erros de um IP (qualquer login) que bloqueiam o IP (50)
 * @param janelaPorIp        janela móvel em que os erros do IP são contados (15 minutos)
 * @param bloqueioPorIp      quanto tempo o IP fica bloqueado (15 minutos)
 * @param alertaPorLogin     mais erros que isto num login, somando os IPs, geram o alerta (20)
 * @param janelaDoAlerta     janela do alerta (1 hora)
 * @param esperaDaTrava      quanto um pedido de login espera a vez do seu IP antes de ser recusado (5 s)
 * @param proxiesConfiaveis  IPs ou faixas (CIDR) dos proxies cujo {@code X-Forwarded-For} vale (vazio = nenhum)
 */
@ConfigurationProperties("corona.seguranca.tentativas")
public record PropriedadesDasTentativas(
        int limitePorIp, Duration janelaPorIp, Duration bloqueioPorIp, int alertaPorLogin, Duration janelaDoAlerta,
        Duration esperaDaTrava, List<String> proxiesConfiaveis) {

    public PropriedadesDasTentativas {
        limitePorIp = limitePorIp <= 0 ? 50 : limitePorIp;
        janelaPorIp = positiva(janelaPorIp, Duration.ofMinutes(15));
        bloqueioPorIp = positiva(bloqueioPorIp, Duration.ofMinutes(15));
        alertaPorLogin = alertaPorLogin <= 0 ? 20 : alertaPorLogin;
        janelaDoAlerta = positiva(janelaDoAlerta, Duration.ofHours(1));
        esperaDaTrava = positiva(esperaDaTrava, Duration.ofSeconds(5));
        proxiesConfiaveis = proxiesConfiaveis == null ? List.of()
                : proxiesConfiaveis.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
    }

    private static Duration positiva(Duration valor, Duration padrao) {
        return valor == null || valor.isNegative() || valor.isZero() ? padrao : valor;
    }
}

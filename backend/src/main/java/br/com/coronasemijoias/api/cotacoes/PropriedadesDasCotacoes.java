package br.com.coronasemijoias.api.cotacoes;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros da busca das cotações na configuração do servidor (ADR-0006, itens 1 e 3). Sem segredo:
 * o arquivo do BCB é público. Os horários são em UTC. A agenda do disparo (cron) fica em
 * {@code corona.cotacoes.agenda} e precisa cobrir a janela abaixo.
 *
 * @param urlDoArquivo       endereço do arquivo, com {@code {data}} no lugar do dia (AAAAMMDD)
 * @param primeiraTentativa  primeira tentativa do dia, em UTC (16:30)
 * @param ultimaTentativa    última tentativa do dia, em UTC (21:00); depois dela o dia sem cotação vira aviso
 * @param tempoLimiteDoPedido tempo máximo de cada pedido ao BCB (20 segundos)
 * @param diasDeRecuperacao  quantos dias para trás a rodada procura dias úteis sem cotação (10)
 */
@ConfigurationProperties("corona.cotacoes")
public record PropriedadesDasCotacoes(
        String urlDoArquivo,
        LocalTime primeiraTentativa,
        LocalTime ultimaTentativa,
        Duration tempoLimiteDoPedido,
        Integer diasDeRecuperacao) {

    public static final String MARCADOR_DO_DIA = "{data}";

    public PropriedadesDasCotacoes {
        if (urlDoArquivo == null || !urlDoArquivo.contains(MARCADOR_DO_DIA)) {
            urlDoArquivo = "https://www4.bcb.gov.br/Download/fechamento/{data}.csv";
        }
        if (primeiraTentativa == null) {
            primeiraTentativa = LocalTime.of(16, 30);
        }
        if (ultimaTentativa == null) {
            ultimaTentativa = LocalTime.of(21, 0);
        }
        if (tempoLimiteDoPedido == null || tempoLimiteDoPedido.isNegative() || tempoLimiteDoPedido.isZero()) {
            tempoLimiteDoPedido = Duration.ofSeconds(20);
        }
        if (diasDeRecuperacao == null || diasDeRecuperacao < 1) {
            diasDeRecuperacao = 10;
        }
        if (ultimaTentativa.isBefore(primeiraTentativa)) {
            throw new IllegalArgumentException("corona.cotacoes: a última tentativa (" + ultimaTentativa
                    + ") não pode ser antes da primeira (" + primeiraTentativa + ").");
        }
    }
}

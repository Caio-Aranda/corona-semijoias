package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": envios com problema e vendas de computador bloqueado (tarefa 5.10). Calculado na hora;
 * o aviso some sozinho quando a dona decide cada um. Os textos são os do glossário.
 */
@Component
public class FonteDeAvisosDosEnvios implements FonteDeAvisos {

    public static final String TIPO_ENVIOS_COM_PROBLEMA = "ENVIOS_COM_PROBLEMA";
    public static final String TIPO_VENDAS_BLOQUEADO = "VENDAS_DE_COMPUTADOR_BLOQUEADO";

    private final JdbcTemplate jdbc;

    public FonteDeAvisosDosEnvios(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        List<AvisoDaDona> avisos = new ArrayList<>();
        long problemas = jdbc.queryForObject(
                "SELECT count(*) FROM corona.sync_log WHERE resultado IN ('RECUSADO', 'CONFLITO') AND resolucao IS NULL",
                Long.class);
        if (problemas > 0) {
            avisos.add(new AvisoDaDona(TIPO_ENVIOS_COM_PROBLEMA,
                    problemas == 1 ? "Há 1 envio com problema." : "Há " + problemas + " envios com problema.",
                    "São vendas ou cadastros feitos no Corona Vendas que não puderam entrar. "
                            + "Abra Envios com problema e escolha Tentar de novo ou Descartar.",
                    List.of()));
        }
        long bloqueadas = jdbc.queryForObject(
                "SELECT count(*) FROM corona.envios_em_quarentena WHERE situacao = 'GUARDADO'", Long.class);
        if (bloqueadas > 0) {
            avisos.add(new AvisoDaDona(TIPO_VENDAS_BLOQUEADO,
                    bloqueadas == 1 ? "Há 1 venda de um computador bloqueado esperando você."
                            : "Há " + bloqueadas + " vendas de um computador bloqueado esperando você.",
                    "Elas ainda não valem. Abra Vendas de computador bloqueado e escolha Aprovar ou Descartar.",
                    List.of()));
        }
        return avisos;
    }
}

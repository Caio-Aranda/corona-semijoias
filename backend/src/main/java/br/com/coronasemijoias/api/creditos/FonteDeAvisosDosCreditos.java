package br.com.coronasemijoias.api.creditos;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": as vendas pagas pelo envio com crédito do cliente que já tinha acabado, esperando a
 * dona (tarefa 6.7). Calculado na hora, nunca gravado: some quando a dona decide. Texto do glossário (parte 6).
 */
@Component
public class FonteDeAvisosDosCreditos implements FonteDeAvisos {

    public static final String TIPO_SEM_SALDO = "CREDITOS_SEM_SALDO_PARA_REVISAR";

    private final JdbcTemplate jdbc;

    FonteDeAvisosDosCreditos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        long quantas = jdbc.queryForObject(
                "SELECT count(*) FROM corona.creditos_sem_saldo WHERE situacao = 'EM_REVISAO' AND deleted_at IS NULL", Long.class);
        return aviso(quantas).map(List::of).orElse(List.of());
    }

    static Optional<AvisoDaDona> aviso(long quantas) {
        if (quantas <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AvisoDaDona(TIPO_SEM_SALDO,
                quantas == 1 ? "Há 1 venda paga com crédito que já tinha acabado."
                        : "Há " + quantas + " vendas pagas com crédito que já tinha acabado.",
                "Abra Créditos dos clientes para revisar e escolha Manter ou Não aceitar.", List.of()));
    }
}

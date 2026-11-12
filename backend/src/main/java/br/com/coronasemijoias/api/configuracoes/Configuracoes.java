package br.com.coronasemijoias.api.configuracoes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lê a tabela {@code configuracoes} (especificação §9). Lê a cada uso, sem guardar em memória: a
 * mudança de um valor vale na próxima chamada. As chaves existem desde a migração que as criou; a
 * falta de uma é defeito do banco, não da pessoa.
 */
@Component
public class Configuracoes {

    private final JdbcTemplate jdbc;

    public Configuracoes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int inteiro(ChaveDeConfiguracao chave) {
        List<Integer> valores = jdbc.queryForList(
                "SELECT valor_inteiro FROM corona.configuracoes WHERE chave = ? AND tipo = 'INTEIRO'",
                Integer.class, chave.chave());
        if (valores.isEmpty() || valores.get(0) == null) {
            throw new IllegalStateException("A configuração " + chave.chave() + " não existe na tabela configuracoes.");
        }
        return valores.get(0);
    }

    /** Valor em dinheiro (Real); vazio quando a chave existe e está sem valor ("sem limite"). */
    public Optional<BigDecimal> dinheiroOuVazio(ChaveDeConfiguracao chave) {
        List<BigDecimal> valores = jdbc.query(
                "SELECT valor_dinheiro FROM corona.configuracoes WHERE chave = ? AND tipo = 'DINHEIRO'",
                (rs, n) -> rs.getBigDecimal(1), chave.chave());
        if (valores.isEmpty()) {
            throw new IllegalStateException("A configuração " + chave.chave() + " não existe na tabela configuracoes.");
        }
        return Optional.ofNullable(valores.get(0));
    }
}

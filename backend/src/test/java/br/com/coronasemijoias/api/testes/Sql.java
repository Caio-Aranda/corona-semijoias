package br.com.coronasemijoias.api.testes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Atalhos de JDBC puro para os testes que controlam a transação passo a passo. */
public final class Sql {

    private Sql() {
    }

    public static int executar(Connection conexao, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement comando = preparar(conexao, sql, parametros)) {
            return comando.executeUpdate();
        }
    }

    /** Primeira coluna da primeira linha (ou {@code null} se não houver linha). */
    public static <T> T valor(Connection conexao, Class<T> tipo, String sql, Object... parametros)
            throws SQLException {
        try (PreparedStatement comando = preparar(conexao, sql, parametros);
                ResultSet linhas = comando.executeQuery()) {
            return linhas.next() ? linhas.getObject(1, tipo) : null;
        }
    }

    /** Primeira coluna de todas as linhas. */
    public static <T> List<T> valores(Connection conexao, Class<T> tipo, String sql, Object... parametros)
            throws SQLException {
        try (PreparedStatement comando = preparar(conexao, sql, parametros);
                ResultSet linhas = comando.executeQuery()) {
            List<T> resultado = new ArrayList<>();
            while (linhas.next()) {
                resultado.add(linhas.getObject(1, tipo));
            }
            return resultado;
        }
    }

    private static PreparedStatement preparar(Connection conexao, String sql, Object... parametros)
            throws SQLException {
        PreparedStatement comando = conexao.prepareStatement(sql);
        for (int i = 0; i < parametros.length; i++) {
            comando.setObject(i + 1, parametros[i]);
        }
        return comando;
    }
}

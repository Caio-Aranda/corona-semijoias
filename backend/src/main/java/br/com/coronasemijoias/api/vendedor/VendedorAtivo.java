package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Vendedor com o cadastro inativo não vende nem confere maleta, mesmo com o usuário ainda ativo (ADR-0011, linha de
 * 11/11/2026; auditoria, achado S1/D2). O servidor confere, não só a tela.
 */
public final class VendedorAtivo {

    private VendedorAtivo() {
    }

    /** Recusa com 409 ("Este vendedor está desativado. Fale com a dona.") se o cadastro do vendedor está inativo. */
    public static void exigir(JdbcTemplate jdbc, UUID vendedorId) {
        Boolean ativo = jdbc.query("SELECT ativo FROM corona.vendedores WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, vendedorId);
        if (!Boolean.TRUE.equals(ativo)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.VENDEDOR_DESATIVADO);
        }
    }
}

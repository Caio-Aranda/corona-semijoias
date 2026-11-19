package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alertas do consignado para a dona (ADR-0016, item 11): o que o servidor ACEITOU mas difere do que ele sabe (peça sem saldo,
 * preço diferente, total recalculado, pagamento acima do devido, documento repetido). O alerta é um registro (guarda o que o
 * app viu), nunca se calcula depois. Quem chama já está numa transação: o alerta entra junto com o que o causou.
 */
@Service
public class ServicoDeAlertasDoConsignado {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public ServicoDeAlertasDoConsignado(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void registrar(String tipo, UUID vendedorId, String tabela, UUID registroId, Map<String, Object> detalhe) {
        jdbc.update("""
                INSERT INTO corona.alertas_consignado (id, tipo, vendedor_id, registro_tabela, registro_id, detalhe)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)""", UuidV7.gerar(), tipo, vendedorId, tabela, registroId,
                json.writeValueAsString(detalhe == null ? Map.of() : detalhe));
    }
}

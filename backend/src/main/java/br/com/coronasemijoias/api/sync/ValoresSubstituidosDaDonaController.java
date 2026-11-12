package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Valores de cliente substituídos" (tarefa 5.10): quando o vendedor muda um campo do cliente que a dona também tinha
 * mudado, vale o último que chegou e o valor anterior fica na auditoria (tarefa 5.5). Aqui a dona vê o que foi trocado.
 */
@RestController
@RequestMapping("/api/dona/clientes/valores-substituidos")
@ExigePerfil("ADMIN")
public class ValoresSubstituidosDaDonaController {

    public record Campo(String campo, String valorAnterior, String valorNovo) {
    }

    public record ValorSubstituido(UUID id, UUID clienteId, String clienteNome, String quemMudou, Instant quando,
            List<Campo> campos) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    ValoresSubstituidosDaDonaController(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Operation(operationId = "listarValoresDeClienteSubstituidos", summary = "Valores de cliente substituídos",
            description = "Os 200 mais recentes: o campo, o valor que estava e o que passou a valer, e quem mudou.")
    @GetMapping
    public List<ValorSubstituido> listar() {
        return jdbc.query("""
                SELECT a.id, a.registro_id, c.nome AS cliente, u.nome AS quem, a.ocorrida_em, a.antes::text, a.depois::text
                  FROM corona.auditoria a
                  LEFT JOIN corona.clientes c ON c.id = a.registro_id
                  LEFT JOIN corona.usuarios u ON u.id = a.ator_usuario_id
                 WHERE a.acao = 'CLIENTE_VALOR_SUBSTITUIDO' AND a.tabela = 'clientes'
                 ORDER BY a.ocorrida_em DESC, a.id LIMIT 200""", (rs, i) -> {
            JsonNode antes = rs.getString(6) == null ? json.createObjectNode() : json.readTree(rs.getString(6));
            JsonNode depois = rs.getString(7) == null ? json.createObjectNode() : json.readTree(rs.getString(7));
            List<Campo> campos = new ArrayList<>();
            depois.propertyNames().forEach(nome -> campos.add(new Campo(nome, texto(antes.get(nome)), texto(depois.get(nome)))));
            return new ValorSubstituido(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    rs.getString(4), rs.getTimestamp(5).toInstant(), campos);
        });
    }

    private static String texto(JsonNode v) {
        return v == null || v.isNull() ? null : v.asString();
    }
}

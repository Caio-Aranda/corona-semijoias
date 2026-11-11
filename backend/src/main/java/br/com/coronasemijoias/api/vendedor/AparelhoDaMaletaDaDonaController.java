package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A dona libera o aparelho de uma maleta (tarefa 4.3; ADR-0011, item 11): o vendedor passa a usar outro aparelho. */
@RestController
@RequestMapping("/api/dona/remessas")
@ExigePerfil("ADMIN")
public class AparelhoDaMaletaDaDonaController {

    public record RespostaDaLiberacao(UUID remessaId, boolean tinhaAparelho) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;

    AparelhoDaMaletaDaDonaController(JdbcTemplate jdbc, ServicoDeAuditoria auditoria) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
    }

    @Operation(operationId = "liberarAparelhoDaMaleta", summary = "Liberar o aparelho da maleta",
            description = "Desliga a maleta do aparelho em que ela estava; o próximo aparelho do vendedor que vender ou conferir "
                    + "passa a ser o dela. Fica na auditoria. Maleta sem aparelho: 200, sem mudar nada.")
    @PostMapping("/{id}/liberar-aparelho")
    @Transactional
    public RespostaDaLiberacao liberar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        List<Map<String, Object>> r = jdbc.queryForList(
                "SELECT dispositivo_id FROM corona.remessas WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id);
        if (r.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        Object antes = r.get(0).get("dispositivo_id");
        if (antes != null) {
            jdbc.update("UPDATE corona.remessas SET dispositivo_id = NULL WHERE id = ?", id);
            auditoria.registrar(Ator.usuario(quem.id()), "MALETA_APARELHO_LIBERADO", "remessas", id,
                    Map.of("dispositivo_id", antes.toString()), Map.of("dispositivo_id", "livre"));
        }
        return new RespostaDaLiberacao(id, antes != null);
    }
}

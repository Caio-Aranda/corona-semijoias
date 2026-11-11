package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.vendedor.ConsultasDaConferencia.Conferencia;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A dona vê o resultado da conferência que o vendedor fez da maleta (tarefa 4.3; ADR-0011, item 11). Só ADMIN. */
@RestController
@RequestMapping("/api/dona/remessas")
@ExigePerfil("ADMIN")
public class ConferenciaDaMaletaDaDonaController {

    private final ConsultasDaConferencia consultas;

    ConferenciaDaMaletaDaDonaController(ConsultasDaConferencia consultas) {
        this.consultas = consultas;
    }

    @Operation(operationId = "verConferenciaDaMaleta", summary = "Ver a conferência da maleta",
            description = "O que o vendedor contou de cada peça, o que devia haver e a diferença. 404 enquanto o vendedor não conferiu.")
    @GetMapping("/{id}/conferencia")
    public Conferencia ver(@PathVariable UUID id) {
        return consultas.daMaleta(id, null);
    }
}

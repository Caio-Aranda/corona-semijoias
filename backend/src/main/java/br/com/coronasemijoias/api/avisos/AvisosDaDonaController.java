package br.com.coronasemijoias.api.avisos;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "O que precisa da sua atenção": a lista da tela inicial da dona (tarefa 1.7). */
@RestController
@RequestMapping("/api/dona/avisos")
@ExigePerfil("ADMIN")
public class AvisosDaDonaController {

    public record AvisosResposta(List<AvisoDaDona> avisos) {
    }

    private final List<FonteDeAvisos> fontes;

    public AvisosDaDonaController(List<FonteDeAvisos> fontes) {
        this.fontes = List.copyOf(fontes);
    }

    @Operation(
            summary = "O que precisa da sua atenção",
            description = "Os avisos que valem agora, calculados na hora (nada é gravado): cada um some sozinho quando "
                    + "o problema some. Lista vazia quando não há nada. Hoje: a cotação do Dólar e do Guarani que não chegou.")
    @GetMapping
    public AvisosResposta listar() {
        return new AvisosResposta(fontes.stream().flatMap(f -> f.avisos().stream()).toList());
    }
}

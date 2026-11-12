package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.sync.EnviosComProblema.EnvioComProblema;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Envios com problema" na mão da dona (tarefa 5.5; ADR-0013, item 2). A tela é da tarefa 5.10. */
@RestController
@RequestMapping("/api/dona/envios-com-problema")
@ExigePerfil("ADMIN")
public class EnviosComProblemaDaDonaController {

    public record PedidoDeDescarte(@Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    private final EnviosComProblema envios;

    EnviosComProblemaDaDonaController(EnviosComProblema envios) {
        this.envios = envios;
    }

    @Operation(operationId = "listarEnviosComProblema", summary = "Envios com problema",
            description = "Vendas, estornos, clientes e conferências que vieram do Corona Vendas e não foram aceitos (recusados "
                    + "ou em conflito), ainda sem decisão. Com o motivo, o vendedor, o computador e o conteúdo guardado (por 90 dias).")
    @GetMapping
    public List<EnvioComProblema> listar() {
        return envios.listar();
    }

    @Operation(operationId = "tentarEnvioDeNovo", summary = "Tentar de novo",
            description = "Passa a operação guardada outra vez pelo mesmo serviço, com o mesmo id e a mesma hora. Use depois de "
                    + "corrigir a causa (por exemplo, devolver o cliente ao vendedor). Recusada de novo: o erro volta e o envio "
                    + "continua na lista.")
    @PostMapping("/{id}/tentar-de-novo")
    public EnvioComProblema tentarDeNovo(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return envios.tentarDeNovo(id, quem.id());
    }

    @Operation(operationId = "descartarEnvio", summary = "Descartar",
            description = "A operação não entra. O motivo é obrigatório e fica na auditoria. Nada é apagado.")
    @PostMapping("/{id}/descartar")
    public EnvioComProblema descartar(@PathVariable UUID id, @Valid @RequestBody PedidoDeDescarte pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return envios.descartar(id, pedido.motivo(), quem.id());
    }
}

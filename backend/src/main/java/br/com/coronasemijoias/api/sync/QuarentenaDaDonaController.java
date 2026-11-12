package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.sync.ServicoDeEnvio.ResultadoDaOperacao;
import br.com.coronasemijoias.api.sync.ServicoDeQuarentena.EnvioGuardado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Vendas de computador bloqueado" na mão da dona (tarefa 5.10; ADR-0013, item 14). Só a dona. */
@RestController
@RequestMapping("/api/dona/quarentena")
@ExigePerfil("ADMIN")
public class QuarentenaDaDonaController {

    public record PedidoDeDescarte(@Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    private final ServicoDeQuarentena quarentena;

    QuarentenaDaDonaController(ServicoDeQuarentena quarentena) {
        this.quarentena = quarentena;
    }

    @Operation(operationId = "listarVendasDeComputadorBloqueado", summary = "Vendas de computador bloqueado",
            description = "O que o computador bloqueado ainda não tinha enviado e ficou guardado, sem valer, esperando a dona.")
    @GetMapping
    public List<EnvioGuardado> listar() {
        return quarentena.listar();
    }

    @Operation(operationId = "aprovarVendaDeComputadorBloqueado", summary = "Aprovar",
            description = "Passa o envio guardado pelo mesmo serviço do envio normal. Se for recusado, ele vai para os envios "
                    + "com problema, onde a dona resolve.")
    @PostMapping("/{id}/aprovar")
    public ResultadoDaOperacao aprovar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return quarentena.aprovar(id, quem.id());
    }

    @Operation(operationId = "descartarVendaDeComputadorBloqueado", summary = "Descartar",
            description = "O envio guardado não entra. O motivo é obrigatório e fica na auditoria. Nada é apagado.")
    @PostMapping("/{id}/descartar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void descartar(@PathVariable UUID id, @Valid @RequestBody PedidoDeDescarte pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de descartar este envio.");
        }
        quarentena.descartar(id, quem.id(), motivo);
    }
}

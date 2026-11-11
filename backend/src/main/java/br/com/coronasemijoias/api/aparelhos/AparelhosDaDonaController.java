package br.com.coronasemijoias.api.aparelhos;

import br.com.coronasemijoias.api.aparelhos.ServicoDeAparelhos.AparelhoDoVendedor;
import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
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

/** Os computadores dos vendedores, na mão da dona (tarefa 5.3; ADR-0013, itens 13 e 14). As telas são da tarefa 5.10. */
@RestController
@RequestMapping("/api/dona")
@ExigePerfil("ADMIN")
public class AparelhosDaDonaController {

    /** Pedido de bloqueio: o motivo é opcional (fica na auditoria). */
    public record PedidoDeBloqueio(@Size(max = 500, message = Mensagens.LIMITE_LONGO) String motivo) {
    }

    private final ServicoDeAparelhos aparelhos;

    AparelhosDaDonaController(ServicoDeAparelhos aparelhos) {
        this.aparelhos = aparelhos;
    }

    @Operation(operationId = "listarAparelhosDoVendedor", summary = "Computadores do vendedor",
            description = "Todos os computadores em que o vendedor já entrou, do mais novo para o mais antigo, com a situação, "
                    + "o último contato, a versão do app, quantas vendas o computador disse ter aguardando envio e o último "
                    + "envio completo.")
    @GetMapping("/vendedores/{vendedorId}/aparelhos")
    public List<AparelhoDoVendedor> listar(@PathVariable UUID vendedorId) {
        return aparelhos.doVendedor(vendedorId);
    }

    @Operation(operationId = "liberarAparelho", summary = "Trocar computador (liberar o novo)",
            description = "Libera o computador que espera a dona. O computador em uso antes passa a trocado (ainda envia as "
                    + "vendas que guardou, mas não vende) e as maletas abertas dele passam para o novo. Computador bloqueado "
                    + "ou já trocado não volta (409). Liberar o que já está em uso não muda nada.")
    @PostMapping("/aparelhos/{id}/liberar")
    public AparelhoDoVendedor liberar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return aparelhos.liberar(id, quem.id());
    }

    @Operation(operationId = "bloquearAparelho", summary = "Bloquear computador (perdido ou roubado)",
            description = "Sem volta. Encerra as sessões do computador; na próxima conexão ele recebe a ordem de apagar os "
                    + "dados da loja (as vendas guardadas vão antes para a revisão da dona). Bloquear de novo não muda nada.")
    @PostMapping("/aparelhos/{id}/bloquear")
    public AparelhoDoVendedor bloquear(@PathVariable UUID id, @Valid @RequestBody(required = false) PedidoDeBloqueio pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return aparelhos.bloquear(id, pedido == null ? null : pedido.motivo(), quem.id());
    }
}

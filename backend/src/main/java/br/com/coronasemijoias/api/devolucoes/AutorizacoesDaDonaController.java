package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.devolucoes.ConsultasDeAutorizacoes.Autorizacao;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A dona decide os pedidos de autorização de devolução (tarefa 6.4; ADR-0014, item 6). Só ADMIN. As telas chegam com a
 * tarefa 6.9.
 */
@RestController
@RequestMapping("/api/dona/autorizacoes")
@ExigePerfil("ADMIN")
public class AutorizacoesDaDonaController {

    public record PedidoDeNegacao(String motivo) {
    }

    private final ServicoDeAutorizacoes servico;
    private final ConsultasDeAutorizacoes consultas;

    AutorizacoesDaDonaController(ServicoDeAutorizacoes servico, ConsultasDeAutorizacoes consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarAutorizacoes", summary = "Pedidos de autorização de devolução",
            description = "Do mais novo para o mais antigo (no máximo 500). Filtro pela situação (padrão: esperando a dona) e "
                    + "pelo vendedor.")
    @GetMapping
    public List<Autorizacao> listar(@RequestParam(required = false) String situacao,
            @RequestParam(required = false) UUID vendedorId) {
        return consultas.listarDaDona(situacao, vendedorId);
    }

    @Operation(operationId = "verAutorizacao", summary = "Ver um pedido de autorização")
    @GetMapping("/{id}")
    public Autorizacao ver(@PathVariable UUID id) {
        return consultas.ver(id, null);
    }

    @Operation(operationId = "aprovarAutorizacao", summary = "Aprovar um pedido de autorização",
            description = "A autorização vale até o 7º dia depois de hoje (dias de Brasília) e serve para uma devolução só. "
                    + "Aprovar de novo devolve o mesmo; depois de negar, 409.")
    @PostMapping("/{id}/aprovar")
    public Autorizacao aprovar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.aprovar(id, quem.id());
        return consultas.ver(id, null);
    }

    @Operation(operationId = "negarAutorizacao", summary = "Negar um pedido de autorização",
            description = "Motivo obrigatório. Negar de novo devolve o mesmo; depois de aprovar, 409.")
    @PostMapping("/{id}/negar")
    public Autorizacao negar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeNegacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.negar(id, quem.id(), pedido == null ? null : pedido.motivo());
        return consultas.ver(id, null);
    }
}

package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.acertos.ServicoDePendencias.Pendencia;
import br.com.coronasemijoias.api.acertos.ServicoDePendencias.PedidoDeResolucao;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** As pendências do acerto, na mão da dona (tarefa 7.5; ADR-0012, item 6). Só ADMIN. */
@RestController
@RequestMapping("/api/dona/pendencias-acerto")
@ExigePerfil("ADMIN")
public class PendenciasDaDonaController {

    private final ServicoDePendencias servico;

    PendenciasDaDonaController(ServicoDePendencias servico) {
        this.servico = servico;
    }

    @Operation(operationId = "listarPendenciasDoAcerto", summary = "Pendências do acerto",
            description = "Falta, defeito e o que a dona não aceitou, da mais nova para a mais antiga (no máximo 500). Filtros: vendedor e situação.")
    @GetMapping
    public List<Pendencia> listar(@RequestParam(required = false) UUID vendedorId, @RequestParam(required = false) String status) {
        return servico.listar(vendedorId, status);
    }

    @Operation(operationId = "resolverPendenciaDoAcerto", summary = "Resolver uma pendência aberta",
            description = "LOJA_ASSUME (a loja assume a perda) ou ENCONTRADA (só falta: a peça volta ao estoque). Motivo obrigatório. Mesmo id e "
                    + "mesmo conteúdo devolve 200; já resolvida por outro pedido, 409.")
    @PostMapping("/{id}/resolver")
    public ResponseEntity<Pendencia> resolver(@PathVariable UUID id, @RequestBody PedidoDeResolucao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        ServicoDePendencias.Resultado r = servico.resolver(quem.id(), id, pedido);
        Pendencia p = servico.ver(id);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(p);
    }
}

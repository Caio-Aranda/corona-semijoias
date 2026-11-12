package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.devolucoes.ConsultasDeDevolucoes.Devolucao;
import br.com.coronasemijoias.api.devolucoes.DevolucoesDoVendedorController.PedidoDeCancelamento;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A dona cancela qualquer devolução, sem limite de prazo (tarefa 6.3; ADR-0014, item 11: "depois do dia, só a dona"). Só
 * ADMIN. As listas e telas da dona chegam com a tarefa 6.9.
 */
@RestController
@RequestMapping("/api/dona/devolucoes")
@ExigePerfil("ADMIN")
public class DevolucoesDaDonaController {

    private final ServicoDeDevolucoes servico;
    private final ConsultasDeDevolucoes consultas;

    DevolucoesDaDonaController(ServicoDeDevolucoes servico, ConsultasDeDevolucoes consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "cancelarDevolucao", summary = "Cancelar uma devolução",
            description = "Desfaz a devolução com registros de sinal oposto, a qualquer hora: a peça sai de novo da maleta e o "
                    + "reembolso é estornado. Motivo obrigatório; mesmo id de cancelamento devolve 200.")
    @PostMapping("/{id}/cancelar")
    public ResponseEntity<Devolucao> cancelar(@PathVariable UUID id, @RequestBody PedidoDeCancelamento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de cancelar a devolução.");
        }
        Resultado r = servico.cancelar(id, null, quem.id(), null, pedido.id(), pedido.motivo(), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(id, null));
    }
}

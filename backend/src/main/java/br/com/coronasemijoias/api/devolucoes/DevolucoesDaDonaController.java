package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.devolucoes.ConsultasDeDevolucoes.Devolucao;
import br.com.coronasemijoias.api.devolucoes.DevolucoesDoVendedorController.PedidoDeCancelamento;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.Resultado;
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

/**
 * As devoluções na mão da dona: cancelar qualquer uma, sem limite de prazo (tarefa 6.3; ADR-0014, item 11), e revisar as
 * que estão para revisão, com Manter ou Não aceitar (tarefa 6.4; ADR-0014, item 5). Só ADMIN. As telas chegam com a
 * tarefa 6.9.
 */
@RestController
@RequestMapping("/api/dona/devolucoes")
@ExigePerfil("ADMIN")
public class DevolucoesDaDonaController {

    public record PedidoDeRevisao(String observacao) {
    }

    private final ServicoDeDevolucoes servico;
    private final ConsultasDeDevolucoes consultas;

    DevolucoesDaDonaController(ServicoDeDevolucoes servico, ConsultasDeDevolucoes consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarDevolucoes", summary = "Devoluções",
            description = "Da mais nova para a mais antiga (no máximo 500). Filtro pela situação (padrão: para revisão) e pelo "
                    + "vendedor. Cada devolução traz os motivos da revisão, quando há.")
    @GetMapping
    public List<Devolucao> listar(@RequestParam(required = false) String situacao,
            @RequestParam(required = false) UUID vendedorId) {
        return consultas.listarDaDona(situacao, vendedorId);
    }

    @Operation(operationId = "verDevolucao", summary = "Ver uma devolução")
    @GetMapping("/{id}")
    public Devolucao ver(@PathVariable UUID id) {
        return consultas.ver(id, null);
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

    @Operation(operationId = "manterDevolucao", summary = "Manter uma devolução para revisão",
            description = "A devolução vale como está. Nada muda na maleta nem no dinheiro. Observação opcional. Manter de novo "
                    + "devolve o mesmo; depois de não aceitar, 409.")
    @PostMapping("/{id}/manter")
    public Devolucao manter(@PathVariable UUID id, @RequestBody(required = false) PedidoDeRevisao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.manter(id, quem.id(), pedido == null ? null : pedido.observacao());
        return consultas.ver(id, null);
    }

    @Operation(operationId = "naoAceitarDevolucao", summary = "Não aceitar uma devolução para revisão",
            description = "Nada muda na maleta nem no dinheiro já devolvido: o valor devolvido fica como pendência do vendedor, "
                    + "cobrada no acerto. Observação obrigatória. Não aceitar de novo devolve o mesmo; depois de manter, 409.")
    @PostMapping("/{id}/nao-aceitar")
    public Devolucao naoAceitar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeRevisao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.naoAceitar(id, quem.id(), pedido == null ? null : pedido.observacao());
        return consultas.ver(id, null);
    }
}

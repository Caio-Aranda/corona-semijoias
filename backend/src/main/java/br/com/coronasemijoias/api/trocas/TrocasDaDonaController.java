package br.com.coronasemijoias.api.trocas;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.trocas.ConsultasDeTrocas.TrocaLida;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.Resultado;
import br.com.coronasemijoias.api.trocas.TrocasDoVendedorController.PedidoDeCancelamentoDaTroca;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
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

/** A dona vê qualquer troca e cancela sem limite de prazo (tarefa 6.5; ADR-0014, item 11). Só ADMIN. As telas são da 6.9. */
@RestController
@RequestMapping("/api/dona/trocas")
@ExigePerfil("ADMIN")
public class TrocasDaDonaController {

    private final ServicoDeTrocas servico;
    private final ConsultasDeTrocas consultas;

    TrocasDaDonaController(ServicoDeTrocas servico, ConsultasDeTrocas consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarTrocas", summary = "Trocas",
            description = "Todas as trocas, da mais nova para a mais antiga (no máximo 500). Filtros opcionais por vendedor, dia "
                    + "(de e até, no horário de Brasília) e situação (ATIVA ou CANCELADA).")
    @GetMapping
    public List<TrocaLida> listar(@RequestParam(required = false) UUID vendedorId, @RequestParam(required = false) LocalDate de,
            @RequestParam(required = false) LocalDate ate, @RequestParam(required = false) String situacao) {
        return consultas.listar(vendedorId, de, ate, situacao);
    }

    @Operation(operationId = "verTroca", summary = "Ver uma troca")
    @GetMapping("/{id}")
    public TrocaLida ver(@PathVariable UUID id) {
        return consultas.ver(id, null);
    }

    @Operation(operationId = "cancelarTroca", summary = "Cancelar uma troca",
            description = "Desfaz a troca a qualquer hora, com registros de sinal oposto. Recusado se o crédito que sobrou já foi "
                    + "usado, se a peça A já foi vendida de novo ou se uma das maletas já não está com o vendedor. Motivo "
                    + "obrigatório; mesmo id devolve 200.")
    @PostMapping("/{id}/cancelar")
    public ResponseEntity<TrocaLida> cancelar(@PathVariable UUID id, @RequestBody PedidoDeCancelamentoDaTroca pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de cancelar a troca.");
        }
        Resultado r = servico.cancelar(id, null, quem.id(), null, pedido.id(), pedido.motivo(), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(id, null));
    }
}

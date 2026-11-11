package br.com.coronasemijoias.api.vendas;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Venda;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.Resultado;
import br.com.coronasemijoias.api.vendas.VendasDoVendedorController.PedidoDeEstorno;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A dona vê qualquer venda e pode estornar sem limite de prazo (tarefa 4.2; ADR-0011, item 8). Só ADMIN. */
@RestController
@RequestMapping("/api/dona/vendas")
@ExigePerfil("ADMIN")
public class VendasDaDonaController {

    private final ServicoDeVendas servico;
    private final ConsultasDeVendas consultas;

    VendasDaDonaController(ServicoDeVendas servico, ConsultasDeVendas consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "verVenda", summary = "Ver uma venda")
    @GetMapping("/{id}")
    public Venda ver(@PathVariable UUID id) {
        return consultas.ver(id, null);
    }

    @Operation(operationId = "estornarVenda", summary = "Estornar uma venda",
            description = "Estorna a venda inteira, sem limite de prazo: as peças voltam para a maleta e o que o cliente pagou vira "
                    + "reembolso. Motivo obrigatório; mesmo id de estorno devolve 200.")
    @PostMapping("/{id}/estorno")
    public ResponseEntity<Venda> estornar(@PathVariable UUID id, @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar a venda.");
        }
        Resultado r = servico.estornar(id, null, quem.id(), null, pedido.id(), pedido.motivo());
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(id, null));
    }
}

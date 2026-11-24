package br.com.coronasemijoias.api.creditos;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.creditos.ConsultasDeCreditos.CreditoDoCliente;
import br.com.coronasemijoias.api.creditos.ServicoDeCreditos.Resultado;
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

/** A dona vê todos os créditos do cliente e transfere o saldo para outro vendedor (tarefa 6.5; dúvida T7). Só ADMIN. */
@RestController
@RequestMapping("/api/dona/creditos")
@ExigePerfil("ADMIN")
public class CreditosDaDonaController {

    /** {@code id}: o id do crédito novo (do aplicativo, para a idempotência); {@code vendedorId}: quem passa a usar. */
    public record PedidoDeTransferencia(UUID id, UUID vendedorId) {
    }

    private final ServicoDeCreditos servico;
    private final ConsultasDeCreditos consultas;

    CreditosDaDonaController(ServicoDeCreditos servico, ConsultasDeCreditos consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarCreditos", summary = "Créditos dos clientes",
            description = "Os créditos do cliente, do mais novo para o mais antigo (no máximo 500), pela situação (padrão: "
                    + "ABERTO, em aberto), com filtros opcionais de cliente e de vendedor.")
    @GetMapping
    public List<CreditoDoCliente> listar(@RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) UUID vendedorId, @RequestParam(required = false) String situacao) {
        return consultas.daDona(clienteId, vendedorId, situacao);
    }

    @Operation(operationId = "verCredito", summary = "Ver um crédito de cliente")
    @GetMapping("/{id}")
    public CreditoDoCliente ver(@PathVariable UUID id) {
        return consultas.ver(id, null);
    }

    @Operation(operationId = "transferirCredito", summary = "Transferir o crédito para outro vendedor",
            description = "Passa o saldo inteiro do crédito para outro vendedor ativo: nasce um crédito novo dele (com o id "
                    + "mandado) e o original fica usado. Sem saldo, 409; para o mesmo vendedor ou um vendedor desligado, 400. "
                    + "Mesmo id e mesmo vendedor devolve 200; mesmo id com outro vendedor, 409.")
    @PostMapping("/{id}/transferir")
    public ResponseEntity<CreditoDoCliente> transferir(@PathVariable UUID id, @RequestBody PedidoDeTransferencia pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("vendedorId", "Escolha o representante que vai receber o crédito.");
        }
        Resultado r = servico.transferir(id, pedido.vendedorId(), pedido.id(), quem.id());
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(r.creditoId(), null));
    }
}

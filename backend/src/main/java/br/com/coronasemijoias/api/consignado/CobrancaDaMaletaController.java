package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.consignado.ServicoDaCobrancaDaMaleta.PedidoDePagamento;
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

/** Receber a cobrança da falta do Fechamento da maleta (§23.10, item 1a). Só a dona; nunca vai ao Corona Vendas. */
@RestController
@RequestMapping("/api/maletas")
@ExigePerfil("ADMIN")
public class CobrancaDaMaletaController {

    public record PedidoDeEstorno(UUID id, String motivo) {
    }

    public record RespostaDoPagamento(UUID id) {
    }

    private final ServicoDaCobrancaDaMaleta cobranca;

    CobrancaDaMaletaController(ServicoDaCobrancaDaMaleta cobranca) {
        this.cobranca = cobranca;
    }

    @Operation(operationId = "pagarCobrancaDaMaleta", summary = "Registrar o que o representante pagou da falta cobrada",
            description = "Valor em Real ou, em DOLAR ou GUARANI, só o valorNaMoeda (o servidor converte pela cotação vigente). Forma "
                    + "DINHEIRO ou PIX. Parte do valor é aceita; acima do que falta pagar, 409 PAGAMENTO_ACIMA_DO_SALDO. Entra no caixa. "
                    + "201 na primeira vez, 200 no reenvio do mesmo id.")
    @PostMapping("/pendencias/{id}/pagamentos")
    public ResponseEntity<RespostaDoPagamento> pagar(@PathVariable UUID id, @RequestBody PedidoDePagamento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        boolean jaExistia = cobranca.pagar(id, pedido, quem.id());
        return ResponseEntity.status(jaExistia ? HttpStatus.OK : HttpStatus.CREATED).body(new RespostaDoPagamento(pedido.id()));
    }

    @Operation(operationId = "desfazerPagamentoDaCobrancaDaMaleta", summary = "Desfazer um pagamento da cobrança (estorno com motivo)",
            description = "Grava o estorno (valor oposto) e a saída oposta no caixa. Um estorno por pagamento (409 LANCAMENTO_JA_DESFEITO).")
    @PostMapping("/pagamentos-cobranca/{id}/estorno")
    public ResponseEntity<RespostaDoPagamento> estornar(@PathVariable UUID id, @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        cobranca.estornar(id, pedido == null ? null : pedido.id(), pedido == null ? null : pedido.motivo(), quem.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RespostaDoPagamento(pedido == null ? null : pedido.id()));
    }
}

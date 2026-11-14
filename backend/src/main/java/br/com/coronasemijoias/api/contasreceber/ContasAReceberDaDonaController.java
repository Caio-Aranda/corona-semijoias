package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.contasreceber.ContasAReceberDoVendedorController.PedidoDeEstornoDeRecebimento;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Venda;
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

/** Contas a receber do cliente, lado da dona (tarefa 7.3): lê tudo e estorna recebimentos sem limite de dia. */
@RestController
@RequestMapping("/api/dona")
@ExigePerfil("ADMIN")
public class ContasAReceberDaDonaController {

    private final ServicoDeContasAReceber servico;
    private final ConsultasDeVendas vendas;

    ContasAReceberDaDonaController(ServicoDeContasAReceber servico, ConsultasDeVendas vendas) {
        this.servico = servico;
        this.vendas = vendas;
    }

    @Operation(operationId = "estornarRecebimentoDeCliente", summary = "Estornar um recebimento do cliente",
            description = "Desfaz um recebimento feito depois da venda, sem limite de dia: as parcelas voltam a ficar em "
                    + "aberto. Motivo obrigatório; mesmo id de estorno devolve 200.")
    @PostMapping("/recebimentos-de-cliente/{pagamentoId}/estorno")
    public ResponseEntity<Venda> estornar(@PathVariable UUID pagamentoId, @RequestBody PedidoDeEstornoDeRecebimento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o recebimento.");
        }
        Resultado r = servico.estornarRecebimento(pagamentoId, null, quem.id(), null, pedido.id(), pedido.motivo(), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(vendas.ver(r.vendaId(), null));
    }
}

package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Recebimento;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Resultado;
import br.com.coronasemijoias.api.contasreceber.ConsultasDeContasAReceber.Conta;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Venda;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
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
 * Contas a receber do cliente, lado do vendedor (tarefa 7.3; ADR-0012, item 7). Só o perfil VENDEDOR e só as vendas dele:
 * o vendedor vem do token. Os ids dos comandos vêm do aplicativo; 201 na primeira gravação, 200 no reenvio igual, 409 com
 * o mesmo id e dados diferentes.
 */
@RestController
@RequestMapping("/api/vendedor")
@ExigePerfil("VENDEDOR")
public class ContasAReceberDoVendedorController {

    public record PedidoDeRecebimento(UUID id, BigDecimal valor, String forma) {
    }

    public record PedidoDeEstornoDeRecebimento(UUID id, String motivo) {
    }

    private final ServicoDeContasAReceber servico;
    private final ConsultasDeVendas vendas;
    private final ConsultasDeContasAReceber consultas;

    ContasAReceberDoVendedorController(ServicoDeContasAReceber servico, ConsultasDeVendas vendas,
            ConsultasDeContasAReceber consultas) {
        this.servico = servico;
        this.vendas = vendas;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarMinhasContasAReceber", summary = "Minhas contas a receber",
            description = "As parcelas das vendas a prazo do vendedor logado, da que vence primeiro para a que vence depois. "
                    + "Filtros opcionais: situação (ABERTA, PARCIAL, PAGA, CANCELADA, EM_ABERTO ou VENCIDA, calculada pela "
                    + "data de hoje em Brasília), cliente e venda. Só valores em Real.")
    @GetMapping("/contas-receber")
    public List<Conta> listar(@RequestParam(required = false) String situacao, @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) UUID vendaId, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.doVendedor(quem.vendedorId(), situacao, clienteId, vendaId);
    }

    @Operation(operationId = "receberDoCliente", summary = "Registrar um recebimento do cliente",
            description = "O cliente paga, depois da venda, parte ou o que falta de uma venda a prazo (só dinheiro ou "
                    + "transferência). O servidor distribui o valor pelas parcelas, da que vence primeiro para a que vence "
                    + "depois, e não aceita mais do que falta pagar. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados "
                    + "diferentes, 409.")
    @PostMapping("/vendas/{vendaId}/recebimentos")
    public ResponseEntity<Venda> receber(@PathVariable UUID vendaId, @RequestBody PedidoDeRecebimento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("recebimento", "Informe o recebimento.");
        }
        Resultado r = servico.receber(vendaId, quem.vendedorId(), quem.id(), quem.dispositivoId(),
                new Recebimento(pedido.id(), pedido.valor(), pedido.forma()), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(vendas.ver(r.vendaId(), quem.vendedorId()));
    }

    @Operation(operationId = "estornarRecebimentoDoVendedor", summary = "Estornar um recebimento do cliente (no mesmo dia)",
            description = "Desfaz um recebimento feito depois da venda: as parcelas voltam a ficar em aberto. Só no dia do "
                    + "recebimento; depois, só a dona. O pagamento feito na hora da venda se corrige estornando a venda. Exige "
                    + "o id do estorno (do aplicativo) e o motivo. Mesmo id devolve 200.")
    @PostMapping("/recebimentos-de-cliente/{pagamentoId}/estorno")
    public ResponseEntity<Venda> estornar(@PathVariable UUID pagamentoId, @RequestBody PedidoDeEstornoDeRecebimento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o recebimento.");
        }
        Resultado r = servico.estornarRecebimento(pagamentoId, quem.vendedorId(), quem.id(), quem.dispositivoId(), pedido.id(),
                pedido.motivo(), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(vendas.ver(r.vendaId(), quem.vendedorId()));
    }
}

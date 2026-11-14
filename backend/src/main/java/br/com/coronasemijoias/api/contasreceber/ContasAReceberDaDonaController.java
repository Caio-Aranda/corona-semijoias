package br.com.coronasemijoias.api.contasreceber;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.contasreceber.ContasAReceberDoVendedorController.PedidoDeEstornoDeRecebimento;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber.Resultado;
import br.com.coronasemijoias.api.contasreceber.ConsultasDeContasAReceber.ContaDaDona;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Venda;
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

/** Contas a receber do cliente, lado da dona (tarefa 7.3): lê tudo e estorna recebimentos sem limite de dia. */
@RestController
@RequestMapping("/api/dona")
@ExigePerfil("ADMIN")
public class ContasAReceberDaDonaController {

    private final ServicoDeContasAReceber servico;
    private final ConsultasDeVendas vendas;
    private final ConsultasDeContasAReceber consultas;

    ContasAReceberDaDonaController(ServicoDeContasAReceber servico, ConsultasDeVendas vendas, ConsultasDeContasAReceber consultas) {
        this.servico = servico;
        this.vendas = vendas;
        this.consultas = consultas;
    }

    @Operation(operationId = "listarContasAReceber", summary = "Contas a receber dos clientes",
            description = "Todas as parcelas das vendas a prazo, da que vence primeiro para a que vence depois, com o vendedor "
                    + "de cada venda. Filtros opcionais: vendedor, cliente e situação (ABERTA, PARCIAL, PAGA, CANCELADA, "
                    + "EM_ABERTO ou VENCIDA, calculada pela data de hoje em Brasília). Só valores em Real.")
    @GetMapping("/contas-receber")
    public List<ContaDaDona> listar(@RequestParam(required = false) UUID vendedorId, @RequestParam(required = false) UUID clienteId,
            @RequestParam(required = false) String situacao) {
        return consultas.daDona(vendedorId, situacao, clienteId);
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

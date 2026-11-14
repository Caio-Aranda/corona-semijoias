package br.com.coronasemijoias.api.contaspagar;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.contaspagar.ConsultasDeContasAPagar.Conta;
import br.com.coronasemijoias.api.contaspagar.ConsultasDeContasAPagar.Filtro;
import br.com.coronasemijoias.api.contaspagar.ServicoDeContasAPagar.PedidoDePagamento;
import br.com.coronasemijoias.api.contaspagar.ServicoDeContasAPagar.PedidoDeEstorno;
import br.com.coronasemijoias.api.contaspagar.ServicoDeContasAPagar.PedidoParaGerar;
import br.com.coronasemijoias.api.contaspagar.ServicoDeContasAPagar.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Contas a pagar das compras, lado da dona (tarefa 7.7; ADR-0012, itens 12 e 16). Só o perfil ADMIN: tudo que é dinheiro é da dona.
 * Os ids dos comandos vêm do aplicativo: 201 na primeira gravação, 200 no reenvio igual, 409 com outros dados. O aplicativo nunca
 * manda valor de parcela, saldo nem situação: o servidor calcula tudo (em Real).
 */
@RestController
@RequestMapping("/api/dona")
@ExigePerfil("ADMIN")
public class ContasAPagarDaDonaController {

    private final ServicoDeContasAPagar servico;
    private final ConsultasDeContasAPagar consultas;

    ContasAPagarDaDonaController(ServicoDeContasAPagar servico, ConsultasDeContasAPagar consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "gerarContasAPagarDaCompra", summary = "Gerar as contas a pagar de uma compra",
            description = "Divide o total da compra em parcelas (1 a 24), vencendo de mês em mês a partir do vencimento informado. As "
                    + "parcelas somam exatamente o total da compra. Só uma vez por compra: mesmo id e mesmo pedido devolve 200; outro "
                    + "pedido, 409.")
    @PostMapping("/compras/{compraId}/contas-a-pagar")
    public ResponseEntity<List<Conta>> gerar(@PathVariable UUID compraId, @RequestBody PedidoParaGerar pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.gerar(quem.id(), compraId, pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.daCompra(compraId));
    }

    @Operation(operationId = "listarContasAPagarDaCompra", summary = "Contas a pagar de uma compra",
            description = "As parcelas da compra, em ordem. Vazio quando as contas ainda não foram geradas.")
    @GetMapping("/compras/{compraId}/contas-a-pagar")
    public List<Conta> daCompra(@PathVariable UUID compraId) {
        return consultas.daCompra(compraId);
    }

    @Operation(operationId = "listarContasAPagar", summary = "Contas a pagar",
            description = "As parcelas a pagar, pelo vencimento. Filtros: fornecedor, situação (ABERTA, PARCIAL, PAGA, CANCELADA ou "
                    + "VENCIDA, calculada pela data de hoje) e vencimento de/até. A página começa em 0 (até 100 por página).")
    @GetMapping("/contas-a-pagar")
    public Pagina<Conta> listar(@RequestParam(required = false) UUID fornecedorId, @RequestParam(required = false) String situacao,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate vencimentoDe,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate vencimentoAte,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return consultas.listar(new Filtro(fornecedorId, situacao, vencimentoDe, vencimentoAte), pagina, tamanho);
    }

    @Operation(operationId = "verContaAPagar", summary = "Ver uma conta a pagar",
            description = "A parcela com as baixas (pagamentos, estornos e cancelamento).")
    @GetMapping("/contas-a-pagar/{id}")
    public Conta ver(@PathVariable UUID id) {
        return consultas.ver(id);
    }

    @Operation(operationId = "pagarContaAPagar", summary = "Registrar um pagamento a fornecedor",
            description = "Paga a parcela (inteira ou parte) em dinheiro, Transferência (PIX) ou boleto. Não aceita mais do que falta "
                    + "pagar. A data é opcional (padrão hoje; nunca no futuro nem numa semana já fechada). Sai do caixa na mesma hora. "
                    + "Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping("/contas-a-pagar/{id}/pagamentos")
    public ResponseEntity<Conta> pagar(@PathVariable UUID id, @RequestBody PedidoDePagamento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.pagar(quem.id(), id, pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(id));
    }

    @Operation(operationId = "estornarPagamentoAFornecedor", summary = "Estornar um pagamento a fornecedor",
            description = "Desfaz o pagamento, sem limite de dia, com motivo. O dinheiro volta ao caixa (com a data de hoje) e a conta "
                    + "volta a ter saldo. Mesmo id de estorno devolve 200; outro id para o mesmo pagamento, 409.")
    @PostMapping("/contas-a-pagar/pagamentos/{baixaId}/estorno")
    public ResponseEntity<Conta> estornar(@PathVariable UUID baixaId, @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.estornarPagamento(quem.id(), baixaId, pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(consultas.contaDaBaixa(r.id())));
    }
}

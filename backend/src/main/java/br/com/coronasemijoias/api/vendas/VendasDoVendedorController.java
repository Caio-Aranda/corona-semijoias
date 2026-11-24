package br.com.coronasemijoias.api.vendas;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.cadastros.ServicoDeCadastro;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendas.ConsultasDeVendas.Venda;
import br.com.coronasemijoias.api.contasreceber.ServicoDeContasAReceber;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.Resultado;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
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

/**
 * Vendas do vendedor (tarefa 4.2; ADR-0011). Só o perfil VENDEDOR e só as vendas dele: o vendedor vem do token, nunca da
 * requisição. O app manda produto, quantidade, desconto e pagamentos; preço e totais são do servidor. 201 na primeira
 * gravação, 200 no reenvio igual, 409 com o mesmo id e dados diferentes.
 */
@RestController
@RequestMapping("/api/vendedor/vendas")
@ExigePerfil("VENDEDOR")
public class VendasDoVendedorController {

    public record PedidoDaLinha(UUID produtoId, Integer quantidade, BigDecimal desconto) {
    }

    /** {@code creditoId}: o crédito do cliente, obrigatório na forma {@code CREDITO} (tarefa 6.5). */
    public record PedidoDoPagamento(UUID id, BigDecimal valor, String forma, UUID creditoId) {
    }

    /** Quantas parcelas e quando vence a primeira (as outras são mensais); os valores são do servidor (tarefa 7.3). */
    public record PedidoDoParcelamento(Integer quantidade, LocalDate primeiroVencimento) {
    }

    public record PedidoDaVenda(UUID id, UUID remessaId, UUID clienteId, List<PedidoDaLinha> itens,
            List<PedidoDoPagamento> pagamentos, BigDecimal totalConferido, PedidoDoParcelamento parcelamento) {
    }

    public record PedidoDeEstorno(UUID id, String motivo) {
    }

    private final ServicoDeVendas servico;
    private final ConsultasDeVendas consultas;

    VendasDoVendedorController(ServicoDeVendas servico, ConsultasDeVendas consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "venderNaMaleta", summary = "Registrar uma venda da maleta",
            description = "Baixa as peças da maleta do vendedor pelo preço congelado da remessa. O servidor recalcula subtotal, "
                    + "desconto e total; o desconto respeita o limite da dona. Pagamentos opcionais (venda a prazo grava só o "
                    + "que foi pago na hora). A forma CREDITO usa um crédito do cliente da venda (creditoId), do próprio "
                    + "vendedor, até o saldo. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<Venda> vender(@RequestBody PedidoDaVenda pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("venda", "Informe a venda.");
        }
        PedidoDeVenda p = new PedidoDeVenda(pedido.id(), pedido.remessaId(), pedido.clienteId(),
                pedido.itens() == null ? null : pedido.itens().stream()
                        .map(l -> l == null ? null : new LinhaDoPedido(l.produtoId(), l.quantidade(), l.desconto())).toList(),
                pedido.pagamentos() == null ? null : pedido.pagamentos().stream()
                        .map(g -> g == null ? null : new PagamentoDoPedido(g.id(), g.valor(), g.forma(), g.creditoId())).toList(),
                pedido.totalConferido(), pedido.parcelamento() == null ? null : new ServicoDeContasAReceber.Parcelamento(
                        pedido.parcelamento().quantidade(), pedido.parcelamento().primeiroVencimento()));
        Resultado r = servico.vender(quem.vendedorId(), quem.id(), quem.dispositivoId(), p);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.ver(r.vendaId(), quem.vendedorId()));
    }

    @Operation(operationId = "estornarVendaDoVendedor", summary = "Estornar a própria venda (no mesmo dia)",
            description = "Estorna a venda inteira: as peças voltam para a maleta e o que o cliente pagou vira reembolso. Só no "
                    + "dia da venda; depois, só a dona. Exige o id do estorno (do aplicativo) e o motivo. Mesmo id devolve 200.")
    @PostMapping("/{id}/estorno")
    public ResponseEntity<Venda> estornar(@PathVariable UUID id, @RequestBody PedidoDeEstorno pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar a venda.");
        }
        Resultado r = servico.estornar(id, quem.vendedorId(), quem.id(), quem.dispositivoId(), pedido.id(), pedido.motivo());
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.ver(id, quem.vendedorId()));
    }

    @Operation(operationId = "listarVendasDoVendedor", summary = "Minhas vendas",
            description = "Só as vendas do vendedor logado, da mais nova para a mais antiga, por página (issue 270). Filtros "
                    + "opcionais por dia (de e até, no horário de Brasília), cliente e situação (CONCLUIDA ou ESTORNADA); "
                    + "os filtros valem antes da página e o total conta só o que passou neles. A página começa em 0 "
                    + "(padrão 20, até 100 por página).")
    @GetMapping
    public Pagina<Venda> listar(@RequestParam(required = false) LocalDate de, @RequestParam(required = false) LocalDate ate,
            @RequestParam(required = false) UUID clienteId, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (status != null && !List.of("CONCLUIDA", "ESTORNADA").contains(status)) {
            throw Validacoes.recusar("status", "Escolha concluída ou estornada.");
        }
        int p = pagina == null ? 0 : pagina;
        int t = tamanho == null ? ServicoDeCadastro.TAMANHO_PADRAO : tamanho;
        if (p < 0) {
            throw Validacoes.recusar("pagina", "A página não pode ser negativa.");
        }
        if (t < 1 || t > ServicoDeCadastro.TAMANHO_MAXIMO) {
            throw Validacoes.recusar("tamanho", "Escolha de 1 a " + ServicoDeCadastro.TAMANHO_MAXIMO + " itens por página.");
        }
        return consultas.listar(quem.vendedorId(), de, ate, clienteId, status, p, t);
    }

    @Operation(operationId = "verVendaDoVendedor", summary = "Ver uma das minhas vendas")
    @GetMapping("/{id}")
    public Venda ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.ver(id, quem.vendedorId());
    }
}

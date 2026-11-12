package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.devolucoes.ConsultasDeDevolucoes.Devolucao;
import br.com.coronasemijoias.api.devolucoes.ConsultasDeDevolucoes.VendasComAPeca;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.Resultado;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
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
 * Devolução do cliente pelo vendedor (tarefa 6.3; ADR-0014). Só o perfil VENDEDOR e só as vendas e devoluções dele: o
 * vendedor vem do token, nunca da requisição. O app manda a venda, as peças (produto, quantidade e estado), o motivo e a
 * forma do reembolso; prazo, valor e destino da peça são do servidor. 201 na primeira gravação, 200 no reenvio igual,
 * 409 com o mesmo id e dados diferentes.
 */
@RestController
@RequestMapping("/api/vendedor/devolucoes")
@ExigePerfil("VENDEDOR")
public class DevolucoesDoVendedorController {

    public record PecaDevolvida(UUID produtoId, Integer quantidade, String estado) {
    }

    public record PedidoDaDevolucao(UUID id, UUID vendaId, String motivo, String observacao, String formaReembolso,
            List<PecaDevolvida> itens, BigDecimal valorConferido) {
    }

    public record PedidoDeCancelamento(UUID id, String motivo) {
    }

    private final ServicoDeDevolucoes servico;
    private final ConsultasDeDevolucoes consultas;

    DevolucoesDoVendedorController(ServicoDeDevolucoes servico, ConsultasDeDevolucoes consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "devolverDoCliente", summary = "Registrar a devolução de um cliente",
            description = "Devolução de peças de uma venda à vista do vendedor, dentro da garantia. A venda não muda; o servidor "
                    + "calcula o valor (o preço pago), o reembolso na forma escolhida e para onde vai cada peça (boa de maleta "
                    + "aberta volta à maleta; com defeito ou de maleta acertada, peça para a loja). Fora do prazo, venda a prazo "
                    + "ou acima do vendido: 409. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<Devolucao> devolver(@RequestBody PedidoDaDevolucao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("devolucao", "Informe a devolução.");
        }
        PedidoDaDevolucaoDoCliente p = new PedidoDaDevolucaoDoCliente(pedido.id(), pedido.vendaId(), pedido.motivo(), pedido.observacao(),
                pedido.formaReembolso(), pedido.itens() == null ? null : pedido.itens().stream()
                        .map(i -> i == null ? null : new PecaDoPedido(i.produtoId(), i.quantidade(), i.estado())).toList(),
                pedido.valorConferido());
        Resultado r = servico.devolver(quem.vendedorId(), quem.id(), quem.dispositivoId(), p, null, false, ModoDaDevolucao.ONLINE);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.ver(r.devolucaoId(), quem.vendedorId()));
    }

    @Operation(operationId = "listarDevolucoesDoVendedor", summary = "Minhas devoluções",
            description = "Só as devoluções do vendedor logado, da mais nova para a mais antiga. Filtros opcionais por dia (de e "
                    + "até, no horário de Brasília) e por venda.")
    @GetMapping
    public List<Devolucao> listar(@RequestParam(required = false) LocalDate de, @RequestParam(required = false) LocalDate ate,
            @RequestParam(required = false) UUID vendaId, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.listar(quem.vendedorId(), de, ate, vendaId);
    }

    @Operation(operationId = "vendasComAPeca", summary = "Vendas com a peça lida",
            description = "Pelo código lido (sem diferenciar maiúsculas, sem espaços nas pontas) ou pelo produto: as vendas "
                    + "concluídas do vendedor com a peça, da mais nova para a mais antiga (no máximo 20), com o que ainda pode "
                    + "voltar de cada peça, até que dia vale a garantia e se a venda é a prazo.")
    @GetMapping("/vendas-com-a-peca")
    public VendasComAPeca vendasComAPeca(@RequestParam(required = false) String codigo,
            @RequestParam(required = false) UUID produtoId, @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (produtoId == null && Validacoes.limpar(codigo) == null) {
            throw Validacoes.recusar("codigo", "Leia o código da peça ou escolha a peça.");
        }
        return consultas.vendasComAPeca(quem.vendedorId(), codigo, produtoId);
    }

    @Operation(operationId = "verDevolucaoDoVendedor", summary = "Ver uma das minhas devoluções")
    @GetMapping("/{id}")
    public Devolucao ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.ver(id, quem.vendedorId());
    }

    @Operation(operationId = "cancelarDevolucaoDoVendedor", summary = "Cancelar a própria devolução (no mesmo dia)",
            description = "Desfaz a devolução com registros de sinal oposto: a peça sai de novo da maleta e o reembolso é "
                    + "estornado. Só no dia da devolução; depois, só a dona. Exige o id do cancelamento (do aplicativo) e o "
                    + "motivo. Mesmo id devolve 200.")
    @PostMapping("/{id}/cancelar")
    public ResponseEntity<Devolucao> cancelar(@PathVariable UUID id, @RequestBody PedidoDeCancelamento pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de cancelar a devolução.");
        }
        Resultado r = servico.cancelar(id, quem.vendedorId(), quem.id(), quem.dispositivoId(), pedido.id(), pedido.motivo(), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.ver(id, quem.vendedorId()));
    }
}

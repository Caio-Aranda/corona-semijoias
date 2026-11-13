package br.com.coronasemijoias.api.trocas;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.trocas.ConsultasDeTrocas.TrocaLida;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.DevolucaoDaTroca;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.PedidoDeTroca;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.Resultado;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.VendaDaTroca;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
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
 * Troca pelo vendedor (tarefa 6.5; ADR-0014, itens 8 e 11). Só o perfil VENDEDOR e só as vendas e trocas dele: o
 * vendedor vem do token, nunca da requisição. 201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e dados
 * diferentes.
 */
@RestController
@RequestMapping("/api/vendedor/trocas")
@ExigePerfil("VENDEDOR")
public class TrocasDoVendedorController {

    public record PecaQueVoltou(UUID produtoId, Integer quantidade, String estado) {
    }

    public record PecaQueSaiu(UUID produtoId, Integer quantidade, BigDecimal desconto) {
    }

    public record PagamentoDaDiferenca(UUID id, BigDecimal valor, String forma, UUID creditoId) {
    }

    public record DevolucaoDaTrocaPedida(UUID id, UUID vendaId, String motivo, String observacao, List<PecaQueVoltou> itens,
            UUID autorizacaoId) {
    }

    public record VendaDaTrocaPedida(UUID id, UUID remessaId, List<PecaQueSaiu> itens, List<PagamentoDaDiferenca> pagamentos,
            BigDecimal totalConferido) {
    }

    /** Os ids vêm do aplicativo: a troca, o crédito, o pagamento em crédito, a devolução e a venda nova. Nunca valores. */
    public record PedidoDaTroca(UUID id, UUID creditoId, UUID pagamentoDoCreditoId, DevolucaoDaTrocaPedida devolucao,
            VendaDaTrocaPedida venda) {
    }

    public record PedidoDeCancelamentoDaTroca(UUID id, String motivo) {
    }

    private final ServicoDeTrocas servico;
    private final ConsultasDeTrocas consultas;

    TrocasDoVendedorController(ServicoDeTrocas servico, ConsultasDeTrocas consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "registrarTroca", summary = "Registrar uma troca",
            description = "O cliente devolve peças de uma venda (A) e leva outras (B), numa operação só: a devolução de A e a "
                    + "venda nova de B. O valor de A vira crédito do cliente, que paga B na hora; o resto de B vem nos "
                    + "pagamentos (à vista, a prazo ou com outro crédito do cliente), ou, se B vale menos, a sobra fica de "
                    + "crédito. O servidor calcula tudo: o app nunca manda valor de A, diferença nem crédito. Prazo e "
                    + "autorização como na devolução; fora do limite da semana e do valor de aprovação. Qualquer recusa "
                    + "desfaz tudo. Mesmo id e mesmo conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<TrocaLida> trocar(@RequestBody PedidoDaTroca pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.trocar(quem.vendedorId(), quem.id(), quem.dispositivoId(), paraOServico(pedido), null, false,
                ModoDaDevolucao.ONLINE);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.ver(r.trocaId(), quem.vendedorId()));
    }

    @Operation(operationId = "listarTrocasDoVendedor", summary = "Minhas trocas",
            description = "Só as trocas do vendedor logado, da mais nova para a mais antiga. Filtros opcionais por dia (de e "
                    + "até, no horário de Brasília) e situação (ATIVA ou CANCELADA).")
    @GetMapping
    public List<TrocaLida> listar(@RequestParam(required = false) LocalDate de, @RequestParam(required = false) LocalDate ate,
            @RequestParam(required = false) String situacao, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.listar(quem.vendedorId(), de, ate, situacao);
    }

    @Operation(operationId = "verTrocaDoVendedor", summary = "Ver uma das minhas trocas")
    @GetMapping("/{id}")
    public TrocaLida ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.ver(id, quem.vendedorId());
    }

    @Operation(operationId = "cancelarTrocaDoVendedor", summary = "Cancelar a própria troca (no mesmo dia)",
            description = "Desfaz a troca com registros de sinal oposto: B volta à maleta, A sai de novo, o crédito é "
                    + "cancelado e o que foi pago da diferença volta. Só no dia da troca; depois, só a dona. Recusado se o "
                    + "crédito que sobrou já foi usado ou se a peça A já foi vendida de novo. Mesmo id devolve 200.")
    @PostMapping("/{id}/cancelar")
    public ResponseEntity<TrocaLida> cancelar(@PathVariable UUID id, @RequestBody PedidoDeCancelamentoDaTroca pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de cancelar a troca.");
        }
        Resultado r = servico.cancelar(id, quem.vendedorId(), quem.id(), quem.dispositivoId(), pedido.id(), pedido.motivo(), null);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(consultas.ver(id, quem.vendedorId()));
    }

    static PedidoDeTroca paraOServico(PedidoDaTroca pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("troca", "Informe a troca.");
        }
        DevolucaoDaTrocaPedida d = pedido.devolucao();
        VendaDaTrocaPedida v = pedido.venda();
        DevolucaoDaTroca devolucao = d == null ? null : new DevolucaoDaTroca(d.id(), d.vendaId(), d.motivo(), d.observacao(),
                d.itens() == null ? null : d.itens().stream()
                        .map(i -> i == null ? null : new PecaDoPedido(i.produtoId(), i.quantidade(), i.estado())).toList(),
                d.autorizacaoId());
        VendaDaTroca venda = v == null ? null : new VendaDaTroca(v.id(), v.remessaId(),
                v.itens() == null ? null : v.itens().stream()
                        .map(l -> l == null ? null : new LinhaDoPedido(l.produtoId(), l.quantidade(), l.desconto())).toList(),
                v.pagamentos() == null ? null : v.pagamentos().stream()
                        .map(g -> g == null ? null : new PagamentoDoPedido(g.id(), g.valor(), g.forma(), g.creditoId())).toList(),
                v.totalConferido());
        return new PedidoDeTroca(pedido.id(), pedido.creditoId(), pedido.pagamentoDoCreditoId(), devolucao, venda);
    }
}

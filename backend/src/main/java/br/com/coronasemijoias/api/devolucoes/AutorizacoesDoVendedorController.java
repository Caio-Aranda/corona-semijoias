package br.com.coronasemijoias.api.devolucoes;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.devolucoes.ConsultasDeAutorizacoes.Autorizacao;
import br.com.coronasemijoias.api.devolucoes.DevolucoesDoVendedorController.PecaDevolvida;
import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes.PedidoDeAutorizacao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes.Resultado;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
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

/**
 * Pedido de autorização à dona pelo vendedor (tarefa 6.4; ADR-0014, item 6). Só o perfil VENDEDOR e só as vendas e os
 * pedidos dele: o vendedor vem do token. O app manda a venda, o motivo, a observação e as peças; tipo e valor são do
 * servidor. 201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e dados diferentes.
 */
@RestController
@RequestMapping("/api/vendedor/autorizacoes")
@ExigePerfil("VENDEDOR")
public class AutorizacoesDoVendedorController {

    public record PedidoDaAutorizacao(UUID id, UUID vendaId, String motivo, String observacao, List<PecaDevolvida> itens) {
    }

    private final ServicoDeAutorizacoes servico;
    private final ConsultasDeAutorizacoes consultas;

    AutorizacoesDoVendedorController(ServicoDeAutorizacoes servico, ConsultasDeAutorizacoes consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(operationId = "pedirAutorizacao", summary = "Pedir autorização à dona para uma devolução",
            description = "Para a devolução fora do prazo da garantia ou acima do valor que a dona aprova antes. O servidor decide "
                    + "o tipo e calcula o valor pedido (o preço pago). No prazo e sem passar do valor: 409 (registre a devolução "
                    + "direto). Um pedido aberto por venda. Mesmo id e mesmo conteúdo devolve 200; com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<Autorizacao> pedir(@RequestBody PedidoDaAutorizacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("autorizacao", "Informe o pedido de autorização.");
        }
        PedidoDeAutorizacao p = new PedidoDeAutorizacao(pedido.id(), pedido.vendaId(), pedido.motivo(), pedido.observacao(),
                pedido.itens() == null ? null : pedido.itens().stream()
                        .map(i -> i == null ? null : new PecaDoPedido(i.produtoId(), i.quantidade(), i.estado())).toList());
        Resultado r = servico.pedir(quem.vendedorId(), quem.id(), quem.dispositivoId(), p, null, false, ModoDaDevolucao.ONLINE);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(consultas.ver(r.autorizacaoId(), quem.vendedorId()));
    }

    @Operation(operationId = "listarAutorizacoesDoVendedor", summary = "Meus pedidos de autorização",
            description = "Só os pedidos do vendedor logado, do mais novo para o mais antigo. Filtro opcional por venda.")
    @GetMapping
    public List<Autorizacao> listar(@RequestParam(required = false) UUID vendaId,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.listarDoVendedor(quem.vendedorId(), vendaId);
    }

    @Operation(operationId = "verAutorizacaoDoVendedor", summary = "Ver um dos meus pedidos de autorização")
    @GetMapping("/{id}")
    public Autorizacao ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.ver(id, quem.vendedorId());
    }
}

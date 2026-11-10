package br.com.coronasemijoias.api.compras;

import br.com.coronasemijoias.api.cadastros.Pagina;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.Detalhe;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.Filtro;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.HistoricoDeCusto;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.Item;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.LinhaDeCusto;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.Produto;
import br.com.coronasemijoias.api.compras.ConsultasDeCompras.Resumo;
import br.com.coronasemijoias.api.compras.ServicoDeCompras.ItemPedido;
import br.com.coronasemijoias.api.compras.ServicoDeCompras.PedidoDeCompra;
import br.com.coronasemijoias.api.compras.ServicoDeCompras.Resultado;
import br.com.coronasemijoias.api.fotos.FotosDosProdutosController.FotoPrincipal;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Compras da dona (tarefa 2.3). Só ADMIN: o custo nunca sai daqui para o vendedor. O servidor recalcula totais e
 * custos; toda peça mostrada traz a foto principal (vazia se não há foto).
 */
@RestController
@RequestMapping("/api/dona/compras")
@ExigePerfil("ADMIN")
public class ComprasDaDonaController {

    public record PedidoDeItem(UUID produtoId, String codigo, Integer quantidade, BigDecimal custoUnitario) {
    }

    public record PedidoDaCompra(UUID id, UUID fornecedorId, LocalDate dataCompra, String numero, String observacoes,
            List<PedidoDeItem> itens) {
    }

    public record PedidoParaDesfazer(UUID id, String motivo) {
    }

    public record RespostaDaPeca(UUID id, String nome, String referencia, String codigo, FotoPrincipal fotoPrincipal) {

        static RespostaDaPeca de(Produto p) {
            return new RespostaDaPeca(p.id(), p.nome(), p.referencia(), p.codigo(), FotoPrincipal.de(p.fotoPrincipalHash()));
        }
    }

    public record RespostaDoItem(UUID id, RespostaDaPeca produto, int quantidade, BigDecimal custoUnitario, BigDecimal total) {

        static RespostaDoItem de(Item i) {
            return new RespostaDoItem(i.id(), RespostaDaPeca.de(i.produto()), i.quantidade(), i.custoUnitario(), i.total());
        }
    }

    public record RespostaDoResumo(UUID id, UUID fornecedorId, String fornecedorNome, LocalDate dataCompra, String numero,
            BigDecimal total, String situacao, int itens, int pecas, Instant registradaEm) {

        static RespostaDoResumo de(Resumo r) {
            return new RespostaDoResumo(r.id(), r.fornecedorId(), r.fornecedorNome(), r.dataCompra(), r.numero(), r.total(),
                    r.situacao(), r.itens(), r.pecas(), r.registradaEm());
        }
    }

    public record RespostaDaCompra(RespostaDoResumo compra, String observacoes, UUID usuarioId, String usuarioNome,
            String motivoDeDesfeita, Instant desfeitaEm, List<RespostaDoItem> itens) {

        static RespostaDaCompra de(Detalhe d) {
            return new RespostaDaCompra(RespostaDoResumo.de(d.resumo()), d.observacoes(), d.usuarioId(), d.usuarioNome(),
                    d.estornoMotivo(), d.estornadaEm(), d.itens().stream().map(RespostaDoItem::de).toList());
        }
    }

    public record RespostaDaLinhaDeCusto(UUID compraId, LocalDate dataCompra, String fornecedorNome, String situacaoDaCompra,
            BigDecimal custo, BigDecimal custoAnterior, int quantidade) {

        static RespostaDaLinhaDeCusto de(LinhaDeCusto l) {
            return new RespostaDaLinhaDeCusto(l.compraId(), l.dataCompra(), l.fornecedorNome(), l.situacaoDaCompra(),
                    l.custo(), l.custoAnterior(), l.quantidade());
        }
    }

    public record RespostaDoHistoricoDeCusto(RespostaDaPeca produto, BigDecimal custoAtual, Pagina<RespostaDaLinhaDeCusto> historico) {
    }

    private final ServicoDeCompras servico;
    private final ConsultasDeCompras consultas;

    public ComprasDaDonaController(ServicoDeCompras servico, ConsultasDeCompras consultas) {
        this.servico = servico;
        this.consultas = consultas;
    }

    @Operation(summary = "Receber uma compra",
            description = "Vários itens, cada um por produtoId OU por codigo (texto do código da etiqueta, sem diferenciar "
                    + "maiúsculas). Uma transação só: grava a compra, soma as peças no estoque e atualiza o custo. O servidor "
                    + "recalcula os totais (custo com 2 casas, arredondado para cima na metade). Mesma peça duas vezes na "
                    + "mesma compra é recusada. Código que não existe volta 400 com o erro de cada linha e nada é gravado. "
                    + "O id (UUID) torna o reenvio seguro: mesmo id e mesmos dados devolve a compra (200) sem somar de novo; "
                    + "outros dados, 409.")
    @PostMapping
    public ResponseEntity<RespostaDaCompra> receber(@RequestBody PedidoDaCompra pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        List<ItemPedido> itens = pedido.itens() == null ? null : pedido.itens().stream()
                .map(i -> i == null ? null : new ItemPedido(i.produtoId(), i.codigo(), i.quantidade(), i.custoUnitario())).toList();
        Resultado r = servico.registrar(new PedidoDeCompra(pedido.id(), pedido.fornecedorId(), pedido.dataCompra(),
                pedido.numero(), pedido.observacoes(), itens), quem.id());
        return resposta(r);
    }

    @Operation(summary = "Desfazer uma compra",
            description = "Desfaz as entradas no estoque e marca a compra como desfeita (ela continua no histórico). Exige o "
                    + "motivo. Recusa (409) se já saíram peças e o estoque não cobre, ou se a compra já foi desfeita. "
                    + "O custo do produto só volta se esta era a compra mais recente dele.")
    @PostMapping("/{id}/desfazer")
    public ResponseEntity<RespostaDaCompra> desfazer(@PathVariable UUID id, @RequestBody PedidoParaDesfazer pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return resposta(servico.desfazer(id, pedido.id(), pedido.motivo(), quem.id()));
    }

    @Operation(summary = "Ver uma compra", description = "Com os itens, as fotos das peças, os custos e o total.")
    @GetMapping("/{id}")
    public RespostaDaCompra ver(@PathVariable UUID id) {
        return RespostaDaCompra.de(consultas.ver(id));
    }

    @Operation(summary = "Lista de compras",
            description = "Filtros: período (de, ate: data da compra, as duas inclusivas), fornecedorId, produtoId e situacao "
                    + "(REGISTRADA ou ESTORNADA). Mais nova primeiro. A página começa em 0 (até 200 por página).")
    @GetMapping
    public Pagina<RespostaDoResumo> listar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(required = false) UUID fornecedorId, @RequestParam(required = false) UUID produtoId,
            @RequestParam(required = false) String situacao, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(consultas.listar(new Filtro(de, ate, fornecedorId, produtoId, situacao), pagina, tamanho),
                RespostaDoResumo::de);
    }

    @Operation(summary = "Histórico de custo do produto", description = "Custo atual e cada custo que uma compra deu à peça.")
    @GetMapping("/produtos/{produtoId}/custos")
    public RespostaDoHistoricoDeCusto custos(@PathVariable UUID produtoId, @RequestParam(required = false) Integer pagina,
            @RequestParam(required = false) Integer tamanho) {
        HistoricoDeCusto h = consultas.custos(produtoId, pagina, tamanho);
        return new RespostaDoHistoricoDeCusto(RespostaDaPeca.de(h.produto()), h.custoAtual(),
                Pagina.de(h.linhas(), RespostaDaLinhaDeCusto::de));
    }

    private ResponseEntity<RespostaDaCompra> resposta(Resultado r) {
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(RespostaDaCompra.de(consultas.ver(r.compraId())));
    }
}

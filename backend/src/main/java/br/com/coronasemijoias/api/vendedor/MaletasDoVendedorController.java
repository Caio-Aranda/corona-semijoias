package br.com.coronasemijoias.api.vendedor;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.vendedor.ConsultasDaMaletaDoVendedor.Linha;
import br.com.coronasemijoias.api.vendedor.ConsultasDaMaletaDoVendedor.Maleta;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia.LinhaContada;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia.Resultado;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A maleta do vendedor (tarefa 4.3; ADR-0011, itens 11 e 12). Só VENDEDOR e só as maletas dele: o vendedor vem do
 * token. Nunca traz custo, estoque central nem dados de outros vendedores.
 */
@RestController
@RequestMapping("/api/vendedor/maletas")
@ExigePerfil("VENDEDOR")
public class MaletasDoVendedorController {

    public record PedidoDaContagem(UUID produtoId, Integer contada) {
    }

    public record PedidoDeConferencia(UUID id, List<PedidoDaContagem> itens) {
    }

    public record LinhaDaConferencia(UUID produtoId, String produtoNome, int esperado, int contado, int diferenca) {
    }

    public record RespostaDaConferencia(UUID id, UUID remessaId, Instant conferidaEm, int divergencias,
            List<LinhaDaConferencia> itens) {
    }

    private final ConsultasDaMaletaDoVendedor consultas;
    private final ServicoDeConferencia conferencia;
    private final JdbcTemplate jdbc;

    MaletasDoVendedorController(ConsultasDaMaletaDoVendedor consultas, ServicoDeConferencia conferencia, JdbcTemplate jdbc) {
        this.consultas = consultas;
        this.conferencia = conferencia;
        this.jdbc = jdbc;
    }

    @Operation(operationId = "listarMinhasMaletas", summary = "Minhas maletas",
            description = "As maletas que estão com o vendedor logado (enviadas ou conferidas): peças, vendida, devolvida, saldo, "
                    + "preço de venda e foto.")
    @GetMapping
    public List<Maleta> listar(@AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.listar(quem.vendedorId());
    }

    @Operation(operationId = "verMinhaMaleta", summary = "Ver uma das minhas maletas")
    @GetMapping("/{id}")
    public Maleta ver(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.ver(id, quem.vendedorId());
    }

    @Operation(operationId = "acharPecaDaMinhaMaleta", summary = "Ler uma peça pelo código",
            description = "O leitor manda o código; só responde peça que está na maleta (404 com \"Esta peça não consta na sua "
                    + "maleta\" nas outras). Devolve a linha com a foto e o saldo.")
    @GetMapping("/{id}/pecas")
    public Linha peca(@PathVariable UUID id, @RequestParam String codigo, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return consultas.peca(id, quem.vendedorId(), codigo);
    }

    @Operation(operationId = "conferirMinhaMaleta", summary = "Conferir a maleta",
            description = "O vendedor manda quantas peças contou de cada produto (o que faltar na lista conta como zero). O servidor "
                    + "compara com o saldo da maleta, grava a diferença e liga a maleta a este aparelho. Uma conferência por maleta: "
                    + "mesmo id e mesma contagem devolve 200; outra tentativa, 409.")
    @PostMapping("/{id}/conferencia")
    public ResponseEntity<RespostaDaConferencia> conferir(@PathVariable UUID id, @RequestBody PedidoDeConferencia pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        if (pedido == null) {
            throw Validacoes.recusar("itens", "Informe as peças contadas.");
        }
        List<LinhaContada> linhas = pedido.itens() == null ? null : pedido.itens().stream()
                .map(l -> l == null ? null : new LinhaContada(l.produtoId(), l.contada())).toList();
        Resultado r = conferencia.conferir(quem.vendedorId(), quem.id(), quem.dispositivoId(), id, pedido.id(), linhas);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(resposta(r.conferenciaId()));
    }

    @Operation(operationId = "verConferenciaDaMinhaMaleta", summary = "Ver a conferência da maleta")
    @GetMapping("/{id}/conferencia")
    public RespostaDaConferencia verConferencia(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        List<UUID> ids = jdbc.query("SELECT id FROM corona.conferencias WHERE remessa_id = ? AND vendedor_id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), id, quem.vendedorId());
        if (ids.isEmpty()) {
            throw new br.com.coronasemijoias.api.comum.erros.ErroDaApi(HttpStatus.NOT_FOUND,
                    br.com.coronasemijoias.api.comum.erros.CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        return resposta(ids.get(0));
    }

    private RespostaDaConferencia resposta(UUID conferenciaId) {
        var cabeca = jdbc.queryForMap("SELECT remessa_id, divergencias, conferida_em FROM corona.conferencias WHERE id = ?",
                conferenciaId);
        List<LinhaDaConferencia> itens = jdbc.query("""
                SELECT i.produto_id, p.nome, i.esperado, i.contado FROM corona.itens_conferencia i
                  JOIN corona.produtos p ON p.id = i.produto_id WHERE i.conferencia_id = ? ORDER BY p.nome, p.id""",
                (rs, n) -> new LinhaDaConferencia(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(4) - rs.getInt(3)), conferenciaId);
        return new RespostaDaConferencia(conferenciaId, (UUID) cabeca.get("remessa_id"),
                ((java.sql.Timestamp) cabeca.get("conferida_em")).toInstant(), ((Number) cabeca.get("divergencias")).intValue(),
                itens);
    }
}

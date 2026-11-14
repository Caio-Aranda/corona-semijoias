package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.caixa.ServicoDeCategoriasDoCaixa.PedidoDeCategoria;
import br.com.coronasemijoias.api.caixa.ServicoDeCategoriasDoCaixa.PedidoDeNome;
import br.com.coronasemijoias.api.caixa.ServicoDeCategoriasDoCaixa.Resultado;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Categorias do caixa, lado da dona (tarefa 7.7; ADR-0012, item 11). Só o perfil ADMIN. Sem rota de exclusão: só inativar. */
@RestController
@RequestMapping("/api/dona/caixa/categorias")
@ExigePerfil("ADMIN")
public class CategoriasDoCaixaDaDonaController {

    /** Uma categoria do caixa; {@code sistema} marca as que os lançamentos automáticos usam (não mudam). */
    public record Categoria(UUID id, String nome, String tipo, boolean ativa, boolean sistema) {
    }

    private final ServicoDeCategoriasDoCaixa servico;
    private final JdbcTemplate jdbc;

    CategoriasDoCaixaDaDonaController(ServicoDeCategoriasDoCaixa servico, JdbcTemplate jdbc) {
        this.servico = servico;
        this.jdbc = jdbc;
    }

    @Operation(operationId = "listarCategoriasDoCaixa", summary = "Categorias do caixa",
            description = "Todas as categorias, as do sistema primeiro e depois por nome, com a situação (ativa ou inativa).")
    @GetMapping
    public List<Categoria> listar() {
        return jdbc.query("""
                SELECT id, nome, tipo, ativo, sistema FROM corona.categorias_caixa WHERE deleted_at IS NULL
                 ORDER BY sistema DESC, lower(nome)""", (rs, n) -> new Categoria(rs.getObject("id", UUID.class), rs.getString("nome"),
                rs.getString("tipo"), rs.getBoolean("ativo"), rs.getBoolean("sistema")));
    }

    @Operation(operationId = "criarCategoriaDoCaixa", summary = "Criar uma categoria do caixa",
            description = "Nome único (sem diferenciar maiúsculas) e tipo ENTRADA ou SAIDA, que não muda depois. Mesmo id e mesmo "
                    + "conteúdo devolve 200; mesmo id com dados diferentes, 409.")
    @PostMapping
    public ResponseEntity<Categoria> criar(@RequestBody PedidoDeCategoria pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        Resultado r = servico.criar(quem.id(), pedido);
        return ResponseEntity.status(r.jaExistia() ? HttpStatus.OK : HttpStatus.CREATED).body(ver(r.id()));
    }

    @Operation(operationId = "renomearCategoriaDoCaixa", summary = "Renomear uma categoria do caixa",
            description = "Só o nome muda. Categoria do sistema não muda.")
    @PutMapping("/{id}")
    public Categoria renomear(@PathVariable UUID id, @RequestBody PedidoDeNome pedido, @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.renomear(quem.id(), id, pedido);
        return ver(id);
    }

    @Operation(operationId = "inativarCategoriaDoCaixa", summary = "Inativar uma categoria do caixa",
            description = "A categoria inativa não recebe lançamento novo; os antigos continuam. Categoria do sistema não muda.")
    @PostMapping("/{id}/inativar")
    public Categoria inativar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.inativar(quem.id(), id);
        return ver(id);
    }

    @Operation(operationId = "reativarCategoriaDoCaixa", summary = "Reativar uma categoria do caixa",
            description = "A categoria volta a receber lançamentos.")
    @PostMapping("/{id}/reativar")
    public Categoria reativar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        servico.reativar(quem.id(), id);
        return ver(id);
    }

    private Categoria ver(UUID id) {
        List<Categoria> achada = jdbc.query("SELECT id, nome, tipo, ativo, sistema FROM corona.categorias_caixa WHERE id = ?",
                (rs, n) -> new Categoria(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("tipo"), rs.getBoolean("ativo"),
                        rs.getBoolean("sistema")), id);
        if (achada.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CATEGORIA_DO_CAIXA_NAO_ENCONTRADA);
        }
        return achada.get(0);
    }
}

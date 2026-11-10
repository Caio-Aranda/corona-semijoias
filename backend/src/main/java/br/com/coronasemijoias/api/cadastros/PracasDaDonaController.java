package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Praças (regiões ou rotas dos vendedores): cadastro da dona (tarefa 1.2). */
@RestController
@RequestMapping("/api/dona/pracas")
@ExigePerfil("ADMIN")
public class PracasDaDonaController {

    /** {@code version} (a que a tela leu) só vale ao editar. */
    public record Pedido(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 100, message = Mensagens.LIMITE_CURTO) String nome,
            Integer version) {
    }

    public record Resposta(UUID id, int version, String nome, boolean ativo) {

        static Resposta de(Praca p) {
            return new Resposta(p.getId(), p.getVersion(), p.getNome(), p.isAtivo());
        }
    }

    private final ServicoDePracas servico;

    public PracasDaDonaController(ServicoDePracas servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar praças", description = "Busca pelo nome. A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(servico.listar(busca, situacao, pagina, tamanho), Resposta::de);
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(servico.obter(id));
    }

    @Operation(summary = "Criar praça", description = "O nome não pode repetir (sem diferenciar maiúsculas).")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody Pedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Resposta.de(servico.criar(pedido.nome())));
    }

    @Operation(summary = "Editar praça", description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody Pedido pedido) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.nome()));
    }

    @Operation(summary = "Inativar praça",
            description = "Inativar não é excluir: a praça some das escolhas e das buscas (a lista de ativos), mas continua nos relatórios "
                    + "e nos históricos, e pode ser reativada a qualquer hora. Mande a version que a tela leu (opcional).")
    @PostMapping("/{id}/inativar")
    public Resposta inativar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeSituacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.definirAtivo(id, pedido == null ? null : pedido.version(), false, quem.id()));
    }

    @Operation(summary = "Reativar praça", description = "Desfaz a inativação: volta para as escolhas e para a lista de ativos.")
    @PostMapping("/{id}/reativar")
    public Resposta reativar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeSituacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.definirAtivo(id, pedido == null ? null : pedido.version(), true, quem.id()));
    }

    @Operation(summary = "Excluir praça", description = "Exclusão lógica: o registro some das listas e o nome fica livre.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}

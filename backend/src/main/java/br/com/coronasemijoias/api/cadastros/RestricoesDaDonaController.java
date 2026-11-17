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

/** Restrições que a dona põe num vendedor: cadastro da dona (tarefa 1.2). */
@RestController
@RequestMapping("/api/dona/restricoes")
@ExigePerfil("ADMIN")
public class RestricoesDaDonaController {

    /** {@code version} (a que a tela leu) só vale ao editar. */
    public record Pedido(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 30, message = Mensagens.LIMITE_CURTO) String codigo,
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 200, message = Mensagens.LIMITE_LONGO) String descricao,
            Boolean bloqueiaRemessa,
            Integer version) {
    }

    public record Resposta(UUID id, int version, String codigo, String descricao, boolean bloqueiaRemessa, boolean ativo) {

        static Resposta de(Restricao r) {
            return new Resposta(r.getId(), r.getVersion(), r.getCodigo(), r.getDescricao(), r.isBloqueiaRemessa(), r.isAtivo());
        }
    }

    private final ServicoDeRestricoes servico;

    public RestricoesDaDonaController(ServicoDeRestricoes servico) {
        this.servico = servico;
    }

    @Operation(summary = "Listar restrições", description = "Busca pelo código ou pela descrição. A página começa em 0 (até 100 por página).")
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

    @Operation(summary = "Criar restrição", description = "O código não pode repetir (sem diferenciar maiúsculas).")
    @PostMapping
    public ResponseEntity<Resposta> criar(@Valid @RequestBody Pedido pedido) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Resposta.de(servico.criar(pedido.codigo(), pedido.descricao(), Boolean.TRUE.equals(pedido.bloqueiaRemessa()))));
    }

    @Operation(summary = "Editar restrição", description = "Mande a version que a tela leu; se outra pessoa alterou antes, volta 409.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody Pedido pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.editar(id, pedido.version(), pedido.codigo(), pedido.descricao(),
                Boolean.TRUE.equals(pedido.bloqueiaRemessa()), quem.id()));
    }

    @Operation(summary = "Inativar restrição",
            description = "Inativar não é excluir: a restrição some das escolhas e das buscas (a lista de ativos), mas continua nos relatórios "
                    + "e nos históricos, e pode ser reativada a qualquer hora. Mande a version que a tela leu (opcional).")
    @PostMapping("/{id}/inativar")
    public Resposta inativar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeSituacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.definirAtivo(id, pedido == null ? null : pedido.version(), false, quem.id()));
    }

    @Operation(summary = "Reativar restrição", description = "Desfaz a inativação: volta para as escolhas e para a lista de ativos.")
    @PostMapping("/{id}/reativar")
    public Resposta reativar(@PathVariable UUID id, @RequestBody(required = false) PedidoDeSituacao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(servico.definirAtivo(id, pedido == null ? null : pedido.version(), true, quem.id()));
    }

    @Operation(summary = "Excluir restrição", description = "Exclusão lógica: o registro some das listas e o código fica livre.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable UUID id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}

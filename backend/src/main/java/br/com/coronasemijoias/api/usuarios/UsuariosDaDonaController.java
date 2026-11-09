package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.usuarios.ServicoDeUsuarios.UsuarioComSenhaTemporaria;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A dona cria usuários e redefine a senha de quem esqueceu (ADR-0004, itens 6 e 8). Listar, editar e
 * desativar usuários chegam na Fase 1.
 */
@RestController
@RequestMapping("/api/dona/usuarios")
@ExigePerfil("ADMIN")
public class UsuariosDaDonaController {

    /** Pedido de usuário novo. O login é normalizado e conferido no servidor. */
    public record PedidoDeUsuario(
            @NotBlank @Size(max = 120) String nome,
            @NotNull String login,
            @NotBlank String perfil,
            UUID vendedorId) {
    }

    /**
     * O usuário e a senha temporária, mostrada UMA vez: ela só existe nesta resposta (o servidor guarda
     * só o hash) e nunca vai para log. A resposta não deve ficar em cache.
     */
    public record RespostaComSenhaTemporaria(
            UUID id, String nome, String login, String perfil, UUID vendedorId,
            String senhaTemporaria, Instant senhaTemporariaVenceEm) {

        static RespostaComSenhaTemporaria de(UsuarioComSenhaTemporaria criado) {
            Usuario u = criado.usuario();
            return new RespostaComSenhaTemporaria(u.getId(), u.getNome(), u.getLogin(), u.getPerfil(),
                    u.getVendedorId(), criado.senhaTemporaria(), criado.venceEm());
        }

        @Override
        public String toString() {
            return "RespostaComSenhaTemporaria[id=" + id + ", login=" + login + ", senhaTemporaria=(oculta)]";
        }
    }

    private final ServicoDeUsuarios servico;

    public UsuariosDaDonaController(ServicoDeUsuarios servico) {
        this.servico = servico;
    }

    @Operation(summary = "Criar usuário com senha temporária",
            description = "Devolve a senha temporária uma única vez. Ela vale 7 dias (configuração do servidor) "
                    + "e a pessoa precisa trocá-la na primeira entrada.")
    @PostMapping
    public ResponseEntity<RespostaComSenhaTemporaria> criar(@Valid @RequestBody PedidoDeUsuario pedido) {
        UsuarioComSenhaTemporaria criado = servico.criar(pedido.nome(), pedido.login(), pedido.perfil(), pedido.vendedorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(RespostaComSenhaTemporaria.de(criado));
    }

    @Operation(summary = "Redefinir a senha de um usuário",
            description = "Gera outra senha temporária (nova contagem de 7 dias) e derruba as sessões do usuário. "
                    + "Devolve a senha uma única vez.")
    @PostMapping("/{id}/redefinir-senha")
    public ResponseEntity<RespostaComSenhaTemporaria> redefinirSenha(
            @PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quemPediu) {
        UsuarioComSenhaTemporaria redefinido = servico.redefinirSenha(id, quemPediu.id());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RespostaComSenhaTemporaria.de(redefinido));
    }
}

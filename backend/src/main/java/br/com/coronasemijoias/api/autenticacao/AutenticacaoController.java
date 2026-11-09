package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de entrada, comuns aos dois aplicativos ({@code /api/auth}, nos dois grupos do OpenAPI).
 * Renovação, saída e troca de senha chegam nas tarefas 10 e 11.
 */
@RestController
@RequestMapping("/api/auth")
public class AutenticacaoController {

    /**
     * Pedido de login. Campo ausente é pedido malformado (400); vazio ou com formato que não pode
     * existir é tratado como senha errada (401), sem revelar nada.
     */
    public record PedidoDeLogin(@NotNull String login, @NotNull String senha) {

        @Override
        public String toString() {
            return "PedidoDeLogin[login=" + login + ", senha=(oculta)]";
        }
    }

    /**
     * Resposta do login: o token de acesso, quando ele vence e a hora do servidor (UTC). O app guarda a
     * hora do servidor como "último contato" (ADR-0004, item 5), sem confiar no relógio do computador.
     */
    public record RespostaDoLogin(
            String tokenDeAcesso, String tipoDoToken, Instant expiraEm, long validadeEmSegundos,
            Instant horaDoServidor) {
    }

    /** Quem está usando o token. Só estes cinco campos. */
    public record DadosDoUsuario(UUID id, String nome, String login, String perfil, UUID vendedorId) {
    }

    private final ServicoDeLogin servicoDeLogin;
    private final EmissorDeToken emissor;
    private final UsuarioRepositorio usuarios;

    public AutenticacaoController(ServicoDeLogin servicoDeLogin, EmissorDeToken emissor, UsuarioRepositorio usuarios) {
        this.servicoDeLogin = servicoDeLogin;
        this.emissor = emissor;
        this.usuarios = usuarios;
    }

    @Operation(summary = "Entrar com login e senha")
    @PostMapping("/login")
    public RespostaDoLogin entrar(@Valid @RequestBody PedidoDeLogin pedido) {
        TokenEmitido token = servicoDeLogin.entrar(pedido.login(), pedido.senha());
        return new RespostaDoLogin(token.valor(), "Bearer", token.expiraEm(), emissor.duracao().toSeconds(),
                token.emitidoEm());
    }

    @Operation(summary = "Dados de quem está usando o token")
    @ExigePerfil({"ADMIN", "VENDEDOR"})
    @GetMapping("/eu")
    public DadosDoUsuario eu(@AuthenticationPrincipal UsuarioAutenticado autenticado) {
        Usuario usuario = usuarios.findById(autenticado.id())
                .orElseThrow(() -> new ErroDaApi(HttpStatus.UNAUTHORIZED, CodigoErro.SESSAO_TERMINADA));
        return new DadosDoUsuario(usuario.getId(), usuario.getNome(), usuario.getLogin(), usuario.getPerfil(),
                usuario.getVendedorId());
    }
}

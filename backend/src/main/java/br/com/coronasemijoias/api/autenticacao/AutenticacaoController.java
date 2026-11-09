package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken.TokenEmitido;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.ResultadoDoLogin;
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
 * Renovação e saída chegam na tarefa 11.
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

    /** Pedido de troca de senha: só a senha nova (o "repita a senha" é da tela). */
    public record PedidoDeTrocaDeSenha(@NotNull String senhaNova) {

        @Override
        public String toString() {
            return "PedidoDeTrocaDeSenha[senhaNova=(oculta)]";
        }
    }

    /**
     * Resposta do login e da troca de senha: o token, quando ele vence e a hora do servidor (UTC). O app
     * guarda a hora do servidor como "último contato" (ADR-0004, item 5), sem confiar no relógio do
     * computador. Com {@code trocaDeSenhaObrigatoria}, o token é o restrito: só abre a troca de senha.
     */
    public record RespostaDoLogin(
            String tokenDeAcesso, String tipoDoToken, Instant expiraEm, long validadeEmSegundos,
            Instant horaDoServidor, boolean trocaDeSenhaObrigatoria) {
    }

    /** Quem está usando o token. Só estes cinco campos. */
    public record DadosDoUsuario(UUID id, String nome, String login, String perfil, UUID vendedorId) {
    }

    private final ServicoDeLogin servicoDeLogin;
    private final ServicoDeTrocaDeSenha servicoDeTrocaDeSenha;
    private final EmissorDeToken emissor;
    private final UsuarioRepositorio usuarios;

    public AutenticacaoController(ServicoDeLogin servicoDeLogin, ServicoDeTrocaDeSenha servicoDeTrocaDeSenha,
            EmissorDeToken emissor, UsuarioRepositorio usuarios) {
        this.servicoDeLogin = servicoDeLogin;
        this.servicoDeTrocaDeSenha = servicoDeTrocaDeSenha;
        this.emissor = emissor;
        this.usuarios = usuarios;
    }

    @Operation(summary = "Entrar com login e senha")
    @PostMapping("/login")
    public RespostaDoLogin entrar(@Valid @RequestBody PedidoDeLogin pedido) {
        ResultadoDoLogin resultado = servicoDeLogin.entrar(pedido.login(), pedido.senha());
        return resposta(resultado.token(), resultado.trocaDeSenhaObrigatoria());
    }

    @Operation(summary = "Criar a senha nova (troca obrigatória)",
            description = "Só aceita o token restrito devolvido pelo login com senha temporária. A senha nova "
                    + "precisa ter pelo menos 8 caracteres, com letras e números, ser diferente da temporária "
                    + "e caber em 72 bytes. Devolve o token de acesso normal.")
    @ExigePerfil({"ADMIN", "VENDEDOR"})
    @PostMapping("/trocar-senha")
    public RespostaDoLogin trocarSenha(
            @AuthenticationPrincipal UsuarioAutenticado autenticado, @Valid @RequestBody PedidoDeTrocaDeSenha pedido) {
        return resposta(servicoDeTrocaDeSenha.trocar(autenticado, pedido.senhaNova()), false);
    }

    private RespostaDoLogin resposta(TokenEmitido token, boolean trocaDeSenhaObrigatoria) {
        return new RespostaDoLogin(token.valor(), "Bearer", token.expiraEm(), emissor.duracao().toSeconds(),
                token.emitidoEm(), trocaDeSenhaObrigatoria);
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

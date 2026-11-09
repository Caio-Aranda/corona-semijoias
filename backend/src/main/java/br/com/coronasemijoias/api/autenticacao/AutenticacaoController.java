package br.com.coronasemijoias.api.autenticacao;

import br.com.coronasemijoias.api.acessos.IpDoCliente;
import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.PedidoDeEntrada;
import br.com.coronasemijoias.api.autenticacao.ServicoDeLogin.ResultadoDoLogin;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.configuracoes.ChaveDeConfiguracao;
import br.com.coronasemijoias.api.configuracoes.Configuracoes;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import br.com.coronasemijoias.api.seguranca.UsuarioAutenticado;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rotas de entrada, comuns aos dois aplicativos ({@code /api/auth}, nos dois grupos do OpenAPI).
 */
@RestController
@RequestMapping("/api/auth")
public class AutenticacaoController {

    /**
     * Pedido de login. Campo ausente é pedido malformado (400); vazio ou com formato que não pode
     * existir é tratado como senha errada (401), sem revelar nada. {@code dispositivoId} é o
     * identificador do aparelho: obrigatório no Corona Vendas (perfil VENDEDOR), ausente no Corona
     * Gestão (ADR-0004, item 4).
     */
    public record PedidoDeLogin(@NotNull @Size(max = 200) String login, @NotNull String senha, UUID dispositivoId) {

        @Override
        public String toString() {
            return "PedidoDeLogin[login=" + login + ", senha=(oculta), dispositivoId=" + dispositivoId + "]";
        }
    }

    /** Pedido de troca de senha: só a senha nova (o "repita a senha" é da tela). */
    public record PedidoDeTrocaDeSenha(@NotNull String senhaNova) {

        @Override
        public String toString() {
            return "PedidoDeTrocaDeSenha[senhaNova=(oculta)]";
        }
    }

    /** Pedido de renovação: o token de renovação e o mesmo aparelho do login. */
    public record PedidoDeRenovacao(@NotNull @Size(max = 200) String tokenDeRenovacao, UUID dispositivoId) {

        @Override
        public String toString() {
            return "PedidoDeRenovacao[tokenDeRenovacao=(oculto), dispositivoId=" + dispositivoId + "]";
        }
    }

    /**
     * Resposta do login, da troca de senha e da renovação. O app guarda a hora do servidor (UTC) como
     * "último contato" (ADR-0004, item 5), sem confiar no relógio do computador, e recebe o prazo
     * máximo sem internet. Com {@code trocaDeSenhaObrigatoria}, o token é o restrito: só abre a troca
     * de senha e a saída, e não há token de renovação.
     */
    public record RespostaDoLogin(
            String tokenDeAcesso, String tipoDoToken, Instant expiraEm, long validadeEmSegundos,
            String tokenDeRenovacao, Instant tokenDeRenovacaoExpiraEm,
            Instant horaDoServidor, int tempoMaximoOfflineDias, boolean trocaDeSenhaObrigatoria) {

        @Override
        public String toString() {
            return "RespostaDoLogin[tokens=(ocultos), expiraEm=" + expiraEm + ", trocaDeSenhaObrigatoria="
                    + trocaDeSenhaObrigatoria + "]";
        }
    }

    /** Quem está usando o token. Só estes cinco campos. */
    public record DadosDoUsuario(UUID id, String nome, String login, String perfil, UUID vendedorId) {
    }

    private final ServicoDeLogin servicoDeLogin;
    private final ServicoDeTrocaDeSenha servicoDeTrocaDeSenha;
    private final ServicoDeRenovacao servicoDeRenovacao;
    private final ServicoDeSessoes sessoes;
    private final EmissorDeToken emissor;
    private final UsuarioRepositorio usuarios;
    private final Configuracoes configuracoes;
    private final IpDoCliente ipDoCliente;

    public AutenticacaoController(ServicoDeLogin servicoDeLogin, ServicoDeTrocaDeSenha servicoDeTrocaDeSenha,
            ServicoDeRenovacao servicoDeRenovacao, ServicoDeSessoes sessoes, EmissorDeToken emissor,
            UsuarioRepositorio usuarios, Configuracoes configuracoes, IpDoCliente ipDoCliente) {
        this.servicoDeLogin = servicoDeLogin;
        this.servicoDeTrocaDeSenha = servicoDeTrocaDeSenha;
        this.servicoDeRenovacao = servicoDeRenovacao;
        this.sessoes = sessoes;
        this.emissor = emissor;
        this.usuarios = usuarios;
        this.configuracoes = configuracoes;
        this.ipDoCliente = ipDoCliente;
    }

    @Operation(summary = "Entrar com login e senha",
            description = "Devolve o token de acesso (15 minutos) e o de renovação (30 dias). O Corona Vendas manda "
                    + "dispositivoId; o Corona Gestão não. Depois de 5 erros seguidos do mesmo endereço, responde 429 "
                    + "com Retry-After até o fim do bloqueio.")
    @PostMapping("/login")
    public ResponseEntity<RespostaDoLogin> entrar(@Valid @RequestBody PedidoDeLogin pedido, HttpServletRequest requisicao) {
        return resposta(servicoDeLogin.entrar(new PedidoDeEntrada(
                pedido.login(), pedido.senha(), pedido.dispositivoId(), ipDoCliente.de(requisicao))));
    }

    @Operation(summary = "Criar a senha nova (troca obrigatória)",
            description = "Só aceita o token restrito devolvido pelo login com senha temporária. A senha nova "
                    + "precisa ter pelo menos 8 caracteres, com letras e números, ser diferente da temporária "
                    + "e caber em 72 bytes. Encerra todas as sessões abertas e devolve os tokens normais.")
    @ExigePerfil({"ADMIN", "VENDEDOR"})
    @PostMapping("/trocar-senha")
    public ResponseEntity<RespostaDoLogin> trocarSenha(
            @AuthenticationPrincipal UsuarioAutenticado autenticado, @Valid @RequestBody PedidoDeTrocaDeSenha pedido) {
        return resposta(servicoDeTrocaDeSenha.trocar(autenticado, pedido.senhaNova()));
    }

    @Operation(summary = "Renovar a sessão",
            description = "Troca o token de renovação por um novo (o anterior deixa de valer) e devolve um token de "
                    + "acesso novo. Mande o mesmo dispositivoId do login. Não precisa do token de acesso.")
    @PostMapping("/renovar")
    public ResponseEntity<RespostaDoLogin> renovar(@Valid @RequestBody PedidoDeRenovacao pedido) {
        return resposta(servicoDeRenovacao.renovar(pedido.tokenDeRenovacao(), pedido.dispositivoId()));
    }

    @Operation(summary = "Sair", description = "Encerra a sessão do token (o de acesso e o de renovação).")
    @ExigePerfil({"ADMIN", "VENDEDOR"})
    @PostMapping("/sair")
    public ResponseEntity<Void> sair(@AuthenticationPrincipal UsuarioAutenticado autenticado) {
        sessoes.sair(autenticado.sid());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<RespostaDoLogin> resposta(ResultadoDoLogin resultado) {
        RespostaDoLogin corpo = new RespostaDoLogin(
                resultado.token().valor(), "Bearer", resultado.token().expiraEm(), emissor.duracao().toSeconds(),
                resultado.sessao().tokenDeRenovacao(), resultado.sessao().renovacaoExpiraEm(),
                resultado.token().emitidoEm(), configuracoes.inteiro(ChaveDeConfiguracao.TEMPO_MAXIMO_OFFLINE_DIAS),
                resultado.trocaDeSenhaObrigatoria());
        // Tokens nunca ficam em cache de ninguém.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(corpo);
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

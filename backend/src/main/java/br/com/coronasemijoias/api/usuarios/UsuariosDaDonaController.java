package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.cadastros.Mensagens;
import br.com.coronasemijoias.api.cadastros.Pagina;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A dona lista, cria, edita, desativa e reativa usuários e redefine a senha de quem esqueceu
 * (ADR-0004, itens 6 e 8). Só o perfil ADMIN. Nenhuma resposta leva o hash da senha; a senha
 * temporária aparece uma única vez, na resposta de criar ou de redefinir.
 */
@RestController
@RequestMapping("/api/dona/usuarios")
@ExigePerfil("ADMIN")
public class UsuariosDaDonaController {

    /** Pedido de usuário novo. O login é normalizado e conferido no servidor. */
    public record PedidoDeUsuario(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 120, message = Mensagens.LIMITE_LONGO) String nome,
            @NotNull(message = Mensagens.OBRIGATORIO) String login,
            @NotBlank(message = Mensagens.OBRIGATORIO) String perfil,
            UUID vendedorId) {
    }

    /** Edição: nome, tipo de acesso e vendedor. O login e a senha não mudam por aqui. {@code version} é a que a tela leu. */
    public record PedidoDeEdicao(
            @NotBlank(message = Mensagens.OBRIGATORIO) @Size(max = 120, message = Mensagens.LIMITE_LONGO) String nome,
            @NotBlank(message = Mensagens.OBRIGATORIO) String perfil,
            UUID vendedorId,
            @NotNull(message = Mensagens.OBRIGATORIO) Integer version) {
    }

    /** O usuário como a dona vê: sem hash, sem senha. */
    public record Resposta(
            UUID id, int version, String nome, String login, String perfil, UUID vendedorId, boolean ativo,
            boolean trocaDeSenhaObrigatoria, Instant senhaTemporariaVenceEm) {

        static Resposta de(Usuario u) {
            return new Resposta(u.getId(), u.getVersion(), u.getNome(), u.getLogin(), u.getPerfil(),
                    u.getVendedorId(), u.isAtivo(), u.isTrocaDeSenhaObrigatoria(), u.getSenhaTemporariaVenceEm());
        }
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
    private final GestaoDeUsuarios gestao;

    public UsuariosDaDonaController(ServicoDeUsuarios servico, GestaoDeUsuarios gestao) {
        this.servico = servico;
        this.gestao = gestao;
    }

    @Operation(summary = "Listar usuários",
            description = "Busca pelo nome ou pelo usuário (login). Filtros: perfil (ADMIN ou VENDEDOR) e ativo (true ou false). "
                    + "A página começa em 0 (até 100 por página).")
    @GetMapping
    public Pagina<Resposta> listar(@RequestParam(required = false) String busca,
            @RequestParam(required = false) String perfil, @RequestParam(required = false) Boolean ativo,
            @RequestParam(required = false) Integer pagina, @RequestParam(required = false) Integer tamanho) {
        return Pagina.de(gestao.listar(busca, perfil, ativo, pagina, tamanho), Resposta::de);
    }

    @GetMapping("/{id}")
    public Resposta ver(@PathVariable UUID id) {
        return Resposta.de(gestao.obter(id));
    }

    @Operation(summary = "Criar usuário com senha temporária",
            description = "Devolve a senha temporária uma única vez. Ela vale 7 dias (configuração do servidor) "
                    + "e a pessoa precisa trocá-la na primeira entrada.")
    @PostMapping
    public ResponseEntity<RespostaComSenhaTemporaria> criar(@Valid @RequestBody PedidoDeUsuario pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        UsuarioComSenhaTemporaria criado = servico.criar(
                pedido.nome(), pedido.login(), pedido.perfil(), pedido.vendedorId(), quem.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(RespostaComSenhaTemporaria.de(criado));
    }

    @Operation(summary = "Editar usuário",
            description = "Muda nome, tipo de acesso e vendedor. Mande a version que a tela leu; se outra pessoa alterou antes, volta 409. "
                    + "Mudar o tipo de acesso ou o vendedor encerra as sessões da pessoa. A dona não muda o próprio tipo de acesso "
                    + "nem deixa o sistema sem administradora.")
    @PutMapping("/{id}")
    public Resposta editar(@PathVariable UUID id, @Valid @RequestBody PedidoDeEdicao pedido,
            @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(gestao.editar(id, quem.id(), pedido.version(), pedido.nome(), pedido.perfil(), pedido.vendedorId()));
    }

    @Operation(summary = "Desativar usuário",
            description = "A pessoa perde o acesso na hora: todas as sessões dela terminam e ela não entra nem renova. "
                    + "A dona não desativa a si mesma nem a última administradora.")
    @PostMapping("/{id}/desativar")
    public Resposta desativar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(gestao.desativar(id, quem.id()));
    }

    @Operation(summary = "Reativar usuário",
            description = "A pessoa volta a poder entrar com a senha que já tinha. As sessões antigas continuam encerradas.")
    @PostMapping("/{id}/reativar")
    public Resposta reativar(@PathVariable UUID id, @AuthenticationPrincipal UsuarioAutenticado quem) {
        return Resposta.de(gestao.reativar(id, quem.id()));
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

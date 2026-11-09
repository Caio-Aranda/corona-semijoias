package br.com.coronasemijoias.api.testes;

import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Usuários e tokens para os testes. Os usuários são gravados direto no banco (com o usuário da
 * aplicação), com hash BCrypt de custo baixo para ser rápido; o login certo regrava com o custo
 * configurado. Cada usuário tem login único, porque o banco é o mesmo para todos os testes e nada é
 * apagado.
 */
@TestComponent
public class UsuariosDeTeste {

    /** Um usuário de teste e a senha dele (em texto, só nos testes). */
    public record UsuarioDeTeste(UUID id, String nome, String login, String senha, String perfil, UUID vendedorId) {
    }

    private static final BCryptPasswordEncoder RAPIDO = new BCryptPasswordEncoder(4);

    private final JdbcTemplate jdbc;
    private final EmissorDeToken emissor;

    private UsuarioDeTeste dona;
    private UsuarioDeTeste vendedor;

    public UsuariosDeTeste(JdbcTemplate jdbc, EmissorDeToken emissor) {
        this.jdbc = jdbc;
        this.emissor = emissor;
    }

    public UsuarioDeTeste criarDona() {
        return criar("ADMIN", null, true, "senha-da-dona-1", RAPIDO);
    }

    public UsuarioDeTeste criarVendedor() {
        return criar("VENDEDOR", UUID.randomUUID(), true, "senha-do-vendedor-1", RAPIDO);
    }

    public UsuarioDeTeste criar(String perfil, UUID vendedorId, boolean ativo, String senha, BCryptPasswordEncoder bcrypt) {
        UUID id = UUID.randomUUID();
        String login = perfil.toLowerCase() + "." + id.toString().substring(0, 8);
        String nome = "Pessoa de Teste " + login;
        jdbc.update("""
                INSERT INTO corona.usuarios (id, nome, login, senha_hash, perfil, ativo, vendedor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                id, nome, login, bcrypt.encode(senha), perfil, ativo, vendedorId);
        return new UsuarioDeTeste(id, nome, login, senha, perfil, vendedorId);
    }

    public void desativar(UsuarioDeTeste usuario) {
        jdbc.update("UPDATE corona.usuarios SET ativo = false WHERE id = ?", usuario.id());
    }

    public String senhaHash(UsuarioDeTeste usuario) {
        return jdbc.queryForObject("SELECT senha_hash FROM corona.usuarios WHERE id = ?", String.class, usuario.id());
    }

    /** Token válido emitido pela própria API. */
    public String token(UsuarioDeTeste usuario) {
        return emissor.emitir(ConteudoDoToken.de(usuario.id(), usuario.perfil(), usuario.vendedorId())).valor();
    }

    /** Uma dona ativa, criada uma vez por contexto. */
    public synchronized UsuarioDeTeste dona() {
        if (dona == null) {
            dona = criarDona();
        }
        return dona;
    }

    /** Um vendedor ativo, criado uma vez por contexto. */
    public synchronized UsuarioDeTeste vendedor() {
        if (vendedor == null) {
            vendedor = criarVendedor();
        }
        return vendedor;
    }

    public RequestPostProcessor comoDona() {
        return comToken(token(dona()));
    }

    public RequestPostProcessor comoVendedor() {
        return comToken(token(vendedor()));
    }

    public static RequestPostProcessor comToken(String token) {
        return requisicao -> {
            requisicao.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return requisicao;
        };
    }
}

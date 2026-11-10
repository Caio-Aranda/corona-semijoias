package br.com.coronasemijoias.api.testes;

import br.com.coronasemijoias.api.seguranca.ConteudoDoToken;
import br.com.coronasemijoias.api.seguranca.EmissorDeToken;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SessaoAberta;
import br.com.coronasemijoias.api.sessoes.TipoDeSessao;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ServicoDeSessoes sessoes;

    private UsuarioDeTeste dona;
    private UsuarioDeTeste vendedor;

    public UsuariosDeTeste(JdbcTemplate jdbc, EmissorDeToken emissor, ServicoDeSessoes sessoes) {
        this.jdbc = jdbc;
        this.emissor = emissor;
        this.sessoes = sessoes;
    }

    public UsuarioDeTeste criarDona() {
        return criar("ADMIN", null, true, "senha-da-dona-1", RAPIDO);
    }

    public UsuarioDeTeste criarVendedor() {
        return criar("VENDEDOR", UUID.randomUUID(), true, "senha-do-vendedor-1", RAPIDO);
    }

    /** Um cadastro de vendedor mínimo (o que o usuário VENDEDOR precisa ter ligado), devolvendo o id. */
    public UUID criarCadastroDeVendedor() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.vendedores (id, nome) VALUES (?, ?)", id, "Vendedor de Teste " + id);
        return id;
    }

    public UsuarioDeTeste criar(String perfil, UUID vendedorId, boolean ativo, String senha, BCryptPasswordEncoder bcrypt) {
        UUID id = UUID.randomUUID();
        String login = perfil.toLowerCase() + "." + id.toString().substring(0, 8);
        String nome = "Pessoa de Teste " + login;
        if (vendedorId != null) {
            jdbc.update("INSERT INTO corona.vendedores (id, nome) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                    vendedorId, "Vendedor de " + login);
        }
        jdbc.update("""
                INSERT INTO corona.usuarios (id, nome, login, senha_hash, perfil, ativo, vendedor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                id, nome, login, bcrypt.encode(senha), perfil, ativo, vendedorId);
        return new UsuarioDeTeste(id, nome, login, senha, perfil, vendedorId);
    }

    /** Marca o usuário como "com senha temporária" (troca obrigatória), com ou sem vencimento. */
    public void marcarTrocaObrigatoria(UsuarioDeTeste usuario, Instant venceEm) {
        jdbc.update("UPDATE corona.usuarios SET troca_de_senha_obrigatoria = true, senha_temporaria_vence_em = ? WHERE id = ?",
                venceEm == null ? null : java.sql.Timestamp.from(venceEm), usuario.id());
    }

    public void desativar(UsuarioDeTeste usuario) {
        jdbc.update("UPDATE corona.usuarios SET ativo = false WHERE id = ?", usuario.id());
    }

    public String senhaHash(UsuarioDeTeste usuario) {
        return jdbc.queryForObject("SELECT senha_hash FROM corona.usuarios WHERE id = ?", String.class, usuario.id());
    }

    /** O aparelho dos logins de teste do vendedor (um computador só para todos). */
    public static final UUID APARELHO = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

    /**
     * O corpo do login como o aplicativo certo mandaria: com o {@link #APARELHO} se o login for de um
     * vendedor (Corona Vendas), sem aparelho nos demais (Corona Gestão ou login que não existe).
     */
    public Map<String, Object> corpoDoLogin(String login, String senha) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("login", login);
        corpo.put("senha", senha);
        if (login.indexOf('\u0000') >= 0) {
            return corpo; // o PostgreSQL nem aceita o caractere nulo numa consulta: login que não existe
        }
        List<String> perfil = jdbc.queryForList("SELECT perfil FROM corona.usuarios WHERE login = ?", String.class,
                login.strip().toLowerCase(java.util.Locale.ROOT));
        if (perfil.contains("VENDEDOR")) {
            corpo.put("dispositivoId", APARELHO.toString());
        }
        return corpo;
    }

    /** O aparelho que o perfil manda no login: o vendedor manda um; a dona, nenhum (ADR-0004, item 4). */
    public static UUID aparelhoPara(String perfil) {
        return "VENDEDOR".equals(perfil) ? UUID.randomUUID() : null;
    }

    /** Abre uma família de sessão de verdade (como o login) e devolve o que ela tem. */
    public SessaoAberta abrirSessao(UsuarioDeTeste usuario, TipoDeSessao tipo) {
        return sessoes.abrir(usuario.id(), tipo, aparelhoPara(usuario.perfil()));
    }

    /** Token válido emitido pela própria API, com uma família de sessão nova. */
    public String token(UsuarioDeTeste usuario) {
        SessaoAberta sessao = abrirSessao(usuario, TipoDeSessao.NORMAL);
        return emissor.emitir(ConteudoDoToken.de(
                usuario.id(), usuario.perfil(), usuario.vendedorId(), sessao.sid(), sessao.dispositivoId())).valor();
    }

    /** Token restrito da troca de senha, emitido pela própria API (mesmo que o usuário não esteja marcado). */
    public String tokenRestrito(UsuarioDeTeste usuario) {
        SessaoAberta sessao = abrirSessao(usuario, TipoDeSessao.TROCA_SENHA);
        return emissor.emitir(ConteudoDoToken.restritoParaTrocaDeSenha(
                usuario.id(), usuario.perfil(), usuario.vendedorId(), sessao.sid(), sessao.dispositivoId())).valor();
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

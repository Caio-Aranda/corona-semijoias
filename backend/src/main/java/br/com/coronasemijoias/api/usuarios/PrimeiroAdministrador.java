package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.comum.configuracao.ConfiguracaoInvalidaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cria o primeiro administrador ao subir, a partir de {@code ADMIN_NOME}, {@code ADMIN_LOGIN} e
 * {@code ADMIN_SENHA}, só se ainda não existir nenhum usuário ADMIN. Subir de novo não faz nada.
 *
 * <p>Roda depois das migrações e antes de o servidor abrir a porta. Enquanto não existir ADMIN, as
 * três variáveis são obrigatórias: ausentes, vazias ou com "troque-isto", a API não sobe e explica em
 * português. Depois que o administrador existe, elas não são mais lidas (a senha inicial pode sair do
 * {@code .env} do servidor). A senha nunca vai para o log.
 *
 * <p>O primeiro administrador nasce com a troca de senha obrigatória, <strong>sem vencimento</strong>: na
 * primeira entrada ele só consegue criar a senha nova.
 */
@Component
public class PrimeiroAdministrador implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(PrimeiroAdministrador.class);

    static final String PERFIL = "ADMIN";

    /** Trava da transação, para duas instâncias subindo juntas não criarem dois administradores. */
    private static final long TRAVA = 0x436f726f6e6141L;

    private static final String VALOR_DE_EXEMPLO = "troque-isto";

    private static final String ACAO = "No arquivo .env, preencha ADMIN_NOME, ADMIN_LOGIN e ADMIN_SENHA com os dados "
            + "da dona (troque os valores \"troque-isto\") e rode de novo. Esses dados só servem para criar o "
            + "primeiro administrador, quando o banco ainda não tem nenhum.";

    private final PropriedadesDoPrimeiroAdministrador dados;
    private final UsuarioRepositorio usuarios;
    private final ConferenciaDeSenha conferencia;
    private final TransactionTemplate transacao;
    private final JdbcTemplate jdbc;

    public PrimeiroAdministrador(PropriedadesDoPrimeiroAdministrador dados, UsuarioRepositorio usuarios,
            ConferenciaDeSenha conferencia, TransactionTemplate transacao, JdbcTemplate jdbc) {
        this.dados = dados;
        this.usuarios = usuarios;
        this.conferencia = conferencia;
        this.transacao = transacao;
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        transacao.executeWithoutResult(status -> criarSeNaoExistir());
    }

    private void criarSeNaoExistir() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", linha -> { }, TRAVA);
        if (usuarios.existsByPerfil(PERFIL)) {
            return;
        }
        String nome = exigir(dados.nome(), "ADMIN_NOME");
        String login = Logins.normalizar(exigir(dados.login(), "ADMIN_LOGIN"));
        String senha = exigir(dados.senha(), "ADMIN_SENHA");
        if (!Logins.podeExistir(login)) {
            // Um login assim nunca entraria (o login recusa), e depois de criado o ADMIN as variáveis
            // não são mais lidas: só daria para consertar mexendo no banco.
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque o login em ADMIN_LOGIN não pode ter espaço no meio "
                            + "nem caracteres especiais invisíveis.",
                    "Escolha um login sem espaços para ADMIN_LOGIN no arquivo .env (por exemplo, maria.silva) "
                            + "e rode de novo.");
        }
        if (!ConferenciaDeSenha.cabe(senha)) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque a senha em ADMIN_SENHA é longa demais "
                            + "(o limite é de 72 bytes; letras com acento contam mais de um).",
                    "Escolha uma senha mais curta para ADMIN_SENHA no arquivo .env e rode de novo.");
        }
        if (usuarios.findByLogin(login).isPresent()) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque o login em ADMIN_LOGIN já é de outro usuário.",
                    "Escolha outro login para ADMIN_LOGIN no arquivo .env e rode de novo.");
        }
        // Troca obrigatória, mas sem vencimento: a senha veio do .env, não foi gerada pelo sistema.
        Usuario admin = Usuario.novoComSenhaSemVencimento(
                nome.strip(), login, conferencia.gerarHash(senha), PERFIL, null);
        usuarios.save(admin);
        LOG.info("Primeiro administrador criado: login {}", login);
    }

    private static String exigir(String valor, String variavel) {
        if (valor == null || valor.isBlank() || valor.strip().equalsIgnoreCase(VALOR_DE_EXEMPLO)) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque ainda não existe nenhum administrador e a variável "
                            + variavel + " não foi preenchida.",
                    ACAO);
        }
        return valor;
    }
}

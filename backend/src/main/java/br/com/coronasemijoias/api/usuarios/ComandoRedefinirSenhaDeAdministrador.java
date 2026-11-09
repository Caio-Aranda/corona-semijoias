package br.com.coronasemijoias.api.usuarios;

import br.com.coronasemijoias.api.CoronaApiApplication;
import br.com.coronasemijoias.api.usuarios.ServicoDeUsuarios.RecusaDoComando;
import br.com.coronasemijoias.api.usuarios.ServicoDeUsuarios.UsuarioComSenhaTemporaria;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Comando do servidor que redefine a senha de um administrador quando não há outro para fazer isso
 * (ADR-0004, item 8). Roda no contêiner da API, com o mesmo jar, SEM abrir a porta do servidor:
 *
 * <pre>
 * java -jar corona-api.jar redefinir-senha-admin --login=maria.silva
 * </pre>
 *
 * <p>Gera uma senha temporária, mostra no terminal UMA vez, marca a troca obrigatória e grava a hora
 * da troca de credencial (isso derruba as sessões abertas). A senha vai só para o terminal: o log
 * registra a ação, sem a senha. Passo a passo em {@code docs/redefinir-senha-da-dona.md}.
 */
@Component
public class ComandoRedefinirSenhaDeAdministrador {

    /** Primeiro argumento que chama este comando. */
    public static final String NOME = "redefinir-senha-admin";

    public static final int SAIDA_OK = 0;
    public static final int SAIDA_RECUSADO = 1;
    public static final int SAIDA_USO_ERRADO = 2;

    private static final Logger LOG = LoggerFactory.getLogger(ComandoRedefinirSenhaDeAdministrador.class);

    /** Horário de Brasília, o que a dona lê (o banco e a API continuam em UTC). */
    private static final DateTimeFormatter HORA_DA_TELA = DateTimeFormatter
            .ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    private final ServicoDeUsuarios servico;

    public ComandoRedefinirSenhaDeAdministrador(ServicoDeUsuarios servico) {
        this.servico = servico;
    }

    /** Se os argumentos pedem este comando. */
    public static boolean ehEste(String[] argumentos) {
        return argumentos.length > 0 && NOME.equals(argumentos[0]);
    }

    /**
     * Sobe a aplicação SEM servidor web (não ocupa a porta da API, que pode estar rodando) e executa o
     * comando. Devolve o código de saída do programa.
     *
     * @param perguntarLogin pergunta o login no terminal; devolve {@code null} se não há terminal
     */
    public static int rodar(String[] argumentos, PrintStream terminal, Supplier<String> perguntarLogin) {
        String login = loginDosArgumentos(argumentos);
        if (login == null) {
            login = perguntarLogin.get();
        }
        if (login == null || login.isBlank()) {
            terminal.println("Informe o login do administrador. Exemplo: "
                    + NOME + " --login=maria.silva");
            return SAIDA_USO_ERRADO;
        }
        SpringApplication aplicacao = new SpringApplication(CoronaApiApplication.class);
        aplicacao.setWebApplicationType(WebApplicationType.NONE);
        aplicacao.setBannerMode(Banner.Mode.OFF);
        aplicacao.setDefaultProperties(Map.of(
                "spring.main.log-startup-info", "false",
                "logging.level.org.springframework", "WARN",
                "logging.level.org.flywaydb", "WARN",
                "logging.level.com.zaxxer.hikari", "WARN",
                "logging.level.org.hibernate", "WARN"));
        try (ConfigurableApplicationContext contexto = aplicacao.run(argumentos)) {
            return contexto.getBean(ComandoRedefinirSenhaDeAdministrador.class).executar(login, terminal);
        }
    }

    /** Executa o comando: a senha vai só para {@code terminal}, nunca para o log. */
    public int executar(String loginDigitado, PrintStream terminal) {
        try {
            UsuarioComSenhaTemporaria redefinido = servico.redefinirSenhaDeAdministradorPeloServidor(loginDigitado);
            terminal.println();
            terminal.println("Senha temporária do administrador \"" + redefinido.usuario().getLogin() + "\" gerada.");
            terminal.println();
            terminal.println("    Senha temporária: " + redefinido.senhaTemporaria());
            terminal.println("    Vale até: " + HORA_DA_TELA.format(redefinido.venceEm()) + " (horário de Brasília)");
            terminal.println();
            terminal.println("Anote esta senha agora: ela não será mostrada de novo.");
            terminal.println("Os acessos abertos desse usuário foram encerrados. Na próxima entrada, ele usa esta");
            terminal.println("senha e é obrigado a criar uma senha nova.");
            return SAIDA_OK;
        } catch (RecusaDoComando recusa) {
            terminal.println(recusa.getMessage());
            return SAIDA_RECUSADO;
        } catch (RuntimeException erro) {
            // O detalhe técnico vai só para o log (nunca tem a senha: ela nem chegou a existir fora do serviço).
            LOG.error("Não foi possível redefinir a senha pelo servidor", erro);
            terminal.println("Não foi possível redefinir a senha agora. Nada foi mostrado nem guardado. "
                    + "Veja o registro (log) do servidor e tente de novo.");
            return SAIDA_RECUSADO;
        }
    }

    static String loginDosArgumentos(String[] argumentos) {
        for (String argumento : argumentos) {
            if (argumento.startsWith("--login=")) {
                return argumento.substring("--login=".length());
            }
        }
        return null;
    }
}

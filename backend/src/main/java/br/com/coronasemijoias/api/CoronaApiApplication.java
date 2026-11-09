package br.com.coronasemijoias.api;

import br.com.coronasemijoias.api.usuarios.ComandoRedefinirSenhaDeAdministrador;
import java.io.Console;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CoronaApiApplication {

    public static void main(String[] args) {
        // Datas sempre em UTC (ADR-0002), mesmo que a JVM não tenha recebido -Duser.timezone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (ComandoRedefinirSenhaDeAdministrador.ehEste(args)) {
            // Comando do servidor (ADR-0004, item 8): roda e sai, sem abrir a porta da API.
            System.exit(ComandoRedefinirSenhaDeAdministrador.rodar(args, System.out, CoronaApiApplication::perguntarLogin));
        }
        SpringApplication.run(CoronaApiApplication.class, args);
    }

    private static String perguntarLogin() {
        Console terminal = System.console();
        return terminal == null ? null : terminal.readLine("Login do administrador: ");
    }
}

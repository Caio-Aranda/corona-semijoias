package br.com.coronasemijoias.api.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Toda rota registrada declara o perfil ({@link ExigePerfil}) ou está na lista pública fechada. Uma
 * rota nova sem anotação reprova o build aqui (a prova está em {@code RotaSemPerfilTest}).
 */
class TodasAsRotasTest extends TesteDeIntegracao {

    @Autowired
    ApplicationContext contexto;

    @Autowired
    RotasPublicas publicas;

    @Autowired
    PropriedadesDeSeguranca propriedades;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void todaRotaTemPerfilDeclaradoOuEhPublica() {
        var rotas = VerificadorDeRotas.rotasDo(contexto);
        // O teste só vale se enxergar as rotas de verdade.
        assertThat(rotas.keySet().stream().flatMap(i -> i.getPatternValues().stream()))
                .contains("/api/auth/login", "/api/auth/eu", "/api/dona/teste/tempo", "/actuator/health");

        Set<String> perfis = new HashSet<>(jdbc.queryForList("SELECT codigo FROM corona.perfis", String.class));
        assertThat(perfis).containsExactlyInAnyOrder("ADMIN", "VENDEDOR");

        assertThat(VerificadorDeRotas.violacoes(rotas, publicas, propriedades.grupos(), perfis)).isEmpty();
    }

    @Test
    void listaPublicaEhFechada() {
        // Fora do perfil dev a documentação não é pública.
        assertThat(publicas.rotas()).extracting(r -> r.metodo().name() + " " + r.padrao())
                .containsExactly("POST /api/auth/login", "GET /actuator/health", "GET /actuator/health/**");
    }
}

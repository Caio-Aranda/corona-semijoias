package br.com.coronasemijoias.api.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;

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

    @Autowired
    MockMvc mvc;

    @Autowired
    UsuariosDeTeste usuarios;

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

    /**
     * O token restrito da troca de senha (ADR-0004, item 6) só abre {@link FiltroDoTokenRestrito#ROTAS_PERMITIDAS}.
     * Percorre TODAS as rotas registradas, então uma rota nova que escape da regra reprova o build.
     */
    @Test
    void tokenRestritoEBarradoEmTodaRotaMenosATrocaDeSenha() throws Exception {
        // O usuário do token precisa estar marcado, senão o token nem é aceito.
        UsuariosDeTeste.UsuarioDeTeste marcado = usuarios.criarVendedor();
        usuarios.marcarTrocaObrigatoria(marcado, null);
        String token = usuarios.tokenRestrito(marcado);

        var rotas = VerificadorDeRotas.rotasDo(contexto);
        List<String> falhas = new ArrayList<>();
        int chamadas = 0;
        for (var entrada : rotas.entrySet()) {
            if (ErrorController.class.isAssignableFrom(entrada.getValue().getBeanType())) {
                continue;
            }
            Set<RequestMethod> metodos = entrada.getKey().getMethodsCondition().getMethods();
            for (String padrao : entrada.getKey().getPatternValues()) {
                for (RequestMethod metodo : metodos.isEmpty() ? Set.of(RequestMethod.GET) : metodos) {
                    if (publicas.ehPublica(metodo.name(), padrao)) {
                        continue; // rota pública não lê o token
                    }
                    boolean permitida = FiltroDoTokenRestrito.ROTAS_PERMITIDAS.stream()
                            .anyMatch(r -> r.metodo().name().equals(metodo.name()) && r.padrao().equals(padrao));
                    if (permitida) {
                        continue;
                    }
                    String caminho = padrao.replaceAll("\\{[^}]*}", UUID.randomUUID().toString()).replace("**", "x");
                    var resposta = mvc.perform(request(HttpMethod.valueOf(metodo.name()), caminho)
                            .with(UsuariosDeTeste.comToken(token))).andReturn().getResponse();
                    chamadas++;
                    String corpo = resposta.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    if (resposta.getStatus() != 403 || !corpo.contains("\"codigo\":\"TROCA_DE_SENHA_OBRIGATORIA\"")) {
                        falhas.add(metodo + " " + caminho + " -> " + resposta.getStatus() + " " + corpo);
                    }
                }
            }
        }
        assertThat(chamadas).as("o teste precisa enxergar as rotas de verdade").isGreaterThan(5);
        assertThat(falhas).isEmpty();
    }

    @Test
    void aTrocaDeSenhaEASaidaSaoAsUnicasRotasDoTokenRestrito() {
        assertThat(FiltroDoTokenRestrito.ROTAS_PERMITIDAS)
                .extracting(r -> r.metodo().name() + " " + r.padrao())
                .containsExactly("POST /api/auth/trocar-senha", "POST /api/auth/sair");
        // E elas existem de verdade, exigindo token (não estão na lista pública).
        assertThat(VerificadorDeRotas.rotasDo(contexto).keySet().stream()
                .flatMap(i -> i.getPatternValues().stream())).contains("/api/auth/trocar-senha", "/api/auth/sair");
        assertThat(publicas.ehPublica("POST", "/api/auth/trocar-senha")).isFalse();
        assertThat(publicas.ehPublica("POST", "/api/auth/sair")).isFalse();
    }

    @Test
    void listaPublicaEhFechada() {
        // Fora do perfil dev a documentação não é pública. A renovação é pública: o token de acesso
        // pode já ter vencido, e quem prova a sessão é o token de renovação.
        assertThat(publicas.rotas()).extracting(r -> r.metodo().name() + " " + r.padrao())
                .containsExactly("POST /api/auth/login", "POST /api/auth/renovar", "POST /api/vendedor/sync/quarentena", "GET /actuator/health",
                        "GET /actuator/health/**");
    }
}

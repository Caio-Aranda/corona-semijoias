package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.RodadasDeTeste;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** As três rotas da conferência diária (tarefa 8.4): só a dona; o vendedor leva 403, sem sessão 401, id que não existe 404. */
class PermissoesDaConferenciaDiariaTest extends ApoioDasConciliacoes {

    private static final String ROTA = "/api/dona/conferencias";

    @AfterEach
    void deixarUmaRodadaLimpa() throws InterruptedException {
        Thread.sleep(5);
        RodadasDeTeste.limpa(jdbc, java.time.Instant.now());
    }

    @Test
    void aDonaConfereAgoraVeOHistoricoEODetalhe() throws Exception {
        UUID id = UUID.randomUUID();
        JsonNode nova = corpoDe(postarDona(ROTA, mapa("id", id)).andExpect(status().isCreated()));
        assertThat(nova.get("rodada").get("id").asString()).isEqualTo(id.toString());
        assertThat(nova.get("rodada").get("origem").asString()).isEqualTo("PEDIDA");
        assertThat(nova.get("rodada").get("pedidaPor").asString()).isNotBlank();
        assertThat(nova.get("rodada").get("chavesConferidas").size()).isEqualTo(13);
        assertThat(nova.get("grupos").isArray()).isTrue();

        JsonNode igual = corpoDe(postarDona(ROTA, mapa("id", id)).andExpect(status().isOk()));
        assertThat(igual.get("rodada").get("id").asString()).isEqualTo(id.toString());
        assertThat(contar("SELECT count(*) FROM corona.rodadas_conferencia WHERE id = ?", id)).isEqualTo(1);

        JsonNode historico = corpoDe(ler(ROTA + "?pagina=0&tamanho=5", dona()).andExpect(status().isOk()));
        assertThat(historico.get("itens").size()).isBetween(1, 5);
        assertThat(historico.get("itens").get(0).get("id").asString()).isEqualTo(id.toString());
        assertThat(historico.get("tamanho").asInt()).isEqualTo(5);
        assertThat(historico.get("total").asLong()).isPositive();
        assertThat(corpoDe(ler(ROTA + "?tamanho=5000", dona()).andExpect(status().isOk())).get("tamanho").asInt()).isEqualTo(100);
        assertThat(corpoDe(ler(ROTA, dona()).andExpect(status().isOk())).get("tamanho").asInt()).isEqualTo(30);

        JsonNode detalhe = corpoDe(ler(ROTA + "/" + id, dona()).andExpect(status().isOk()));
        assertThat(detalhe.get("rodada").get("id").asString()).isEqualTo(id.toString());
    }

    @Test
    void oVendedorLevaRecusaESemSessaoLevaNaoAutorizado() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID id = UUID.randomUUID();
        String corpo = json.writeValueAsString(mapa("id", id));
        postar(ROTA, como(v), mapa("id", id)).andExpect(status().isForbidden());
        ler(ROTA, como(v)).andExpect(status().isForbidden());
        ler(ROTA + "/" + id, como(v)).andExpect(status().isForbidden());
        mvc.perform(post(ROTA).contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROTA + "/" + id)).andExpect(status().isUnauthorized());
        assertThat(contar("SELECT count(*) FROM corona.rodadas_conferencia WHERE id = ?", id)).as("o vendedor não gravou nada").isZero();
    }

    @Test
    void rodadaQueNaoExisteDa404EmPortugues() throws Exception {
        ler(ROTA + "/" + UUID.randomUUID(), dona()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Não encontramos esta conferência")));
    }

    @Test
    void semIdOuComIdInvalidoDa400() throws Exception {
        postarDona(ROTA, mapa()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("id"));
        mvc.perform(post(ROTA).with(dona()).contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"isto-nao-e-id\"}"))
                .andExpect(status().isBadRequest());
        postarDona(ROTA, mapa("id", new UUID(0, 0))).andExpect(status().isBadRequest());
    }

    @Autowired
    br.com.coronasemijoias.api.sync.RegistroDeTabelas registro;

    @Test
    void asTabelasDaRodadaNuncaEntramNoSyncNemNoPullDoVendedor() {
        for (String tabela : java.util.List.of("rodadas_conferencia", "diferencas_conferencia")) {
            assertThat(registro.sincronizavel(tabela)).as(tabela).isEmpty();
        }
        assertThat(registro.sincronizaveis().stream().map(t -> t.nome())).doesNotContain("rodadas_conferencia", "diferencas_conferencia");
    }

    @Test
    void naoExisteConferenciaNaRotaDoVendedor() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        for (String rota : new String[] {"/api/vendedor/conferencias", "/api/vendedor/conferencias/" + UUID.randomUUID(),
                "/api/vendedor/conferencia-diaria"}) {
            assertThat(ler(rota, como(v)).andReturn().getResponse().getStatus()).as(rota).isIn(403, 404);
        }
    }
}

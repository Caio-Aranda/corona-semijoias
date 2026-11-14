package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A leitura dos acertos pela dona e pelo vendedor (tarefa 7.4; ADR-0012, itens 14 e 16; invariante 8 do plano). Escrito
 * antes das consultas.
 */
class LeituraDosAcertosTest extends ApoioDoAcerto {

    private UsuarioDeTeste vendedor;
    private UUID peca;
    private UUID maleta;
    private UUID semana;
    private UUID acerto;

    @BeforeEach
    void umAcertoComLeitura() throws Exception {
        vendedor = usuarios.criarVendedor();
        peca = peca("50.00", 20);
        semana = semana();
        maleta = maletaConferida(vendedor, semana, peca, 5);
        acerto = iniciar(vendedor, semana);
        postar(ACERTOS_DA_DONA + "/" + acerto + "/leituras", dona(),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 2, "estadoPeca", "BOA")).andExpect(status().isCreated());
    }

    @Test
    void aDonaListaEVeAFichaComMaletasContagemELeituras() throws Exception {
        JsonNode lista = corpoDe(ler(ACERTOS_DA_DONA + "?vendedorId=" + vendedor.vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(lista).hasSize(1);
        assertThat(lista.get(0).get("id").asString()).isEqualTo(acerto.toString());
        assertThat(corpoDe(ler(ACERTOS_DA_DONA + "?semanaId=" + semana + "&situacao=EM_ACERTO", dona())).size()).isGreaterThanOrEqualTo(1);
        assertThat(corpoDe(ler(ACERTOS_DA_DONA + "?vendedorId=" + vendedor.vendedorId() + "&situacao=DESISTIDO", dona()))).isEmpty();

        JsonNode ficha = corpoDe(ler(ACERTOS_DA_DONA + "/" + acerto, dona()).andExpect(status().isOk())
                .andExpect(jsonPath("$.vendedorId").value(vendedor.vendedorId().toString()))
                .andExpect(jsonPath("$.maletas[0].id").value(maleta.toString()))
                .andExpect(jsonPath("$.maletas[0].statusAntes").value("CONFERIDA"))
                .andExpect(jsonPath("$.contagem[0].quantidade").value(2))
                .andExpect(jsonPath("$.contagem[0].estadoPeca").value("BOA"))
                .andExpect(jsonPath("$.vendasQueChegaramDepoisDoInicio").value(0)));
        assertThat(ficha.get("leituras")).hasSize(1);
        assertThat(ficha.get("leituras").get(0).get("peloVendedor").asBoolean()).isFalse();
    }

    @Test
    void aContagemTrazAPecaComNomeCodigoEFoto() throws Exception {
        JsonNode peca = corpoDe(ler(ACERTOS_DA_DONA + "/" + acerto, dona())).get("contagem").get(0).get("produto");
        assertThat(peca.get("nome").asString()).startsWith("Anel ");
        assertThat(peca.get("codigo").asString()).startsWith("CR-");
        assertThat(peca.has("fotoHash")).as("foto da peça em toda contagem").isTrue();
    }

    @Test
    void oVendedorVeSoOsAcertosDele() throws Exception {
        JsonNode lista = corpoDe(ler(ACERTOS_DO_VENDEDOR, como(vendedor)).andExpect(status().isOk()));
        assertThat(lista).hasSize(1);
        ler(ACERTOS_DO_VENDEDOR + "/" + acerto, como(vendedor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.contagem[0].quantidade").value(2));
        UsuarioDeTeste outro = usuarios.criarVendedor();
        assertThat(corpoDe(ler(ACERTOS_DO_VENDEDOR, como(outro)).andExpect(status().isOk()))).isEmpty();
        ler(ACERTOS_DO_VENDEDOR + "/" + acerto, como(outro)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("ACERTO_NAO_ENCONTRADO"));
        ler(ACERTOS_DO_VENDEDOR + "/" + UUID.randomUUID(), como(vendedor)).andExpect(status().isNotFound());
        ler(ACERTOS_DA_DONA + "/" + UUID.randomUUID(), dona()).andExpect(status().isNotFound());
    }

    @Test
    void nenhumaRespostaTemCustoNemDadoDaDonaParaOVendedor() throws Exception {
        String dados = ler(ACERTOS_DO_VENDEDOR + "/" + acerto, como(vendedor)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        String lista = ler(ACERTOS_DO_VENDEDOR, como(vendedor)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        String daDona = ler(ACERTOS_DA_DONA + "/" + acerto, dona()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        for (String texto : new String[] {dados, lista, daDona}) {
            assertThat(texto).doesNotContain("custo");
        }
        // O vendedor não vê quem iniciou nem os envios aguardando de outros: só a ficha dele.
        assertThat(dados).doesNotContain("iniciadopor").doesNotContain("vendasquechegaram");
    }
}

package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A contagem do acerto, uma leitura por pedido (tarefa 7.4; ADR-0012, item 15; invariantes 5, 6 e 7 do plano). Escrito
 * antes do serviço.
 */
class LeiturasDoAcertoTest extends ApoioDoAcerto {

    private UsuarioDeTeste vendedor;
    private UUID peca;
    private UUID maleta;
    private UUID acerto;

    @BeforeEach
    void umAcertoEmAndamento() throws Exception {
        vendedor = usuarios.criarVendedor();
        peca = peca("50.00", 20);
        maleta = maleta(vendedor, peca, 5);
        acerto = iniciar(vendedor, jdbc.queryForObject("SELECT semana_id FROM corona.remessas WHERE id = ?", UUID.class, maleta));
    }

    private String leituras(UUID id) {
        return ACERTOS_DA_DONA + "/" + id + "/leituras";
    }

    private int contagem(JsonNode ficha, String estado) {
        int soma = 0;
        for (JsonNode c : ficha.get("contagem")) {
            if (estado.equals(c.get("estadoPeca").asString())) {
                soma += c.get("quantidade").asInt();
            }
        }
        return soma;
    }

    private JsonNode ficha() throws Exception {
        return corpoDe(ler(ACERTOS_DA_DONA + "/" + acerto, dona()).andExpect(status().isOk()));
    }

    @Test
    void leitura201Reenvio200ComOutroConteudo409() throws Exception {
        UUID id = UUID.randomUUID();
        var corpo = mapa("id", id, "produtoId", peca, "quantidade", 2, "estadoPeca", "BOA");
        postar(leituras(acerto), dona(), corpo).andExpect(status().isCreated());
        postar(leituras(acerto), dona(), corpo).andExpect(status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isEqualTo(1);
        postar(leituras(acerto), dona(), mapa("id", id, "produtoId", peca, "quantidade", 3, "estadoPeca", "BOA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("LEITURA_JA_REGISTRADA_DIFERENTE"));
        assertThat(contagem(ficha(), "BOA")).isEqualTo(2);
    }

    @Test
    void desfazerELeituraNegativaEATiradaAcimaDoContadoNaoGrava() throws Exception {
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 3, "estadoPeca", "BOA"))
                .andExpect(status().isCreated());
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", -1, "estadoPeca", "BOA"))
                .andExpect(status().isCreated());
        assertThat(contagem(ficha(), "BOA")).isEqualTo(2);
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", -3, "estadoPeca", "BOA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("LEITURA_DEIXA_CONTAGEM_NEGATIVA"));
        // Por estado: nada contado como defeito, então nada a tirar.
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", -1, "estadoPeca", "DEFEITO"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("LEITURA_DEIXA_CONTAGEM_NEGATIVA"));
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isEqualTo(2);
    }

    @Test
    void pecaDeForaERecusadaSemRevelarDeOndeE() throws Exception {
        UUID deFora = peca("30.00", 5);
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", deFora, "quantidade", 1, "estadoPeca", "BOA"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("PECA_NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.detail").value("Esta peça não consta nas maletas deste acerto."));
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", UUID.randomUUID(), "quantidade", 1,
                "estadoPeca", "BOA")).andExpect(status().isNotFound());
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isZero();
    }

    @Test
    void aPecaPodeSerContadaComoBoaEComoDefeito() throws Exception {
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 2, "estadoPeca", "BOA"))
                .andExpect(status().isCreated());
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "DEFEITO"))
                .andExpect(status().isCreated());
        JsonNode ficha = ficha();
        assertThat(contagem(ficha, "BOA")).isEqualTo(2);
        assertThat(contagem(ficha, "DEFEITO")).isEqualTo(1);
    }

    @Test
    void donaEVendedorSomamNaMesmaContagemCadaLeituraComQuemEOComputador() throws Exception {
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 2, "estadoPeca", "BOA"))
                .andExpect(status().isCreated());
        postar(ACERTOS_DO_VENDEDOR + "/" + acerto + "/leituras", como(vendedor),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "BOA")).andExpect(status().isCreated());
        JsonNode ficha = ficha();
        assertThat(contagem(ficha, "BOA")).isEqualTo(3);
        assertThat(ficha.get("leituras")).hasSize(2);
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ? AND pelo_vendedor AND dispositivo_id = ?",
                acerto, usuarios.aparelhoDe(vendedor))).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ? AND NOT pelo_vendedor AND lida_por = ?",
                acerto, usuarios.dona().id())).isEqualTo(1);
    }

    @Test
    void quantidadeZeroOuAcimaDoLimiteOuEstadoDesconhecidoERecusada() throws Exception {
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 0, "estadoPeca", "BOA"))
                .andExpect(status().isBadRequest());
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 10_001, "estadoPeca", "BOA"))
                .andExpect(status().isBadRequest());
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "NOVA"))
                .andExpect(status().isBadRequest());
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isZero();
    }

    @Test
    void acertoDesistidoNaoRecebeLeitura() throws Exception {
        postar(ACERTOS_DA_DONA + "/" + acerto + "/desistir", dona(), mapa("id", UUID.randomUUID(), "motivo", "Engano."))
                .andExpect(status().isCreated());
        postar(leituras(acerto), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "BOA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("ACERTO_NAO_ESTA_EM_ANDAMENTO"));
        postar(ACERTOS_DO_VENDEDOR + "/" + acerto + "/leituras", como(vendedor),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "BOA")).andExpect(status().isConflict());
    }

    @Test
    void vendedorNaoLeNoAcertoDeOutro() throws Exception {
        UsuarioDeTeste outro = usuarios.criarVendedor();
        postar(ACERTOS_DO_VENDEDOR + "/" + acerto + "/leituras", como(outro),
                mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1, "estadoPeca", "BOA")).andExpect(status().isNotFound());
        postar(leituras(UUID.randomUUID()), dona(), mapa("id", UUID.randomUUID(), "produtoId", peca, "quantidade", 1,
                "estadoPeca", "BOA")).andExpect(status().isNotFound());
    }
}

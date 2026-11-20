package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** As categorias do caixa (tarefa 7.7; ADR-0012, item 11): criar, nome único, renomear, tipo fixo, sistema intocável, inativar e reativar. */
class CategoriasDoCaixaTest extends ApoioDasContasAPagar {

    static final String CATEGORIAS = "/api/dona/caixa/categorias";

    private String nomeNovo() {
        return "Categoria " + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode criar(String nome, String tipo) throws Exception {
        return corpoDe(postar(CATEGORIAS, mapa("id", UUID.randomUUID(), "nome", nome, "tipo", tipo)).andExpect(status().isCreated()));
    }

    @Test
    void criaComNomeETipoEListaComAsDoSistema() throws Exception {
        String nome = nomeNovo();
        JsonNode c = criar("  " + nome + "  ", "SAIDA");
        assertThat(c.get("nome").asString()).isEqualTo(nome);
        assertThat(c.get("tipo").asString()).isEqualTo("SAIDA");
        assertThat(c.get("ativa").asBoolean()).isTrue();
        assertThat(c.get("sistema").asBoolean()).isFalse();
        JsonNode lista = corpoDe(ler(CATEGORIAS).andExpect(status().isOk()));
        assertThat(lista.valueStream().map(x -> x.get("nome").asString())).contains(nome, "Pagamento a fornecedor", "Repasse do representante",
                "Pagamento ao representante");
        assertThat(lista.valueStream().filter(x -> x.get("sistema").asBoolean()).count()).isGreaterThanOrEqualTo(3);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'CATEGORIA_DO_CAIXA_CRIADA' AND registro_id = ?",
                UUID.fromString(c.get("id").asString()))).isEqualTo(1);
    }

    @Test
    void nomeRepetidoSemDiferenciarMaiusculasNemEspacosERecusado() throws Exception {
        String nome = nomeNovo();
        criar(nome, "ENTRADA");
        postar(CATEGORIAS, mapa("id", UUID.randomUUID(), "nome", "  " + nome.toUpperCase() + " ", "tipo", "SAIDA")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CATEGORIA_DO_CAIXA_JA_EXISTE"));
    }

    @Test
    void validaNomeETipo() throws Exception {
        postar(CATEGORIAS, mapa("id", UUID.randomUUID(), "nome", "  ", "tipo", "SAIDA")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("nome"));
        postar(CATEGORIAS, mapa("id", UUID.randomUUID(), "nome", "x".repeat(81), "tipo", "SAIDA")).andExpect(status().isBadRequest());
        postar(CATEGORIAS, mapa("id", UUID.randomUUID(), "nome", nomeNovo(), "tipo", "CHEQUE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("tipo"));
        postar(CATEGORIAS, mapa("id", UUID.randomUUID(), "nome", nomeNovo())).andExpect(status().isBadRequest());
    }

    @Test
    void reenvioIgualDa200EOMesmoIdComOutrosDadosDa409() throws Exception {
        UUID id = UUID.randomUUID();
        String nome = nomeNovo();
        postar(CATEGORIAS, mapa("id", id, "nome", nome, "tipo", "ENTRADA")).andExpect(status().isCreated());
        postar(CATEGORIAS, mapa("id", id, "nome", nome, "tipo", "ENTRADA")).andExpect(status().isOk());
        postar(CATEGORIAS, mapa("id", id, "nome", nome, "tipo", "SAIDA")).andExpect(status().isConflict());
        postar(CATEGORIAS, mapa("id", id, "nome", nomeNovo(), "tipo", "ENTRADA")).andExpect(status().isConflict());
        assertThat(contar("SELECT count(*) FROM corona.categorias_caixa WHERE id = ?", id)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions renomear(UUID id, String nome) throws Exception {
        return mvc.perform(put(CATEGORIAS + "/" + id).with(dona()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapa("nome", nome))));
    }

    @Test
    void renomeiaOTipoNuncaMudaENomeRepetidoNaoPassa() throws Exception {
        JsonNode a = criar(nomeNovo(), "SAIDA");
        UUID idA = UUID.fromString(a.get("id").asString());
        String novo = nomeNovo();
        renomear(idA, novo).andExpect(status().isOk()).andExpect(jsonPath("$.nome").value(novo)).andExpect(jsonPath("$.tipo").value("SAIDA"));
        String outro = nomeNovo();
        criar(outro, "ENTRADA");
        renomear(idA, outro.toLowerCase()).andExpect(status().isConflict());
        renomear(idA, " ").andExpect(status().isBadRequest());
        renomear(UUID.randomUUID(), nomeNovo()).andExpect(status().isNotFound());
        // O tipo não tem rota para mudar: o corpo do PUT só leva o nome.
        assertThat(jdbc.queryForObject("SELECT tipo FROM corona.categorias_caixa WHERE id = ?", String.class, idA)).isEqualTo("SAIDA");
    }

    @Test
    void categoriaDoSistemaNaoMudaNemInativa() throws Exception {
        UUID sistema = jdbc.queryForObject("SELECT id FROM corona.categorias_caixa WHERE nome = 'Pagamento a fornecedor'", UUID.class);
        renomear(sistema, "Outro nome").andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CATEGORIA_DO_CAIXA_DO_SISTEMA"));
        postar(CATEGORIAS + "/" + sistema + "/inativar", null).andExpect(status().isConflict());
        postar(CATEGORIAS + "/" + sistema + "/reativar", null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT ativo FROM corona.categorias_caixa WHERE id = ?", Boolean.class, sistema)).isTrue();
    }

    @Test
    void inativaEReativaSemPerderOHistorico() throws Exception {
        UUID id = UUID.fromString(criar(nomeNovo(), "SAIDA").get("id").asString());
        postar(CATEGORIAS + "/" + id + "/inativar", null).andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(false));
        // Repetir não muda nada.
        postar(CATEGORIAS + "/" + id + "/inativar", null).andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(false));
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'CATEGORIA_DO_CAIXA_INATIVADA' AND registro_id = ?", id)).isEqualTo(1);
        postar(CATEGORIAS + "/" + id + "/reativar", null).andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(true));
        postar(CATEGORIAS + "/" + UUID.randomUUID() + "/inativar", null).andExpect(status().isNotFound());
    }
}

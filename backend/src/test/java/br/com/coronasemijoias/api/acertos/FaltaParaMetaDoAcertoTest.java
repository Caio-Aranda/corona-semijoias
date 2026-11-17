package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Quanto falta para a meta na prévia do acerto da dona (tarefa 8.11; ADR-0012, item 5). A cotação do exemplo: 1 Gs = R$ 0,0009.
 * Uma venda de R$ 90,00 = 90 / 0,0009 = 100.000 Gs na cotação da venda. O Real do que falta usa a cotação da prévia (a mais
 * recente), como na prévia do vendedor. Nenhuma regra muda: só a resposta ganha os dois campos.
 */
class FaltaParaMetaDoAcertoTest extends ApoioDoFechamento {

    private UsuarioDeTeste vendedor;
    private UUID cliente;

    @BeforeEach
    void umVendedor() throws Exception {
        vendedor = usuarios.criarVendedor();
        cliente = cliente(vendedor);
    }

    /** Uma maleta de 3 peças de R$ 90,00, com ou sem a venda de 1, e o acerto em andamento contado (sem falta nem sobra). */
    private UUID acertoComMeta(int meta, boolean vender) throws Exception {
        configurarComissao(meta, 30, 40);
        UUID peca = peca("90.00", 20);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 3);
        if (vender) {
            vender(vendedor, maleta, cliente, peca, 1, "90.00");
        }
        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, peca, vender ? 2 : 3, "BOA");
        return acerto;
    }

    private void confere(JsonNode previa, long guarani, String reais) {
        assertThat(previa.get("faltaParaMetaGuarani").asLong()).isEqualTo(guarani);
        assertThat(new BigDecimal(previa.get("faltaParaMetaReais").asString())).isEqualByComparingTo(reais);
    }

    private static Map<String, Object> pedidoVazio() {
        return mapa("id", UUID.randomUUID(), "faltas", List.of(), "defeitos", List.of(), "cobrancas", List.of(),
                "pendenciasAntigas", List.of(), "liquidoMostrado", "0.00");
    }

    @Test
    void abaixoDaMetaFaltaADiferencaEmGuaraniEEmReal() throws Exception {
        // Meta 700.000, vendido 100.000: faltam 600.000 Gs = R$ 540,00.
        UUID acerto = acertoComMeta(700_000, true);
        JsonNode previa = previa(acerto);
        assertThat(previa.get("vendidoParaMetaGuarani").asLong()).isEqualTo(100_000L);
        confere(previa, 600_000, "540.00");
        confere(corpoDe(postar(ACERTOS_DA_DONA + "/" + acerto + "/fechamento/previa", dona(), pedidoVazio()).andExpect(status().isOk())),
                600_000, "540.00");
    }

    @Test
    void exatamenteNaMetaNaoFaltaNada() throws Exception {
        confere(previa(acertoComMeta(100_000, true)), 0, "0.00");
    }

    @Test
    void acimaDaMetaNuncaFicaNegativo() throws Exception {
        confere(previa(acertoComMeta(50_000, true)), 0, "0.00");
    }

    @Test
    void semVendaFaltaAMetaInteira() throws Exception {
        // 700.000 x 0,0009 = R$ 630,00.
        confere(previa(acertoComMeta(700_000, false)), 700_000, "630.00");
    }

    /** Uma fórmula só (§13, item 2): a prévia do vendedor, antes do acerto, e a da dona, depois, dão o mesmo. */
    @Test
    void aPreviaDoVendedorEADaDonaDaoOMesmoQueFalta() throws Exception {
        configurarComissao(700_000, 30, 40);
        UUID peca = peca("90.00", 20);
        UUID maleta = maletaConferida(vendedor, semana(), peca, 3);
        vender(vendedor, maleta, cliente, peca, 1, "90.00");
        JsonNode doVendedor = corpoDe(ler("/api/vendedor/comissao/previa", como(vendedor)).andExpect(status().isOk()));
        UUID acerto = iniciar(vendedor, null);
        bipar(acerto, peca, 2, "BOA");
        JsonNode daDona = previa(acerto);
        assertThat(daDona.get("faltaParaMetaGuarani").asLong()).isEqualTo(doVendedor.get("faltaParaMetaGuarani").asLong());
        assertThat(new BigDecimal(daDona.get("faltaParaMetaReais").asString()))
                .isEqualByComparingTo(doVendedor.get("faltaParaMetaReais").asString());
        confere(daDona, 600_000, "540.00");
    }

    /** O 409 de decisões desatualizadas (o líquido mostrado não é o de agora) leva a prévia nova, com os dois campos. */
    @Test
    void oConflitoDeDecisoesLevaAPreviaNovaComOQueFalta() throws Exception {
        UUID acerto = acertoComMeta(700_000, true);
        JsonNode corpo = corpoDe(fechar(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "1.00"))
                .andExpect(status().isConflict()));
        confere(corpo.get("previa"), 600_000, "540.00");
    }

    @Test
    void oAcertoFechadoNaoGanhaOCampo() throws Exception {
        UUID acerto = acertoComMeta(700_000, true);
        // 90,00 vendidos, 30% de comissão (27,00): líquido 63,00.
        fechar(acerto, pedidoDeFechamento(UUID.randomUUID(), List.of(), List.of(), "63.00")).andExpect(status().isCreated());
        JsonNode fechado = previa(acerto);
        assertThat(fechado.has("faltaParaMetaGuarani")).isFalse();
        assertThat(fechado.has("faltaParaMetaReais")).isFalse();
    }

    // --- permissões das duas rotas da prévia ---

    @Test
    void asDuasRotasDaPreviaExigemADona() throws Exception {
        UUID acerto = acertoComMeta(700_000, true);
        String rotaGet = ACERTOS_DA_DONA + "/" + acerto + "/fechamento";
        String rotaPost = rotaGet + "/previa";
        ler(rotaGet, como(vendedor)).andExpect(status().isForbidden());
        postar(rotaPost, como(vendedor), pedidoVazio()).andExpect(status().isForbidden());
        mvc.perform(get(rotaGet)).andExpect(status().isUnauthorized());
        mvc.perform(post(rotaPost).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }
}

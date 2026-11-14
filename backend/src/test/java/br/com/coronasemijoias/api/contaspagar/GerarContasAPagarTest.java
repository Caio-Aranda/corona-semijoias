package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Gerar as contas a pagar de uma compra (tarefa 7.7; ADR-0012, item 12; condição C10): as parcelas só pelo {@code Dinheiro} e
 * somando o total da compra, os vencimentos de mês em mês, as recusas e a idempotência por UUID.
 */
class GerarContasAPagarTest extends ApoioDasContasAPagar {

    @Test
    void cemEmTresParcelasFechaExatoEVenceDeMesEmMesComFimDeMes() throws Exception {
        UUID compra = compra(fornecedor(), "100.00");
        JsonNode r = corpoDe(gerar(compra, UUID.randomUUID(), 3, LocalDate.of(2027, 1, 31)).andExpect(status().isCreated()));
        assertThat(r).hasSize(3);
        assertThat(r.get(0).get("valor").asString()).isEqualTo("33.33");
        assertThat(r.get(1).get("valor").asString()).isEqualTo("33.33");
        assertThat(r.get(2).get("valor").asString()).isEqualTo("33.34");
        assertThat(r.get(0).get("vencimento").asString()).isEqualTo("2027-01-31");
        assertThat(r.get(1).get("vencimento").asString()).isEqualTo("2027-02-28");
        assertThat(r.get(2).get("vencimento").asString()).isEqualTo("2027-03-31");
        BigDecimal soma = r.valueStream().map(c -> new BigDecimal(c.get("valor").asString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(soma).isEqualByComparingTo("100.00");
        assertThat(r.get(0).get("situacao").asString()).isEqualTo("ABERTA");
        assertThat(r.get(0).get("saldo").asString()).isEqualTo("33.33");
        assertThat(contar("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ?", compra)).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'CONTAS_A_PAGAR_GERADAS' AND registro_id = ?", compra)).isEqualTo(1);
    }

    @Test
    void umaParcelaFicaComOTotal() throws Exception {
        UUID compra = compra(fornecedor(), "80.50");
        JsonNode r = corpoDe(gerar(compra, UUID.randomUUID(), 1, LocalDate.of(2027, 5, 10)).andExpect(status().isCreated()));
        assertThat(r).hasSize(1);
        assertThat(r.get(0).get("valor").asString()).isEqualTo("80.50");
    }

    @Test
    void zeroEVinteECincoParcelasSaoRecusadas() throws Exception {
        UUID compra = compra(fornecedor(), "100.00");
        for (Integer n : new Integer[] {0, 25, -1}) {
            gerar(compra, UUID.randomUUID(), n, LocalDate.of(2027, 1, 10)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos[0].campo").value("parcelas"));
        }
        gerar(compra, UUID.randomUUID(), null, LocalDate.of(2027, 1, 10)).andExpect(status().isBadRequest());
        gerar(compra, UUID.randomUUID(), 2, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("primeiroVencimento"));
        assertThat(contar("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ?", compra)).isZero();
    }

    @Test
    void valorPequenoDemaisParaTantasParcelasERecusado() throws Exception {
        UUID compra = compra(fornecedor(), "0.02");
        gerar(compra, UUID.randomUUID(), 3, LocalDate.of(2027, 1, 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos[0].campo").value("parcelas"));
        assertThat(contar("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ?", compra)).isZero();
    }

    @Test
    void compraDesfeitaOuSemValorOuInexistenteERecusada() throws Exception {
        UUID desfeita = compra(fornecedor(), "50.00");
        postar(COMPRAS + "/" + desfeita + "/desfazer", mapa("id", UUID.randomUUID(), "motivo", "Lançada errado")).andExpect(status().isCreated());
        gerar(desfeita, UUID.randomUUID(), 2, LocalDate.of(2027, 1, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("COMPRA_JA_DESFEITA"));

        UUID semValor = UUID.randomUUID();
        jdbc.update("INSERT INTO corona.compras (id, fornecedor_id, data_compra, total, usuario_id) VALUES (?, ?, current_date, 0, ?)", semValor,
                fornecedor(), usuarios.dona().id());
        gerar(semValor, UUID.randomUUID(), 1, LocalDate.of(2027, 1, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("COMPRA_SEM_VALOR_A_PAGAR"));

        gerar(UUID.randomUUID(), UUID.randomUUID(), 1, LocalDate.of(2027, 1, 10)).andExpect(status().isNotFound());
    }

    @Test
    void reenvioIgualDa200ConteudoDiferenteOuOutroIdDa409() throws Exception {
        UUID compra = compra(fornecedor(), "90.00");
        UUID id = UUID.randomUUID();
        JsonNode primeira = corpoDe(gerar(compra, id, 3, LocalDate.of(2027, 1, 10)).andExpect(status().isCreated()));
        JsonNode igual = corpoDe(gerar(compra, id, 3, LocalDate.of(2027, 1, 10)).andExpect(status().isOk()));
        assertThat(igual.get(0).get("id").asString()).isEqualTo(primeira.get(0).get("id").asString());
        gerar(compra, id, 2, LocalDate.of(2027, 1, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONTAS_A_PAGAR_JA_GERADAS"));
        gerar(compra, UUID.randomUUID(), 3, LocalDate.of(2027, 1, 10)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CONTAS_A_PAGAR_JA_GERADAS"));
        assertThat(contar("SELECT count(*) FROM corona.contas_pagar WHERE compra_id = ?", compra)).isEqualTo(3);
    }

    @Test
    void listaDaCompraEListaGeralComFiltros() throws Exception {
        UUID f = fornecedor();
        UUID compra = compra(f, "60.00");
        List<UUID> contas = contasDe(compra, 2, LocalDate.of(2020, 3, 10));
        assertThat(corpoDe(ler(COMPRAS + "/" + compra + "/contas-a-pagar").andExpect(status().isOk()))).hasSize(2);
        // Venceu há muito tempo e ainda está em aberto: VENCIDA (calculada, nunca gravada).
        JsonNode lista = corpoDe(ler(CONTAS + "?fornecedorId=" + f + "&situacao=VENCIDA").andExpect(status().isOk()));
        assertThat(lista.get("total").asInt()).isEqualTo(2);
        assertThat(lista.get("itens").get(0).get("situacao").asString()).isEqualTo("VENCIDA");
        assertThat(jdbc.queryForObject("SELECT status FROM corona.contas_pagar WHERE id = ?", String.class, contas.get(0))).isEqualTo("ABERTA");
        assertThat(corpoDe(ler(CONTAS + "?fornecedorId=" + f + "&situacao=ABERTA").andExpect(status().isOk())).get("total").asInt()).isZero();
        assertThat(corpoDe(ler(CONTAS + "?fornecedorId=" + f + "&vencimentoDe=2020-04-01&vencimentoAte=2020-04-30")
                .andExpect(status().isOk())).get("total").asInt()).isEqualTo(1);
        ler(CONTAS + "?situacao=QUALQUER").andExpect(status().isBadRequest());
        ler(CONTAS + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(corpoDe(ler(CONTAS + "/" + contas.get(0)).andExpect(status().isOk())).get("baixas")).isEmpty();
    }
}

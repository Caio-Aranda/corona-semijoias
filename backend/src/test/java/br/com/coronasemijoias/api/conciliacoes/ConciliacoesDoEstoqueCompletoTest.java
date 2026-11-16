package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Diferenca;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * C11 e C12 (tarefa 8.3) sobre a massa do estoque completo (veja {@link MassaDoEstoqueCompleto} para a conta à mão): as onze
 * zeradas antes e depois do acerto, e em cada regra um teste que estraga o dado de propósito e prova que a diferença aparece
 * (a consulta é a MESMA de produção; o estrago roda no dono das tabelas, sem gatilhos, e é desfeito).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConciliacoesDoEstoqueCompletoTest extends MassaDoEstoqueCompleto {

    private static final List<String> TODAS = ConciliacoesDaFase7.CHAVES;

    private static Optional<Diferenca> de(List<Diferenca> achadas, UUID referencia) {
        return achadas.stream().filter(d -> referencia.equals(d.referencia())).findFirst();
    }

    private static void ehIgualA(BigDecimal valor, int esperado) {
        assertThat(valor).as("valor").isEqualByComparingTo(BigDecimal.valueOf(esperado));
    }

    private void tudoCertoNaMassa(String quando) {
        for (String chave : TODAS) {
            assertThat(daMassa(diferencasDe(chave))).as(chave + " " + quando).isEmpty();
        }
    }

    // --- antes do acerto ---

    @Test
    @Order(1)
    void antesDoAcertoAsContasAMaoBatemEAsOnzeDaoTudoCerto() {
        assertThat(estoqueDaLoja(pecaA)).isEqualTo(3);
        assertThat(estoqueDaLoja(pecaB)).isEqualTo(3);
        assertThat(contar("SELECT sum(qtd_enviada + coalesce((SELECT sum(quantidade) FROM corona.movimentos_remessa m WHERE m.remessa_id = i.remessa_id"
                + " AND m.produto_id = i.produto_id), 0)) FROM corona.itens_remessa i WHERE i.remessa_id = ? AND i.produto_id = ?", maletaM1, pecaA))
                .isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM corona.movimentos_remessa WHERE remessa_id = ?", maletaM3)).isZero();
        tudoCertoNaMassa("antes do acerto");
    }

    @Test
    @Order(2)
    void aRotaDevolveSoAC11ESoAC12ComTudoCertoNaMassa() throws Exception {
        for (String chave : List.of("C11", "C12")) {
            JsonNode r = corpoDe(ler("/api/dona/conciliacoes?chave=" + chave, dona()).andExpect(status().isOk()));
            assertThat(r.get("conferencias").size()).isEqualTo(1);
            JsonNode c = r.get("conferencias").get(0);
            assertThat(c.get("chave").asString()).isEqualTo(chave);
            assertThat(c.get("conferidos").asLong()).isPositive();
            assertThat(c.get("titulo").asString()).isNotBlank();
        }
    }

    @Test
    @Order(3)
    void c11AcusaACompraMaiorQueOQueEntrouNoEstoqueEAC7NaoVe() throws Exception {
        // O movimento de entrada da compra K1 (A) guarda 9 e o atalho da peça também: a C7 (lançamento contra lançamento) não vê;
        // a C11 olha o documento da compra (10) e acusa.
        var estrago = Estrago.de(List.of("movimentacoes_estoque", "produtos"),
                "UPDATE corona.movimentacoes_estoque SET quantidade = quantidade - 1, saldo_depois = saldo_depois - 1 WHERE origem_tipo = 'COMPRA'"
                        + " AND origem_id = '" + compraK1 + "' AND produto_id = '" + pecaA + "'",
                "UPDATE corona.produtos SET estoque_atual = estoque_atual - 1 WHERE id = '" + pecaA + "'");
        var c11 = de(estragarEConferir("C11", estrago), pecaA);
        assertThat(c11).as("C11 acusa a peça A").isPresent();
        assertThat(c11.get().tipo()).isEqualTo("PRODUTO");
        ehIgualA(c11.get().esperado(), 7);
        ehIgualA(c11.get().encontrado(), 6);
        assertThat(c11.get().unidade()).isEqualTo(ConciliacoesDaFase7.Unidade.PECAS);
        assertThat(de(estragarEConferir("C7", estrago), pecaA)).as("a C7 continua quieta").isEmpty();
    }

    @Test
    @Order(4)
    void c11AcusaUmaVendaAlteradaEUmaPecaEscondidaNaLoja() throws Exception {
        var venda = estragarEConferir("C11", Estrago.de(List.of("itens_venda"),
                "UPDATE corona.itens_venda SET quantidade = quantidade + 1 WHERE venda_id = '" + vendaV4 + "' AND produto_id = '" + pecaB + "'"));
        var b = de(venda, pecaB);
        assertThat(b).as("C11 acusa a peça B").isPresent();
        ehIgualA(b.get().esperado(), 3);
        ehIgualA(b.get().encontrado(), 4);

        var achada = estragarEConferir("C11", Estrago.de(List.of("produtos", "movimentacoes_estoque"),
                "UPDATE corona.produtos SET estoque_atual = estoque_atual + 1 WHERE id = '" + pecaB + "'"));
        assertThat(de(achada, pecaB)).as("estoque_atual maior que a conta").isPresent();
    }

    @Test
    @Order(5)
    void c12AcusaEnvioMaiorQueABaixaDoEstoque() throws Exception {
        var achadas = estragarEConferir("C12", Estrago.de(List.of("itens_remessa"),
                "UPDATE corona.itens_remessa SET qtd_enviada = qtd_enviada + 1 WHERE remessa_id = '" + maletaM1 + "' AND produto_id = '" + pecaA + "'"));
        var d = de(achadas, maletaM1);
        assertThat(d).as("C12 acusa a maleta M1").isPresent();
        assertThat(d.get().tipo()).isEqualTo("MALETA");
        assertThat(d.get().descricao()).contains("Anel");
        ehIgualA(d.get().esperado(), 3);
        ehIgualA(d.get().encontrado(), 4);
    }

    @Test
    @Order(6)
    void c12AcusaADevolucaoDoClienteApagadaDaMaleta() throws Exception {
        var achadas = estragarEConferir("C12", Estrago.de(List.of("movimentos_remessa"),
                "DELETE FROM corona.movimentos_remessa WHERE devolucao_id = '" + devolucaoDaTroca + "'"));
        var d = de(achadas, maletaM1);
        assertThat(d).as("C12 acusa a maleta M1").isPresent();
        ehIgualA(d.get().esperado(), 3);
        ehIgualA(d.get().encontrado(), 2);
    }

    @Test
    @Order(7)
    void c12AcusaAjusteDeMaletaSemOrigemENegativoECancelada() throws Exception {
        String inserir = "INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, sync_seq, sync_xid)"
                + " SELECT '" + UUID.randomUUID() + "', r, p, t, q, nextval('corona.sync_seq_global'), pg_current_xact_id() FROM (VALUES ('";
        var ajuste = estragarEConferir("C12", Estrago.de(List.of("movimentos_remessa"), inserir + maletaM2 + "'::uuid, '" + pecaA + "'::uuid, 'AJUSTE', -1)) v(r, p, t, q)"));
        var d = de(ajuste, maletaM2);
        assertThat(d).as("ajuste sem origem").isPresent();
        ehIgualA(d.get().esperado(), 1);
        ehIgualA(d.get().encontrado(), 0);

        var negativa = estragarEConferir("C12", Estrago.de(List.of("movimentos_remessa"), inserir + maletaM2 + "'::uuid, '" + pecaA + "'::uuid, 'PERDA', -9)) v(r, p, t, q)"));
        assertThat(negativa.stream().filter(x -> maletaM2.equals(x.referencia()) && x.descricao().contains("negativo")))
                .as("saldo negativo").hasSize(1);

        var cancelada = estragarEConferir("C12", Estrago.de(List.of("movimentos_remessa"), inserir + maletaM3 + "'::uuid, '" + pecaA + "'::uuid, 'AJUSTE', 1)) v(r, p, t, q)"));
        assertThat(cancelada.stream().filter(x -> maletaM3.equals(x.referencia()) && x.descricao().contains("cancelada")))
                .as("maleta cancelada com peça").hasSize(1);
    }

    // --- depois do acerto ---

    @Test
    @Order(10)
    void depoisDoAcertoAsContasAMaoBatemEAsOnzeDaoTudoCerto() throws Exception {
        fecharOAcertoDoEstoque();
        assertThat(estoqueDaLoja(pecaA)).isEqualTo(7);
        assertThat(estoqueDaLoja(pecaB)).isEqualTo(3);
        assertThat(contar("SELECT count(*) FROM corona.remessas WHERE id IN (?, ?) AND status = 'ACERTADA'", maletaM1, maletaM2)).isEqualTo(2);
        tudoCertoNaMassa("depois do acerto");
    }

    @Test
    @Order(11)
    void c11EAC12AcusamUmaFaltaAlteradaNoAcerto() throws Exception {
        var estrago = Estrago.de(List.of("itens_acerto"),
                "UPDATE corona.itens_acerto SET falta = falta + 1, esperado = esperado + 1 WHERE acerto_id = '" + acertoDoEstoque
                        + "' AND produto_id = '" + pecaA + "' AND remessa_id = '" + maletaM1 + "'");
        var c11 = de(estragarEConferir("C11", estrago), pecaA);
        assertThat(c11).as("C11 acusa a falta").isPresent();
        ehIgualA(c11.get().esperado(), 6);
        ehIgualA(c11.get().encontrado(), 7);
        var c12 = de(estragarEConferir("C12", estrago), maletaM1);
        assertThat(c12).as("C12 acusa a falta").isPresent();
        ehIgualA(c12.get().esperado(), -1);
        ehIgualA(c12.get().encontrado(), 0);
    }
}

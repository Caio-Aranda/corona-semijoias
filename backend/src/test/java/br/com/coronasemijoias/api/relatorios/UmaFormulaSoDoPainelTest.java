package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Uma fórmula só (§13, item 2; tarefa 8.7): cada número do painel sai da mesma consulta que o mostra em outro lugar, e aqui se
 * confere que os dois batem, sobre a mesma massa do {@link PainelSemanalTest} (em julho de 2292, semana nº 9102). Dólar e Guarani
 * nunca entram nestas conferências.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UmaFormulaSoDoPainelTest extends MassaDoPainel {

    private static final String PAINEL = "/api/dona/painel-semanal";
    private static final String FIN = "/api/dona/relatorios/financeiro";

    @BeforeAll
    void massaConhecida() throws Exception {
        montarAMassa("2292-07", 9102);
    }

    @AfterAll
    void tirarAMassa() throws SQLException {
        restaurarAComissao();
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    private JsonNode painel(String... filtros) throws Exception {
        StringBuilder q = new StringBuilder(PAINEL + "?semana=" + semana);
        for (int i = 0; i < filtros.length; i += 2) {
            q.append('&').append(filtros[i]).append('=').append(filtros[i + 1]);
        }
        return corpoDe(ler(q.toString(), dona()).andExpect(status().isOk()));
    }

    private JsonNode relatorio(String rota, String consulta) throws Exception {
        return corpoDe(ler(rota + "?" + consulta + "&tamanho=100", dona()).andExpect(status().isOk()));
    }

    private static JsonNode rodape(JsonNode relatorio, String chave) {
        for (JsonNode t : relatorio.get("totais")) {
            if (t.get("chave").asString().equals(chave)) {
                return t;
            }
        }
        throw new AssertionError("Sem o total " + chave + " em " + relatorio.get("totais"));
    }

    private static void igual(JsonNode valorDoPainel, JsonNode rodape) {
        assertThat(dec(valorDoPainel.get("emReais"))).isEqualByComparingTo(dec(rodape.get("valorEmReais")));
    }

    private static JsonNode linhaDe(JsonNode relatorio, String campo, Object valor) {
        for (JsonNode l : relatorio.get("itens")) {
            if (l.get(campo).asString().equals(valor.toString())) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha " + campo + "=" + valor);
    }

    // --- as linhas acertadas são as do relatório de acertos (7.12) ---

    @Test
    void asLinhasAcertadasSaoAsMesmasDoRelatorioDeAcertos() throws Exception {
        JsonNode relatorio = relatorio(FIN + "/acertos", "semanaId=" + semana);
        assertThat(relatorio.get("itens")).hasSize(2);
        JsonNode p = painel();
        for (var v : List.of(ana, bia)) {
            JsonNode linha = PainelSemanalTest.daLinha(p, v, "ACERTADO");
            JsonNode acerto = linhaDe(relatorio, "id", linha.get("acertoId").asString());
            BigDecimal liquidoDoVendido = dec(acerto.get("vendidoBruto")).subtract(dec(acerto.get("descontos")))
                    .subtract(dec(acerto.get("devolvido")));
            assertThat(dec(linha.get("vendidoLiquido").get("emReais"))).isEqualByComparingTo(liquidoDoVendido);
            assertThat(dec(linha.get("percentualComissao"))).isEqualByComparingTo(dec(acerto.get("percentual")));
            assertThat(dec(linha.get("comissao").get("emReais"))).isEqualByComparingTo(dec(acerto.get("comissao")));
            assertThat(dec(linha.get("liquido").get("emReais"))).isEqualByComparingTo(dec(acerto.get("liquido")));
            assertThat(linha.get("dataDoAcerto").asString()).isEqualTo(acerto.get("fechadoEm").asString());
        }
    }

    @Test
    void oBlocoAcertoEOrodapeDoRelatorioDeAcertosEDePendencias() throws Exception {
        JsonNode relatorio = relatorio(FIN + "/acertos", "semanaId=" + semana);
        JsonNode bloco = painel().get("totais").get("acerto");
        assertThat(bloco.get("quantidade").asInt()).isEqualTo(relatorio.get("total").asInt());
        igual(bloco.get("vendido"), rodape(relatorio, "vendido"));
        igual(bloco.get("descontos"), rodape(relatorio, "descontos"));
        igual(bloco.get("devolvidoPeloCliente"), rodape(relatorio, "devolvido"));
        igual(bloco.get("comissao"), rodape(relatorio, "comissao"));
        igual(bloco.get("faltasCobradas"), rodape(relatorio, "faltas"));
        igual(bloco.get("valorDoAcerto"), rodape(relatorio, "liquido"));
        igual(bloco.get("recebido"), rodape(relatorio, "recebido"));
        igual(bloco.get("ficouDevendo"), rodape(relatorio, "devendo"));
        JsonNode pendencias = relatorio(FIN + "/pendencias", "semanaId=" + semana);
        igual(bloco.get("pendenciasPagas"), rodape(pendencias, "cobradas"));
        igual(bloco.get("pendenciasEmAberto"), rodape(pendencias, "abertas"));
    }

    // --- a comissão prevista é a da prévia do vendedor (7.5) ---

    @Test
    void aComissaoPrevistaDaLinhaEAMesmaDaPreviaDoVendedor() throws Exception {
        JsonNode linha = PainelSemanalTest.daLinha(painel(), cris, "EM_ABERTO");
        JsonNode previa = corpoDe(ler("/api/vendedor/comissao/previa", como(cris)).andExpect(status().isOk()));
        assertThat(dec(linha.get("comissao").get("emReais"))).isEqualByComparingTo(dec(previa.get("comissaoPrevista")));
        assertThat(dec(linha.get("percentualComissao"))).isEqualByComparingTo(dec(previa.get("percentualQueValeriaHoje")));
    }

    // --- a linha em acerto é a prévia do fechamento da dona (7.5) ---

    @Test
    void aLinhaEmAcertoEAPreviaDoFechamentoDaDona() throws Exception {
        JsonNode linha = PainelSemanalTest.daLinha(painel(), dani, "EM_ACERTO");
        JsonNode previa = corpoDe(ler("/api/dona/acertos/" + acertoDani + "/fechamento", dona()).andExpect(status().isOk()));
        assertThat(dec(linha.get("vendidoLiquido").get("emReais"))).isEqualByComparingTo(dec(previa.get("vendidoLiquido")));
        assertThat(dec(linha.get("comissao").get("emReais"))).isEqualByComparingTo(dec(previa.get("comissao")));
        assertThat(dec(linha.get("liquido").get("emReais"))).isEqualByComparingTo(dec(previa.get("liquido")));
        assertThat(dec(linha.get("percentualComissao"))).isEqualByComparingTo(dec(previa.get("percentualComissao")));
    }

    // --- enviado = vendido + devolvido + falta, em peças ---

    @Test
    void enviadoEIgualAVendidoMaisDevolvidoMaisFaltaEmCadaLinhaAcertada() throws Exception {
        JsonNode p = painel();
        for (var v : List.of(ana, bia)) {
            JsonNode l = PainelSemanalTest.daLinha(p, v, "ACERTADO");
            assertThat(l.get("enviadoPecas").asInt())
                    .isEqualTo(l.get("vendidasPecas").asInt() + l.get("devolvidoPecas").asInt() + l.get("emFaltaPecas").asInt());
        }
    }

    @Test
    void enviadoEIgualAVendidoMaisDevolvidoMaisFaltaEmCadaMaletaAcertada() {
        for (UUID maleta : List.of(m1, r1, m2)) {
            assertThat(jdbc.queryForObject("SELECT status FROM corona.remessas WHERE id = ?", String.class, maleta))
                    .isEqualTo("ACERTADA");
            int enviado = contar("SELECT coalesce(sum(qtd_enviada), 0) FROM corona.itens_remessa WHERE remessa_id = ? "
                    + "AND deleted_at IS NULL", maleta);
            int vendido = contar("SELECT coalesce(-sum(quantidade), 0) FROM corona.movimentos_remessa WHERE remessa_id = ? "
                    + "AND tipo IN ('VENDA', 'ESTORNO_VENDA', 'DEVOLUCAO_CLIENTE', 'TROCA_ENTRADA', 'TROCA_SAIDA')", maleta);
            int devolvido = contar("SELECT coalesce(-sum(quantidade), 0) FROM corona.movimentos_remessa WHERE remessa_id = ? "
                    + "AND tipo = 'DEVOLUCAO_DONA'", maleta);
            int falta = contar("SELECT coalesce(-sum(quantidade), 0) FROM corona.movimentos_remessa WHERE remessa_id = ? "
                    + "AND tipo IN ('PERDA', 'AVARIA', 'AJUSTE')", maleta);
            assertThat(enviado).as("maleta %s", maleta).isEqualTo(vendido + devolvido + falta);
        }
    }

    // --- os outros blocos são os rodapés dos relatórios deles ---

    @Test
    void oTotalRecebidoEOrodapeDeRecebimentosPorForma() throws Exception {
        JsonNode r = relatorio(FIN + "/recebimentos-por-forma", "momento=ACERTO&de=" + d(4) + "&ate=" + d(10));
        JsonNode bloco = painel().get("totais").get("recebidoNoAcerto");
        igual(bloco.get("dinheiro"), rodape(r, "DINHEIRO"));
        igual(bloco.get("transferencia"), rodape(r, "PIX"));
        igual(bloco.get("boleto"), rodape(r, "BOLETO"));
        igual(bloco.get("total"), rodape(r, "total"));
    }

    @Test
    void novasMaletasEReposicoesSaoOsRodapesDoRelatorioDeMaletas() throws Exception {
        JsonNode r = relatorio("/api/dona/relatorios/maleta/remessas", "semanaId=" + semana);
        JsonNode t = painel().get("totais");
        for (String[] par : new String[][] {{"novasMaletas", "novas"}, {"reposicoes", "reposicoes"}}) {
            JsonNode rodape = rodape(r, par[1]);
            assertThat(t.get(par[0]).get("quantidade").asLong()).isEqualTo(rodape.get("quantidade").asLong());
            assertThat(t.get(par[0]).get("pecas").asLong()).isEqualTo(rodape.get("pecas").asLong());
            igual(t.get(par[0]).get("valor"), rodape);
        }
    }

    @Test
    void asVendasSaoORodapeDoRelatorioDeVendasNaSemanaEOsDescontosSaoOsDasVendas() throws Exception {
        JsonNode r = relatorio("/api/dona/relatorios/vendas/vendas", "de=" + d(4) + "&ate=" + d(10));
        JsonNode vendas = painel().get("totais").get("vendas");
        assertThat(vendas.get("quantidade").asLong()).isEqualTo(rodape(r, "liquido").get("quantidade").asLong());
        igual(vendas.get("vendido"), rodape(r, "vendido"));
        igual(vendas.get("devolvido"), rodape(r, "devolvido"));
        igual(vendas.get("liquido"), rodape(r, "liquido"));
        BigDecimal descontos = jdbc.queryForObject("""
                SELECT coalesce(sum(desconto), 0) FROM corona.vendas
                 WHERE status = 'CONCLUIDA' AND deleted_at IS NULL
                   AND (vendida_em AT TIME ZONE 'America/Sao_Paulo')::date BETWEEN ?::date AND ?::date""", BigDecimal.class, d(4),
                d(10));
        assertThat(dec(vendas.get("descontos").get("emReais"))).isEqualByComparingTo(descontos);
    }

    @Test
    void trocasECreditosSaoOsRodapesDosRelatoriosDaTarefa610() throws Exception {
        String periodo = "de=" + d(4) + "&ate=" + d(10);
        JsonNode trocas = relatorio("/api/dona/relatorios/devolucoes/trocas", periodo);
        JsonNode creditos = relatorio("/api/dona/relatorios/devolucoes/creditos", periodo);
        JsonNode bloco = painel().get("totais").get("trocas");
        assertThat(bloco.get("trocas").asLong()).isEqualTo(rodape(trocas, "geral").get("quantidade").asLong());
        igual(bloco.get("valorDasTrocas"), rodape(trocas, "geral"));
        igual(bloco.get("diferencaAPagar"), rodape(trocas, "a-pagar"));
        igual(bloco.get("ficouDeCredito"), rodape(trocas, "credito"));
        igual(bloco.get("creditosGerados"), rodape(creditos, "gerados"));
        igual(bloco.get("creditosUsados"), rodape(creditos, "usados"));
        igual(bloco.get("creditosEmAberto"), rodape(creditos, "em-aberto"));
    }

    @Test
    void oBlocoPreviaESomaDasLinhasEmPreviaEOTotalESomaDosBlocos() throws Exception {
        JsonNode p = painel();
        BigDecimal vendido = BigDecimal.ZERO;
        BigDecimal comissao = BigDecimal.ZERO;
        BigDecimal liquido = BigDecimal.ZERO;
        for (JsonNode l : p.get("pedidos")) {
            if (l.get("previa").asBoolean()) {
                vendido = vendido.add(dec(l.get("vendidoLiquido").get("emReais")));
                comissao = comissao.add(dec(l.get("comissao").get("emReais")));
                liquido = liquido.add(dec(l.get("liquido").get("emReais")));
            }
        }
        JsonNode previa = p.get("totais").get("previa");
        assertThat(dec(previa.get("vendido").get("emReais"))).isEqualByComparingTo(vendido);
        assertThat(dec(previa.get("comissaoPrevista").get("emReais"))).isEqualByComparingTo(comissao);
        assertThat(dec(previa.get("liquidoPrevisto").get("emReais"))).isEqualByComparingTo(liquido);

        JsonNode t = p.get("totais");
        BigDecimal enviado = dec(t.get("novasMaletas").get("valor").get("emReais"))
                .add(dec(t.get("reposicoes").get("valor").get("emReais")));
        assertThat(dec(t.get("total").get("enviado").get("emReais"))).isEqualByComparingTo(enviado);
        assertThat(t.get("total").get("maletas").asLong())
                .isEqualTo(t.get("novasMaletas").get("quantidade").asLong() + t.get("reposicoes").get("quantidade").asLong());
        assertThat(t.get("total").get("pecas").asLong())
                .isEqualTo(t.get("novasMaletas").get("pecas").asLong() + t.get("reposicoes").get("pecas").asLong());
        assertThat(dec(t.get("total").get("liquido").get("emReais"))).isEqualByComparingTo(dec(t.get("vendas").get("liquido").get("emReais")));
    }

    @Test
    void osMesmosFiltrosValemParaOsBlocosEParaOsRelatorios() throws Exception {
        JsonNode p = painel("vendedor", ana.vendedorId().toString());
        JsonNode r = relatorio(FIN + "/acertos", "semanaId=" + semana + "&vendedorId=" + ana.vendedorId());
        igual(p.get("totais").get("acerto").get("valorDoAcerto"), rodape(r, "liquido"));
        JsonNode v = relatorio("/api/dona/relatorios/vendas/vendas", "de=" + d(4) + "&ate=" + d(10) + "&vendedorId=" + ana.vendedorId());
        igual(p.get("totais").get("vendas").get("liquido"), rodape(v, "liquido"));
    }
}

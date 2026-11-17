package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * O painel semanal (tarefa 8.7; ADR-0015, item 5), com a massa da semana nº 9101, de 04/06 a 10/06/2292. Cotação: 1 Real = 1.000
 * Guaranis e US$ 1 = R$ 5,00. Meta de 300.000 Gs., 30% abaixo e 40% na meta. Peças: A a 50,00 e B a 25,00.
 *
 * <pre>
 * Ana (praça Norte), acertada acima da meta. M1 em 04/06: 10 x A = 500,00. Reposição R1 em 05/06: 4 x B = 100,00.
 *   S1 6 x A = 300,00; S2 2 x B = 50,00 com desconto de 10,00 = 40,00. Vendido 350,00, descontos 10,00, devolvido 0,
 *   vendido líquido 340,00 = 340.000 Gs. (acima da meta) = 40%: comissão 120,00 + 16,00 = 136,00. Acerto em 09/06: contou 4 x A
 *   e 1 x B, voltam 4 x A + 1 x B = 225,00 (5 peças) e falta 1 x B (25,00), cobrada. Líquido 340,00 - 136,00 + 25,00 = 229,00.
 *   Recebeu 150,00 em dinheiro e 79,00 por Transferência. Peças: enviado 14 = vendido 8 + devolvido 5 + falta 1.
 * Bia (praça Sul), acertada abaixo da meta. M2 em 04/06: 5 x A = 250,00. S3 1 x A = 50,00; S4 2 x A = 100,00 com 1 x A devolvida
 *   pelo cliente (50,00). Vendido 150,00, devolvido 50,00, líquido 100,00 = 100.000 Gs. = 30%: comissão 15,00 + 30,00 - 15,00 =
 *   30,00. Acerto em 10/06: contou 3 x A (volta 150,00), sem falta. Líquido 100,00 - 30,00 = 70,00, recebido por Transferência
 *   (o boleto do fechamento é conta a receber, não recebimento). Peças: enviado 5 = vendido 2 + devolvido 3 + falta 0.
 * Cris (praça Norte), maleta em aberto (prévia). M3 em 06/06: 6 x B = 150,00. S5 4 x B = 100,00; troca de 1 x B por 1 x B (S6,
 *   25,00, diferença 0); 1 x B devolvida em crédito (25,00). Vendido líquido 100,00 + 25,00 - 25,00 - 25,00 = 75,00, 30%: 22,50.
 *   Líquido previsto 75,00 - 22,50 = 52,50 (sem faltas: ainda não contou).
 * Dani (praça Sul), em acerto (prévia do fechamento). M4 em 05/06: 4 x A = 200,00. S7 2 x A = 100,00. Acerto iniciado em 10/06,
 *   contou 1 x A: falta 1 x A (50,00), cobrada pelo padrão da prévia. Vendido líquido 100,00, 30%: 30,00.
 *   Líquido previsto 100,00 - 30,00 + 50,00 = 120,00.
 *
 * Pedidos       Enviado         Devolvido      Vendido  Com%  Comissão  Líquido   Situação
 * Ana           14 pç  600,00    5 pç  225,00   340,00   40     136,00   229,00   ACERTADO
 * Bia            5 pç  250,00    3 pç  150,00   100,00   30      30,00    70,00   ACERTADO
 * Cris           6 pç  150,00    0 pç    0,00    75,00   30      22,50    52,50   EM_ABERTO (prévia)
 * Dani           4 pç  200,00    0 pç    0,00   100,00   30      30,00   120,00   EM_ACERTO (prévia)
 *
 * Novas maletas  4 maletas, 25 peças, 1.100,00        Reposições  1 maleta, 4 peças, 100,00
 * Acerto         2 acertos; vendido 500,00; descontos 10,00; devolvido pelo cliente 50,00; comissão 166,00; faltas cobradas 25,00;
 *                valor do acerto 299,00; recebido 299,00; devendo 0,00; devolvido à loja 8 peças, 375,00; pendências pagas 25,00
 * Prévia         vendido 175,00; comissão prevista 52,50; líquido previsto 172,50
 * Vendas         7 vendas; vendido 715,00; descontos 10,00; devolvido 100,00; líquido 615,00
 * Trocas         1 troca, 25,00; a pagar 0,00; ficou de crédito 0,00; créditos gerados 25,00, usados 0,00, em aberto 25,00
 * Total          5 maletas, 29 peças, 1.200,00; 7 vendas, 715,00, descontos 10,00, líquido 615,00
 * Recebido       Dinheiro 150,00; Transferência 149,00; Boleto 0,00; Total 299,00
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PainelSemanalTest extends MassaDoPainel {

    private static final String ROTA = "/api/dona/painel-semanal";

    @Autowired
    private ConsultasDoPainelSemanal consultas;

    @BeforeAll
    void massaConhecida() throws Exception {
        montarAMassa("2292-06", 9101);
    }

    @AfterAll
    void tirarAMassa() throws SQLException {
        restaurarAComissao();
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    // --- apoio ---

    private JsonNode painel(String... filtros) throws Exception {
        StringBuilder q = new StringBuilder(ROTA + "?semana=" + semana);
        for (int i = 0; i < filtros.length; i += 2) {
            q.append('&').append(filtros[i]).append('=').append(filtros[i + 1]);
        }
        return corpoDe(ler(q.toString(), dona()).andExpect(status().isOk()));
    }

    static JsonNode daLinha(JsonNode painel, UsuarioDeTeste v, String situacao) {
        for (JsonNode l : painel.get("pedidos")) {
            if (l.get("vendedorId").asString().equals(v.vendedorId().toString()) && l.get("situacao").asString().equals(situacao)) {
                return l;
            }
        }
        throw new AssertionError("Sem a linha de " + v.nome() + " como " + situacao + " em " + painel.get("pedidos"));
    }

    static void reais(JsonNode valor, String esperado) {
        assertThat(dec(valor.get("emReais"))).isEqualByComparingTo(esperado);
    }

    private static void linha(JsonNode l, int enviadoPecas, String enviado, int devolvidoPecas, String devolvido, String vendido,
            String percentual, String comissao, String liquido, boolean previa) {
        assertThat(l.get("enviadoPecas").asInt()).isEqualTo(enviadoPecas);
        reais(l.get("enviado"), enviado);
        assertThat(l.get("devolvidoPecas").asInt()).isEqualTo(devolvidoPecas);
        reais(l.get("devolvido"), devolvido);
        reais(l.get("vendidoLiquido"), vendido);
        assertThat(dec(l.get("percentualComissao"))).isEqualByComparingTo(percentual);
        reais(l.get("comissao"), comissao);
        reais(l.get("liquido"), liquido);
        assertThat(l.get("previa").asBoolean()).isEqualTo(previa);
    }

    // --- os pedidos, linha a linha ---

    @Test
    void cadaLinhaDosPedidosBateComAContaFeitaAMao() throws Exception {
        JsonNode p = painel();
        assertThat(p.get("pedidos")).hasSize(4);
        assertThat(p.get("semana").get("numero").asInt()).isEqualTo(9101);

        JsonNode a = daLinha(p, ana, "ACERTADO");
        linha(a, 14, "600.00", 5, "225.00", "340.00", "40", "136.00", "229.00", false);
        assertThat(a.get("acertoId").asString()).isEqualTo(acertoAna.toString());
        assertThat(a.get("vendidasPecas").asInt()).isEqualTo(8);
        assertThat(a.get("emFaltaPecas").asInt()).isEqualTo(1);
        assertThat(a.get("maletas")).hasSize(2);
        assertThat(a.get("dataDoAcerto").asString()).startsWith(d(9));

        JsonNode b = daLinha(p, bia, "ACERTADO");
        linha(b, 5, "250.00", 3, "150.00", "100.00", "30", "30.00", "70.00", false);
        assertThat(b.get("vendidasPecas").asInt()).isEqualTo(2);
        assertThat(b.get("emFaltaPecas").asInt()).isZero();
        assertThat(b.get("maletas")).hasSize(1);

        JsonNode c = daLinha(p, cris, "EM_ABERTO");
        linha(c, 6, "150.00", 0, "0.00", "75.00", "30", "22.50", "52.50", true);
        assertThat(c.get("acertoId").isNull()).isTrue();
        assertThat(c.get("dataDoAcerto").isNull()).isTrue();
        assertThat(c.get("maletas")).hasSize(1);
        assertThat(c.get("maletas").get(0).get("id").asString()).isEqualTo(m3.toString());

        JsonNode d = daLinha(p, dani, "EM_ACERTO");
        linha(d, 4, "200.00", 0, "0.00", "100.00", "30", "30.00", "120.00", true);
        assertThat(d.get("acertoId").asString()).isEqualTo(acertoDani.toString());
        assertThat(d.get("dataDoAcerto").isNull()).isFalse();
    }

    @Test
    void asTresMoedasSaemDaCotacaoDoDiaDeCadaLancamento() throws Exception {
        JsonNode a = daLinha(painel(), ana, "ACERTADO");
        // Enviado 600,00 pela cotação do envio; vendido líquido 340,00 e líquido 229,00 pela cotação gravada no acerto.
        assertThat(dec(a.get("enviado").get("emDolar"))).isEqualByComparingTo("120.00");
        assertThat(dec(a.get("enviado").get("emGuarani"))).isEqualByComparingTo("600000");
        assertThat(dec(a.get("vendidoLiquido").get("emDolar"))).isEqualByComparingTo("68.00");
        assertThat(dec(a.get("vendidoLiquido").get("emGuarani"))).isEqualByComparingTo("340000");
        assertThat(dec(a.get("liquido").get("emGuarani"))).isEqualByComparingTo("229000");
        assertThat(a.get("dataDaCotacao").asString()).isEqualTo(d(4));
        JsonNode c = daLinha(painel(), cris, "EM_ABERTO");
        assertThat(dec(c.get("comissao").get("emGuarani"))).isEqualByComparingTo("22500");
        assertThat(painel().get("cotacaoDaPrevia").get("data").asString()).isEqualTo(d(4));
    }

    // --- os totais, bloco a bloco ---

    @Test
    void cadaBlocoDosTotaisBateComAContaFeitaAMao() throws Exception {
        JsonNode t = painel().get("totais");

        assertThat(t.get("novasMaletas").get("quantidade").asInt()).isEqualTo(4);
        assertThat(t.get("novasMaletas").get("pecas").asInt()).isEqualTo(25);
        reais(t.get("novasMaletas").get("valor"), "1100.00");
        assertThat(t.get("reposicoes").get("quantidade").asInt()).isEqualTo(1);
        assertThat(t.get("reposicoes").get("pecas").asInt()).isEqualTo(4);
        reais(t.get("reposicoes").get("valor"), "100.00");

        JsonNode acerto = t.get("acerto");
        assertThat(acerto.get("quantidade").asInt()).isEqualTo(2);
        reais(acerto.get("vendido"), "500.00");
        reais(acerto.get("descontos"), "10.00");
        reais(acerto.get("devolvidoPeloCliente"), "50.00");
        reais(acerto.get("comissao"), "166.00");
        reais(acerto.get("faltasCobradas"), "25.00");
        reais(acerto.get("valorDoAcerto"), "299.00");
        reais(acerto.get("recebido"), "299.00");
        reais(acerto.get("ficouDevendo"), "0.00");
        assertThat(acerto.get("devolvidoALojaPecas").asInt()).isEqualTo(8);
        reais(acerto.get("devolvidoALoja"), "375.00");
        reais(acerto.get("pendenciasPagas"), "25.00");
        reais(acerto.get("pendenciasEmAberto"), "0.00");

        JsonNode previa = t.get("previa");
        assertThat(previa.get("linhas").asInt()).isEqualTo(2);
        reais(previa.get("vendido"), "175.00");
        reais(previa.get("comissaoPrevista"), "52.50");
        reais(previa.get("liquidoPrevisto"), "172.50");

        JsonNode vendas = t.get("vendas");
        assertThat(vendas.get("quantidade").asInt()).isEqualTo(7);
        reais(vendas.get("vendido"), "715.00");
        reais(vendas.get("descontos"), "10.00");
        reais(vendas.get("devolvido"), "100.00");
        reais(vendas.get("liquido"), "615.00");

        JsonNode trocas = t.get("trocas");
        assertThat(trocas.get("trocas").asInt()).isEqualTo(1);
        reais(trocas.get("valorDasTrocas"), "25.00");
        reais(trocas.get("diferencaAPagar"), "0.00");
        reais(trocas.get("ficouDeCredito"), "0.00");
        reais(trocas.get("creditosGerados"), "25.00");
        reais(trocas.get("creditosUsados"), "0.00");
        reais(trocas.get("creditosEmAberto"), "25.00");

        JsonNode total = t.get("total");
        assertThat(total.get("maletas").asInt()).isEqualTo(5);
        assertThat(total.get("pecas").asInt()).isEqualTo(29);
        reais(total.get("enviado"), "1200.00");
        assertThat(total.get("vendas").asInt()).isEqualTo(7);
        reais(total.get("vendido"), "715.00");
        reais(total.get("descontos"), "10.00");
        reais(total.get("liquido"), "615.00");

        JsonNode recebido = t.get("recebidoNoAcerto");
        reais(recebido.get("dinheiro"), "150.00");
        reais(recebido.get("transferencia"), "149.00");
        reais(recebido.get("boleto"), "0.00");
        reais(recebido.get("total"), "299.00");
    }

    @Test
    void cadaBlocoDizDeOndeSaiParaAbrirOsRegistros() throws Exception {
        JsonNode t = painel().get("totais");
        for (String bloco : List.of("novasMaletas", "reposicoes", "acerto", "vendas", "recebidoNoAcerto")) {
            JsonNode origem = t.get(bloco).get("origem");
            assertThat(origem.get("rota").asString()).as(bloco).startsWith("/api/dona/relatorios/");
        }
        JsonNode acerto = t.get("acerto").get("origem");
        assertThat(acerto.get("filtros").get("semanaId").asString()).isEqualTo(semana.toString());
        JsonNode vendas = t.get("vendas").get("origem");
        assertThat(vendas.get("filtros").get("de").asString()).isEqualTo(d(4));
        assertThat(vendas.get("filtros").get("ate").asString()).isEqualTo(d(10));
    }

    // --- a prévia "até agora" ---

    @Test
    void aPreviaDizQueIncluiOQueNaoFoiAcertadoDeSemanasAnteriores() throws Exception {
        JsonNode p = painel();
        assertThat(p.get("previaIncluiSemanasAnteriores").asBoolean()).isTrue();
        assertThat(p.get("avisoDaPrevia").asString()).contains("semanas anteriores");
    }

    // --- os filtros ---

    @Test
    void filtroDeVendedorMostraSoOVendedorEOsBlocosDele() throws Exception {
        JsonNode p = painel("vendedor", ana.vendedorId().toString());
        assertThat(p.get("pedidos")).hasSize(1);
        assertThat(p.get("pedidos").get(0).get("vendedorId").asString()).isEqualTo(ana.vendedorId().toString());
        JsonNode t = p.get("totais");
        assertThat(t.get("novasMaletas").get("quantidade").asInt()).isEqualTo(1);
        reais(t.get("novasMaletas").get("valor"), "500.00");
        assertThat(t.get("reposicoes").get("quantidade").asInt()).isEqualTo(1);
        assertThat(t.get("acerto").get("quantidade").asInt()).isEqualTo(1);
        reais(t.get("acerto").get("valorDoAcerto"), "229.00");
        assertThat(t.get("previa").get("linhas").asInt()).isZero();
        assertThat(t.get("vendas").get("quantidade").asInt()).isEqualTo(2);
        reais(t.get("vendas").get("liquido"), "340.00");
        reais(t.get("recebidoNoAcerto").get("total"), "229.00");
    }

    @Test
    void filtroDePracaMostraOsVendedoresDaPraca() throws Exception {
        JsonNode p = painel("praca", pracaNorte.toString());
        assertThat(p.get("pedidos")).hasSize(2);
        daLinha(p, ana, "ACERTADO");
        daLinha(p, cris, "EM_ABERTO");
        JsonNode t = p.get("totais");
        assertThat(t.get("novasMaletas").get("quantidade").asInt()).isEqualTo(2);
        reais(t.get("novasMaletas").get("valor"), "650.00");
        assertThat(t.get("acerto").get("quantidade").asInt()).isEqualTo(1);
        reais(t.get("acerto").get("valorDoAcerto"), "229.00");
        reais(t.get("previa").get("liquidoPrevisto"), "52.50");
        reais(t.get("vendas").get("liquido"), "415.00");
        reais(t.get("trocas").get("creditosGerados"), "25.00");
        reais(t.get("recebidoNoAcerto").get("total"), "229.00");
        JsonNode sul = painel("praca", pracaSul.toString());
        assertThat(sul.get("pedidos")).hasSize(2);
        reais(sul.get("totais").get("trocas").get("creditosGerados"), "0.00");
        reais(sul.get("totais").get("recebidoNoAcerto").get("total"), "70.00");
    }

    @Test
    void filtroDeSituacaoSoMudaAsLinhasEABlocoDaPrevia() throws Exception {
        JsonNode acertado = painel("situacao", "ACERTADO");
        assertThat(acertado.get("pedidos")).hasSize(2);
        assertThat(acertado.get("totais").get("previa").get("linhas").asInt()).isZero();
        reais(acertado.get("totais").get("acerto").get("valorDoAcerto"), "299.00");
        reais(acertado.get("totais").get("vendas").get("liquido"), "615.00");

        JsonNode emAcerto = painel("situacao", "EM_ACERTO");
        assertThat(emAcerto.get("pedidos")).hasSize(1);
        daLinha(emAcerto, dani, "EM_ACERTO");
        reais(emAcerto.get("totais").get("previa").get("liquidoPrevisto"), "120.00");

        JsonNode emAberto = painel("situacao", "EM_ABERTO");
        assertThat(emAberto.get("pedidos")).hasSize(1);
        daLinha(emAberto, cris, "EM_ABERTO");
        reais(emAberto.get("totais").get("previa").get("liquidoPrevisto"), "52.50");
    }

    @Test
    void semanaSemNadaVeioVaziaEComOsBlocosZerados() throws Exception {
        UUID vazia = criarSemana(9150, "2292-09-04", "2292-09-10");
        JsonNode p = corpoDe(ler(ROTA + "?semana=" + vazia, dona()).andExpect(status().isOk()));
        assertThat(p.get("pedidos")).isEmpty();
        JsonNode t = p.get("totais");
        assertThat(t.get("novasMaletas").get("quantidade").asInt()).isZero();
        reais(t.get("acerto").get("valorDoAcerto"), "0.00");
        reais(t.get("previa").get("liquidoPrevisto"), "0.00");
        reais(t.get("vendas").get("liquido"), "0.00");
        reais(t.get("recebidoNoAcerto").get("total"), "0.00");
        assertThat(t.get("total").get("maletas").asInt()).isZero();
    }

    @Test
    void semanaOuSituacaoDesconhecidaDa400EmPortuguesSimples() throws Exception {
        var semanaErrada = ler(ROTA + "?semana=" + UUID.randomUUID(), dona()).andExpect(status().isBadRequest());
        assertThat(semanaErrada.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Escolha a semana.");
        var situacaoErrada = ler(ROTA + "?semana=" + semana + "&situacao=QUALQUER", dona()).andExpect(status().isBadRequest());
        assertThat(situacaoErrada.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Escolha em aberto, em acerto ou acertado.");
    }

    @Test
    void semASemanaUsaAQueTemHojeEDaOMesmoQueEscolhendoEla() throws Exception {
        // O relógio parado num dia da semana (o serviço não passa pela rede, então o token não vence).
        relogio.fixar(dia(d(8)));
        try {
            var p = consultas.painel(new ConsultasDoPainelSemanal.Filtros(null, null, null, null));
            assertThat(p.semana().id()).isEqualTo(semana);
            assertThat(p.pedidos()).hasSize(4);
        } finally {
            relogio.reiniciar();
        }
        relogio.fixar(dia("2292-12-31"));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> consultas.painel(new ConsultasDoPainelSemanal.Filtros(null, null, null, null)))
                    .isInstanceOf(br.com.coronasemijoias.api.comum.erros.ErroDaApi.class)
                    .satisfies(e -> assertThat(json.writeValueAsString(((br.com.coronasemijoias.api.comum.erros.ErroDaApi) e)
                            .getBody())).contains("Escolha a semana."));
        } finally {
            relogio.reiniciar();
        }
    }

    // --- o que nunca aparece ---

    @Test
    void nenhumCampoFalaDeEntradaChequeOuRemarcadas() throws Exception {
        String texto = json.writeValueAsString(painel()).toLowerCase();
        assertThat(texto).doesNotContain("entrada").doesNotContain("cheque").doesNotContain("remarc").doesNotContain("custo");
    }

    // --- a prévia não grava ---

    @Test
    void aRotaNaoGravaNada() throws Exception {
        List<String> tabelas = List.of("acertos", "comissoes", "pendencias_acerto", "remessas", "movimentos_remessa",
                "lancamentos_caixa", "acerto_eventos", "itens_acerto", "leituras_acerto", "recebimentos_vendedor", "contas_receber",
                "auditoria");
        List<String> antes = retrato(tabelas);
        painel();
        painel("situacao", "EM_ABERTO");
        painel("praca", pracaSul.toString());
        assertThat(retrato(tabelas)).isEqualTo(antes);
        assertThat(contar("SELECT count(*) FROM corona.acertos WHERE semana_id = ? AND status = 'EM_ACERTO'", semana)).isEqualTo(1);
    }

    private List<String> retrato(List<String> tabelas) {
        List<String> r = new ArrayList<>();
        for (String t : tabelas) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM corona." + t, Integer.class);
            boolean temData = contar("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'corona' "
                    + "AND table_name = ? AND column_name = 'updated_at'", t) > 0;
            Object ultimo = temData ? jdbc.queryForObject("SELECT max(updated_at)::text FROM corona." + t, String.class) : "";
            r.add(t + "=" + n + "@" + ultimo);
        }
        return r;
    }
}

package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * O recebimento do representante digitado em Dólar ou Guarani (pedido de 23/11/2026; plano em
 * docs/planos/recebimento-em-tres-moedas.md): o servidor converte pela cotação, grava em Real e guarda a moeda e o valor
 * digitado só como informação. Avulso (cotação do dia do lançamento) e no fechamento do acerto (cotação do acerto).
 *
 * <p>A cotação de hoje é gravada só nos testes que precisam dela e sai depois de cada teste, para o teste "sem cotação"
 * ver o banco sem nenhuma cotação até hoje (a do exemplo do ADR-0012 é de 2291).
 */
class RecebimentoEmOutraMoedaTest extends ApoioDoFechamento {

    private static final String RECEBIMENTOS = "/api/dona/recebimentos-vendedor";
    /** As taxas de venda de 06/11/2026 (ADR-0006, item 14), gravadas como a cotação de hoje. */
    private static final String USD = "5.50130000";
    private static final String PYG = "0.00089500";

    private UsuarioDeTeste vendedor;
    private UUID maleta;
    private UUID peca;
    private UUID cliente;

    @BeforeEach
    void preparar() throws Exception {
        vendedor = usuarios.criarVendedor();
        peca = peca("50.00", 30);
        maleta = maletaConferida(vendedor, semana(), peca, 20);
        cliente = cliente(vendedor);
    }

    @AfterEach
    void tirarACotacaoDeHoje() throws SQLException {
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
            CotacoesDaMassa.tirarDias(con, List.of(hoje().toString()));
        }
    }

    private static LocalDate hoje() {
        return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    }

    private UUID cotacaoDeHoje() {
        return CotacoesDaMassa.gravar(jdbc, hoje().toString(), USD, PYG);
    }

    /** Fecha por SQL um acerto em que o vendedor ficou devendo R$ 35,00 numa conta. Devolve a conta. */
    private UUID contaDe35() throws Exception {
        UUID venda = vender(vendedor, maleta, cliente, peca, 1, "50.00");
        UUID acerto = acertoNoBanco(vendedor, semana());
        UUID evento = eventoNoBanco(acerto, vendedor, "VENDA", venda, venda, "50.00");
        comissaoNoBanco(acerto, vendedor, venda, evento, "COMISSAO", "50.00", "30", "15.00", null);
        return transacao.execute(s -> {
            UUID conta = contaDoAcertoNoBanco(acerto, new BigDecimal("35.00"), 1, 1, hoje().plusDays(10), null);
            fecharComDinheiro(acerto, "0", "0", "35.00", "0", null);
            return conta;
        });
    }

    private ResultActions receber(Object... pares) throws Exception {
        Map<String, Object> corpo = mapa("vendedorId", vendedor.vendedorId(), "forma", "DINHEIRO");
        for (int i = 0; i < pares.length; i += 2) {
            corpo.put((String) pares[i], pares[i + 1]);
        }
        return postar(RECEBIMENTOS, dona(), corpo);
    }

    private Map<String, Object> linha(UUID id) {
        return jdbc.queryForMap("SELECT valor, moeda, valor_na_moeda, cotacao_id FROM corona.recebimentos_vendedor WHERE id = ?", id);
    }

    private String situacao(UUID conta) {
        return jdbc.queryForObject("SELECT status || ':' || valor_pago FROM corona.contas_receber WHERE id = ?", String.class, conta);
    }

    private BigDecimal caixaDe(UUID recebimento) {
        return jdbc.queryForObject("SELECT valor FROM corona.lancamentos_caixa WHERE origem_id = ?", BigDecimal.class, recebimento);
    }

    private int recebimentosDoVendedor() {
        return contar("SELECT count(*) FROM corona.recebimentos_vendedor WHERE vendedor_id = ?", vendedor.vendedorId());
    }

    // --- recebimento avulso ---

    @Test
    void guaraniGravaORealConvertidoEGuardaAMoedaEOValorDigitado() throws Exception {
        UUID conta = contaDe35();
        UUID cotacao = cotacaoDeHoje();
        UUID id = UUID.randomUUID();
        // Gs. 20.000 x 0,000895 = 17,90.
        JsonNode r = corpoDe(receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoData", hoje().toString())
                .andExpect(status().isCreated()));
        Map<String, Object> l = linha(id);
        assertThat((BigDecimal) l.get("valor")).isEqualByComparingTo("17.90");
        assertThat(l.get("moeda")).isEqualTo("GUARANI");
        assertThat((BigDecimal) l.get("valor_na_moeda")).isEqualByComparingTo("20000");
        assertThat(l.get("cotacao_id")).isEqualTo(cotacao);
        // O caixa e a conta recebem o Real.
        assertThat(caixaDe(id)).isEqualByComparingTo("17.90");
        assertThat(situacao(conta)).isEqualTo("PARCIAL:17.90");
        // A resposta: o Real, a moeda digitada exata e o Dólar convertido do Real (17,90 / 5,5013 = 3,2538 → 3,25).
        assertThat(r.get("valor").asString()).isEqualTo("17.90");
        assertThat(r.get("moeda").asString()).isEqualTo("GUARANI");
        assertThat(new BigDecimal(r.get("valorNaMoeda").asString())).isEqualByComparingTo("20000");
        assertThat(new BigDecimal(r.get("guarani").asString())).isEqualByComparingTo("20000");
        assertThat(new BigDecimal(r.get("dolar").asString())).isEqualByComparingTo("3.25");
        assertThat(r.get("dataCotacao").asString()).isEqualTo(hoje().toString());
    }

    @Test
    void dolarGravaORealConvertido() throws Exception {
        UUID conta = contaDe35();
        cotacaoDeHoje();
        UUID id = UUID.randomUUID();
        // US$ 5,00 x 5,5013 = 27,5065 → 27,51 (HALF_UP).
        JsonNode r = corpoDe(receber("id", id, "forma", "PIX", "moeda", "DOLAR", "valorNaMoeda", "5.00").andExpect(status().isCreated()));
        assertThat((BigDecimal) linha(id).get("valor")).isEqualByComparingTo("27.51");
        assertThat(situacao(conta)).isEqualTo("PARCIAL:27.51");
        assertThat(new BigDecimal(r.get("dolar").asString())).isEqualByComparingTo("5.00");
        // 27,51 / 0,000895 = 30.737,43 → 30.737.
        assertThat(new BigDecimal(r.get("guarani").asString())).isEqualByComparingTo("30737");
    }

    @Test
    void realContinuaComoAntesEMostraDolarEGuaraniPelaCotacaoDoDia() throws Exception {
        contaDe35();
        cotacaoDeHoje();
        UUID id = UUID.randomUUID();
        JsonNode r = corpoDe(receber("id", id, "valor", "10.00").andExpect(status().isCreated()));
        Map<String, Object> l = linha(id);
        assertThat(l.get("moeda")).isEqualTo("REAL");
        assertThat(l.get("valor_na_moeda")).isNull();
        assertThat(l.get("cotacao_id")).isNull();
        assertThat(r.get("moeda").asString()).isEqualTo("REAL");
        assertThat(r.get("valorNaMoeda").isNull()).isTrue();
        // 10 / 5,5013 = 1,8177 → 1,82; 10 / 0,000895 = 11.173,18 → 11.173.
        assertThat(new BigDecimal(r.get("dolar").asString())).isEqualByComparingTo("1.82");
        assertThat(new BigDecimal(r.get("guarani").asString())).isEqualByComparingTo("11173");
        // "moeda": "REAL" explícito também vale.
        receber("id", UUID.randomUUID(), "valor", "1.00", "moeda", "REAL").andExpect(status().isCreated());
    }

    @Test
    void semCotacaoSoAceitaReal() throws Exception {
        contaDe35();
        // A cotação do exemplo (2291) vale "desde ontem" para o acerto destes testes; aqui o relógio volta antes de qualquer cotação.
        relogio.fixar(java.time.Instant.parse("2000-01-03T15:00:00Z"));
        assertThat(contar("SELECT count(*) FROM corona.cotacoes WHERE vigente_desde <= ?", java.sql.Timestamp.from(relogio.instant())))
                .as("o teste precisa do banco sem cotação vigente").isZero();
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000").andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("SEM_COTACAO_PARA_O_RECEBIMENTO"));
        receber("id", UUID.randomUUID(), "moeda", "DOLAR", "valorNaMoeda", "5.00").andExpect(status().isConflict());
        assertThat(recebimentosDoVendedor()).isZero();
        // Em Real, normal; a lista mostra só o Real.
        JsonNode r = corpoDe(receber("id", UUID.randomUUID(), "valor", "10.00").andExpect(status().isCreated()));
        assertThat(r.get("dolar").isNull()).isTrue();
        assertThat(r.get("guarani").isNull()).isTrue();
    }

    @Test
    void cotacaoDiferenteDaQueATelaMostrouEhRecusada() throws Exception {
        contaDe35();
        cotacaoDeHoje();
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoData", hoje().minusDays(1).toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("COTACAO_DO_RECEBIMENTO_MUDOU"));
        assertThat(recebimentosDoVendedor()).isZero();
    }

    @Test
    void pedidosForaDaRegraSaoRecusadosSemGravarNada() throws Exception {
        contaDe35();
        cotacaoDeHoje();
        // O app nunca manda o valor convertido: Real junto de outra moeda é recusado.
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000", "valor", "17.90").andExpect(status().isBadRequest());
        // Outra moeda sem o valor digitado; Real com valor digitado.
        receber("id", UUID.randomUUID(), "moeda", "GUARANI").andExpect(status().isBadRequest());
        receber("id", UUID.randomUUID(), "moeda", "REAL", "valor", "10.00", "valorNaMoeda", "10.00").andExpect(status().isBadRequest());
        // Moeda que não existe.
        receber("id", UUID.randomUUID(), "moeda", "EURO", "valorNaMoeda", "10.00").andExpect(status().isBadRequest());
        // Guarani com centavos; Dólar com três casas; zero e negativo.
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000.50").andExpect(status().isBadRequest());
        receber("id", UUID.randomUUID(), "moeda", "DOLAR", "valorNaMoeda", "1.005").andExpect(status().isBadRequest());
        receber("id", UUID.randomUUID(), "moeda", "DOLAR", "valorNaMoeda", "0").andExpect(status().isBadRequest());
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "-20000").andExpect(status().isBadRequest());
        // Gs. 5 x 0,000895 = 0,004475: não chega a um centavo.
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "5").andExpect(status().isBadRequest());
        // Acima do que o vendedor deve, depois de convertido: US$ 7,00 = R$ 38,51.
        receber("id", UUID.randomUUID(), "moeda", "DOLAR", "valorNaMoeda", "7.00").andExpect(status().isConflict());
        assertThat(recebimentosDoVendedor()).isZero();
    }

    @Test
    void mesmoIdEMesmoConteudoDa200EOutraMoedaOuOutroValorDa409() throws Exception {
        contaDe35();
        cotacaoDeHoje();
        UUID id = UUID.randomUUID();
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000").andExpect(status().isCreated());
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000").andExpect(status().isOk());
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000.00").andExpect(status().isOk());
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20001").andExpect(status().isConflict());
        receber("id", id, "moeda", "DOLAR", "valorNaMoeda", "3.25").andExpect(status().isConflict());
        receber("id", id, "valor", "17.90").andExpect(status().isConflict());
        assertThat(recebimentosDoVendedor()).isEqualTo(1);
    }

    @Test
    void estornoCopiaAMoedaEOValorDigitadoComOSinalTrocado() throws Exception {
        UUID conta = contaDe35();
        UUID cotacao = cotacaoDeHoje();
        UUID id = UUID.randomUUID();
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000").andExpect(status().isCreated());
        UUID estorno = UUID.randomUUID();
        JsonNode e = corpoDe(postar(RECEBIMENTOS + "/" + id + "/estorno", dona(), mapa("id", estorno, "motivo", "Lancei errado"))
                .andExpect(status().isCreated()));
        Map<String, Object> l = linha(estorno);
        assertThat((BigDecimal) l.get("valor")).isEqualByComparingTo("-17.90");
        assertThat(l.get("moeda")).isEqualTo("GUARANI");
        assertThat((BigDecimal) l.get("valor_na_moeda")).isEqualByComparingTo("-20000");
        assertThat(l.get("cotacao_id")).isEqualTo(cotacao);
        assertThat(situacao(conta)).isEqualTo("ABERTA:0.00");
        assertThat(caixaDe(estorno)).isEqualByComparingTo("-17.90");
        assertThat(new BigDecimal(e.get("valorNaMoeda").asString())).isEqualByComparingTo("-20000");
        assertThat(new BigDecimal(e.get("guarani").asString())).isEqualByComparingTo("-20000");
        // O original nunca mudou.
        assertThat((BigDecimal) linha(id).get("valor_na_moeda")).isEqualByComparingTo("20000");
    }

    @Test
    void aListaTrazAsTresMoedasComADigitadaExata() throws Exception {
        contaDe35();
        cotacaoDeHoje();
        UUID guarani = UUID.randomUUID();
        UUID real = UUID.randomUUID();
        receber("id", guarani, "moeda", "GUARANI", "valorNaMoeda", "10000").andExpect(status().isCreated());
        receber("id", real, "valor", "5.00").andExpect(status().isCreated());
        JsonNode lista = corpoDe(ler(RECEBIMENTOS + "?vendedorId=" + vendedor.vendedorId(), dona()).andExpect(status().isOk()));
        assertThat(lista).hasSize(2);
        for (JsonNode r : lista) {
            if (r.get("id").asString().equals(guarani.toString())) {
                // Gs. 10.000 = R$ 8,95; voltando, 8,95 / 0,000895 = 10.000 (aqui fecha; a regra é mostrar a digitada).
                assertThat(r.get("valor").asString()).isEqualTo("8.95");
                assertThat(new BigDecimal(r.get("guarani").asString())).isEqualByComparingTo("10000");
                assertThat(new BigDecimal(r.get("dolar").asString())).isEqualByComparingTo("1.63");
            } else {
                assertThat(r.get("moeda").asString()).isEqualTo("REAL");
                assertThat(new BigDecimal(r.get("dolar").asString())).isEqualByComparingTo("0.91");
                assertThat(new BigDecimal(r.get("guarani").asString())).isEqualByComparingTo("5587");
            }
        }
    }

    // --- o banco também confere ---

    @Test
    void oBancoRecusaMoedaIncompletaEEstornoQueNaoConfere() throws Exception {
        contaDe35();
        UUID cotacao = cotacaoDeHoje();
        UUID id = UUID.randomUUID();
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000").andExpect(status().isCreated());
        String inserir = """
                INSERT INTO corona.recebimentos_vendedor (id, vendedor_id, momento, aplicado_em, forma, valor, moeda, valor_na_moeda,
                    cotacao_id, motivo, estorna_id) VALUES (?, ?, 'AVULSO', 'CONTAS', 'DINHEIRO', ?::numeric, ?, ?::numeric, ?, ?, ?)""";
        // Real com valor digitado; Guarani sem cotação; Guarani com centavos; sinal trocado.
        for (Object[] errado : List.of(
                new Object[] {"10.00", "REAL", "10.00", null},
                new Object[] {"10.00", "GUARANI", "11173", null},
                new Object[] {"10.00", "GUARANI", "11173.50", cotacao},
                new Object[] {"10.00", "DOLAR", "-1.82", cotacao})) {
            recusadoNoInsert(inserir, UUID.randomUUID(), vendedor.vendedorId(), errado[0], errado[1], errado[2], errado[3], null, null);
        }
        // O estorno precisa desfazer a mesma moeda e o mesmo valor digitado.
        for (Object[] errado : List.of(
                new Object[] {"REAL", null, null},
                new Object[] {"GUARANI", "-19999", cotacao},
                new Object[] {"DOLAR", "-3.25", cotacao})) {
            recusadoNoInsert(inserir, UUID.randomUUID(), vendedor.vendedorId(), "-17.90", errado[0], errado[1], errado[2], "teste", id);
        }
    }

    /** O INSERT é recusado na hora (trava ou gatilho), e não só no COMMIT, onde o caixa também confere. */
    private void recusadoNoInsert(String sql, Object... args) {
        transacao.executeWithoutResult(s -> {
            assertThatThrownBy(() -> jdbc.update(sql, args)).as(java.util.Arrays.toString(args))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            s.setRollbackOnly();
        });
    }

    // --- a cotação da dona no meio do dia (ADR-0006, item 15) ---

    private UUID bcbDeHojeEDonaAgora(String usd, String guaranisPorReal) {
        cotacaoDeHoje();
        return CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), java.time.Instant.now().minusSeconds(60), usd, guaranisPorReal);
    }

    @Test
    void comACotacaoDaDonaOGuaraniDivideEGravaOIdDela() throws Exception {
        contaDe35();
        UUID dona = bcbDeHojeEDonaAgora("5.80", "1350");
        UUID id = UUID.randomUUID();
        // Gs. 20.000 ÷ 1350 = 14,81 (pela taxa de 8 casas daria o mesmo aqui; o caso dos 37.037,04 está no teste da conversão).
        receber("id", id, "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoId", dona.toString()).andExpect(status().isCreated());
        Map<String, Object> l = linha(id);
        assertThat((BigDecimal) l.get("valor")).isEqualByComparingTo("14.81");
        assertThat(l.get("cotacao_id")).isEqualTo(dona);
        // Dólar: US$ 5,00 x 5,80 = 29,00? Passa de 35 - 14,81 = 20,19: recusado por passar do que se deve, nada gravado.
        receber("id", UUID.randomUUID(), "moeda", "DOLAR", "valorNaMoeda", "5.00", "cotacaoId", dona.toString())
                .andExpect(status().isConflict());
        receber("id", UUID.randomUUID(), "moeda", "DOLAR", "valorNaMoeda", "3.00", "cotacaoId", dona.toString())
                .andExpect(status().isCreated());
    }

    @Test
    void asTelasQueMostraramOBancoCentralMasAGoraEAdaDonaRecebem409PeloId() throws Exception {
        contaDe35();
        UUID dona = bcbDeHojeEDonaAgora("5.80", "1350");
        UUID bcb = jdbc.queryForObject(
                "SELECT id FROM corona.cotacoes WHERE origem = 'BANCO_CENTRAL' AND data_cotacao = ?", UUID.class, hoje());
        // Mesma data, outro id: só o id percebe a troca.
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoId", bcb.toString(),
                "cotacaoData", hoje().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("COTACAO_DO_RECEBIMENTO_MUDOU"));
        assertThat(recebimentosDoVendedor()).isZero();
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoId", dona.toString())
                .andExpect(status().isCreated());
    }

    @Test
    void semCotacaoIdAindaVale409PelaDataParaOGestaoAntigo() throws Exception {
        contaDe35();
        bcbDeHojeEDonaAgora("5.80", "1350");
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoData", hoje().minusDays(1).toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("COTACAO_DO_RECEBIMENTO_MUDOU"));
        receber("id", UUID.randomUUID(), "moeda", "GUARANI", "valorNaMoeda", "20000", "cotacaoData", hoje().toString())
                .andExpect(status().isCreated());
    }

    @Test
    void oRecebimentoEmRealMostraDolarEGuaraniPelaCotacaoVigenteNaHoraDele() throws Exception {
        contaDe35();
        bcbDeHojeEDonaAgora("5.80", "1350");
        UUID id = UUID.randomUUID();
        JsonNode r = corpoDe(receber("id", id, "valor", "10.00").andExpect(status().isCreated()));
        assertThat(linha(id).get("cotacao_id")).as("em Real não grava cotação").isNull();
        // R$ 10,00 pela cotação da dona (e não pela do Banco Central de hoje): US$ 1,72 e Gs. 13.500.
        assertThat(new BigDecimal(r.get("dolar").asString())).isEqualByComparingTo("1.72");
        assertThat(new BigDecimal(r.get("guarani").asString())).isEqualByComparingTo("13500");
    }

    // --- no fechamento do acerto: a cotação do acerto ---

    @Test
    void pagouAgoraEmGuaraniNoAcertoConvertePelaCotacaoDoAcerto() throws Exception {
        // Outro vendedor, só com esta maleta (a do vendedor dos outros testes viraria falta no acerto).
        UsuarioDeTeste doAcerto = usuarios.criarVendedor();
        UUID pecaDoAcerto = peca("50.00", 50);
        UUID maletaDoAcerto = maletaConferida(doAcerto, semana(), pecaDoAcerto, 5);
        vender(doAcerto, maletaDoAcerto, cliente(doAcerto), pecaDoAcerto, 1, "50.00");
        UUID acerto = iniciar(doAcerto, null);
        bipar(acerto, pecaDoAcerto, 4, "BOA");
        // A cotação mais recente é a do exemplo do ADR-0012 (2291): 1 Gs = R$ 0,0009; Gs. 10.000 = R$ 9,00.
        UUID id = UUID.randomUUID();
        Map<String, Object> pagamento = mapa("recebimentos", List.of(mapa("id", id, "forma", "DINHEIRO", "moeda", "GUARANI",
                "valorNaMoeda", "10000")), "restante", mapa("parcelas", 1, "primeiroVencimento", hoje().plusDays(30).toString()));
        Map<String, Object> pedido = mapa("id", UUID.randomUUID(), "faltas", List.of(), "defeitos", List.of(), "cobrancas", List.of(),
                "pendenciasAntigas", List.of(), "liquidoMostrado", "35.00", "pagamento", pagamento);
        JsonNode previa = corpoDe(postar(ACERTOS_DA_DONA + "/" + acerto + "/fechamento/previa", dona(), pedido).andExpect(status().isOk()));
        assertThat(new BigDecimal(previa.get("recebidoNoAcerto").asString())).isEqualByComparingTo("9.00");
        assertThat(new BigDecimal(previa.get("ficouDevendo").asString())).isEqualByComparingTo("26.00");
        // Real junto com outra moeda também é recusado no acerto.
        Map<String, Object> errado = new java.util.LinkedHashMap<>(pedido);
        errado.put("id", UUID.randomUUID());
        errado.put("pagamento", mapa("recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "moeda", "GUARANI",
                "valorNaMoeda", "10000", "valor", "9.00"))));
        fechar(acerto, errado).andExpect(status().isBadRequest());

        fechar(acerto, pedido).andExpect(status().isCreated());
        Map<String, Object> l = linha(id);
        assertThat((BigDecimal) l.get("valor")).isEqualByComparingTo("9.00");
        assertThat(l.get("moeda")).isEqualTo("GUARANI");
        assertThat((BigDecimal) l.get("valor_na_moeda")).isEqualByComparingTo("10000");
        assertThat(l.get("cotacao_id")).isEqualTo(cotacaoDoExemplo);
        assertThat(jdbc.queryForObject("SELECT recebido_no_acerto FROM corona.acertos WHERE id = ?", BigDecimal.class, acerto))
                .isEqualByComparingTo("9.00");
        assertThat(caixaDe(id)).isEqualByComparingTo("9.00");
        // A ficha do acerto mostra a moeda e o valor digitado.
        JsonNode ficha = corpoDe(ler(ACERTOS_DA_DONA + "/" + acerto, dona()).andExpect(status().isOk()));
        JsonNode recebimento = ficha.get("fechamento").get("recebimentos").get(0);
        assertThat(recebimento.get("moeda").asString()).isEqualTo("GUARANI");
        assertThat(new BigDecimal(recebimento.get("valorNaMoeda").asString())).isEqualByComparingTo("10000");
        // O reenvio igual devolve 200 (o resumo inclui a moeda).
        fechar(acerto, pedido).andExpect(status().isOk());
    }

    @Test
    void oAcertoUsaAVigenteAgoraEACotacaoQueMudouDa409SoComRecebimentoEmOutraMoeda() throws Exception {
        UsuarioDeTeste doAcerto = usuarios.criarVendedor();
        UUID pecaDoAcerto = peca("50.00", 50);
        UUID maletaDoAcerto = maletaConferida(doAcerto, semana(), pecaDoAcerto, 5);
        vender(doAcerto, maletaDoAcerto, cliente(doAcerto), pecaDoAcerto, 1, "50.00");
        UUID acerto = iniciar(doAcerto, null);
        bipar(acerto, pecaDoAcerto, 4, "BOA");
        // O Banco Central do exemplo é de 2291; a dona lança a dela agora: a do acerto é a vigente agora, a dela.
        UUID dona = CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), java.time.Instant.now().minusSeconds(60), "5.80", "1350");
        Map<String, Object> restante = mapa("parcelas", 1, "primeiroVencimento", hoje().plusDays(30).toString());
        Map<String, Object> emGuarani = mapa("recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "moeda", "GUARANI",
                "valorNaMoeda", "13500")), "restante", restante);
        Map<String, Object> emReal = mapa("recebimentos", List.of(mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "valor", "5.00")),
                "restante", restante);
        Map<String, Object> pedido = mapa("id", UUID.randomUUID(), "faltas", List.of(), "defeitos", List.of(), "cobrancas", List.of(),
                "pendenciasAntigas", List.of(), "liquidoMostrado", "35.00", "pagamento", emGuarani, "cotacaoId", dona);
        JsonNode previa = corpoDe(postar(ACERTOS_DA_DONA + "/" + acerto + "/fechamento/previa", dona(), pedido).andExpect(status().isOk()));
        assertThat(previa.get("cotacaoId").asString()).isEqualTo(dona.toString());
        // Gs. 13.500 ÷ 1350 = R$ 10,00 (e não pelo Banco Central do exemplo).
        assertThat(new BigDecimal(previa.get("recebidoNoAcerto").asString())).isEqualByComparingTo("10.00");

        // A dona lança outra cotação depois da prévia: o fechamento com recebimento em Guarani é recusado, nada é gravado.
        UUID maisNova = CotacoesDaMassa.gravarDaDona(jdbc, usuarios.dona().id(), java.time.Instant.now().minusSeconds(30), "5.90", "1400");
        fechar(acerto, pedido).andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("COTACAO_DO_ACERTO_MUDOU"));
        assertThat(jdbc.queryForObject("SELECT status FROM corona.acertos WHERE id = ?", String.class, acerto)).isEqualTo("EM_ACERTO");

        // Sem recebimento em outra moeda, a mesma cotação velha no pedido fecha normal, com a vigente agora.
        Map<String, Object> soEmReal = new java.util.LinkedHashMap<>(pedido);
        soEmReal.put("id", UUID.randomUUID());
        soEmReal.put("pagamento", emReal);
        fechar(acerto, soEmReal).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT cotacao_id FROM corona.acertos WHERE id = ?", UUID.class, acerto)).isEqualTo(maisNova);
    }

    // --- relatório "Recebimentos por forma" ---

    @Test
    void relatorioDeRecebimentosMostraAMoedaEOValorDigitadoSoForaDoReal() throws Exception {
        contaDe35();
        cotacaoDeHoje();
        UUID emGuarani = UUID.randomUUID();
        UUID emDolar = UUID.randomUUID();
        UUID emReal = UUID.randomUUID();
        receber("id", emGuarani, "moeda", "GUARANI", "valorNaMoeda", "20000").andExpect(status().isCreated());
        receber("id", emDolar, "forma", "PIX", "moeda", "DOLAR", "valorNaMoeda", "2.50").andExpect(status().isCreated());
        receber("id", emReal, "valor", "1.00").andExpect(status().isCreated());
        postar(RECEBIMENTOS + "/" + emGuarani + "/estorno", dona(), mapa("id", UUID.randomUUID(), "motivo", "Lancei errado"))
                .andExpect(status().isCreated());

        JsonNode r = corpoDe(ler("/api/dona/relatorios/financeiro/recebimentos-por-forma?tamanho=100&vendedorId="
                + vendedor.vendedorId(), dona()).andExpect(status().isOk()));
        Map<String, JsonNode> porId = new java.util.HashMap<>();
        List<JsonNode> estornos = new java.util.ArrayList<>();
        for (JsonNode l : r.get("itens")) {
            if (l.get("estorno").asBoolean()) {
                estornos.add(l);
            } else {
                porId.put(l.get("id").asString(), l);
            }
        }
        JsonNode g = porId.get(emGuarani.toString());
        assertThat(g.get("moeda").asString()).isEqualTo("GUARANI");
        assertThat(new BigDecimal(g.get("valorNaMoeda").asString())).isEqualByComparingTo("20000");
        // O Real (17,90) segue sendo o valor que soma; a moeda digitada é só informação.
        assertThat(new BigDecimal(g.get("valorEmReais").asString())).isEqualByComparingTo("17.90");
        JsonNode d = porId.get(emDolar.toString());
        assertThat(d.get("moeda").asString()).isEqualTo("DOLAR");
        assertThat(new BigDecimal(d.get("valorNaMoeda").asString())).isEqualByComparingTo("2.50");
        // Em Real não há valor digitado em outra moeda.
        JsonNode real = porId.get(emReal.toString());
        assertThat(real.get("moeda").asString()).isEqualTo("REAL");
        assertThat(real.get("valorNaMoeda").isNull()).isTrue();
        // O estorno mostra a mesma moeda, com o valor digitado negativo.
        assertThat(estornos).hasSize(1);
        assertThat(estornos.get(0).get("moeda").asString()).isEqualTo("GUARANI");
        assertThat(new BigDecimal(estornos.get(0).get("valorNaMoeda").asString())).isEqualByComparingTo("-20000");
        // O total em Real não mudou de regra: 17,90 + 13,75 + 1,00 - 17,90.
        for (JsonNode t : r.get("totais")) {
            if ("total".equals(t.get("chave").asString())) {
                assertThat(new BigDecimal(t.get("valorEmReais").asString())).isEqualByComparingTo("14.75");
            }
        }
    }
}

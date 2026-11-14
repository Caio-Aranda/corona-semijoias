package br.com.coronasemijoias.api.contaspagar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * O caixa manual (tarefa 7.7; ADR-0012, itens 11 e 15): entrada e saída com categoria, as recusas, a idempotência, o estorno, o
 * saldo e o aviso de caixa negativo (que só avisa, nunca bloqueia).
 */
class CaixaManualTest extends ApoioDasContasAPagar {

    static final String CAIXA = "/api/dona/caixa";
    private static final String DATA = "2020-01-15";

    private UUID categoria(String tipo) throws Exception {
        UUID id = UUID.randomUUID();
        postar(CAIXA + "/categorias", mapa("id", id, "nome", "Cat " + id, "tipo", tipo)).andExpect(status().isCreated());
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions lancar(UUID id, String tipo, String valor, UUID categoria) throws Exception {
        return postar(CAIXA + "/lancamentos", mapa("id", id, "tipo", tipo, "valor", valor, "forma", "DINHEIRO", "categoriaId", categoria,
                "data", DATA, "descricao", "Teste"));
    }

    private BigDecimal saldo() throws Exception {
        return new BigDecimal(corpoDe(ler(CAIXA + "/saldo").andExpect(status().isOk())).get("saldo").asString());
    }

    private boolean temAvisoDeCaixaNegativo() throws Exception {
        return corpoDe(ler("/api/dona/avisos").andExpect(status().isOk())).get("avisos").valueStream()
                .anyMatch(a -> "CAIXA_NEGATIVO".equals(a.get("tipo").asString()));
    }

    @Test
    void entradaESaidaTemOSinalCertoESaldoEASomaDosLancamentos() throws Exception {
        UUID entrada = categoria("ENTRADA");
        UUID saida = categoria("SAIDA");
        BigDecimal antes = saldo();
        JsonNode e = corpoDe(lancar(UUID.randomUUID(), "ENTRADA", "100.00", entrada).andExpect(status().isCreated()));
        assertThat(e.get("valor").asString()).isEqualTo("100.00");
        assertThat(e.get("origemTipo").asString()).isEqualTo("MANUAL");
        assertThat(e.get("dataLancamento").asString()).isEqualTo(DATA);
        JsonNode s = corpoDe(lancar(UUID.randomUUID(), "SAIDA", "30.50", saida).andExpect(status().isCreated()));
        assertThat(s.get("valor").asString()).isEqualTo("-30.50");
        assertThat(saldo()).isEqualByComparingTo(antes.add(new BigDecimal("69.50")));
        BigDecimal soma = jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa", BigDecimal.class);
        assertThat(saldo()).isEqualByComparingTo(soma);
        // O extrato do dia traz os dois, em Real.
        JsonNode extrato = corpoDe(ler(CAIXA + "/lancamentos?de=" + DATA + "&ate=" + DATA).andExpect(status().isOk()));
        assertThat(extrato.get("lancamentos").valueStream().filter(l -> "MANUAL".equals(l.get("origemTipo").asString())).count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void categoriaInativaDoSistemaDeOutroTipoOuInexistenteERecusada() throws Exception {
        UUID entrada = categoria("ENTRADA");
        UUID inativa = categoria("SAIDA");
        postar(CAIXA + "/categorias/" + inativa + "/inativar", null).andExpect(status().isOk());
        UUID sistema = jdbc.queryForObject("SELECT id FROM corona.categorias_caixa WHERE nome = 'Pagamento a fornecedor'", UUID.class);
        lancar(UUID.randomUUID(), "SAIDA", "10.00", inativa).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("categoriaId"));
        lancar(UUID.randomUUID(), "SAIDA", "10.00", sistema).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("categoriaId"));
        lancar(UUID.randomUUID(), "SAIDA", "10.00", entrada).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("categoriaId"));
        lancar(UUID.randomUUID(), "SAIDA", "10.00", UUID.randomUUID()).andExpect(status().isBadRequest());
        lancar(UUID.randomUUID(), "SAIDA", "10.00", null).andExpect(status().isBadRequest());
    }

    @Test
    void validaValorFormaTipoDataEDescricao() throws Exception {
        UUID saida = categoria("SAIDA");
        for (String valor : new String[] {"0", "-1.00", "1.005"}) {
            lancar(UUID.randomUUID(), "SAIDA", valor, saida).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("valor"));
        }
        lancar(UUID.randomUUID(), "TROCA", "1.00", saida).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("tipo"));
        for (String forma : new String[] {"CHEQUE", "CARTAO", ""}) {
            postar(CAIXA + "/lancamentos", mapa("id", UUID.randomUUID(), "tipo", "SAIDA", "valor", "1.00", "forma", forma, "categoriaId", saida,
                    "data", DATA, "descricao", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("forma"));
        }
        LocalDate amanha = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(1);
        postar(CAIXA + "/lancamentos", mapa("id", UUID.randomUUID(), "tipo", "SAIDA", "valor", "1.00", "forma", "PIX", "categoriaId", saida,
                "data", amanha, "descricao", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("data"));
        postar(CAIXA + "/lancamentos", mapa("id", UUID.randomUUID(), "tipo", "SAIDA", "valor", "1.00", "forma", "PIX", "categoriaId", saida,
                "data", DATA, "descricao", " ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("descricao"));
    }

    @Test
    void reenvioIgualDa200EOutroConteudoComOMesmoIdDa409() throws Exception {
        UUID saida = categoria("SAIDA");
        UUID id = UUID.randomUUID();
        lancar(id, "SAIDA", "10.00", saida).andExpect(status().isCreated());
        BigDecimal depoisDoPrimeiro = saldo();
        lancar(id, "SAIDA", "10.00", saida).andExpect(status().isOk());
        assertThat(saldo()).isEqualByComparingTo(depoisDoPrimeiro);
        lancar(id, "SAIDA", "11.00", saida).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LANCAMENTO_DO_CAIXA_JA_REGISTRADO_DIFERENTE"));
        assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE id = ?", id)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'LANCAMENTO_MANUAL_REGISTRADO' AND registro_id = ?", id)).isEqualTo(1);
    }

    @Test
    void estornoManualComMotivoEUnicoENaoSeEstornaEstornoNemAutomatico() throws Exception {
        UUID saida = categoria("SAIDA");
        UUID original = UUID.randomUUID();
        lancar(original, "SAIDA", "25.00", saida).andExpect(status().isCreated());
        BigDecimal comOLancamento = saldo();
        UUID estorno = UUID.randomUUID();
        String rota = CAIXA + "/lancamentos/" + original + "/estorno";
        postar(rota, mapa("id", estorno, "motivo", " ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos[0].campo").value("motivo"));
        JsonNode r = corpoDe(postar(rota, mapa("id", estorno, "motivo", "Lancei duas vezes")).andExpect(status().isCreated()));
        assertThat(r.get("valor").asString()).isEqualTo("25.00");
        assertThat(r.get("estornaId").asString()).isEqualTo(original.toString());
        assertThat(r.get("motivo").asString()).isEqualTo("Lancei duas vezes");
        assertThat(saldo()).isEqualByComparingTo(comOLancamento.add(new BigDecimal("25.00")));
        assertThat(contar("SELECT count(*) FROM corona.auditoria WHERE acao = 'LANCAMENTO_MANUAL_ESTORNADO' AND registro_id = ?", estorno)).isEqualTo(1);

        postar(rota, mapa("id", estorno, "motivo", "Lancei duas vezes")).andExpect(status().isOk());
        postar(rota, mapa("id", UUID.randomUUID(), "motivo", "De novo")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LANCAMENTO_JA_ESTORNADO"));
        postar(CAIXA + "/lancamentos/" + estorno + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LANCAMENTO_DO_CAIXA_E_ESTORNO"));
        postar(CAIXA + "/lancamentos/" + UUID.randomUUID() + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isNotFound());

        // Lançamento automático (pagamento a fornecedor) não se estorna pelo caixa manual.
        UUID conta = contasDe(compra(fornecedor(), "10.00"), 1, LocalDate.of(2027, 6, 10)).get(0);
        UUID pagamento = UUID.randomUUID();
        pagar(conta, pagamento, "10.00", "PIX").andExpect(status().isCreated());
        UUID automatico = jdbc.queryForObject("SELECT id FROM corona.lancamentos_caixa WHERE origem_tipo = 'PAGAMENTO_FORNECEDOR' AND origem_id = ?",
                UUID.class, pagamento);
        postar(CAIXA + "/lancamentos/" + automatico + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("LANCAMENTO_AUTOMATICO"));
    }

    @Test
    void saidaQueDeixaOSaldoNegativoEAceitaEOAvisoApareceESome() throws Exception {
        UUID saida = categoria("SAIDA");
        BigDecimal atual = saldo();
        // A saída passa do saldo: aceita (não bloqueia) e o caixa fica negativo em R$ 5,00.
        BigDecimal valor = atual.max(BigDecimal.ZERO).add(new BigDecimal("5.00"));
        UUID id = UUID.randomUUID();
        lancar(id, "SAIDA", valor.toPlainString(), saida).andExpect(status().isCreated());
        JsonNode s = corpoDe(ler(CAIXA + "/saldo").andExpect(status().isOk()));
        assertThat(s.get("negativo").asBoolean()).isTrue();
        assertThat(saldo().signum()).isNegative();
        assertThat(temAvisoDeCaixaNegativo()).isTrue();
        JsonNode aviso = corpoDe(ler("/api/dona/avisos")).get("avisos").valueStream()
                    .filter(a -> "CAIXA_NEGATIVO".equals(a.get("tipo").asString())).findFirst().orElseThrow();
        assertThat(aviso.get("titulo").asString()).contains("negativo").contains("R$ ");
        // Estornar a saída volta o caixa: o aviso some.
        postar(CAIXA + "/lancamentos/" + id + "/estorno", mapa("id", UUID.randomUUID(), "motivo", "Foi um teste")).andExpect(status().isCreated());
        assertThat(saldo()).isEqualByComparingTo(atual);
        assertThat(temAvisoDeCaixaNegativo()).isEqualTo(atual.signum() < 0);
    }

    @Test
    void semAvisoQuandoOSaldoNaoEstaNegativoEComSaldoDeZero() {
        // A regra do aviso: só saldo menor que zero (R$ 0,00 não avisa).
        assertThat(br.com.coronasemijoias.api.caixa.FonteDeAvisosDoCaixa.aviso(BigDecimal.ZERO)).isEmpty();
        assertThat(br.com.coronasemijoias.api.caixa.FonteDeAvisosDoCaixa.aviso(new BigDecimal("10.00"))).isEmpty();
        assertThat(br.com.coronasemijoias.api.caixa.FonteDeAvisosDoCaixa.aviso(new BigDecimal("-0.01"))).isPresent();
    }
}

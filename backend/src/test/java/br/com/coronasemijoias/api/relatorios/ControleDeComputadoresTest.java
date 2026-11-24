package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Relatório de Sincronizações e computadores (tarefa 8.9, §12 Controle). Massa feita à mão, de dois vendedores só desta classe:
 *
 * <pre>
 * Vendedora A: computador A1 ATIVO (recebeu há 2h, enviou há 1h, 3 aguardando; 2 envios com problema sem decisão e 1 já
 *              descartado e 1 aceito; 1 em quarentena guardada) e computador A0 SUBSTITUIDO (sem problema).
 * Vendedora B: computador B1 BLOQUEADO (1 envio em conflito sem decisão; 1 quarentena já descartada).
 * Total: 3 computadores; 1 ativo, 0 aguardando liberação, 1 bloqueado, 1 substituído; com problema = A1 e B1 = 2.
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControleDeComputadoresTest extends ApoioDoControle {

    UsuarioDeTeste a;
    UsuarioDeTeste b;
    UUID a1;
    UUID a0;
    UUID b1;
    final Instant haUmaHora = Instant.now().minus(1, ChronoUnit.HOURS);
    final Instant haDuasHoras = Instant.now().minus(2, ChronoUnit.HOURS);

    @BeforeAll
    void massa() {
        a = usuarios.criarVendedor();
        b = usuarios.criarVendedor();
        a0 = usuarios.registrarAparelho(a, UUID.randomUUID(), "SUBSTITUIDO");
        a1 = usuarios.registrarAparelho(a, UUID.randomUUID(), "ATIVO");
        b1 = usuarios.registrarAparelho(b, UUID.randomUUID(), "BLOQUEADO");
        jdbc.update("""
                UPDATE corona.dispositivos SET versao_app = '2.3.0', ultimo_pull_em = ?, ultimo_push_em = ?, aguardando_envio = 3
                 WHERE id = ?""", java.sql.Timestamp.from(haDuasHoras), java.sql.Timestamp.from(haUmaHora), a1);
        envio(a1, a, "RECUSADO", null);
        envio(a1, a, "RECUSADO", null);
        envio(a1, a, "RECUSADO", "DESCARTADO");
        envio(a1, a, "ACEITO", null);
        envio(b1, b, "CONFLITO", null);
        quarentena(a1, a, "GUARDADO");
        quarentena(b1, b, "DESCARTADO");
    }

    private void envio(UUID aparelho, UsuarioDeTeste u, String resultado, String resolucao) {
        jdbc.update("""
                INSERT INTO corona.sync_log (id, dispositivo_id, usuario_id, vendedor_id, tipo, formato, resumo, resultado, recebido_em,
                    dados, resolucao, resolvido_em, resolvido_por)
                VALUES (?, ?, ?, ?, 'VENDA', 1, ?, ?, now(), '{}'::jsonb, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END,
                    CASE WHEN ?::text IS NULL THEN NULL ELSE ?::uuid END)""",
                UUID.randomUUID(), aparelho, u.id(), u.vendedorId(), "a".repeat(64), resultado, resolucao, resolucao, resolucao,
                resolucao == null ? null : u.id());
    }

    private void quarentena(UUID aparelho, UsuarioDeTeste u, String situacao) {
        boolean decidida = !situacao.equals("GUARDADO");
        jdbc.update("""
                INSERT INTO corona.envios_em_quarentena (id, dispositivo_id, usuario_id, vendedor_id, tipo, formato, resumo, dados,
                    recebido_em, situacao, decidido_em, decidido_por, motivo_do_descarte)
                VALUES (?, ?, ?, ?, 'VENDA', 1, ?, '{}'::jsonb, now(), ?, CASE WHEN ? THEN now() END,
                    CASE WHEN ? THEN ?::uuid END, CASE WHEN ? THEN 'teste' END)""",
                UUID.randomUUID(), aparelho, u.id(), u.vendedorId(), "b".repeat(64), situacao, decidida, decidida,
                decidida ? u.id() : null, decidida);
    }

    private JsonNode relatorio(String extra) throws Exception {
        // Os dois vendedores desta classe: um relatório por vendedor, somados à mão onde preciso.
        return ler("/computadores?" + extra);
    }

    @Test
    void cadaComputadorMostraUltimoEnvioRecebimentoEProblemas() throws Exception {
        JsonNode r = relatorio("vendedorId=" + a.vendedorId());
        assertThat(r.get("total").asInt()).isEqualTo(2);
        JsonNode ativo = linha(r, a1);
        assertThat(ativo.get("vendedor").asString()).startsWith("Vendedor de vendedor.");
        assertThat(ativo.get("situacao").asString()).isEqualTo("ATIVO");
        assertThat(ativo.get("situacaoNaTela").asString()).isEqualTo("Em uso");
        assertThat(ativo.get("versaoApp").asString()).isEqualTo("2.3.0");
        assertThat(Instant.parse(ativo.get("ultimoEnvioEm").asString()).truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(haUmaHora.truncatedTo(ChronoUnit.SECONDS));
        assertThat(Instant.parse(ativo.get("ultimoRecebimentoEm").asString()).truncatedTo(ChronoUnit.SECONDS))
                .isEqualTo(haDuasHoras.truncatedTo(ChronoUnit.SECONDS));
        assertThat(ativo.get("aguardandoEnvio").asInt()).isEqualTo(3);
        assertThat(ativo.get("enviosComProblema").asInt()).isEqualTo(2);
        assertThat(ativo.get("emQuarentena").asInt()).isEqualTo(1);
        JsonNode antigo = linha(r, a0);
        assertThat(antigo.get("situacaoNaTela").asString()).isEqualTo("Trocado por outro");
        assertThat(antigo.get("enviosComProblema").asInt()).isZero();
        assertThat(antigo.get("emQuarentena").asInt()).isZero();
        assertThat(antigo.get("ultimoEnvioEm").isNull()).isTrue();
    }

    @Test
    void totaisDoRodapeSobreOFiltroInteiro() throws Exception {
        JsonNode a = relatorio("vendedorId=" + this.a.vendedorId());
        assertThat(total(a, "todos").get("quantidade").asInt()).isEqualTo(2);
        assertThat(total(a, "em-uso").get("quantidade").asInt()).isEqualTo(1);
        assertThat(total(a, "com-problema").get("quantidade").asInt()).isEqualTo(1);
        assertThat(total(a, "bloqueados").get("quantidade").asInt()).isZero();
        JsonNode b = relatorio("vendedorId=" + this.b.vendedorId());
        assertThat(total(b, "todos").get("quantidade").asInt()).isEqualTo(1);
        assertThat(total(b, "bloqueados").get("quantidade").asInt()).isEqualTo(1);
        // B tem um envio em conflito sem decisão; a quarentena dele já foi descartada.
        assertThat(total(b, "com-problema").get("quantidade").asInt()).isEqualTo(1);
        assertThat(linha(b, b1).get("enviosComProblema").asInt()).isEqualTo(1);
        assertThat(linha(b, b1).get("emQuarentena").asInt()).isZero();
        assertThat(linha(b, b1).get("situacaoNaTela").asString()).isEqualTo("Bloqueado");
    }

    @Test
    void filtraPorSituacaoEComProblemaEBuscaERecorte() throws Exception {
        String v = "vendedorId=" + a.vendedorId();
        assertThat(relatorio(v + "&situacao=SUBSTITUIDO").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio(v + "&situacao=BLOQUEADO").get("total").asInt()).isZero();
        assertThat(relatorio(v + "&comProblema=SIM").get("total").asInt()).isEqualTo(1);
        assertThat(relatorio(v + "&comProblema=NAO").get("total").asInt()).isEqualTo(1);
        JsonNode recorte = relatorio(v + "&recorte=com-problema");
        assertThat(recorte.get("itens")).hasSize(1);
        assertThat(recorte.get("itens").get(0).get("id").asString()).isEqualTo(a1.toString());
        assertThat(relatorio("busca=" + a.vendedorId() + "x").get("total").asInt()).isZero();
        assertThat(relatorio("busca=vendedor." + a.login().substring(9) + "&vendedorId=" + a.vendedorId()).get("total").asInt())
                .isEqualTo(2);
    }

    @Test
    void periodoPeloUltimoContato() throws Exception {
        String v = "vendedorId=" + a.vendedorId();
        assertThat(relatorio(v + "&de=2000-01-01&ate=2000-01-02").get("total").asInt()).isZero();
        String hoje = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertThat(relatorio(v + "&de=" + hoje + "&ate=" + hoje).get("total").asInt()).isEqualTo(2);
    }

    @Test
    void parametrosInvalidosDao400() throws Exception {
        chamar("/computadores?situacao=QUALQUER").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        chamar("/computadores?comProblema=TALVEZ").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        chamar("/computadores?recorte=nada").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    private static JsonNode linha(JsonNode r, UUID id) {
        for (JsonNode l : r.get("itens")) {
            if (l.get("id").asString().equals(id.toString())) {
                return l;
            }
        }
        throw new AssertionError("Sem o computador " + id);
    }
}

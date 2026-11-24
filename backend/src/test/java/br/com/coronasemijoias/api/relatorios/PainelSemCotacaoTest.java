package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Sem nenhuma cotação (tarefa 8.7): a montagem do fechamento daria 409, mas o painel não pode cair. As linhas acertadas saem
 * normais (os números gravados) e as de prévia saem sem comissão e sem líquido, marcadas "sem cotação". Mesma massa do
 * {@link PainelSemanalTest}, em dezembro de 2292 (semana nº 9103); só a cotação "mais recente" some durante a consulta.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PainelSemCotacaoTest extends MassaDoPainel {

    @MockitoSpyBean
    private CotacaoVigente cotacaoVigente;

    @BeforeAll
    void massaConhecida() throws Exception {
        montarAMassa("2292-12", 9103);
    }

    @AfterAll
    void tirarAMassa() throws SQLException {
        reset(cotacaoVigente);
        restaurarAComissao();
        try (Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    @Test
    void semCotacaoOPainelNaoCaiAsAcertadasSaemNormaisEAsPreviasSemValor() throws Exception {
        doReturn(Optional.empty()).when(cotacaoVigente).maisRecente();
        try {
            JsonNode p = corpoDe(ler("/api/dona/painel-semanal?semana=" + semana, dona()).andExpect(status().isOk()));
            assertThat(p.get("pedidos")).hasSize(4);
            assertThat(p.get("cotacaoDaPrevia").isNull()).isTrue();

            JsonNode daAna = PainelSemanalTest.daLinha(p, ana, "ACERTADO");
            PainelSemanalTest.reais(daAna.get("liquido"), "229.00");
            assertThat(daAna.get("semCotacao").asBoolean()).isFalse();

            JsonNode daCris = PainelSemanalTest.daLinha(p, cris, "EM_ABERTO");
            assertThat(daCris.get("previa").asBoolean()).isTrue();
            assertThat(daCris.get("semCotacao").asBoolean()).isTrue();
            assertThat(daCris.get("comissao").isNull()).isTrue();
            assertThat(daCris.get("liquido").isNull()).isTrue();
            // As maletas continuam aparecendo (o enviado é a preço congelado).
            PainelSemanalTest.reais(daCris.get("enviado"), "150.00");
            JsonNode daDani = PainelSemanalTest.daLinha(p, dani, "EM_ACERTO");
            assertThat(daDani.get("semCotacao").asBoolean()).isTrue();
            assertThat(daDani.get("liquido").isNull()).isTrue();

            JsonNode previa = p.get("totais").get("previa");
            assertThat(previa.get("semCotacao").asBoolean()).isTrue();
            PainelSemanalTest.reais(previa.get("liquidoPrevisto"), "0.00");
            PainelSemanalTest.reais(p.get("totais").get("acerto").get("valorDoAcerto"), "299.00");
        } finally {
            reset(cotacaoVigente);
        }
    }
}

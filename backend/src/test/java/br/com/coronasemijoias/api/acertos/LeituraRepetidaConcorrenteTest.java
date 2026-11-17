package br.com.coronasemijoias.api.acertos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Issue 208: a mesma leitura (mesmo id) gravada por dois pedidos ao mesmo tempo. A conferência "já existe?" não viu a
 * linha do outro pedido e o INSERT bateu na chave. O espião faz essa conferência não ver a linha, de forma
 * determinística, sem depender de corrida: tem de dar 409 {@code LEITURA_JA_REGISTRADA_DIFERENTE}, nunca 500.
 */
class LeituraRepetidaConcorrenteTest extends ApoioDoAcerto {

    @MockitoSpyBean
    private JdbcTemplate espiao;

    @AfterEach
    void soltarOEspiao() {
        reset(espiao);
    }

    @Test
    void chaveDuplicadaNoInsertDa409EmVezDe500() throws Exception {
        UsuarioDeTeste vendedor = usuarios.criarVendedor();
        UUID peca = peca("50.00", 20);
        UUID maleta = maleta(vendedor, peca, 5);
        UUID acerto = iniciar(vendedor, jdbc.queryForObject("SELECT semana_id FROM corona.remessas WHERE id = ?", UUID.class, maleta));
        String url = ACERTOS_DA_DONA + "/" + acerto + "/leituras";
        UUID id = UUID.randomUUID();
        postar(url, dona(), mapa("id", id, "produtoId", peca, "quantidade", 2, "estadoPeca", "BOA")).andExpect(status().isCreated());

        doReturn(List.of()).when(espiao).queryForList(
                argThat((String sql) -> sql != null && sql.contains("FROM corona.leituras_acerto WHERE id = ?")), eq(id));
        postar(url, dona(), mapa("id", id, "produtoId", peca, "quantidade", 3, "estadoPeca", "BOA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("LEITURA_JA_REGISTRADA_DIFERENTE"));
        assertThat(contar("SELECT count(*) FROM corona.leituras_acerto WHERE acerto_id = ?", acerto)).isEqualTo(1);
    }
}

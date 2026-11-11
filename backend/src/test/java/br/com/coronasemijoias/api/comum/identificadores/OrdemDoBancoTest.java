package br.com.coronasemijoias.api.comum.identificadores;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link OrdemDoBanco} põe os ids na mesma ordem do {@code ORDER BY} do PostgreSQL (ADR-0008, item 7). */
class OrdemDoBancoTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void ordenaComoOPostgreSql() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ids.add(UUID.randomUUID());
            ids.add(UuidV7.gerar());
        }
        // Os casos de fronteira do sinal, nos dois "long".
        ids.add(UUID.fromString("7fffffff-ffff-4fff-bfff-ffffffffffff"));
        ids.add(UUID.fromString("80000000-0000-4000-8000-000000000000"));
        ids.add(UUID.fromString("00000000-0000-4000-8000-000000000000"));
        ids.add(UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"));

        List<UUID> peloJava = new ArrayList<>(ids);
        peloJava.sort(OrdemDoBanco.UUIDS);
        String valores = ids.stream().map(id -> "('" + id + "'::uuid)").collect(Collectors.joining(", "));
        List<UUID> peloBanco = jdbc.queryForList("SELECT u FROM (VALUES " + valores + ") AS t(u) ORDER BY u", UUID.class);

        assertThat(peloJava).containsExactlyElementsOf(peloBanco);
    }

    @Test
    void oCompareToDoJavaEhDiferenteEPorIssoNaoServe() {
        UUID comecaCom1 = UUID.fromString("10000000-0000-4000-8000-000000000000");
        UUID comecaCom9 = UUID.fromString("90000000-0000-4000-8000-000000000000");
        assertThat(comecaCom9.compareTo(comecaCom1)).isNegative();
        assertThat(OrdemDoBanco.UUIDS.compare(comecaCom1, comecaCom9)).isNegative();
    }
}

package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** As conciliações só leem (tarefa 7.8): nenhuma tabela muda quando a rota é chamada, e a classe nem sabe escrever. */
class SoLeituraDasConciliacoesTest extends ApoioDasConciliacoes {

    private static final List<String> TABELAS = List.of("vendas", "pagamentos_cliente", "devolucoes", "contas_receber", "baixas_receber",
            "acertos", "acerto_eventos", "comissoes", "pendencias_acerto", "recebimentos_vendedor", "lancamentos_caixa", "contas_pagar",
            "baixas_pagar", "compras", "produtos", "movimentacoes_estoque", "remessas", "itens_remessa", "movimentos_remessa",
            "auditoria");

    private String retrato(String tabela) {
        return jdbc.queryForObject("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '' ORDER BY t::text)), '') FROM corona."
                + tabela + " t", String.class);
    }

    @Test
    void chamarARotaNaoMudaNenhumaTabelaDaFase7NemAAuditoria() throws Exception {
        List<String> antes = new ArrayList<>();
        TABELAS.forEach(t -> antes.add(retrato(t)));
        ler("/api/dona/conciliacoes", dona()).andExpect(status().isOk());
        ler("/api/dona/conciliacoes?chave=C9", dona()).andExpect(status().isOk());
        List<String> depois = new ArrayList<>();
        TABELAS.forEach(t -> depois.add(retrato(t)));
        for (int i = 0; i < TABELAS.size(); i++) {
            assertThat(depois.get(i)).as(TABELAS.get(i)).isEqualTo(antes.get(i));
        }
    }

    @Test
    void todoMetodoPublicoELeituraNumRetratoUnico() {
        int publicos = 0;
        for (Method m : ConciliacoesDaFase7.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                publicos++;
                Transactional t = m.getAnnotation(Transactional.class);
                assertThat(t).as(m.getName() + " é transacional").isNotNull();
                assertThat(t.readOnly()).as(m.getName() + " só lê").isTrue();
                assertThat(t.isolation()).as(m.getName() + " lê um retrato só").isEqualTo(Isolation.REPEATABLE_READ);
            }
        }
        assertThat(publicos).isPositive();
    }

    @Test
    void aClasseSoTemConsultaENenhumTextoQueEscreve() throws Exception {
        String fonte = Files.readString(Path.of("src/main/java/br/com/coronasemijoias/api/conciliacoes/ConciliacoesDaFase7.java"));
        for (String proibido : List.of("\\bINSERT\\b", "\\bUPDATE\\b", "\\bDELETE\\b", "\\bTRUNCATE\\b", "\\bALTER\\b", "\\bDROP\\b",
                "FOR\\s+(NO\\s+KEY\\s+)?(UPDATE|SHARE)", "\\.update\\(", "\\.execute\\(", "\\.batchUpdate\\(", "\\.call\\(")) {
            assertThat(Pattern.compile(proibido, Pattern.CASE_INSENSITIVE).matcher(fonte).find()).as(proibido).isFalse();
        }
        String semAConsultaDoEstoque = fonte.replace("ServicoDeEstoque", "");
        assertThat(semAConsultaDoEstoque).doesNotContain("Servico").doesNotContain("ConversaoDeMoeda").doesNotContain("double ").doesNotContain("float ");
        assertThat(fonte).contains("jdbc.query(");
    }
}

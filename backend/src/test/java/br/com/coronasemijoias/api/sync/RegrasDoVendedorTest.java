package br.com.coronasemijoias.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Convenções do pull do vendedor (ADR-0013, item 5, prova 3; tarefa 5.4): toda sincronizável da aplicação declara a
 * regra do vendedor; toda coluna "de quem é" ({@code vendedor_id}, {@code vendedor_responsavel_id}) tem o banco
 * garantindo que não muda depois de entregue (tabela imutável ou gatilho da V032), ou a tabela é do diretório da loja; e
 * as expansões citam tabelas e colunas que existem.
 */
class RegrasDoVendedorTest extends TesteDeIntegracao {

    private static final Set<String> COLUNAS_DE_PERTENCA = Set.of("vendedor_id", "vendedor_responsavel_id", "remessa_id",
            "venda_id", "conferencia_id", "produto_id");

    @Autowired
    JdbcTemplate jdbc;

    private final RegistroDeTabelas registro = RegistroDeTabelas.daAplicacao();

    @Test
    void todaSincronizavelDaAplicacaoDeclaraARegraDoVendedor() {
        assertThat(registro.sincronizaveis()).allSatisfy(t -> assertThat(t.regra().declarada())
                .as("a tabela %s precisa declarar a regra do vendedor no RegistroDeTabelas", t.nome()).isTrue());
    }

    @Test
    void todaColunaDePertencaNaoMudaDepoisDeEntregue() {
        List<String> semGarantia = new ArrayList<>();
        for (TabelaSincronizavel t : registro.sincronizaveis()) {
            if (!t.regra().diretorio().isEmpty() || "TRUE".equals(t.regra().condicao())) {
                continue; // dado da loja, para todos: nada a "sair"
            }
            List<String> colunas = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns WHERE table_schema = 'corona' AND table_name = ?""",
                    String.class, t.nome());
            boolean temPertenca = colunas.stream().anyMatch(COLUNAS_DE_PERTENCA::contains);
            if (!temPertenca) {
                continue;
            }
            List<String> gatilhos = jdbc.queryForList("""
                    SELECT tgname FROM pg_trigger WHERE tgrelid = ('corona.' || ?)::regclass AND NOT tgisinternal""",
                    String.class, t.nome());
            boolean garantida = gatilhos.contains("corona_recusa_update") || gatilhos.contains("corona_pertenca_nao_muda");
            if (!garantida) {
                semGarantia.add(t.nome());
            }
            if (t.imutavel()) {
                assertThat(gatilhos).as("%s é imutável", t.nome()).contains("corona_recusa_update");
            }
            assertThat(t.regra().pertencaMuda()).as("%s: pertença que muda pede a tabela de saídas (ADR-0013, item 5, regra 2)",
                    t.nome()).isFalse();
        }
        assertThat(semGarantia).as("tabelas com coluna de pertença sem gatilho que a impeça de mudar (V032)").isEmpty();
    }

    @Test
    void asExpansoesCitamTabelasEColunasQueExistem() {
        for (TabelaSincronizavel t : registro.sincronizaveis()) {
            for (RegraDoVendedor.Expansao e : t.regra().expansoes()) {
                TabelaSincronizavel alvo = registro.sincronizavel(e.tabela()).orElseThrow();
                assertThat(alvo.colunasDoPull()).as("expansão de %s para %s", t.nome(), e.tabela()).contains(e.colunaNaTabela());
                assertThat(t.colunasDoPull()).as("expansão de %s", t.nome()).contains(e.colunaNaLinha());
            }
        }
    }

    @Test
    void naDuvidaOFiltroNaoEntregaNada() {
        FiltroPorVendedor filtro = new FiltroPorVendedor(java.util.UUID.randomUUID());
        assertThat(filtro.condicaoPara(new TabelaSincronizavel("sem_regra", List.of("x"), false)))
                .isEqualTo(FiltroDoVendedor.CondicaoSql.NENHUMA_LINHA);
        // As colunas fora do diretório só saem para o vendedor que atende o cliente.
        TabelaSincronizavel clientes = registro.sincronizavel("clientes").orElseThrow();
        assertThat(filtro.expressao(clientes, "nome")).isEqualTo("t.\"nome\"");
        assertThat(filtro.expressao(clientes, "observacoes")).startsWith("CASE WHEN t.\"vendedor_responsavel_id\" = '");
        assertThat(filtro.expressao(clientes, "id")).isEqualTo("t.\"id\"");
    }
}

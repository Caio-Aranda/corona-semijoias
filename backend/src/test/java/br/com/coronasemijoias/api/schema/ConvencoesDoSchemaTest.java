package br.com.coronasemijoias.api.schema;

import static br.com.coronasemijoias.api.testes.Sql.executar;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.sync.RegistroDeTabelas;
import br.com.coronasemijoias.api.sync.TabelaDoServidor;
import br.com.coronasemijoias.api.sync.TabelaSincronizavel;
import br.com.coronasemijoias.api.testes.TabelasDeTeste;
import br.com.coronasemijoias.api.testes.TesteDeIntegracao;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Teste de convenções (ADR-0002): falha o build se alguma tabela do schema {@code corona} sair da
 * regra. O segundo teste prova que o verificador enxerga cada tipo de erro: cria tabelas erradas
 * de propósito dentro de uma transação, confere as mensagens e desfaz tudo (DDL no PostgreSQL é
 * transacional).
 */
class ConvencoesDoSchemaTest extends TesteDeIntegracao {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegistroDeTabelas registro;

    @Test
    void todasAsTabelasSeguemAsConvencoes() {
        List<String> violacoes = VerificadorDeConvencoes.verificar(jdbc, registro, APP_USUARIO);
        assertThat(violacoes)
                .as("Tabelas fora das convenções do ADR-0002 (veja backend/README.md, \"Como criar uma tabela\")")
                .isEmpty();
    }

    @Test
    void oVerificadorReprovaCadaRegraQuebrada() throws Exception {
        try (Connection dono = conectarComoMigracao()) {
            executar(dono, """
                    CREATE TABLE corona.errada_tipos (
                        id uuid PRIMARY KEY, valor real, preco double precision, taxa numeric(10,3),
                        solto numeric, quando timestamp, caixa money, cotacao_solta numeric(18,8))""");
            executar(dono, "GRANT DELETE, TRUNCATE ON corona.errada_tipos TO " + APP_USUARIO);
            executar(dono, "GRANT SELECT ON corona.flyway_schema_history TO " + APP_USUARIO);

            executar(dono, "CREATE TABLE corona.errada_sync (id uuid PRIMARY KEY, texto text, preco_custo numeric(14,2), sync_seq integer)");

            executar(dono, "CREATE TABLE corona.errada_imutavel (id uuid PRIMARY KEY, valor numeric(14,2))");
            executar(dono, "SELECT corona.aplicar_convencoes_sincronizavel('corona.errada_imutavel')");

            executar(dono, "CREATE TABLE corona.errada_desligada (id uuid PRIMARY KEY)");
            executar(dono, "SELECT corona.aplicar_convencoes_sincronizavel('corona.errada_desligada')");
            executar(dono, "ALTER TABLE corona.errada_desligada DISABLE TRIGGER corona_sync_controle");

            executar(dono, "CREATE TABLE corona.errada_servidor (id uuid PRIMARY KEY, sync_xid xid8, updated_at timestamptz)");

            RegistroDeTabelas comAsErradas = TabelasDeTeste.registroDosTestes().com(new RegistroDeTabelas(
                    List.of(
                            new TabelaSincronizavel("errada_sync", List.of("texto", "nao_existe"), false),
                            new TabelaSincronizavel("errada_imutavel", List.of("valor"), true),
                            new TabelaSincronizavel("errada_desligada", List.of(), false),
                            new TabelaSincronizavel("fantasma", List.of(), false)),
                    List.of(new TabelaDoServidor("errada_servidor", false))));

            JdbcTemplate naTransacao = new JdbcTemplate(new SingleConnectionDataSource(dono, true));
            List<String> violacoes = VerificadorDeConvencoes.verificar(naTransacao, comAsErradas, APP_USUARIO);
            dono.rollback();

            assertThat(violacoes).contains(
                    "errada_tipos: coluna valor é real (proibido float: dinheiro é NUMERIC(14,2))",
                    "errada_tipos: coluna preco é double precision (proibido float: dinheiro é NUMERIC(14,2))",
                    "errada_tipos: coluna taxa é numeric(10,3) (numeric só como NUMERIC(14,2) para dinheiro ou NUMERIC(5,2) para porcentagem)",
                    "errada_tipos: coluna solto é numeric (numeric só como NUMERIC(14,2) para dinheiro ou NUMERIC(5,2) para porcentagem)",
                    "errada_tipos: coluna cotacao_solta é numeric(18,8) (numeric(18,8) só em colunas de cotação (ADR-0006))",
                    "errada_tipos: coluna quando é timestamp without time zone (datas sempre com fuso: timestamptz)",
                    "errada_tipos: coluna caixa é money (dinheiro é NUMERIC(14,2))",
                    "errada_tipos: não está em nenhuma das duas listas do RegistroDeTabelas (sincronizável ou só do servidor)",
                    "errada_tipos: falta o CHECK de UUID v4 ou v7 no id (use as funções corona.aplicar_convencoes_*)",
                    "errada_tipos: falta o gatilho que recusa DELETE",
                    "errada_tipos: falta o gatilho que recusa TRUNCATE",
                    "errada_tipos: o usuário da aplicação tem DELETE",
                    "errada_tipos: o usuário da aplicação tem TRUNCATE",
                    "flyway_schema_history: o usuário da aplicação tem acesso ao histórico das migrações",
                    "errada_sync: falta a coluna de controle created_at (timestamp with time zone)",
                    "errada_sync: falta a coluna de controle sync_xid (xid8)",
                    "errada_sync: coluna de controle sync_seq deveria ser bigint NOT NULL, é integer (aceita nulo)",
                    "errada_sync: falta o gatilho de controle corona.controle_sincronizavel (BEFORE INSERT OR UPDATE, por linha, ligado)",
                    "errada_sync: falta o índice (sync_xid, sync_seq) do pull",
                    "errada_sync: a coluna nao_existe do RegistroDeTabelas não existe na tabela",
                    "errada_sync: coluna preco_custo numa tabela sincronizável (custo nunca vai ao app do vendedor; ADR-0003)",
                    "errada_imutavel: é imutável e falta o gatilho que recusa UPDATE",
                    "errada_desligada: falta o gatilho de controle corona.controle_sincronizavel (BEFORE INSERT OR UPDATE, por linha, ligado)",
                    "fantasma: está no RegistroDeTabelas mas não existe no banco",
                    "errada_servidor: é só do servidor e não pode ter a coluna sync_xid",
                    "errada_servidor: falta created_at timestamptz NOT NULL",
                    "errada_servidor: tem updated_at ou version e falta o gatilho de controle corona.controle_do_servidor");
            // As tabelas certas (as de teste e a sync_estado) não aparecem.
            assertThat(violacoes).allMatch(v -> v.startsWith("errada_") || v.startsWith("fantasma:")
                    || v.startsWith("flyway_schema_history:"));
        }
        // Depois do ROLLBACK, nada sobrou.
        assertThat(VerificadorDeConvencoes.verificar(jdbc, registro, APP_USUARIO)).isEmpty();
    }
}

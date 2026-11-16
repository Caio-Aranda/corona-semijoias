package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.conciliacoes.ConferenciaDaRodada.LinhaDeDiferenca;
import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7.Diferenca;
import br.com.coronasemijoias.api.conciliacoes.ServicoDaConferenciaDiaria.Origem;
import br.com.coronasemijoias.api.conciliacoes.ServicoDaConferenciaDiaria.Resultado;
import br.com.coronasemijoias.api.estoque.ServicoDeEstoque;
import br.com.coronasemijoias.api.relatorios.ConferenciasDeDevolucaoETroca;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A rodada da conferência diária (tarefa 8.4) sobre a massa do estoque completo (8.3) e a da 7.8. As treze conferências rodam
 * pelas classes de produção (nada é copiado); o banco é dividido entre as classes de teste, então a rodada olha o banco inteiro e
 * os testes só olham as linhas cujas referências são desta massa. Uma conferência de teste (chave TESTE) entra na rodada só
 * quando o teste liga {@link #extra}: serve para forçar um erro ou segurar a trava.
 */
class ConferenciaDiariaTest extends MassaDoEstoqueCompleto {

    private static final List<String> TREZE = List.of("ESTOQUE", "DEVOLUCAO_E_TROCA", "C1", "C2", "C3", "C4", "C5", "C7", "C8", "C9",
            "C10", "C11", "C12");
    private static final List<String> TABELAS = List.of("vendas", "pagamentos_cliente", "devolucoes", "contas_receber", "baixas_receber",
            "acertos", "acerto_eventos", "comissoes", "pendencias_acerto", "recebimentos_vendedor", "lancamentos_caixa", "contas_pagar",
            "baixas_pagar", "compras", "itens_compra", "itens_venda", "itens_devolucao", "itens_acerto", "inventarios", "itens_inventario",
            "produtos", "movimentacoes_estoque", "remessas", "itens_remessa", "movimentos_remessa", "auditoria", "usuarios", "configuracoes",
            "semanas", "fechamentos_semana");

    /** A conferência de teste: nula = não entra na rodada. */
    static volatile Supplier<List<LinhaDeDiferenca>> extra;

    @TestConfiguration
    static class ConferenciasDeTeste {

        @Bean
        @Primary
        CatalogoDasConferencias catalogoDeTeste(ConciliacoesDaFase7 fase7, ServicoDeEstoque estoque, ConferenciasDeDevolucaoETroca devolucao) {
            return new CatalogoDasConferencias(fase7, estoque, devolucao) {
                @Override
                public List<ConferenciaDaRodada> todas() {
                    List<ConferenciaDaRodada> todas = new ArrayList<>(super.todas());
                    Supplier<List<LinhaDeDiferenca>> conferencia = extra;
                    if (conferencia != null) {
                        todas.add(CatalogoDasConferencias.de("TESTE", conferencia));
                    }
                    return todas;
                }
            };
        }
    }

    @Autowired
    ServicoDaConferenciaDiaria servico;

    @AfterEach
    void tirarAConferenciaDeTeste() {
        extra = null;
    }

    // --- apoio ---

    private Map<String, Object> rodada(UUID id) {
        return jdbc.queryForMap("SELECT * FROM corona.rodadas_conferencia WHERE id = ?", id);
    }

    private List<String> texto(Object array) throws Exception {
        return List.of((String[]) ((java.sql.Array) array).getArray());
    }

    private List<Map<String, Object>> diferencas(UUID rodada) {
        return jdbc.queryForList("SELECT * FROM corona.diferencas_conferencia WHERE rodada_id = ? ORDER BY ordem", rodada);
    }

    private List<Map<String, Object>> daMassa(UUID rodada, boolean soDaMassa) {
        Set<String> meus = idsDaMassa().stream().map(UUID::toString).collect(Collectors.toSet());
        return diferencas(rodada).stream().filter(d -> !soDaMassa || meus.contains(d.get("referencia"))).toList();
    }

    private String retrato(String tabela) {
        return jdbc.queryForObject("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '' ORDER BY t::text)), '') FROM corona." + tabela
                + " t", String.class);
    }

    /** Estraga um dado de verdade (dono das tabelas, sem gatilhos), grava e deixa a rodada ver; {@code desfazer} volta tudo. */
    private void mexer(String tabela, String comando) throws Exception {
        try (Connection con = conectarComoMigracao(); Statement s = con.createStatement()) {
            s.execute("ALTER TABLE corona." + tabela + " DISABLE TRIGGER USER");
            s.execute(comando);
            s.execute("ALTER TABLE corona." + tabela + " ENABLE TRIGGER USER");
            con.commit();
        }
    }

    // --- a rodada ---

    @Test
    void rodadaSobreAMassaConfereAsTrezeEGuardaSoOQueNaoBate() throws Exception {
        UUID id = UUID.randomUUID();
        UUID dona = usuarios.dona().id();
        Resultado r = servico.rodar(id, Origem.PEDIDA, dona);
        assertThat(r.jaExistia()).isFalse();
        Map<String, Object> linha = rodada(id);
        assertThat(linha.get("origem")).isEqualTo("PEDIDA");
        assertThat(linha.get("pedida_por")).isEqualTo(dona);
        assertThat(texto(linha.get("chaves_conferidas"))).containsExactlyElementsOf(TREZE);
        assertThat(texto(linha.get("chaves_com_falha"))).isEmpty();
        assertThat(daMassa(id, true)).as("nada da massa fora do lugar").isEmpty();
        int guardadas = diferencas(id).size();
        assertThat(linha.get("total_diferencas")).isEqualTo(guardadas);
        assertThat(linha.get("situacao")).isEqualTo(guardadas == 0 ? "SEM_DIFERENCA" : "COM_DIFERENCA");
    }

    @Test
    void rodadaComDiferencaGuardaAsMesmasNumerosDaRotaDaConferencia() throws Exception {
        // A venda V4 passa a ter 1 B a mais do que saiu do estoque: a C11 acusa a peça B (esperado 3, encontrado 4).
        mexer("itens_venda", "UPDATE corona.itens_venda SET quantidade = quantidade + 1 WHERE venda_id = '" + vendaV4 + "' AND produto_id = '"
                + pecaB + "'");
        try {
            Diferenca daRota = diferencasDe("C11").stream().filter(d -> pecaB.equals(d.referencia())).findFirst().orElseThrow();
            UUID id = UUID.randomUUID();
            servico.rodar(id, Origem.AUTOMATICA, null);
            assertThat(rodada(id).get("situacao")).isEqualTo("COM_DIFERENCA");
            assertThat(rodada(id).get("pedida_por")).isNull();
            Map<String, Object> guardada = diferencas(id).stream()
                    .filter(d -> "C11".equals(d.get("chave")) && pecaB.toString().equals(d.get("referencia"))).findFirst().orElseThrow();
            assertThat(guardada.get("tipo")).isEqualTo("PRODUTO");
            assertThat(guardada.get("unidade")).isEqualTo("PECAS");
            assertThat(guardada.get("esperado")).isEqualTo(daRota.esperado().toPlainString()).isEqualTo("3");
            assertThat(guardada.get("encontrado")).isEqualTo(daRota.encontrado().toPlainString()).isEqualTo("4");
            assertThat(guardada.get("descricao")).isEqualTo(daRota.descricao());
            assertThat((Integer) rodada(id).get("total_diferencas")).isEqualTo(diferencas(id).size()).isPositive();
        } finally {
            mexer("itens_venda", "UPDATE corona.itens_venda SET quantidade = quantidade - 1 WHERE venda_id = '" + vendaV4 + "' AND produto_id = '"
                    + pecaB + "'");
        }
        assertThat(daMassa(servicoLimpo(), true)).as("o banco voltou ao normal").isEmpty();
    }

    private UUID servicoLimpo() {
        UUID id = UUID.randomUUID();
        servico.rodar(id, Origem.AUTOMATICA, null);
        return id;
    }

    @Test
    void conferenciaComErroNaoDerrubaAsOutrasEAChaveFicaGuardada() throws Exception {
        // Erro que passa por uma classe transacional de produção (marca a transação de fora) e depois um erro comum.
        for (Supplier<List<LinhaDeDiferenca>> estrago : List.<Supplier<List<LinhaDeDiferenca>>>of(
                () -> conciliacoes.conferir("C99").conferencias().isEmpty() ? List.of() : List.of(),
                () -> {
                    throw new IllegalStateException("erro de teste");
                },
                // Erro do próprio banco: a transação do PostgreSQL fica abortada até voltar ao ponto de salvamento.
                () -> {
                    jdbc.queryForObject("SELECT 1 / (SELECT count(*) - count(*) FROM corona.produtos)", Integer.class);
                    return List.of();
                })) {
            extra = estrago;
            UUID id = UUID.randomUUID();
            servico.rodar(id, Origem.PEDIDA, usuarios.dona().id());
            Map<String, Object> linha = rodada(id);
            assertThat(linha.get("situacao")).isEqualTo("FALHOU");
            assertThat(texto(linha.get("chaves_com_falha"))).containsExactly("TESTE");
            assertThat(texto(linha.get("chaves_conferidas"))).containsExactlyElementsOf(TREZE);
            assertThat(daMassa(id, true)).isEmpty();
        }
    }

    @Test
    void conferenciaDeTesteSemDiferencaNaoMudaOResultado() throws Exception {
        extra = List::of;
        UUID id = UUID.randomUUID();
        servico.rodar(id, Origem.AUTOMATICA, null);
        assertThat(texto(rodada(id).get("chaves_conferidas"))).hasSize(14).endsWith("TESTE");
    }

    @Test
    void conferenciaDeTesteComDiferencaGuardaALinhaComOTextoExato() throws Exception {
        extra = () -> List.of(new LinhaDeDiferenca("ITEM", "VENDA", "ref-1", "Descrição", "12.50", "12.5", "REAIS"));
        UUID id = UUID.randomUUID();
        servico.rodar(id, Origem.AUTOMATICA, null);
        Map<String, Object> d = diferencas(id).stream().filter(x -> "TESTE".equals(x.get("chave"))).findFirst().orElseThrow();
        assertThat(d.get("item")).isEqualTo("ITEM");
        assertThat(d.get("esperado")).isEqualTo("12.50");
        assertThat(d.get("encontrado")).isEqualTo("12.5");
        assertThat(rodada(id).get("situacao")).isEqualTo("COM_DIFERENCA");
    }

    @Test
    void omesmoIdGravaUmaRodadaSoEODeNovoDevolveAGravada() {
        UUID id = UUID.randomUUID();
        Resultado primeira = servico.rodar(id, Origem.PEDIDA, usuarios.dona().id());
        Resultado segunda = servico.rodar(id, Origem.PEDIDA, usuarios.dona().id());
        assertThat(primeira.jaExistia()).isFalse();
        assertThat(segunda.jaExistia()).isTrue();
        assertThat(segunda.id()).isEqualTo(id);
        assertThat(contar("SELECT count(*) FROM corona.rodadas_conferencia WHERE id = ?", id)).isEqualTo(1);
        UUID outra = UUID.randomUUID();
        assertThat(servico.rodar(outra, Origem.PEDIDA, usuarios.dona().id()).jaExistia()).isFalse();
        assertThat(contar("SELECT count(*) FROM corona.rodadas_conferencia WHERE id IN (?, ?)", id, outra)).isEqualTo(2);
    }

    @Test
    void umaRodadaPorVezASegundaRecebe409ENadaEGravado() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        extra = () -> {
            entrou.countDown();
            try {
                liberar.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        ExecutorService fio = Executors.newSingleThreadExecutor();
        try {
            Future<Resultado> rodando = fio.submit(() -> servico.rodar(primeira, Origem.PEDIDA, usuarios.dona().id()));
            assertThat(entrou.await(60, TimeUnit.SECONDS)).as("a primeira rodada está rodando").isTrue();
            assertThatThrownBy(() -> servico.rodar(segunda, Origem.PEDIDA, usuarios.dona().id()))
                    .isInstanceOfSatisfying(ErroDaApi.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
            assertThat(CodigoErro.CONFERENCIA_EM_ANDAMENTO.mensagem()).contains("Espere um pouco e atualize a tela");
            liberar.countDown();
            assertThat(rodando.get(120, TimeUnit.SECONDS).jaExistia()).isFalse();
        } finally {
            liberar.countDown();
            fio.shutdownNow();
        }
        assertThat(contar("SELECT count(*) FROM corona.rodadas_conferencia WHERE id = ?", primeira)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.rodadas_conferencia WHERE id = ?", segunda)).isZero();
        // Terminou: a trava soltou e a próxima rodada roda.
        extra = null;
        assertThat(servico.rodar(segunda, Origem.PEDIDA, usuarios.dona().id()).jaExistia()).isFalse();
    }

    @Test
    void aRodadaSoEscreveNasDuasTabelasNovas() {
        List<String> antes = TABELAS.stream().map(this::retrato).toList();
        servico.rodar(UUID.randomUUID(), Origem.PEDIDA, usuarios.dona().id());
        List<String> depois = TABELAS.stream().map(this::retrato).toList();
        for (int i = 0; i < TABELAS.size(); i++) {
            assertThat(depois.get(i)).as(TABELAS.get(i)).isEqualTo(antes.get(i));
        }
    }
}

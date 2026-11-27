package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.NovoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeAcertosDaNota.ReciboDoAcerto;
import br.com.coronasemijoias.api.consignado.ServicoDeNotas.NovaNota;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.Leitura;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.LeituraDoFechamento;
import br.com.coronasemijoias.api.relatorios.RelatorioDeCadastro;
import br.com.coronasemijoias.api.relatorios.RelatorioDeCadastro.Total;
import br.com.coronasemijoias.api.relatorios.RelatorioDoFechamentoDaMaleta;
import br.com.coronasemijoias.api.relatorios.RelatorioDoFechamentoDaMaleta.LinhaDoFechamento;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O relatório "Fechamento da maleta" (§23.10) sai da MESMA leitura da tela: cada linha e cada total do relatório são os da
 * leitura. É dele que o Corona Gestão tira o PDF, o Excel e o CSV. PostgreSQL de verdade.
 */
class RelatorioDoFechamentoDaMaletaTest extends ApoioDoConsignado {

    /** As cotações de 2020 deste teste são gravadas de verdade (a tabela é imutável): saem no fim, para não vazar para outros testes. */
    @org.junit.jupiter.api.AfterAll
    static void tirarAsCotacoesDe2020() throws java.sql.SQLException {
        try (java.sql.Connection con = conectarComoMigracao()) {
            CotacoesDaMassa.tirarDias(con, java.util.List.of("2020-03-10", "2020-03-12"));
        }
    }

    @Autowired
    ServicoDoFechamentoDaMaleta fechamento;

    @Autowired
    RelatorioDoFechamentoDaMaleta relatorio;

    private Total total(RelatorioDeCadastro<LinhaDoFechamento> r, String chave) {
        return r.totais().stream().filter(t -> t.chave().equals(chave)).findFirst().orElseThrow();
    }

    /** A maleta do caso de teste do Fechamento: 10 anéis e 6 colares; 5 anéis em nota acertada, 2 anéis e 1 colar com outro cliente. */
    private UUID maletaComMovimento(UsuarioDeTeste v) throws Exception {
        UUID cliente = cliente(v);
        UUID outro = cliente(v);
        UUID anel = peca("100.00", 20);
        UUID colar = peca("50.00", 20);
        UUID maleta = maletaEnviada(v, anel, 10, colar, 6);
        UUID semana = abrirSemana(v);
        UUID n1 = criarNota(v, semana, 1, cliente, item(anel, maleta, 5, "100.00"));
        acertar(v, n1, semana, "30", List.of(volta(anel, maleta, 2)), 3, paga("PIX", "100.00"));
        criarNota(v, semana, 2, outro, item(anel, maleta, 2, "100.00"), item(colar, maleta, 1, "50.00"));
        fechamento.iniciar(maleta, usuarios.dona().id());
        fechamento.registrarLeituras(maleta, List.of(new Leitura(UUID.randomUUID(), anel, 3), new Leitura(UUID.randomUUID(), colar, 5)),
                usuarios.dona().id());
        return maleta;
    }

    @Test
    void linhasETotaisSaemDaMesmaLeituraDaTela() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID maleta = maletaComMovimento(v);
        LeituraDoFechamento leitura = fechamento.ler(maleta);
        RelatorioDeCadastro<LinhaDoFechamento> r = relatorio.montar(maleta, null, null, null);

        assertThat(r.total()).isEqualTo(leitura.pecas().size()).isEqualTo(2);
        assertThat(r.itens()).hasSize(2);
        for (LinhaDoFechamento l : r.itens()) {
            var p = leitura.pecas().stream().filter(x -> x.produtoId().equals(l.id())).findFirst().orElseThrow();
            assertThat(l.nome()).isEqualTo(p.nome());
            assertThat(l.enviadas()).isEqualTo(p.enviadas());
            assertThat(l.devolvidasPelosClientes()).isEqualTo(p.devolvidasPelosClientes());
            assertThat(l.vendidas()).isEqualTo(p.vendidas());
            assertThat(l.comClientes()).isEqualTo(p.comClientes());
            assertThat(l.esperadoNaMaleta()).isEqualTo(p.esperadoNaMaleta());
            assertThat(l.bipadas()).isEqualTo(p.bipadas());
            assertThat(l.diferenca()).isEqualTo(p.diferenca());
        }
        // Anéis: 10 enviados, 2 devolvidos, 3 vendidos, 2 com cliente; esperados 5, bipados 3 (falta 2). Colares: 6 enviados, 1 com cliente.
        assertThat(total(r, "enviadas").pecas()).isEqualTo(16);
        assertThat(total(r, "devolvidas").pecas()).isEqualTo(2);
        assertThat(total(r, "vendidas").pecas()).isEqualTo(3);
        assertThat(total(r, "comClientes").pecas()).isEqualTo(3);
        assertThat(total(r, "bipadas").pecas()).isEqualTo(8);
        assertThat(total(r, "diferenca").pecas()).isEqualTo(-2);
        assertThat(total(r, "vd").valorEmReais()).isEqualByComparingTo(leitura.valores().vd()).isEqualByComparingTo("300.00");
        assertThat(total(r, "comissao").valorEmReais()).isEqualByComparingTo("90.00");
        assertThat(total(r, "recebido").valorEmReais()).isEqualByComparingTo("100.00");
        assertThat(total(r, "pendencias").valorEmReais()).isEqualByComparingTo("110.00");
        // "Com quem" diz o cliente, a nota e a quantidade.
        LinhaDoFechamento aneis = r.itens().stream().filter(l -> l.enviadas() == 10).findFirst().orElseThrow();
        assertThat(aneis.comQuem()).contains("(nota ").contains("): 2");
    }

    /** Duas notas acertadas em dias diferentes, cada acerto com a cotação do seu dia (a Dt. Acerto, tarefa 9.8). */
    private UUID maletaComDoisDias(UsuarioDeTeste v, String diaDoPrimeiro, LocalDate dataDoPrimeiro) throws Exception {
        UUID cliente = cliente(v);
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 20);
        UUID semana = abrirSemana(v, 1, Instant.parse(diaDoPrimeiro + "T10:00:00Z"));
        NovaNota a = nota(semana, 1, cliente, item(anel, maleta, 2, "100.00"));
        notas.criar(ctxEm(v, Instant.parse(diaDoPrimeiro + "T13:00:00Z")), a);
        NovaNota b = nota(semana, 2, cliente, item(anel, maleta, 1, "100.00"));
        notas.criar(ctxEm(v, Instant.parse(diaDoPrimeiro + "T13:00:00Z")), b);
        // Primeiro acerto: a data escolhida (cotação desse dia), pagando 100 num recibo (cotação do mesmo dia).
        comComissao(BigDecimal.ZERO, () -> acertos.acertar(ctxEm(v, Instant.parse("2020-03-12T13:00:00Z")), new NovoAcerto(UUID.randomUUID(),
                a.id(), semana, BigDecimal.ZERO, List.of(), new ReciboDoAcerto(UUID.randomUUID(), 1, List.of(paga("PIX", "100.00"))), null,
                dataDoPrimeiro)));
        // Segundo acerto: no dia 12 (hoje do computador), sem pagar.
        comComissao(BigDecimal.ZERO, () -> acertos.acertar(ctxEm(v, Instant.parse("2020-03-12T18:00:00Z")),
                new NovoAcerto(UUID.randomUUID(), b.id(), semana, BigDecimal.ZERO, List.of(), null, null, LocalDate.of(2020, 3, 12))));
        fechamento.iniciar(maleta, usuarios.dona().id());
        return maleta;
    }

    @Test
    void totaisDeDinheiroTrazemDolarEGuaraniCadaLancamentoPelaCotacaoDoSeuDia() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        CotacoesDaMassa.gravar(jdbc, "2020-03-10", "5.00000000", "0.00100000");
        CotacoesDaMassa.gravar(jdbc, "2020-03-12", "10.00000000", "0.00200000");
        UUID maleta = maletaComDoisDias(v, "2020-03-02", LocalDate.of(2020, 3, 10));
        RelatorioDeCadastro<LinhaDoFechamento> r = relatorio.montar(maleta, null, null, null);

        // VD 300: 200 pelo dia 10 (US$ 40,00 e Gs. 200.000) e 100 pelo dia 12 (US$ 10,00 e Gs. 50.000).
        assertThat(total(r, "vd").valorEmReais()).isEqualByComparingTo("300.00");
        assertThat(total(r, "vd").valorEmDolar()).isEqualByComparingTo("50.00");
        assertThat(total(r, "vd").valorEmGuarani()).isEqualByComparingTo("250000");
        // Recebido: o recibo de 100 foi do dia 10.
        assertThat(total(r, "recebido").valorEmReais()).isEqualByComparingTo("100.00");
        assertThat(total(r, "recebido").valorEmDolar()).isEqualByComparingTo("20.00");
        assertThat(total(r, "recebido").valorEmGuarani()).isEqualByComparingTo("100000");
        // Pendências: 100 do acerto do dia 10 e 100 do acerto do dia 12.
        assertThat(total(r, "pendencias").valorEmReais()).isEqualByComparingTo("200.00");
        assertThat(total(r, "pendencias").valorEmDolar()).isEqualByComparingTo("30.00");
        assertThat(total(r, "pendencias").valorEmGuarani()).isEqualByComparingTo("150000");
        assertThat(total(r, "comissao").valorEmDolar()).isEqualByComparingTo("0.00");
    }

    @Test
    void existindoCotacaoMaletaSemAcertoMostraZeroVerdadeiroEmDolarEGuarani() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        CotacoesDaMassa.gravar(jdbc, "2020-03-12", "10.00000000", "0.00200000");
        UUID anel = peca("100.00", 20);
        UUID maleta = maletaEnviada(v, anel, 5);
        fechamento.iniciar(maleta, usuarios.dona().id());
        RelatorioDeCadastro<LinhaDoFechamento> r = relatorio.montar(maleta, null, null, null);
        assertThat(total(r, "vd").valorEmDolar()).isEqualByComparingTo("0.00");
        assertThat(total(r, "vd").valorEmGuarani()).isEqualByComparingTo("0");
    }

    @Test
    void semCotacaoParaUmDosLancamentosOTotalSaiSoEmReal() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        CotacoesDaMassa.gravar(jdbc, "2020-03-12", "10.00000000", "0.00200000");
        // O primeiro acerto é de 2000, antes de qualquer cotação.
        UUID maleta = maletaComDoisDias(v, "2000-01-05", LocalDate.of(2000, 1, 6));
        RelatorioDeCadastro<LinhaDoFechamento> r = relatorio.montar(maleta, null, null, null);
        assertThat(total(r, "vd").valorEmReais()).isEqualByComparingTo("300.00");
        assertThat(total(r, "vd").valorEmDolar()).isNull();
        assertThat(total(r, "vd").valorEmGuarani()).isNull();
    }

    @Test
    void paginaEOTotalContinuaSendoDoRelatorioInteiro() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID maleta = maletaComMovimento(v);
        RelatorioDeCadastro<LinhaDoFechamento> r = relatorio.montar(maleta, 0, 1, null);
        assertThat(r.itens()).hasSize(1);
        assertThat(r.total()).isEqualTo(2);
        assertThat(total(r, "enviadas").pecas()).isEqualTo(16);
        assertThat(relatorio.montar(maleta, 1, 1, null).itens()).hasSize(1);
        assertThat(relatorio.montar(maleta, 5, 1, null).itens()).isEmpty();
    }

    @Test
    void recorteDeUmTotalMostraSoAsLinhasQueOFormamEORodapeContinuaInteiro() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID maleta = maletaComMovimento(v);
        // Só os anéis foram devolvidos pelos clientes.
        RelatorioDeCadastro<LinhaDoFechamento> r = relatorio.montar(maleta, null, null, "devolvidas");
        assertThat(r.itens()).hasSize(1);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.recorte()).isEqualTo("devolvidas");
        assertThat(total(r, "enviadas").pecas()).isEqualTo(16);
    }

    @Test
    void rotaDaDonaDevolveORelatorioEOVendedorNaoEntra() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID maleta = maletaComMovimento(v);
        mvc.perform(get("/api/dona/relatorios/maleta/fechamento").param("remessaId", maleta.toString()).with(dona()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totais[?(@.chave=='vd')].valorEmReais").value("300.00"));
        mvc.perform(get("/api/dona/relatorios/maleta/fechamento").param("remessaId", maleta.toString()).with(como(v)))
                .andExpect(status().isForbidden());
    }

    @Test
    void semFechamentoComecadoDa404() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 10);
        mvc.perform(get("/api/dona/relatorios/maleta/fechamento").param("remessaId", maleta.toString()).with(dona()))
                .andExpect(status().isNotFound());
    }
}

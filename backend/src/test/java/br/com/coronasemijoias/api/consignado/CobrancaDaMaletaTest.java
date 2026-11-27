package br.com.coronasemijoias.api.consignado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.coronasemijoias.api.conciliacoes.ConciliacoesDaFase7;
import br.com.coronasemijoias.api.consignado.ServicoDaCobrancaDaMaleta.PedidoDePagamento;
import br.com.coronasemijoias.api.consignado.ServicoDoFechamentoDaMaleta.DecisaoDaFalta;
import br.com.coronasemijoias.api.testes.CotacoesDaMassa;
import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Receber a cobrança da falta do Fechamento da maleta (ESPECIFICACAO §23.10, item 1a; 27/11/2026): pagamento por valor (parcial
 * aceito), em Real, Dólar ou Guarani (gravado em Real), com entrada no caixa; estorno com motivo. PostgreSQL de verdade. Escritos
 * antes do código.
 */
class CobrancaDaMaletaTest extends ApoioDoConsignado {

    @Autowired
    ServicoDoFechamentoDaMaleta fechamento;

    @Autowired
    ServicoDaCobrancaDaMaleta cobranca;

    @AfterEach
    void semCotacao() throws SQLException {
        try (var con = conectarComoMigracao()) {
            CotacoesDaMassa.tirar(con);
        }
    }

    private UUID donaId() {
        return usuarios.dona().id();
    }

    private record Falta(UsuarioDeTeste v, UUID maleta, UUID pendencia) {
    }

    /** Uma maleta de 2 anéis de R$ 100 que voltou vazia: falta de R$ 200 com a decisão dada. */
    private Falta falta(String decisao) throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        UUID anel = peca("100.00", 10);
        UUID maleta = maletaEnviada(v, anel, 2);
        fechamento.iniciar(maleta, donaId());
        UUID pendencia = UUID.randomUUID();
        fechamento.concluir(maleta, List.of(new DecisaoDaFalta(pendencia, anel, decisao, "PERDA_DA_LOJA".equals(decisao) ? "Sumiu" : null)),
                donaId());
        return new Falta(v, maleta, pendencia);
    }

    private static PedidoDePagamento emReais(String forma, String valor) {
        return new PedidoDePagamento(UUID.randomUUID(), forma, "REAL", new BigDecimal(valor), null, null);
    }

    private BigDecimal noCaixa(UUID pagamento) {
        return jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.lancamentos_caixa WHERE origem_tipo = 'COBRANCA_DA_MALETA'"
                + " AND origem_id = ?", BigDecimal.class, pagamento);
    }

    private void conferenciasLimpas(UUID... ids) {
        for (String chave : List.of("C4", "C8")) {
            var d = new ConciliacoesDaFase7(jdbc).conferir(chave).conferencias().get(0).divergencias();
            for (var x : d) {
                for (UUID id : ids) {
                    assertThat(String.valueOf(x.referencia())).as(chave + " " + d).isNotEqualTo(id.toString());
                }
            }
        }
    }

    @Test
    void pagamentoParcialEDepoisOResto_cadaUmComEntradaNoCaixa_eAcimaDoQueFaltaERecusado() throws Exception {
        Falta f = falta("COBRAR_DO_REPRESENTANTE");
        PedidoDePagamento p1 = emReais("DINHEIRO", "50.00");
        cobranca.pagar(f.pendencia(), p1, donaId());
        var pend = fechamento.ler(f.maleta()).pendencias().get(0);
        assertThat(pend.pago()).isEqualByComparingTo("50.00");
        assertThat(pend.faltaPagar()).isEqualByComparingTo("150.00");
        assertThat(noCaixa(p1.id())).isEqualByComparingTo("50.00");

        PedidoDePagamento p2 = emReais("PIX", "150.00");
        cobranca.pagar(f.pendencia(), p2, donaId());
        var c = fechamento.ler(f.maleta()).cobranca();
        assertThat(c.pagoNestaMaleta()).isEqualByComparingTo("200.00");
        assertThat(c.faltaPagarNestaMaleta()).isEqualByComparingTo("0.00");
        assertThat(c.faltaPagarTodasAsMaletas()).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT forma FROM corona.lancamentos_caixa WHERE origem_id = ?", String.class, p2.id()))
                .isEqualTo("PIX");

        assertThat(codigoDoErro(catchThrowable(() -> cobranca.pagar(f.pendencia(), emReais("DINHEIRO", "0.01"), donaId()))))
                .isEqualTo("PAGAMENTO_ACIMA_DO_SALDO");
        conferenciasLimpas(p1.id(), p2.id(), f.pendencia());
    }

    /** Pela API: só a dona; o representante recebe 403 e nada é gravado. */
    @Test
    void soADonaRegistraPeloEndereco() throws Exception {
        Falta f = falta("COBRAR_DO_REPRESENTANTE");
        var corpo = mapa("id", UUID.randomUUID(), "forma", "DINHEIRO", "moeda", "REAL", "valor", "20.00");
        postar("/api/maletas/pendencias/" + f.pendencia() + "/pagamentos", como(f.v()), corpo)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        postar("/api/maletas/pendencias/" + f.pendencia() + "/pagamentos", dona(), corpo)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        postar("/api/maletas/pendencias/" + f.pendencia() + "/pagamentos", dona(), corpo)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cobranca_maleta WHERE pendencia_id = ?", f.pendencia())).isEqualTo(1);
    }

    @Test
    void perdaDaLojaNaoRecebePagamento_eFormaDeCartaoERecusada() throws Exception {
        Falta perda = falta("PERDA_DA_LOJA");
        assertThat(codigoDoErro(catchThrowable(() -> cobranca.pagar(perda.pendencia(), emReais("DINHEIRO", "10.00"), donaId()))))
                .isEqualTo("DADOS_INVALIDOS");
        Falta f = falta("COBRAR_DO_REPRESENTANTE");
        assertThat(codigoDoErro(catchThrowable(() -> cobranca.pagar(f.pendencia(), emReais("DEBITO", "10.00"), donaId()))))
                .isEqualTo("DADOS_INVALIDOS");
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cobranca_maleta WHERE pendencia_id IN (?, ?)", perda.pendencia(),
                f.pendencia())).isZero();
    }

    @Test
    void mesmoIdDuasVezesGravaUmSo_eComOutroValorEConflito() throws Exception {
        Falta f = falta("COBRAR_DO_REPRESENTANTE");
        PedidoDePagamento p = emReais("DINHEIRO", "30.00");
        assertThat(cobranca.pagar(f.pendencia(), p, donaId())).isFalse();
        assertThat(cobranca.pagar(f.pendencia(), p, donaId())).isTrue();
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cobranca_maleta WHERE id = ?", p.id())).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM corona.lancamentos_caixa WHERE origem_id = ?", p.id())).isEqualTo(1);
        var outro = new PedidoDePagamento(p.id(), "DINHEIRO", "REAL", new BigDecimal("31.00"), null, null);
        assertThat(codigoDoErro(catchThrowable(() -> cobranca.pagar(f.pendencia(), outro, donaId()))))
                .isEqualTo("LANCAMENTO_JA_REGISTRADO_DIFERENTE");
    }

    @Test
    void desfazerOPagamentoPedeMotivo_saiDoCaixa_eDepoisDaParaPagarDeNovo() throws Exception {
        Falta f = falta("COBRAR_DO_REPRESENTANTE");
        PedidoDePagamento p = emReais("DINHEIRO", "200.00");
        cobranca.pagar(f.pendencia(), p, donaId());
        assertThat(codigoDoErro(catchThrowable(() -> cobranca.estornar(p.id(), UUID.randomUUID(), "  ", donaId()))))
                .isEqualTo("DADOS_INVALIDOS");
        UUID estorno = UUID.randomUUID();
        cobranca.estornar(p.id(), estorno, "Lancei no representante errado", donaId());
        assertThat(noCaixa(p.id())).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("SELECT sum(valor) FROM corona.lancamentos_caixa WHERE origem_tipo = 'COBRANCA_DA_MALETA'"
                + " AND origem_id IN (?, ?)", BigDecimal.class, p.id(), estorno)).isEqualByComparingTo("0.00");
        assertThat(fechamento.ler(f.maleta()).pendencias().get(0).faltaPagar()).isEqualByComparingTo("200.00");
        // Desfazer de novo o mesmo pagamento: recusado; o mesmo estorno reenviado não duplica.
        assertThat(codigoDoErro(catchThrowable(() -> cobranca.estornar(p.id(), UUID.randomUUID(), "De novo", donaId()))))
                .isEqualTo("LANCAMENTO_JA_DESFEITO");
        cobranca.estornar(p.id(), estorno, "Lancei no representante errado", donaId());
        assertThat(contar("SELECT count(*) FROM corona.pagamentos_cobranca_maleta WHERE estorna_id = ?", p.id())).isEqualTo(1);

        PedidoDePagamento deNovo = emReais("PIX", "200.00");
        cobranca.pagar(f.pendencia(), deNovo, donaId());
        assertThat(fechamento.ler(f.maleta()).pendencias().get(0).faltaPagar()).isEqualByComparingTo("0.00");
        conferenciasLimpas(p.id(), estorno, deNovo.id(), f.pendencia());
    }

    @Test
    void emGuaraniGravaEmReaisPelaCotacaoDoDia_eSemCotacaoRecusa() throws Exception {
        Falta f = falta("COBRAR_DO_REPRESENTANTE");
        var semCotacao = new PedidoDePagamento(UUID.randomUUID(), "DINHEIRO", "GUARANI", null, new BigDecimal("100000"), null);
        assertThat(codigoDoErro(catchThrowable(() -> cobranca.pagar(f.pendencia(), semCotacao, donaId()))))
                .isEqualTo("SEM_COTACAO_PARA_O_RECEBIMENTO");

        CotacoesDaMassa.doExemplo(jdbc);
        var emGuarani = new PedidoDePagamento(UUID.randomUUID(), "DINHEIRO", "GUARANI", null, new BigDecimal("100000"), null);
        cobranca.pagar(f.pendencia(), emGuarani, donaId());
        var linha = jdbc.queryForMap("SELECT valor, moeda, valor_na_moeda, cotacao_id FROM corona.pagamentos_cobranca_maleta WHERE id = ?",
                emGuarani.id());
        assertThat((BigDecimal) linha.get("valor")).isEqualByComparingTo("90.00");
        assertThat(linha.get("moeda")).isEqualTo("GUARANI");
        assertThat((BigDecimal) linha.get("valor_na_moeda")).isEqualByComparingTo("100000");
        assertThat(linha.get("cotacao_id")).isNotNull();
        assertThat(noCaixa(emGuarani.id())).isEqualByComparingTo("90.00");
        conferenciasLimpas(emGuarani.id(), f.pendencia());
    }
}

package br.com.coronasemijoias.api.conciliacoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.coronasemijoias.api.testes.UsuariosDeTeste.UsuarioDeTeste;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Relatórios financeiros da dona (tarefa 7.12, §12). Cada relatório confere o número contra a massa conhecida da Fase 7
 * (a mesma das conciliações, calculada à mão nos comentários) e, no fim, as conciliações da massa continuam zeradas.
 */
class RelatoriosFinanceirosTest extends MassaDaFase7 {

    private static final String REL = "/api/dona/relatorios/financeiro";

    private JsonNode rel(String rota, String filtros) throws Exception {
        return corpoDe(ler(REL + rota + "?tamanho=100&" + filtros, dona()).andExpect(status().isOk()));
    }

    private static BigDecimal total(JsonNode r, String chave) {
        for (JsonNode t : r.get("totais")) {
            if (chave.equals(t.get("chave").asString())) {
                return new BigDecimal(t.get("valorEmReais").asString());
            }
        }
        throw new AssertionError("sem o total " + chave);
    }

    private static void igual(BigDecimal achado, String esperado) {
        assertThat(achado).isEqualByComparingTo(esperado);
    }

    @AfterEach
    void voltarORelogio() {
        relogio.reiniciar();
    }

    @Test
    void acertoPorVendedorESemanaFechaComOsNumerosDaMassa() throws Exception {
        // Acima da meta: vendeu 150 (100 + 50), a venda de 50 foi estornada (devolvido 50): vendido líquido 100,
        // comissão 40% = 40, falta cobrada 50: valor do acerto 100 - 40 + 50 = 110.
        JsonNode a = rel("/acertos", "vendedorId=" + acima.vendedorId());
        assertThat(a.get("itens").size()).isEqualTo(1);
        igual(total(a, "vendido"), "150.00");
        igual(total(a, "descontos"), "0.00");
        igual(total(a, "devolvido"), "50.00");
        igual(total(a, "comissao"), "40.00");
        igual(total(a, "faltas"), "50.00");
        igual(total(a, "liquido"), "110.00");
        assertThat(a.get("itens").get(0).get("percentual").asString()).isEqualTo("40.00");
        // Abaixo da meta: 100 vendidos a 30% = 30 e líquido 70; no acerto seguinte a devolução de 50 estorna 15 e fica -35.
        JsonNode b = rel("/acertos", "vendedorId=" + abaixo.vendedorId());
        assertThat(b.get("itens").size()).isEqualTo(2);
        igual(total(b, "vendido"), "100.00");
        igual(total(b, "devolvido"), "50.00");
        igual(total(b, "comissao"), "15.00");
        igual(total(b, "liquido"), "35.00");
        // Dólar e Guarani: o rodapé é a soma das linhas já convertidas pela cotação gravada de cada acerto.
        BigDecimal soma = BigDecimal.ZERO;
        for (JsonNode l : b.get("itens")) {
            assertThat(l.get("dataDaCotacao").isNull()).isFalse();
            soma = soma.add(new BigDecimal(l.get("liquidoEmGuarani").asString()));
        }
        for (JsonNode t : b.get("totais")) {
            if ("liquido".equals(t.get("chave").asString())) {
                assertThat(new BigDecimal(t.get("valorEmGuarani").asString())).isEqualByComparingTo(soma);
            }
        }
    }

    @Test
    void faltasEPendenciasMostramOQueFicouComQuem() throws Exception {
        // Acima: 1 peça faltou (50,00) e foi cobrada. W: 1 peça faltou (50,00) e a loja assumiu; 1 defeito aprovado.
        JsonNode a = rel("/pendencias", "vendedorId=" + acima.vendedorId());
        assertThat(a.get("itens").size()).isEqualTo(1);
        assertThat(a.get("itens").get(0).get("tipo").asString()).isEqualTo("FALTA");
        assertThat(a.get("itens").get(0).get("situacao").asString()).isEqualTo("COBRADA");
        igual(total(a, "cobradas"), "50.00");
        JsonNode w = rel("/pendencias", "vendedorId=" + this.w.vendedorId() + "&tipo=FALTA");
        assertThat(w.get("itens").size()).isEqualTo(1);
        assertThat(w.get("itens").get(0).get("quantidade").asInt()).isEqualTo(1);
        assertThat(w.get("itens").get(0).get("situacao").asString()).isEqualTo("ASSUMIDA_PELA_LOJA");
        igual(total(w, "assumidas"), "50.00");
        igual(total(w, "abertas"), "0.00");
    }

    @Test
    void esperadoXContadoDaMaletaDoW() throws Exception {
        // Maleta de 6, vendeu 1: esperado 5; contou 3 boas e 1 com defeito; faltou 1.
        JsonNode r = rel("/esperado-contado", "vendedorId=" + w.vendedorId());
        assertThat(r.get("itens").size()).isEqualTo(1);
        JsonNode l = r.get("itens").get(0);
        assertThat(l.get("esperado").asInt()).isEqualTo(5);
        assertThat(l.get("contadoBoa").asInt()).isEqualTo(3);
        assertThat(l.get("contadoDefeito").asInt()).isEqualTo(1);
        assertThat(l.get("falta").asInt()).isEqualTo(1);
        igual(total(r, "faltas"), "50.00");
    }

    @Test
    void contasAReceberDoClienteSomamPagoECancelado() throws Exception {
        // Z: a venda a prazo de 100 em 3 parcelas recebeu 70; a venda estornada de 100 teve as parcelas canceladas.
        JsonNode r = rel("/contas-a-receber", "vendedorId=" + z.vendedorId() + "&devedor=CLIENTE");
        igual(total(r, "pago"), "70.00");
        igual(total(r, "cancelado"), "100.00");
        BigDecimal valor = total(r, "valor");
        igual(total(r, "aberto"), valor.subtract(total(r, "pago")).subtract(total(r, "cancelado")).toPlainString());
        // Detalhamento: o recorte "pago" devolve só as parcelas que têm pagamento.
        JsonNode so = rel("/contas-a-receber", "vendedorId=" + z.vendedorId() + "&devedor=CLIENTE&recorte=pago");
        assertThat(so.get("total").asLong()).isEqualTo(total(r, "pago").signum() > 0 ? so.get("itens").size() : 0);
        assertThat(so.get("itens").size()).isGreaterThan(0);
    }

    @Test
    void contasAReceberDoVendedorMostramOQueEleFicouDevendo() throws Exception {
        // X: o acerto 1 deixou 10 de boleto e 10 de restante; o recebimento de 3 (acerto 2) e o boleto de 10 baixaram.
        JsonNode r = rel("/contas-a-receber", "vendedorId=" + x.vendedorId() + "&devedor=VENDEDOR");
        assertThat(r.get("itens").size()).isGreaterThan(0);
        BigDecimal valor = total(r, "valor");
        igual(total(r, "aberto"), valor.subtract(total(r, "pago")).subtract(total(r, "cancelado")).toPlainString());
    }

    @Test
    void contasAPagarDoFornecedorDaMassa() throws Exception {
        // Compra de 300 em 3 de 100: pagou 100 e 50 (o outro pagamento de 50 foi estornado): pago 150, aberto 150.
        // Compra desfeita de 40 em 2 de 20: pagou 10, o resto (30) foi cancelado.
        UUID fornecedor = umId("SELECT fornecedor_id FROM corona.compras WHERE id = ?", compraDasParcelas);
        JsonNode r = rel("/contas-a-pagar", "fornecedorId=" + fornecedor);
        igual(total(r, "valor"), "340.00");
        igual(total(r, "pago"), "160.00");
        igual(total(r, "cancelado"), "30.00");
        igual(total(r, "aberto"), "150.00");
    }

    @Test
    void inadimplenciaSoAparecePassadoOVencimento() throws Exception {
        String filtro = "vendedorId=" + z.vendedorId();
        assertThat(rel("/inadimplencia", filtro).get("itens").size()).isZero();
        relogio.avancar(Duration.ofDays(400));
        JsonNode r = rel("/inadimplencia", filtro);
        assertThat(r.get("itens").size()).isGreaterThan(0);
        for (JsonNode l : r.get("itens")) {
            assertThat(l.get("diasDeAtraso").asInt()).isGreaterThan(300);
        }
        // Em aberto de Z (parcelas não pagas, sem as canceladas): 30 da venda paga + as da venda a prazo em aberto.
        BigDecimal aberto = total(r, "vencido");
        assertThat(aberto.signum()).isPositive();
        BigDecimal soma = BigDecimal.ZERO;
        for (JsonNode l : r.get("itens")) {
            soma = soma.add(new BigDecimal(l.get("emAbertoEmReais").asString()));
        }
        assertThat(aberto).isEqualByComparingTo(soma);
    }

    @Test
    void fluxoDeCaixaDoXEDoY() throws Exception {
        // X: entradas 10+5 (acerto), 4 e -4 (avulso estornado), 10 (boleto), 35 e -35 (acerto 2 estornado), 3 (conta antiga) = 28.
        JsonNode fx = rel("/fluxo-de-caixa", "vendedorId=" + x.vendedorId());
        igual(total(fx, "entradas"), "28.00");
        igual(total(fx, "saidas"), "0.00");
        igual(total(fx, "saldo"), "28.00");
        // Y: pagou 35 em dinheiro ao vendedor (acerto a favor).
        JsonNode fy = rel("/fluxo-de-caixa", "vendedorId=" + y.vendedorId());
        igual(total(fy, "entradas"), "0.00");
        igual(total(fy, "saidas"), "-35.00");
        // O lançamento manual da massa (saída de 12,50) aparece pelo dia dele.
        JsonNode m = rel("/fluxo-de-caixa", "de=2020-01-15&ate=2020-01-15&origem=MANUAL");
        boolean achou = false;
        for (JsonNode l : m.get("itens")) {
            achou |= lancamentoManual.toString().equals(l.get("id").asString())
                    && new BigDecimal(l.get("valorEmReais").asString()).compareTo(new BigDecimal("-12.50")) == 0;
        }
        assertThat(achou).isTrue();
    }

    @Test
    void recebimentosPorFormaDoX() throws Exception {
        // Dinheiro 10+4-4+35-35 = 10; transferência 5+3 = 8; boleto 10; total 28.
        JsonNode r = rel("/recebimentos-por-forma", "vendedorId=" + x.vendedorId());
        igual(total(r, "DINHEIRO"), "10.00");
        igual(total(r, "PIX"), "8.00");
        igual(total(r, "BOLETO"), "10.00");
        igual(total(r, "total"), "28.00");
        assertThat(r.toString()).doesNotContain("CHEQUE").doesNotContain("CARTAO");
    }

    @Test
    void comissoesPorVendedorESemana() throws Exception {
        JsonNode a = rel("/comissoes", "vendedorId=" + acima.vendedorId());
        assertThat(a.get("itens").size()).isEqualTo(1);
        igual(total(a, "comissao"), "40.00");
        assertThat(a.get("itens").get(0).get("percentual").asString()).isEqualTo("40.00");
        assertThat(a.get("itens").get(0).get("vendidoEmGuarani").asLong()).isPositive();
        JsonNode b = rel("/comissoes", "vendedorId=" + abaixo.vendedorId());
        assertThat(b.get("itens").size()).isEqualTo(2);
        igual(total(b, "comissao"), "15.00");
    }

    @Test
    void soADonaLeEOVendedorNuncaRecebe() throws Exception {
        UsuarioDeTeste v = usuarios.criarVendedor();
        List<String> rotas = new ArrayList<>(List.of("/acertos", "/pendencias", "/esperado-contado", "/contas-a-receber",
                "/contas-a-pagar", "/inadimplencia", "/fluxo-de-caixa", "/recebimentos-por-forma", "/comissoes"));
        for (String r : rotas) {
            ler(REL + r, como(v)).andExpect(status().isForbidden());
            mvc.perform(get(REL + r)).andExpect(status().isUnauthorized());
            ler(REL + r, dona()).andExpect(status().isOk());
        }
    }

    @Test
    void filtroInvalidoDaErroEmPortuguesESemMudarNada() throws Exception {
        ler(REL + "/acertos?de=2026-02-10&ate=2026-02-01", dona()).andExpect(status().isBadRequest());
        ler(REL + "/contas-a-receber?devedor=XYZ", dona()).andExpect(status().isBadRequest());
        ler(REL + "/acertos?recorte=inventado", dona()).andExpect(status().isBadRequest());
    }

    @Test
    void asConciliacoesDaMassaContinuamZeradas() {
        for (String chave : ConciliacoesDaFase7.CHAVES) {
            assertThat(daMassa(diferencasDe(chave))).as(chave).isEmpty();
        }
    }
}

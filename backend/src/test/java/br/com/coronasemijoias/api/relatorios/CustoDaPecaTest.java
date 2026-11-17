package br.com.coronasemijoias.api.relatorios;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * O custo da peça numa data (tarefa 8.6; ADR-0015, item 1, decisão R1): o último custo de compra até o dia, o dia da compra
 * valendo; compra desfeita não conta; sem compra, o custo do cadastro, marcado. Cada teste cria as próprias peças e compras
 * (de 2292, fora do que os outros testes usam) e confere a fórmula de produção ({@link CustoDaPeca}) direto no banco.
 */
class CustoDaPecaTest extends ApoioDoCusto {

    private Map<String, Object> custo(UUID produto, String dia) {
        return jdbc.queryForMap("SELECT cu.custo, cu.cadastro FROM (SELECT ?::uuid AS produto_id, ?::date AS dia) l"
                + CustoDaPeca.juntar("cu", "l.produto_id", "l.dia"), produto, dia);
    }

    private void vale(UUID produto, String dia, String custoEsperado, boolean doCadastro) {
        Map<String, Object> c = custo(produto, dia);
        assertThat((BigDecimal) c.get("custo")).as("custo em %s", dia).isEqualByComparingTo(custoEsperado);
        assertThat(c.get("cadastro")).as("custo do cadastro em %s", dia).isEqualTo(doCadastro);
    }

    @Test
    void valeOUltimoCustoDeCompraAteODiaOdiaDaCompraConta() throws Exception {
        UUID a = pecaComCusto(categoria(), "50.00", "12.00");
        UUID f = fornecedor();
        comprarEm(f, "2292-05-01", a, 10, "10.00");
        comprarEm(f, "2292-05-10", a, 10, "12.00");
        vale(a, "2292-05-09", "10.00", false);
        vale(a, "2292-05-10", "12.00", false);
        vale(a, "2292-05-11", "12.00", false);
        vale(a, "2292-05-01", "10.00", false);
    }

    @Test
    void semCompraAteODiaValeOCustoDoCadastroMarcado() throws Exception {
        UUID a = pecaComCusto(categoria(), "50.00", "7.50");
        vale(a, "2292-05-15", "7.50", true);
        // Com compra só depois do dia: antes dela ainda é o cadastro.
        comprarEm(fornecedor(), "2292-05-10", a, 5, "9.00");
        cadastroPassaA(a, "7.50"); // a compra muda o cadastro; a dona o deixa como estava
        vale(a, "2292-05-09", "7.50", true);
        vale(a, "2292-05-10", "9.00", false);
    }

    @Test
    void compraDesfeitaNaoConta() throws Exception {
        UUID a = pecaComCusto(categoria(), "50.00", "5.00");
        UUID f = fornecedor();
        comprarEm(f, "2292-05-01", a, 10, "10.00");
        UUID desfeita = comprarEm(f, "2292-05-20", a, 5, "15.00");
        vale(a, "2292-05-25", "15.00", false);
        desfazerCompra(desfeita);
        vale(a, "2292-05-25", "10.00", false);
        vale(a, "2292-05-20", "10.00", false);
    }

    @Test
    void compraDesfeitaSozinhaVoltaAoCadastro() throws Exception {
        UUID a = pecaComCusto(categoria(), "50.00", "5.00");
        UUID unica = comprarEm(fornecedor(), "2292-05-20", a, 5, "15.00");
        desfazerCompra(unica);
        cadastroPassaA(a, "5.00");
        vale(a, "2292-05-25", "5.00", true);
    }

    @Test
    void duasComprasNoMesmoDiaValeAUltimaRegistrada() throws Exception {
        UUID a = pecaComCusto(categoria(), "50.00", "1.00");
        UUID f = fornecedor();
        comprarEm(f, "2292-06-01", a, 3, "8.00");
        comprarEm(f, "2292-06-01", a, 3, "9.00");
        vale(a, "2292-06-01", "9.00", false);
        vale(a, "2292-06-02", "9.00", false);
        cadastroPassaA(a, "1.00");
        vale(a, "2292-05-31", "1.00", true);
    }

    @Test
    void oCustoDaLinhaEExatoSemArredondar() throws Exception {
        UUID a = pecaComCusto(categoria(), "50.00", "1.00");
        comprarEm(fornecedor(), "2292-06-05", a, 3, "7.33");
        BigDecimal linha = jdbc.queryForObject("SELECT 3 * cu.custo FROM (SELECT ?::uuid AS produto_id, ?::date AS dia) l"
                + CustoDaPeca.juntar("cu", "l.produto_id", "l.dia"), BigDecimal.class, a, "2292-06-05");
        assertThat(linha).isEqualByComparingTo("21.99");
        assertThat(linha.scale()).isEqualTo(2);
    }
}

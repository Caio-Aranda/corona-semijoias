package br.com.coronasemijoias.api.contasreceber;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * As conciliações do nível 1 (tarefa 7.3): C1 (por venda, total menos o pago líquido é o que falta nas parcelas; venda
 * estornada, zero) e C5 (os atalhos da parcela são a soma das baixas). Aqui elas rodam sobre dados certos (vazias) e sobre
 * um dado estragado de propósito (acusam a diferença). Desde a 7.8 elas rodam pela consulta de produção (ConciliacoesDaFase7), sem SQL copiado.
 */
class ConciliacaoDasContasTest extends ApoioDasContas {

    private APrazo estragada;

    /** O dado estragado de propósito sai de cena (o banco é dividido entre os testes): a venda vira estornada por SQL. */
    @AfterEach
    void tirarAVendaDeCena() {
        conciliar = false;
        if (estragada != null) {
            jdbc.update("""
                    UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), motivo_estorno = 'teste'
                     WHERE id = ? AND status = 'CONCLUIDA'""", UUID.randomUUID(), estragada.venda());
        }
    }

    @Test
    void dadosCertosNaoTemDiferencaEmNenhumPasso() throws Exception {
        APrazo c = vendaAPrazo("100.00", "10.00", 3);
        assertThat(diferencasDaConciliacao()).isEmpty();
        UUID recebimento = receberOk(c, "35.00");
        assertThat(diferencasDaConciliacao()).isEmpty();
        estornarRecebimento(c, recebimento, UUID.randomUUID(), "Erro");
        assertThat(diferencasDaConciliacao()).isEmpty();
        receberOk(c, "90.00");
        assertThat(diferencasDaConciliacao()).isEmpty();
        postar(VENDAS + "/" + c.venda() + "/estorno", como(c.vendedor()), mapa("id", UUID.randomUUID(), "motivo", "Desistiu"));
        assertThat(diferencasDaConciliacao()).isEmpty();
    }

    @Test
    void baixaSemOAtalhoAtualizadoApareceNaC5ENaC1() throws Exception {
        estragada = vendaAPrazo("100.00", null, 2);
        UUID conta = (UUID) parcelas(estragada.venda()).get(0).get("id");
        UUID pagamento = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior)
                VALUES (?, ?, 'PAGAMENTO', 'PIX', 20.00, now(), true)""", pagamento, estragada.venda());
        jdbc.update("""
                INSERT INTO corona.baixas_receber (id, conta_id, tipo, valor, baixada_em, pagamento_cliente_id)
                VALUES (?, ?, 'PAGAMENTO', 20.00, now(), ?)""", UuidV7.gerar(), conta, pagamento);
        List<String> diferencas = diferencasDaConciliacao();
        assertThat(diferencas).anyMatch(d -> d.startsWith("C5 CONTA_A_RECEBER ")).anyMatch(d -> d.startsWith("C1 VENDA " + estragada.venda()));
    }

    @Test
    void pagamentoPosteriorSemBaixaApareceNaC1() throws Exception {
        estragada = vendaAPrazo("100.00", null, 2);
        jdbc.update("""
                INSERT INTO corona.pagamentos_cliente (id, venda_id, tipo, forma, valor, pago_em, posterior)
                VALUES (?, ?, 'PAGAMENTO', 'PIX', 20.00, now(), true)""", UUID.randomUUID(), estragada.venda());
        assertThat(diferencasDaConciliacao()).anyMatch(d -> d.startsWith("C1 VENDA " + estragada.venda()));
    }

    @Test
    void vendaEstornadaComParcelaEmAbertoApareceNaC1() throws Exception {
        estragada = vendaAPrazo("100.00", null, 2);
        // Estorno por SQL, sem o serviço (que cancelaria as parcelas): as duas ficam abertas numa venda estornada.
        jdbc.update("""
                UPDATE corona.vendas SET status = 'ESTORNADA', estorno_id = ?, estornada_em = now(), motivo_estorno = 'teste'
                 WHERE id = ?""", UUID.randomUUID(), estragada.venda());
        assertThat(diferencasDaConciliacao()).anyMatch(d -> d.startsWith("C1 VENDA " + estragada.venda()) && d.contains("foi desfeita"));
    }
}

package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aplica um pagamento aos débitos do cliente do mais antigo para o mais novo (ESPECIFICACAO §23.8; ADR-0016, item 10).
 * Pura, sem banco. Quem chama passa os débitos JÁ ordenados e só com saldo conhecido; o que sobrar depois do último
 * débito é o "a aplicar" do recibo (nunca se perde dinheiro).
 */
public final class AplicacaoDePagamento {

    private AplicacaoDePagamento() {
    }

    /** Um débito do cliente (o acerto) com o saldo que ainda deve. */
    public record Debito(UUID id, BigDecimal saldo) {
    }

    public record Aplicacao(UUID debitoId, BigDecimal valor) {
    }

    public record Resultado(List<Aplicacao> aplicacoes, BigDecimal sobra) {
    }

    /**
     * @param debitosDoMaisAntigo débitos em ordem; os de saldo zero (ou negativo) são pulados
     * @param valor o valor do pagamento, maior que zero e com no máximo duas casas
     */
    public static Resultado aplicar(List<Debito> debitosDoMaisAntigo, BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("O valor do pagamento precisa ser maior que zero.");
        }
        BigDecimal restante = Dinheiro.somar(List.of(valor));
        List<Aplicacao> aplicacoes = new ArrayList<>();
        for (Debito d : debitosDoMaisAntigo) {
            if (restante.signum() == 0) {
                break;
            }
            if (d.saldo().signum() <= 0) {
                continue;
            }
            BigDecimal aplicado = restante.min(d.saldo());
            aplicacoes.add(new Aplicacao(d.id(), aplicado));
            restante = Dinheiro.subtrair(restante, aplicado);
        }
        return new Resultado(List.copyOf(aplicacoes), restante);
    }
}

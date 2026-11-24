package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A conta do acerto da nota (ESPECIFICACAO §23.8; ADR-0016, item 8). Pura, sem banco: o servidor recalcula tudo a partir
 * dos itens da nota e das devoluções, e o Corona Vendas usa as mesmas fórmulas ({@code packages/shared}), provadas pelo
 * mesmo arquivo de exemplos.
 *
 * <p>VD = DX − DV. A comissão incide <strong>uma vez sobre o total</strong> do VD da nota (não por linha), HALF_UP com
 * duas casas. Total a receber = bruto + pendências anteriores; "ficou devendo" nunca é negativo.
 */
public final class CalculoDoAcertoDaNota {

    /** Comissão do cliente, em porcentagem (§23.1: de 0% a 40%). */
    public static final BigDecimal COMISSAO_MAXIMA = new BigDecimal("40");

    private CalculoDoAcertoDaNota() {
    }

    /** Uma linha da nota (produto + maleta): o que foi levado (DX), o que voltou (DV) e o preço da maleta. */
    public record LinhaDaNota(int dx, int dv, BigDecimal precoUnitario) {
    }

    public record Resultado(int itensDx, int itensDv, int itensVd, BigDecimal valorDx, BigDecimal valorDv,
            BigDecimal valorVd, BigDecimal vendaPercentual, BigDecimal comissao, BigDecimal bruto,
            BigDecimal pendenciasAnteriores, BigDecimal totalAReceber, BigDecimal totalPago, BigDecimal ficouDevendo) {
    }

    /** A comissão está entre 0 e 40 (com até duas casas)? */
    public static boolean comissaoValida(BigDecimal percentual) {
        return percentual != null && percentual.signum() >= 0 && percentual.compareTo(COMISSAO_MAXIMA) <= 0
                && percentual.stripTrailingZeros().scale() <= Dinheiro.ESCALA;
    }

    /**
     * @throws IllegalArgumentException comissão fora de 0 a 40, DV maior que DX ou quantidade negativa (o servidor
     *     converte em recusa antes de chamar; aqui é a última trava)
     */
    public static Resultado calcular(List<LinhaDaNota> linhas, BigDecimal comissaoPercentual, BigDecimal pendenciasAnteriores,
            BigDecimal totalPago) {
        if (!comissaoValida(comissaoPercentual)) {
            throw new IllegalArgumentException("A comissão precisa estar entre 0% e 40%.");
        }
        int itensDx = 0;
        int itensDv = 0;
        List<BigDecimal> dxs = new ArrayList<>();
        List<BigDecimal> dvs = new ArrayList<>();
        for (LinhaDaNota l : linhas) {
            if (l.dx() < 0 || l.dv() < 0 || l.dv() > l.dx()) {
                throw new IllegalArgumentException("A devolução não pode passar do que foi levado.");
            }
            itensDx += l.dx();
            itensDv += l.dv();
            dxs.add(Dinheiro.precoDaLinha(l.precoUnitario(), l.dx()));
            dvs.add(Dinheiro.precoDaLinha(l.precoUnitario(), l.dv()));
        }
        BigDecimal valorDx = Dinheiro.somar(dxs);
        BigDecimal valorDv = Dinheiro.somar(dvs);
        BigDecimal valorVd = Dinheiro.subtrair(valorDx, valorDv);
        // Venda % só é mostrada; com DX zero mostra 0 (sem dividir por zero).
        BigDecimal vendaPercentual = valorDx.signum() == 0 ? Dinheiro.somar(List.of()) : Dinheiro.percentualDe(valorVd, valorDx);
        BigDecimal comissao = Dinheiro.aplicarPorcentagem(valorVd, comissaoPercentual);
        BigDecimal bruto = Dinheiro.subtrair(valorVd, comissao);
        BigDecimal pendencias = Dinheiro.somar(List.of(pendenciasAnteriores));
        BigDecimal totalAReceber = Dinheiro.somar(List.of(bruto, pendencias));
        BigDecimal pago = Dinheiro.somar(List.of(totalPago));
        BigDecimal ficouDevendo = Dinheiro.subtrair(totalAReceber, pago);
        if (ficouDevendo.signum() < 0) {
            ficouDevendo = Dinheiro.somar(List.of());
        }
        return new Resultado(itensDx, itensDv, itensDx - itensDv, valorDx, valorDv, valorVd, vendaPercentual, comissao, bruto,
                pendencias, totalAReceber, pago, ficouDevendo);
    }
}

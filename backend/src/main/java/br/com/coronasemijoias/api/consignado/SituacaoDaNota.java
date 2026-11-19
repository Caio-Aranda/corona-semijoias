package br.com.coronasemijoias.api.consignado;

import java.math.BigDecimal;

/**
 * A situação da nota (ADR-0016, item 10), sempre calculada a partir dos fatos: sem acerto ativo é
 * {@code REMARCADO} (se houve remarcação) ou {@code EM_ABERTO}; com acerto ativo é {@code PENDENTE} enquanto o débito
 * tiver saldo e {@code FECHADO} quando zerar. O servidor grava o resultado na nota, na mesma transação do que mudou.
 */
public final class SituacaoDaNota {

    public enum Situacao { EM_ABERTO, PENDENTE, FECHADO, REMARCADO }

    private SituacaoDaNota() {
    }

    /**
     * @param saldoDoDebito o saldo do débito do acerto ativo (ignorado quando não há acerto ativo)
     */
    public static Situacao calcular(boolean temAcertoAtivo, boolean foiRemarcada, BigDecimal saldoDoDebito) {
        if (!temAcertoAtivo) {
            return foiRemarcada ? Situacao.REMARCADO : Situacao.EM_ABERTO;
        }
        return saldoDoDebito != null && saldoDoDebito.signum() > 0 ? Situacao.PENDENTE : Situacao.FECHADO;
    }
}

package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;

/**
 * O instante que vale para a data escolhida na tela (Data do pagamento e Data do acerto, ESPECIFICACAO §23.8): vazia = o
 * momento do lançamento; igual ao dia do lançamento = o próprio momento; outro dia = meio-dia desse dia em Brasília. Nunca
 * depois de hoje: conferido pelo relógio do servidor E pelo dia que o computador declarou, em Brasília. A cotação é a desse dia
 * (ADR-0006).
 */
final class MomentoDoLancamento {

    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private MomentoDoLancamento() {
    }

    static Instant de(ContextoDoConsignado ctx, LocalDate data, CodigoErro codigoSeForFuturo) {
        if (data == null) {
            return ctx.quando();
        }
        if (data.isAfter(LocalDate.now(BRASILIA)) || data.isAfter(LocalDate.ofInstant(ctx.quando(), BRASILIA))) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, codigoSeForFuturo);
        }
        if (data.equals(LocalDate.ofInstant(ctx.quando(), BRASILIA))) {
            return ctx.quando();
        }
        return data.atTime(12, 0).atZone(BRASILIA).toInstant();
    }
}

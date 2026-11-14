package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": o caixa está negativo (tarefa 7.7; ADR-0012, item 11; dúvida 13 do plano da Fase 7). Só avisa, nada
 * é bloqueado. Calculado na hora, nunca gravado: some sozinho quando o saldo volta a zero ou mais. O saldo vai em Real; a tela
 * converte para Dólar e Guarani pela cotação do dia (ADR-0006).
 */
@Component
public class FonteDeAvisosDoCaixa implements FonteDeAvisos {

    public static final String TIPO = "CAIXA_NEGATIVO";

    private final ConsultasDoCaixa caixa;

    FonteDeAvisosDoCaixa(ConsultasDoCaixa caixa) {
        this.caixa = caixa;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        return aviso(caixa.saldo().saldo()).map(List::of).orElse(List.of());
    }

    public static Optional<AvisoDaDona> aviso(BigDecimal saldo) {
        if (saldo.signum() >= 0) {
            return Optional.empty();
        }
        String valor = "R$ " + saldo.abs().setScale(2).toPlainString().replace('.', ',');
        return Optional.of(new AvisoDaDona(TIPO, "O caixa está negativo em " + valor + ".",
                "Entrou menos dinheiro do que saiu. Confira os lançamentos do caixa e lance o que faltar.", List.of()));
    }
}

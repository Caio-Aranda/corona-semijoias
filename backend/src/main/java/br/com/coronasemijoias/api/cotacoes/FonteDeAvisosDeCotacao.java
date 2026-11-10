package br.com.coronasemijoias.api.cotacoes;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Transforma o {@link AvisoDeCotacao} (ADR-0006, item 12) no aviso da tela inicial da dona. Os textos
 * são os do glossário (parte 11).
 */
@Component
public class FonteDeAvisosDeCotacao implements FonteDeAvisos {

    public static final String TIPO = "COTACAO_NAO_CHEGOU";

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String TENTA_DE_NOVO =
            "O sistema tenta de novo sozinho. Se o aviso continuar amanhã, fale com o suporte do sistema.";

    private final AvisoDeCotacao aviso;
    private final Clock relogio;

    public FonteDeAvisosDeCotacao(AvisoDeCotacao aviso, Clock relogio) {
        this.aviso = aviso;
        this.relogio = relogio;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        AvisoDeCotacao.Aviso calculado = aviso.calcular();
        if (!calculado.aparece()) {
            return List.of();
        }
        List<LocalDate> dias = calculado.diasSemCotacao();
        LocalDate hoje = relogio.instant().atZone(ZoneOffset.UTC).toLocalDate();
        boolean soHoje = dias.size() == 1 && dias.get(0).equals(hoje);
        boolean varios = dias.size() > 1;

        String titulo;
        if (soHoje) {
            titulo = "A cotação de hoje ainda não chegou.";
        } else if (varios) {
            titulo = "As cotações de " + listar(dias) + " não chegaram.";
        } else {
            titulo = "A cotação de " + DATA.format(dias.get(0)) + " não chegou.";
        }

        String uso = calculado.cotacaoEmUso()
                .map(d -> {
                    String sujeito = soHoje ? "estão" : varios ? "desses dias estão" : "desse dia estão";
                    return "Os valores em Dólar e Guarani " + sujeito + " usando a cotação de " + DATA.format(d) + ". ";
                })
                .orElse("Os valores aparecem só em reais por enquanto. ");
        return List.of(new AvisoDaDona(TIPO, titulo, uso + TENTA_DE_NOVO, dias));
    }

    private static String listar(List<LocalDate> dias) {
        List<String> textos = new ArrayList<>();
        dias.forEach(d -> textos.add(DATA.format(d)));
        int ultimo = textos.size() - 1;
        return String.join(", ", textos.subList(0, ultimo)) + " e " + textos.get(ultimo);
    }
}

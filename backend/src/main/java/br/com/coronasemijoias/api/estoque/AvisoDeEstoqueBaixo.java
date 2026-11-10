package br.com.coronasemijoias.api.estoque;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": quantos produtos estão no estoque mínimo ou abaixo (tarefa 2.4). Calculado na hora;
 * some sozinho quando a reposição chega.
 */
@Component
public class AvisoDeEstoqueBaixo implements FonteDeAvisos {

    public static final String TIPO = "ESTOQUE_BAIXO";

    private final ConsultasDeEstoque consultas;

    AvisoDeEstoqueBaixo(ConsultasDeEstoque consultas) {
        this.consultas = consultas;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        long quantos = consultas.contarAbaixoDoMinimo();
        if (quantos == 0) {
            return List.of();
        }
        String titulo = quantos == 1 ? "1 produto está acabando." : quantos + " produtos estão acabando.";
        return List.of(new AvisoDaDona(TIPO, titulo,
                "Esses produtos chegaram ao estoque mínimo. Veja a lista e faça a reposição.", List.of()));
    }
}

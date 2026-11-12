package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.avisos.AvisoDaDona;
import br.com.coronasemijoias.api.avisos.FonteDeAvisos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * "O que precisa da sua atenção": computador parado e computador novo (tarefa 5.11; ADR-0013, item 21). Os textos são os
 * do glossário. Calculado na hora; o aviso some sozinho quando o computador volta ou a dona resolve.
 */
@Component
public class FonteDeAvisosDosComputadores implements FonteDeAvisos {

    public static final String TIPO_SEM_ENVIAR = "COMPUTADOR_SEM_ENVIAR";
    public static final String TIPO_NOVO = "COMPUTADOR_NOVO";

    private final ComputadoresParados computadores;

    public FonteDeAvisosDosComputadores(ComputadoresParados computadores) {
        this.computadores = computadores;
    }

    @Override
    public List<AvisoDaDona> avisos() {
        List<AvisoDaDona> avisos = new ArrayList<>();
        for (ComputadoresParados.Parado p : computadores.semEnviar()) {
            String dias = p.dias() == 1 ? "1 dia" : p.dias() + " dias";
            avisos.add(new AvisoDaDona(TIPO_SEM_ENVIAR,
                    "O computador de " + p.vendedor() + " está há " + dias + " sem enviar.",
                    "Peça a " + p.vendedor() + " para conectar o Corona Vendas à internet. "
                            + "As vendas feitas sem internet só chegam quando o computador conectar.",
                    List.of()));
        }
        for (ComputadoresParados.Novo n : computadores.aguardandoLiberacao()) {
            avisos.add(new AvisoDaDona(TIPO_NOVO, n.vendedor() + " entrou num computador novo.",
                    "Para " + n.vendedor() + " vender nesse computador, abra a ficha do vendedor e use Trocar computador. "
                            + "Se não foi " + n.vendedor() + ", use Bloquear computador.",
                    List.of()));
        }
        return avisos;
    }
}

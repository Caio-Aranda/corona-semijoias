package br.com.coronasemijoias.api.avisos;

import java.time.LocalDate;
import java.util.List;

/**
 * Um item de "O que precisa da sua atenção" na tela inicial da dona (especificação §19.3). O texto vem
 * pronto do servidor, em português simples; o aplicativo só mostra.
 *
 * @param tipo   o que é, para a tela escolher o ícone e o botão (por exemplo {@code COTACAO_NAO_CHEGOU})
 * @param titulo a frase em destaque
 * @param texto  a explicação e o que fazer
 * @param dias   os dias a que o aviso se refere, quando houver (vazio nos outros casos)
 */
public record AvisoDaDona(String tipo, String titulo, String texto, List<LocalDate> dias) {

    public AvisoDaDona {
        dias = List.copyOf(dias);
    }
}

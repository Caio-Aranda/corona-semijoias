package br.com.coronasemijoias.api.avisos;

import java.util.List;

/**
 * Quem sabe de um tipo de aviso para a dona. Cada tipo novo (acertos pendentes, estoque baixo, maletas
 * atrasadas...) é um {@code @Component} que implementa esta interface; a rota junta todos. Os avisos são
 * sempre <strong>calculados na hora</strong>, nunca gravados: somem sozinhos quando o problema some.
 */
public interface FonteDeAvisos {

    /** Os avisos que valem agora (lista vazia quando não há nada). */
    List<AvisoDaDona> avisos();
}

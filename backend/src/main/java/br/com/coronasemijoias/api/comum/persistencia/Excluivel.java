package br.com.coronasemijoias.api.comum.persistencia;

import java.time.Instant;
import java.util.UUID;

/** Registro com exclusão lógica ({@code deleted_at}) e versão de edição: o que os serviços de cadastro precisam. */
public interface Excluivel {

    UUID getId();

    Integer getVersion();

    boolean estaExcluida();

    void marcarComoExcluida(Instant quando);
}

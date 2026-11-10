package br.com.coronasemijoias.api.cadastros;

import java.util.List;
import java.util.Locale;

/** Quais cadastros uma lista ou busca mostra: ativos (padrão), inativos ou todos. */
public enum Situacao {
    ATIVOS(List.of(true)), INATIVOS(List.of(false)), TODOS(List.of(true, false));

    private final List<Boolean> valores;

    Situacao(List<Boolean> valores) {
        this.valores = valores;
    }

    /** Os valores de {@code ativo} que entram na lista. */
    public List<Boolean> valores() {
        return valores;
    }

    /** Lê o parâmetro {@code situacao}; vazio quer dizer só os ativos. */
    public static Situacao ler(String texto) {
        if (texto == null || texto.isBlank()) {
            return ATIVOS;
        }
        try {
            return valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Validacoes.recusar("situacao", "Escolha ATIVOS, INATIVOS ou TODOS.");
        }
    }
}

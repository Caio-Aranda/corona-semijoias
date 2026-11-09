package br.com.coronasemijoias.api.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Uma tabela sincronizável (ADR-0002, item 3) e as colunas de negócio que o pull entrega, sempre
 * em lista explícita (nunca {@code SELECT *}). As colunas de controle {@link #COLUNAS_DE_CONTROLE}
 * entram sempre; {@code sync_seq}, {@code sync_xid} e {@code origem_dispositivo} ficam no servidor.
 *
 * @param imutavel a tabela recusa UPDATE (vendas, itens, pagamentos, movimentos)
 */
public record TabelaSincronizavel(String nome, List<String> colunas, boolean imutavel) {

    /** Colunas de controle que todo registro do pull leva. */
    public static final List<String> COLUNAS_DE_CONTROLE = List.of("id", "version", "created_at", "updated_at", "deleted_at");

    /** Colunas que nunca saem do servidor pelo pull. */
    public static final Set<String> COLUNAS_SO_DO_SERVIDOR = Set.of("sync_seq", "sync_xid", "origem_dispositivo");

    static final Pattern IDENTIFICADOR = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    public TabelaSincronizavel {
        nome = identificador(nome, "tabela");
        colunas = List.copyOf(colunas);
        Set<String> vistas = new HashSet<>();
        for (String coluna : colunas) {
            identificador(coluna, "coluna de " + nome);
            if (COLUNAS_DE_CONTROLE.contains(coluna) || COLUNAS_SO_DO_SERVIDOR.contains(coluna)) {
                throw new IllegalArgumentException("A coluna " + coluna + " de " + nome
                        + " é de controle: não a liste entre as colunas de negócio.");
            }
            if (!vistas.add(coluna)) {
                throw new IllegalArgumentException("A coluna " + coluna + " de " + nome + " está repetida.");
            }
        }
    }

    /** Colunas lidas pelo pull, na ordem: as de controle e depois as de negócio. */
    public List<String> colunasDoPull() {
        List<String> todas = new ArrayList<>(COLUNAS_DE_CONTROLE);
        todas.addAll(colunas);
        return List.copyOf(todas);
    }

    static String identificador(String nome, String oQue) {
        Objects.requireNonNull(nome, oQue);
        if (!IDENTIFICADOR.matcher(nome).matches()) {
            throw new IllegalArgumentException("Nome de " + oQue + " fora do padrão (minúsculas, números e _): " + nome);
        }
        return nome;
    }
}

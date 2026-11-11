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
 * @param regra    o que o vendedor recebe desta tabela (ADR-0013, itens 4 e 5; tarefa 5.4)
 */
public record TabelaSincronizavel(String nome, List<String> colunas, boolean imutavel, RegraDoVendedor regra) {

    /** Tabela sem regra do vendedor declarada (só as de teste): o filtro do vendedor não entrega nada dela. */
    public TabelaSincronizavel(String nome, List<String> colunas, boolean imutavel) {
        this(nome, colunas, imutavel, RegraDoVendedor.NAO_DECLARADA);
    }

    /** Colunas de controle que todo registro do pull leva. */
    public static final List<String> COLUNAS_DE_CONTROLE = List.of("id", "version", "created_at", "updated_at", "deleted_at");

    /** Colunas que nunca saem do servidor pelo pull. */
    public static final Set<String> COLUNAS_SO_DO_SERVIDOR = Set.of("sync_seq", "sync_xid", "origem_dispositivo");

    static final Pattern IDENTIFICADOR = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    public TabelaSincronizavel {
        nome = identificador(nome, "tabela");
        colunas = List.copyOf(colunas);
        Objects.requireNonNull(regra, "regra");
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
        if (!regra.diretorio().isEmpty() && (!vistas.containsAll(regra.diretorio()) || !vistas.contains(regra.colunaDoDono()))) {
            throw new IllegalArgumentException("O diretório de " + nome + " cita coluna que a tabela não entrega.");
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

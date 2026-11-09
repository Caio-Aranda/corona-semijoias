package br.com.coronasemijoias.api.sync;

/**
 * Uma tabela só do servidor (ADR-0002, item 3): nunca entra no pull e não tem {@code sync_seq},
 * {@code sync_xid} nem {@code origem_dispositivo}.
 *
 * @param imutavel tabela de histórico, só com inserção (recusa UPDATE)
 */
public record TabelaDoServidor(String nome, boolean imutavel) {

    public TabelaDoServidor {
        nome = TabelaSincronizavel.identificador(nome, "tabela");
    }
}

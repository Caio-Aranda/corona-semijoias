package br.com.coronasemijoias.api.sync;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Uma linha entregue pelo pull: o estado atual do registro, com as colunas da lista explícita
 * da tabela. Linha com {@code excluidaEm} preenchido é uma lápide (exclusão lógica).
 *
 * @param colunas colunas de controle e de negócio, na ordem do registro (valores podem ser nulos)
 * @param syncXid posição da linha no pull (fica no servidor; serve para o cursor)
 * @param syncSeq posição da linha no pull (fica no servidor; serve para o cursor)
 */
public record LinhaDoPull(
        String tabela,
        UUID id,
        int versao,
        Instant excluidaEm,
        Map<String, Object> colunas,
        long syncXid,
        long syncSeq) {
}

package br.com.coronasemijoias.api.consignado;

import java.time.Instant;
import java.util.UUID;

/**
 * Quem faz o que e quando, para os serviços do consignado (tarefa 9.1): o vendedor e o usuário vêm do token; o computador
 * (pode ser nulo em teste) vira a origem dos registros; {@code quando} é a hora que o computador declarou, JÁ corrigida pela
 * diferença do relógio dele (ADR-0013, item 11); {@code horaAjustada} marca a correção acima de 5 minutos.
 */
public record ContextoDoConsignado(UUID vendedorId, UUID usuarioId, UUID aparelho, Instant quando, boolean horaAjustada) {
}

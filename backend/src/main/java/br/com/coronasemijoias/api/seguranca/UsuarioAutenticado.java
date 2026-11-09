package br.com.coronasemijoias.api.seguranca;

import java.util.UUID;

/**
 * Quem fez a requisição, conferido no banco a cada chamada ({@link ConversorDoToken}). Os
 * controladores recebem com {@code @AuthenticationPrincipal UsuarioAutenticado}. O filtro "só do
 * vendedor" usa sempre o {@link #vendedorId()} daqui, nunca um parâmetro da requisição (ADR-0003).
 *
 * @param id            usuário (o {@code sub} do token)
 * @param perfil        perfil atual, lido do banco
 * @param vendedorId    vendedor do usuário, lido do banco (só perfil VENDEDOR)
 * @param jti           identificador do token
 * @param sid           família da sessão (tarefa 11; hoje sempre vazio)
 * @param dispositivoId aparelho (tarefa 11; hoje sempre vazio)
 * @param escopo        escopo restrito ({@code TROCA_SENHA}) ou vazio no token de acesso comum
 */
public record UsuarioAutenticado(
        UUID id, String perfil, UUID vendedorId, String jti, UUID sid, UUID dispositivoId, String escopo) {
}

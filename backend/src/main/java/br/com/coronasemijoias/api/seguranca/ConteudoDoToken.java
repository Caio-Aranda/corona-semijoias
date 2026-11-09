package br.com.coronasemijoias.api.seguranca;

import java.util.Objects;
import java.util.UUID;

/**
 * O que vai no token de acesso, além de {@code jti}, {@code iat}, {@code exp} e {@code iss}
 * (ADR-0004, item 1). Nunca: senha, nome, CPF, e-mail, telefone, custo ou dados de outros vendedores.
 *
 * <p>Todo token leva o {@code sid} da família da sessão (tarefa 11), inclusive o restrito da troca de
 * senha: a conferência de cada requisição recusa o token de uma família revogada.
 *
 * @param usuarioId     {@code sub}
 * @param perfil        código do perfil (tabela {@code perfis})
 * @param vendedorId    {@code vendedor_id}, só para o perfil VENDEDOR
 * @param sid           família da sessão (tabela {@code sessoes})
 * @param dispositivoId aparelho do Corona Vendas (vazio no Corona Gestão)
 * @param escopo        token restrito, por exemplo {@code TROCA_SENHA}
 */
public record ConteudoDoToken(
        UUID usuarioId, String perfil, UUID vendedorId, UUID sid, UUID dispositivoId, String escopo) {

    public static final String PERFIL = "perfil";
    public static final String VENDEDOR_ID = "vendedor_id";
    public static final String SID = "sid";
    public static final String DISPOSITIVO_ID = "dispositivo_id";
    public static final String ESCOPO = "escopo";

    /** Escopo do token restrito: só abre a troca de senha e a saída (ADR-0004, item 6). */
    public static final String ESCOPO_TROCA_SENHA = "TROCA_SENHA";

    public ConteudoDoToken {
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(perfil, "perfil");
        Objects.requireNonNull(sid, "sid");
    }

    /** Token restrito de quem entrou com senha temporária: só serve para criar a senha nova (ou sair). */
    public static ConteudoDoToken restritoParaTrocaDeSenha(
            UUID usuarioId, String perfil, UUID vendedorId, UUID sid, UUID dispositivoId) {
        return new ConteudoDoToken(usuarioId, perfil, vendedorId, sid, dispositivoId, ESCOPO_TROCA_SENHA);
    }

    /** Token de acesso comum. */
    public static ConteudoDoToken de(UUID usuarioId, String perfil, UUID vendedorId, UUID sid, UUID dispositivoId) {
        return new ConteudoDoToken(usuarioId, perfil, vendedorId, sid, dispositivoId, null);
    }
}

package br.com.coronasemijoias.api.seguranca;

import java.util.Objects;
import java.util.UUID;

/**
 * O que vai no token de acesso, além de {@code jti}, {@code iat}, {@code exp} e {@code iss}
 * (ADR-0004, item 1). Nunca: senha, nome, CPF, e-mail, telefone, custo ou dados de outros vendedores.
 *
 * <p>{@code sid}, {@code dispositivoId} e {@code escopo} já têm lugar aqui, mas só são preenchidos
 * pelas tarefas 10 (escopo da troca de senha) e 11 (sessão longa e aparelho). Vazios, não vão no token.
 *
 * @param usuarioId     {@code sub}
 * @param perfil        código do perfil (tabela {@code perfis})
 * @param vendedorId    {@code vendedor_id}, só para o perfil VENDEDOR
 * @param sid           família da sessão (tarefa 11)
 * @param dispositivoId aparelho do Corona Vendas (tarefa 11)
 * @param escopo        token restrito, por exemplo {@code TROCA_SENHA} (tarefa 10)
 */
public record ConteudoDoToken(
        UUID usuarioId, String perfil, UUID vendedorId, UUID sid, UUID dispositivoId, String escopo) {

    public static final String PERFIL = "perfil";
    public static final String VENDEDOR_ID = "vendedor_id";
    public static final String SID = "sid";
    public static final String DISPOSITIVO_ID = "dispositivo_id";
    public static final String ESCOPO = "escopo";

    public ConteudoDoToken {
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(perfil, "perfil");
    }

    /** Token de acesso comum, sem sessão longa nem aparelho (tarefa 9). */
    public static ConteudoDoToken de(UUID usuarioId, String perfil, UUID vendedorId) {
        return new ConteudoDoToken(usuarioId, perfil, vendedorId, null, null, null);
    }
}

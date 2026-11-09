package br.com.coronasemijoias.api.usuarios;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dados do primeiro administrador ({@code ADMIN_NOME}, {@code ADMIN_LOGIN}, {@code ADMIN_SENHA} do
 * {@code .env}). Só são usados enquanto não existe nenhum ADMIN.
 */
@ConfigurationProperties("corona.admin-inicial")
public record PropriedadesDoPrimeiroAdministrador(String nome, String login, String senha) {

    @Override
    public String toString() {
        return "PropriedadesDoPrimeiroAdministrador[nome=" + nome + ", login=" + login + ", senha=(oculta)]";
    }
}

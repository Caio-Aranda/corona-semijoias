package br.com.coronasemijoias.api.usuarios;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Vendedor de demonstração ({@code DEMO_VENDEDOR_*} do {@code .env}). Só é lido no perfil {@code dev}. */
@ConfigurationProperties("corona.vendedor-demo")
public record PropriedadesDoVendedorDeDemonstracao(String nome, String login, String senha) {

    @Override
    public String toString() {
        return "PropriedadesDoVendedorDeDemonstracao[nome=" + nome + ", login=" + login + ", senha=(oculta)]";
    }
}

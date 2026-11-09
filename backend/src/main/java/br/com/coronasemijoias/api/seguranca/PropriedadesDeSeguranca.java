package br.com.coronasemijoias.api.seguranca;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parâmetros de segurança da configuração do servidor (ADR-0004, "Parâmetros técnicos e onde
 * ficam"): {@code application.properties} e variáveis do {@code .env}.
 *
 * @param jwt    token de acesso
 * @param bcrypt custo do BCrypt
 * @param grupos perfis aceitos em cada grupo de rotas: a chave {@code dona} vale para
 *               {@code /api/dona/**}, a {@code vendedor} para {@code /api/vendedor/**}. Perfil novo
 *               num grupo é um valor a mais aqui (por exemplo {@code ADMIN,GERENTE}), sem mudar classe.
 */
@ConfigurationProperties("corona.seguranca")
public record PropriedadesDeSeguranca(Jwt jwt, Bcrypt bcrypt, Map<String, List<String>> grupos) {

    public PropriedadesDeSeguranca {
        grupos = grupos == null ? Map.of() : Map.copyOf(grupos);
    }

    /**
     * @param emissor       valor do {@code iss}
     * @param duracao       validade do token de acesso (15 minutos)
     * @param chaveAtual    chave que assina os tokens novos
     * @param chaveAnterior chave antiga, aceita só na conferência, durante a troca de chave (opcional)
     */
    public record Jwt(String emissor, Duration duracao, Chave chaveAtual, Chave chaveAnterior) {
    }

    /**
     * Uma chave HS256: um nome curto ({@code kid}) e o valor em Base64.
     *
     * @param id    {@code kid}, vai no cabeçalho do token
     * @param valor segredo em Base64 (nunca aparece em log nem no {@link #toString()})
     */
    public record Chave(String id, String valor) {

        public boolean vazia() {
            return (id == null || id.isBlank()) && (valor == null || valor.isBlank());
        }

        @Override
        public String toString() {
            return "Chave[id=" + id + ", valor=(oculto)]";
        }
    }

    /** @param custo custo do BCrypt (12, ADR-0004 item 6) */
    public record Bcrypt(int custo) {
    }
}

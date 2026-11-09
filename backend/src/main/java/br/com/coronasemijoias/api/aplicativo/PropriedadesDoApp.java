package br.com.coronasemijoias.api.aplicativo;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração por tipo de app (ADR-0004, item 10).
 *
 * @param versaoMinima  {@code corona.app.versao-minima.gestao|vendas}, no formato 1.4.2 (vem do {@code .env})
 * @param perfisAceitos {@code corona.app.perfis-aceitos.gestao|vendas}: perfis que entram em cada app.
 *                      Perfil novo é um valor a mais aqui.
 */
@ConfigurationProperties("corona.app")
public record PropriedadesDoApp(Map<String, String> versaoMinima, Map<String, List<String>> perfisAceitos) {

    public PropriedadesDoApp {
        versaoMinima = versaoMinima == null ? Map.of() : Map.copyOf(versaoMinima);
        perfisAceitos = perfisAceitos == null ? Map.of() : Map.copyOf(perfisAceitos);
        for (TipoDeApp tipo : TipoDeApp.values()) {
            String minima = versaoMinima.get(tipo.codigo());
            if (minima == null || VersaoDoApp.ler(minima).isEmpty()) {
                throw new IllegalStateException("corona.app.versao-minima." + tipo.codigo()
                        + " precisa ter três números, por exemplo 1.0.0");
            }
            List<String> perfis = perfisAceitos.get(tipo.codigo());
            if (perfis == null || perfis.isEmpty()) {
                throw new IllegalStateException("corona.app.perfis-aceitos." + tipo.codigo() + " não pode ficar vazio");
            }
        }
    }

    public VersaoDoApp minima(TipoDeApp tipo) {
        return VersaoDoApp.ler(versaoMinima.get(tipo.codigo())).orElseThrow();
    }

    public boolean aceitaPerfil(TipoDeApp tipo, String perfil) {
        return perfisAceitos.get(tipo.codigo()).stream().anyMatch(p -> p.strip().equals(perfil));
    }
}

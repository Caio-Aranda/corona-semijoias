package br.com.coronasemijoias.api.seguranca;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Autenticação por token de acesso (JWT HS256) e primeira camada da permissão (ADR-0003 e ADR-0004).
 *
 * <ol>
 *   <li>Lista pública fechada ({@link RotasPublicas}): responde sem token.</li>
 *   <li>Grupo do prefixo: {@code /api/<grupo>/**} só aceita os perfis de
 *       {@code corona.seguranca.grupos.<grupo>}.</li>
 *   <li>Qualquer outra rota: exige token válido de usuário ativo.</li>
 *   <li>Segunda camada: {@link InterceptadorDePerfil} exige a {@link ExigePerfil} da rota.</li>
 * </ol>
 *
 * Sem sessão no servidor, sem cookie, sem CSRF (o token vai no cabeçalho {@code Authorization}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class ConfiguracaoDeSeguranca implements WebMvcConfigurer {

    private final InterceptadorDePerfil interceptadorDePerfil;

    public ConfiguracaoDeSeguranca(InterceptadorDePerfil interceptadorDePerfil) {
        this.interceptadorDePerfil = interceptadorDePerfil;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(interceptadorDePerfil);
    }

    @Bean
    ChavesDoToken chavesDoToken(PropriedadesDeSeguranca propriedades) {
        return ChavesDoToken.de(propriedades.jwt());
    }

    @Bean
    SecurityFilterChain cadeiaDeSeguranca(HttpSecurity http, RotasPublicas publicas, RespostasDeSeguranca respostas,
            ConversorDoToken conversor, JwtDecoder decodificador, PropriedadesDeSeguranca propriedades) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(respostas).accessDeniedHandler(respostas))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(ignorandoRotasPublicas(publicas))
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas)
                        .jwt(j -> j.decoder(decodificador).jwtAuthenticationConverter(conversor)))
                .authorizeHttpRequests(a -> {
                    // A página de erro do servidor (despacho de ERRO) só existe depois de uma falha já tratada.
                    a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    a.requestMatchers(publicas.comparador()).permitAll();
                    for (Map.Entry<String, List<String>> grupo : propriedades.grupos().entrySet()) {
                        String[] autoridades = grupo.getValue().stream()
                                .map(p -> ConversorDoToken.PREFIXO_DA_AUTORIDADE + p.strip())
                                .toArray(String[]::new);
                        a.requestMatchers("/api/" + grupo.getKey() + "/**").hasAnyAuthority(autoridades);
                    }
                    a.anyRequest().authenticated();
                });
        return http.build();
    }

    /**
     * Rota pública não lê o token: um token velho no cabeçalho do login não pode impedir de entrar.
     */
    private static BearerTokenResolver ignorandoRotasPublicas(RotasPublicas publicas) {
        DefaultBearerTokenResolver padrao = new DefaultBearerTokenResolver();
        return requisicao -> publicas.ehPublica(requisicao) ? null : padrao.resolve(requisicao);
    }

    /**
     * Confere o token: só HS256, {@code kid} obrigatório e igual ao da chave atual ou da anterior,
     * assinatura, validade sem tolerância de relógio (quem assina e quem confere é o mesmo servidor),
     * {@code iss} e presença de {@code sub}, {@code perfil} e {@code jti}. Token sem assinatura
     * ({@code alg: none}) é recusado.
     */
    @Bean
    JwtDecoder decodificadorDeToken(ChavesDoToken chaves, PropriedadesDeSeguranca propriedades, Clock relogio) {
        DefaultJWTProcessor<SecurityContext> processador = new DefaultJWTProcessor<>();
        processador.setJWSKeySelector((cabecalho, contexto) -> {
            if (!JWSAlgorithm.HS256.equals(cabecalho.getAlgorithm())) {
                return List.of();
            }
            SecretKey chave = chaves.porId(cabecalho.getKeyID());
            return chave == null ? List.of() : List.of(chave);
        });
        // As regras de validade ficam nos validadores do Spring, abaixo.
        processador.setJWTClaimsSetVerifier((claims, contexto) -> { });
        NimbusJwtDecoder decodificador = new NimbusJwtDecoder(processador);

        JwtTimestampValidator validade = new JwtTimestampValidator(Duration.ZERO);
        validade.setClock(relogio);
        decodificador.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                validade,
                new JwtIssuerValidator(propriedades.jwt().emissor()),
                camposObrigatorios()));
        return decodificador;
    }

    private static OAuth2TokenValidator<Jwt> camposObrigatorios() {
        OAuth2Error invalido = new OAuth2Error("invalid_token", "campos obrigatórios ausentes", null);
        return token -> {
            boolean ok = token.getExpiresAt() != null
                    && token.getIssuedAt() != null
                    && token.getId() != null
                    && token.getClaimAsString(ConteudoDoToken.PERFIL) != null
                    && ehUuid(token.getSubject());
            return ok ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(invalido);
        };
    }

    private static boolean ehUuid(String texto) {
        if (texto == null) {
            return false;
        }
        try {
            UUID.fromString(texto);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

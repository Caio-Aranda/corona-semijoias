package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Assina o token de acesso (HS256, {@code kid} da chave atual; ADR-0004, item 1). */
@Component
public class EmissorDeToken {

    /** Um token assinado e as horas (UTC) em que foi emitido e em que vence. */
    public record TokenEmitido(String valor, Instant emitidoEm, Instant expiraEm) {
    }

    private final NimbusJwtEncoder codificador;
    private final String idDaChave;
    private final String emissor;
    private final Duration duracao;
    private final Clock relogio;

    public EmissorDeToken(ChavesDoToken chaves, PropriedadesDeSeguranca propriedades, Clock relogio) {
        OctetSequenceKey chave = new OctetSequenceKey.Builder(chaves.atual())
                .keyID(chaves.idAtual())
                .algorithm(JWSAlgorithm.HS256)
                .build();
        this.codificador = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(chave)));
        this.idDaChave = chaves.idAtual();
        this.emissor = propriedades.jwt().emissor();
        this.duracao = propriedades.jwt().duracao();
        this.relogio = relogio;
    }

    public TokenEmitido emitir(ConteudoDoToken conteudo) {
        // Segundos inteiros: é a precisão do iat e do exp no token.
        Instant agora = relogio.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expira = agora.plus(duracao);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(emissor)
                .subject(conteudo.usuarioId().toString())
                .id(UuidV7.gerar().toString())
                .issuedAt(agora)
                .expiresAt(expira)
                .claim(ConteudoDoToken.PERFIL, conteudo.perfil());
        if (conteudo.vendedorId() != null) {
            claims.claim(ConteudoDoToken.VENDEDOR_ID, conteudo.vendedorId().toString());
        }
        if (conteudo.sid() != null) {
            claims.claim(ConteudoDoToken.SID, conteudo.sid().toString());
        }
        if (conteudo.dispositivoId() != null) {
            claims.claim(ConteudoDoToken.DISPOSITIVO_ID, conteudo.dispositivoId().toString());
        }
        if (conteudo.escopo() != null) {
            claims.claim(ConteudoDoToken.ESCOPO, conteudo.escopo());
        }
        JwsHeader cabecalho = JwsHeader.with(MacAlgorithm.HS256).keyId(idDaChave).type("JWT").build();
        String valor = codificador.encode(JwtEncoderParameters.from(cabecalho, claims.build())).getTokenValue();
        return new TokenEmitido(valor, agora, expira);
    }

    public Duration duracao() {
        return duracao;
    }
}

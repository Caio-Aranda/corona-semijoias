package br.com.coronasemijoias.api.testes;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Monta tokens "à mão" para os testes de recusa (vencido, chave errada, sem assinatura...), sem
 * passar pelo emissor da API.
 */
public final class TokensDeTeste {

    private TokensDeTeste() {
    }

    /** Conteúdo de um token válido de agora, para os testes mudarem só o que interessa. */
    public static JWTClaimsSet.Builder conteudo(UUID usuario, String perfil, UUID vendedorId) {
        Instant agora = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(TesteDeIntegracao.EMISSOR)
                .subject(usuario.toString())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(agora))
                .expirationTime(Date.from(agora.plusSeconds(900)))
                .claim("perfil", perfil);
        if (vendedorId != null) {
            claims.claim("vendedor_id", vendedorId.toString());
        }
        return claims;
    }

    /** Assina com HS256, a chave e o {@code kid} dados ({@code kid} nulo = sem {@code kid}). */
    public static String assinar(JWTClaimsSet claims, byte[] chave, String kid) {
        try {
            JWSHeader.Builder cabecalho = new JWSHeader.Builder(JWSAlgorithm.HS256);
            if (kid != null) {
                cabecalho.keyID(kid);
            }
            SignedJWT jwt = new SignedJWT(cabecalho.build(), claims);
            jwt.sign(new MACSigner(chave));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Token sem assinatura ({@code alg: none}). */
    public static String semAssinatura(JWTClaimsSet claims, String kid) {
        String cabecalho = "{\"alg\":\"none\",\"kid\":\"" + kid + "\"}";
        return base64Url(cabecalho) + "." + base64Url(claims.toString()) + ".";
    }

    /** Troca o conteúdo (segunda parte) de um token assinado, mantendo cabeçalho e assinatura. */
    public static String trocarConteudo(String token, JWTClaimsSet novoConteudo) {
        String[] partes = token.split("\\.");
        return partes[0] + "." + base64Url(novoConteudo.toString()) + "." + partes[2];
    }

    /** O JSON de uma parte do token (0 = cabeçalho, 1 = conteúdo). */
    public static String parte(String token, int indice) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[indice]), StandardCharsets.UTF_8);
    }

    private static String base64Url(String texto) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }
}

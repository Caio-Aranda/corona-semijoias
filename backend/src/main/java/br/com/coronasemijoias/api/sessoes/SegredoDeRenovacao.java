package br.com.coronasemijoias.api.sessoes;

import br.com.coronasemijoias.api.comum.configuracao.ConfiguracaoInvalidaException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Valores dos tokens de renovação (ADR-0004, item 2). O primeiro token da família é aleatório (256
 * bits); o sucessor é derivado com HMAC-SHA256 sobre o id do registro filho, com um segredo próprio do
 * {@code .env}, para o servidor devolver o mesmo sucessor dentro da janela de tolerância sem guardá-lo.
 * No banco vai só o SHA-256 do valor.
 *
 * <p>Conferido ao subir: ausente, fora do Base64, com menos de 256 bits ou igual a uma chave do token
 * de acesso impede a API de subir, com a explicação em português.
 */
@Component
public class SegredoDeRenovacao {

    static final int MINIMO_DE_BYTES = 32;

    private static final String COMO_GERAR = "Gere um valor novo com \"openssl rand -base64 32\" (ou com o comando do "
            + "PowerShell indicado no .env.example), coloque-o em SESSAO_SEGREDO_RENOVACAO no arquivo .env e rode de novo.";

    private final SecureRandom aleatorio = new SecureRandom();
    private final SecretKeySpec chave;

    public SegredoDeRenovacao(PropriedadesDaSessao propriedades,
            @Value("${corona.seguranca.jwt.chave-atual.valor:}") String chaveAtualDoToken,
            @Value("${corona.seguranca.jwt.chave-anterior.valor:}") String chaveAnteriorDoToken) {
        String valor = propriedades.segredoDeRenovacao();
        if (valor == null || valor.isBlank()) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque falta o segredo dos tokens de renovação: "
                            + "a variável SESSAO_SEGREDO_RENOVACAO está vazia.",
                    COMO_GERAR);
        }
        byte[] bytes = decodificar(valor);
        if (bytes.length < MINIMO_DE_BYTES) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque o segredo em SESSAO_SEGREDO_RENOVACAO não é válido: ele "
                            + "precisa ser um texto em Base64 com pelo menos 32 bytes (256 bits).",
                    COMO_GERAR);
        }
        if (Arrays.equals(bytes, decodificar(chaveAtualDoToken)) || Arrays.equals(bytes, decodificar(chaveAnteriorDoToken))) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque SESSAO_SEGREDO_RENOVACAO é igual a uma das chaves do token de "
                            + "acesso (JWT_CHAVE_ATUAL ou JWT_CHAVE_ANTERIOR).",
                    "Use um valor diferente para cada segredo. " + COMO_GERAR);
        }
        this.chave = new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** Valor do primeiro token de uma família: 256 bits aleatórios. */
    public String aleatorio() {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Valor do token de renovação cujo registro tem este id: sempre o mesmo para o mesmo id. */
    public String derivado(UUID idDoRegistro) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(chave);
            ByteBuffer id = ByteBuffer.allocate(16)
                    .putLong(idDoRegistro.getMostSignificantBits())
                    .putLong(idDoRegistro.getLeastSignificantBits());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(id.array()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponível", e);
        }
    }

    /** O que vai para o banco: o SHA-256 do valor. */
    public static byte[] hash(String valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    private static byte[] decodificar(String valor) {
        if (valor == null || valor.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(valor.strip());
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    @Override
    public String toString() {
        return "SegredoDeRenovacao[(oculto)]";
    }
}

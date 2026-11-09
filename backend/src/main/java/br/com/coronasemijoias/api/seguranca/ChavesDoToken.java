package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.comum.configuracao.ConfiguracaoInvalidaException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * As chaves HS256 do token de acesso (ADR-0004, item 1): a atual, que assina, e a anterior
 * (opcional), aceita só na conferência, para trocar o segredo sem derrubar ninguém.
 *
 * <p>Conferidas ao subir: chave atual ausente, fora do Base64, com menos de 256 bits ou com nome
 * inválido impede a API de subir, com a explicação em português.
 */
public final class ChavesDoToken {

    /** 256 bits. */
    static final int MINIMO_DE_BYTES = 32;

    private static final Pattern FORMATO_DO_ID = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private static final String COMO_GERAR = "Gere um valor novo com \"openssl rand -base64 32\" (ou com o comando do "
            + "PowerShell indicado no .env.example), coloque-o no arquivo .env e rode de novo.";

    private final String idAtual;
    private final SecretKey atual;
    private final Map<String, SecretKey> porId;

    private ChavesDoToken(String idAtual, SecretKey atual, Map<String, SecretKey> porId) {
        this.idAtual = idAtual;
        this.atual = atual;
        this.porId = Map.copyOf(porId);
    }

    /** Confere e monta as chaves a partir da configuração. */
    public static ChavesDoToken de(PropriedadesDeSeguranca.Jwt jwt) {
        PropriedadesDeSeguranca.Chave atual = jwt == null ? null : jwt.chaveAtual();
        if (atual == null || atual.valor() == null || atual.valor().isBlank()) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque falta a chave que assina os tokens de acesso: "
                            + "a variável JWT_CHAVE_ATUAL está vazia.",
                    COMO_GERAR + " Dê também um nome curto à chave em JWT_CHAVE_ATUAL_ID (por exemplo, chave-1).");
        }
        Map<String, SecretKey> porId = new LinkedHashMap<>();
        String idAtual = conferirId(atual.id(), "JWT_CHAVE_ATUAL_ID");
        SecretKey chaveAtual = decodificar(atual.valor(), "JWT_CHAVE_ATUAL");
        porId.put(idAtual, chaveAtual);

        PropriedadesDeSeguranca.Chave anterior = jwt.chaveAnterior();
        if (anterior != null && !anterior.vazia()) {
            if (anterior.valor() == null || anterior.valor().isBlank()) {
                throw new ConfiguracaoInvalidaException(
                        "A API não conseguiu começar porque JWT_CHAVE_ANTERIOR_ID está preenchida, "
                                + "mas JWT_CHAVE_ANTERIOR está vazia.",
                        "Preencha as duas variáveis da chave anterior, ou deixe as duas vazias, e rode de novo.");
            }
            String idAnterior = conferirId(anterior.id(), "JWT_CHAVE_ANTERIOR_ID");
            if (idAnterior.equals(idAtual)) {
                throw new ConfiguracaoInvalidaException(
                        "A API não conseguiu começar porque a chave atual e a anterior têm o mesmo nome "
                                + "(JWT_CHAVE_ATUAL_ID e JWT_CHAVE_ANTERIOR_ID).",
                        "Dê um nome novo à chave atual (por exemplo, chave-2) e rode de novo.");
            }
            porId.put(idAnterior, decodificar(anterior.valor(), "JWT_CHAVE_ANTERIOR"));
        }
        return new ChavesDoToken(idAtual, chaveAtual, porId);
    }

    public String idAtual() {
        return idAtual;
    }

    public SecretKey atual() {
        return atual;
    }

    /** A chave deste {@code kid}, ou {@code null} se o nome não for de nenhuma das duas. */
    public SecretKey porId(String id) {
        return id == null ? null : porId.get(id);
    }

    private static String conferirId(String id, String variavel) {
        if (id == null || !FORMATO_DO_ID.matcher(id.strip()).matches()) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque o nome da chave em " + variavel + " não é válido: "
                            + "use de 1 a 40 letras sem acento, números, ponto, hífen ou sublinhado.",
                    "Corrija " + variavel + " no arquivo .env (por exemplo, chave-1) e rode de novo.");
        }
        return id.strip();
    }

    private static SecretKey decodificar(String valor, String variavel) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(valor.strip());
        } catch (IllegalArgumentException e) {
            bytes = new byte[0];
        }
        if (bytes.length < MINIMO_DE_BYTES) {
            throw new ConfiguracaoInvalidaException(
                    "A API não conseguiu começar porque a chave em " + variavel + " não é válida: ela precisa "
                            + "ser um texto em Base64 com pelo menos 32 bytes (256 bits).",
                    COMO_GERAR);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}

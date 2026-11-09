package br.com.coronasemijoias.api.usuarios;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Senhas com BCrypt (ADR-0004, item 6). O custo vem da configuração ({@code corona.seguranca.bcrypt.custo}).
 *
 * <p>Toda conferência roda o BCrypt uma vez, mesmo quando o login não existe ou a senha passa de 72
 * bytes, para o tempo de resposta não revelar quais logins existem. Para o login inexistente, a
 * conferência é feita contra um hash fixo, gerado ao subir com o custo configurado.
 */
@Component
public class ConferenciaDeSenha {

    /** O BCrypt só lê os primeiros 72 bytes da senha. */
    public static final int LIMITE_DE_BYTES = 72;

    /** Usado no lugar da senha longa demais, só para gastar o mesmo tempo. Nunca confere. */
    private static final String SUBSTITUTA = "senha-longa-demais";

    private final BCryptPasswordEncoder bcrypt;
    private final String hashFixo;

    @Autowired
    public ConferenciaDeSenha(@Value("${corona.seguranca.bcrypt.custo}") int custo) {
        this(new BCryptPasswordEncoder(custo));
    }

    ConferenciaDeSenha(BCryptPasswordEncoder bcrypt) {
        this.bcrypt = bcrypt;
        this.hashFixo = bcrypt.encode(UUID.randomUUID().toString());
    }

    /**
     * Se a senha confere com o hash guardado. {@code hashGuardado} nulo = login inexistente: confere
     * com o hash fixo e devolve {@code false}. Senha com mais de 72 bytes nunca confere.
     */
    public boolean confere(String senha, String hashGuardado) {
        boolean tamanhoValido = senha != null && !senha.isEmpty() && cabe(senha);
        String hash = hashGuardado != null ? hashGuardado : hashFixo;
        boolean bate = bcrypt.matches(tamanhoValido ? senha : SUBSTITUTA, hash);
        return tamanhoValido && hashGuardado != null && bate;
    }

    /** Se o hash foi gerado com custo menor que o configurado (regravar no próximo login certo). */
    public boolean precisaRegravar(String hashGuardado) {
        return bcrypt.upgradeEncoding(hashGuardado);
    }

    /** Hash novo com o custo configurado. A senha precisa caber em 72 bytes ({@link #cabe(String)}). */
    public String gerarHash(String senha) {
        if (!cabe(senha)) {
            throw new IllegalArgumentException("senha com mais de " + LIMITE_DE_BYTES + " bytes");
        }
        return bcrypt.encode(senha);
    }

    public static boolean cabe(String senha) {
        return senha.getBytes(StandardCharsets.UTF_8).length <= LIMITE_DE_BYTES;
    }
}

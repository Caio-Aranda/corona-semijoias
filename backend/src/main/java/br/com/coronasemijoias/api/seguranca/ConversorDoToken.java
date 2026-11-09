package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Transforma o token já conferido (assinatura, validade, {@code iss}) no usuário da requisição. A cada
 * chamada consulta o usuário pela chave primária (ADR-0004, item 1): desativado perde o acesso no
 * próximo uso, mesmo com o token ainda válido. O perfil e o vendedor valem os do banco; se o token
 * disser outra coisa, ele está desatualizado e é recusado.
 */
@Component
public class ConversorDoToken implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Prefixo das autoridades do Spring Security: {@code PERFIL_ADMIN}, {@code PERFIL_VENDEDOR}... */
    public static final String PREFIXO_DA_AUTORIDADE = "PERFIL_";

    private final UsuarioRepositorio usuarios;

    public ConversorDoToken(UsuarioRepositorio usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        UUID id = uuid(token.getSubject());
        Usuario usuario = Optional.ofNullable(id).flatMap(usuarios::findById)
                .orElseThrow(() -> new BadCredentialsException("usuário do token não existe"));
        if (!usuario.isAtivo()) {
            throw new DisabledException("usuário desativado");
        }
        if (!usuario.getPerfil().equals(token.getClaimAsString(ConteudoDoToken.PERFIL))
                || !Objects.equals(usuario.getVendedorId(), uuid(token.getClaimAsString(ConteudoDoToken.VENDEDOR_ID)))) {
            throw new BadCredentialsException("perfil ou vendedor do token diferentes do cadastro");
        }
        UsuarioAutenticado principal = new UsuarioAutenticado(
                usuario.getId(), usuario.getPerfil(), usuario.getVendedorId(), token.getId(),
                uuid(token.getClaimAsString(ConteudoDoToken.SID)),
                uuid(token.getClaimAsString(ConteudoDoToken.DISPOSITIVO_ID)),
                token.getClaimAsString(ConteudoDoToken.ESCOPO));
        return new AutenticacaoPorToken(principal, token,
                List.of(new SimpleGrantedAuthority(PREFIXO_DA_AUTORIDADE + usuario.getPerfil())));
    }

    private static UUID uuid(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return UUID.fromString(texto);
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException("identificador inválido no token");
        }
    }
}

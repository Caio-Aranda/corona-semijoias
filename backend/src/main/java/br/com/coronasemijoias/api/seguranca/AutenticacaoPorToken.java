package br.com.coronasemijoias.api.seguranca;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Autenticação de uma requisição com token de acesso válido e usuário ativo. */
public final class AutenticacaoPorToken extends AbstractAuthenticationToken {

    private final UsuarioAutenticado usuario;
    private final transient Jwt token;

    AutenticacaoPorToken(UsuarioAutenticado usuario, Jwt token, Collection<? extends GrantedAuthority> autoridades) {
        super(autoridades);
        this.usuario = usuario;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public UsuarioAutenticado getPrincipal() {
        return usuario;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return usuario.id().toString();
    }
}

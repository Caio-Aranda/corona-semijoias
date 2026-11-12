package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.sessoes.MotivoDaRevogacao;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes;
import br.com.coronasemijoias.api.sessoes.ServicoDeSessoes.SituacaoDaSessao;
import br.com.coronasemijoias.api.sessoes.TipoDeSessao;
import br.com.coronasemijoias.api.usuarios.Usuario;
import br.com.coronasemijoias.api.usuarios.UsuarioRepositorio;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * disser outra coisa, ele está desatualizado e é recusado. Também confere pela chave primária a família
 * da sessão ({@code sid}): família revogada derruba o token no próximo uso.
 */
@Component
public class ConversorDoToken implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Prefixo das autoridades do Spring Security: {@code PERFIL_ADMIN}, {@code PERFIL_VENDEDOR}... */
    public static final String PREFIXO_DA_AUTORIDADE = "PERFIL_";

    private final UsuarioRepositorio usuarios;
    private final ServicoDeSessoes sessoes;

    public ConversorDoToken(UsuarioRepositorio usuarios, ServicoDeSessoes sessoes) {
        this.usuarios = usuarios;
        this.sessoes = sessoes;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        UUID id = uuid(token.getSubject());
        Usuario usuario = Optional.ofNullable(id).flatMap(usuarios::findById)
                .orElseThrow(() -> new BadCredentialsException("usuário do token não existe"));
        UUID dispositivo = uuid(token.getClaimAsString(ConteudoDoToken.DISPOSITIVO_ID));
        if (!usuario.isAtivo()) {
            // No Corona Vendas (token com computador), o vendedor desativado recebe a ordem de apagar (ADR-0013, item 14).
            if (dispositivo != null) {
                throw new ComputadorDesligado(CodigoErro.USUARIO_DESATIVADO);
            }
            throw new DisabledException("usuário desativado");
        }
        if (!usuario.getPerfil().equals(token.getClaimAsString(ConteudoDoToken.PERFIL))
                || !Objects.equals(usuario.getVendedorId(), uuid(token.getClaimAsString(ConteudoDoToken.VENDEDOR_ID)))) {
            throw new BadCredentialsException("perfil ou vendedor do token diferentes do cadastro");
        }
        // Senha redefinida ou trocada depois da emissão: o token é de antes e não vale mais. A hora da
        // troca é gravada em segundos inteiros, a mesma precisão do iat (ADR-0004, item 2).
        Instant alterada = usuario.getCredencialAlteradaEm();
        if (alterada != null && token.getIssuedAt().isBefore(alterada.truncatedTo(ChronoUnit.SECONDS))) {
            throw new BadCredentialsException("token emitido antes da última troca de credencial");
        }
        // Token restrito só para quem ainda deve trocar a senha; token comum só para quem já trocou.
        String escopo = token.getClaimAsString(ConteudoDoToken.ESCOPO);
        boolean restrito = ConteudoDoToken.ESCOPO_TROCA_SENHA.equals(escopo);
        if ((escopo != null && !restrito) || restrito != usuario.isTrocaDeSenhaObrigatoria()) {
            throw new BadCredentialsException("escopo do token não confere com a situação da senha");
        }
        // A família da sessão (ADR-0004, item 1): existe, é deste usuário, não foi revogada, é do mesmo
        // aparelho e do tipo que combina com o escopo. Revogar a família derruba o token na hora,
        // inclusive o emitido no mesmo segundo de uma redefinição.
        UUID sid = uuid(token.getClaimAsString(ConteudoDoToken.SID));
        SituacaoDaSessao sessao = sessoes.situacao(sid)
                .orElseThrow(() -> new BadCredentialsException("token sem sessão"));
        if (sessao.revogada() && sessao.motivo() == MotivoDaRevogacao.APARELHO_BLOQUEADO
                && sessao.dispositivoId() != null && sessao.dispositivoId().equals(dispositivo)
                && sessao.usuarioId().equals(usuario.getId())) {
            // Computador bloqueado: a ordem é apagar, não só entrar de novo. (O vendedor desativado já saiu acima; o
            // reativado, com a família revogada pela desativação, só entra de novo.)
            throw new ComputadorDesligado(CodigoErro.APARELHO_BLOQUEADO);
        }
        TipoDeSessao tipoEsperado = restrito ? TipoDeSessao.TROCA_SENHA : TipoDeSessao.NORMAL;
        if (sessao.revogada() || !sessao.usuarioId().equals(usuario.getId()) || sessao.tipo() != tipoEsperado
                || !Objects.equals(sessao.dispositivoId(), dispositivo)) {
            throw new BadCredentialsException("sessão do token revogada ou diferente");
        }
        UsuarioAutenticado principal = new UsuarioAutenticado(
                usuario.getId(), usuario.getPerfil(), usuario.getVendedorId(), token.getId(),
                sid, dispositivo, escopo);
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

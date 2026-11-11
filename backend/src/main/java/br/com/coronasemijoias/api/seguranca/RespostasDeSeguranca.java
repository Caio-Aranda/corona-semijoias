package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.Problemas;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Respostas 401 e 403 que saem da segurança, antes de chegar ao controlador. Mesmo formato do
 * {@code TratadorGlobalDeErros}: {@link ProblemDetail} com {@code codigo} e textos do glossário.
 *
 * <ul>
 *   <li>401 {@code SESSAO_TERMINADA}: sem token, vencido, adulterado, chave desconhecida...;</li>
 *   <li>401 {@code USUARIO_DESATIVADO}: token ainda válido de um usuário desativado (Corona Gestão);</li>
 *   <li>403 {@code APARELHO_BLOQUEADO} ou {@code USUARIO_DESATIVADO} com {@code acao: APAGAR_DADOS}: token de um
 *       computador do Corona Vendas bloqueado pela dona, ou de vendedor desativado (ADR-0013, item 14);</li>
 *   <li>403 {@code SEM_PERMISSAO}: perfil fora do grupo da rota;</li>
 *   <li>403 {@code TROCA_DE_SENHA_OBRIGATORIA}: token restrito fora da troca de senha.</li>
 * </ul>
 */
@Component
public class RespostasDeSeguranca implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    public RespostasDeSeguranca(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest requisicao, HttpServletResponse resposta, AuthenticationException erro)
            throws IOException {
        ComputadorDesligado desligado = desligado(erro);
        if (desligado != null) {
            // Computador bloqueado ou vendedor desativado: 403 com a ordem de apagar (ADR-0013, item 14).
            escrever(requisicao, resposta, HttpStatus.FORBIDDEN,
                    br.com.coronasemijoias.api.comum.erros.ErroDaApi.corpoComOrdemDeApagar(desligado.codigo()));
            return;
        }
        CodigoErro codigo = desativado(erro) ? CodigoErro.USUARIO_DESATIVADO : CodigoErro.SESSAO_TERMINADA;
        resposta.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        escrever(requisicao, resposta, HttpStatus.UNAUTHORIZED, codigo);
    }

    @Override
    public void handle(HttpServletRequest requisicao, HttpServletResponse resposta, AccessDeniedException erro)
            throws IOException {
        escrever(requisicao, resposta, HttpStatus.FORBIDDEN, CodigoErro.SEM_PERMISSAO);
    }

    /** 403: quem tem o token restrito só pode criar a senha nova. */
    public void trocaDeSenhaObrigatoria(HttpServletRequest requisicao, HttpServletResponse resposta)
            throws IOException {
        escrever(requisicao, resposta, HttpStatus.FORBIDDEN, CodigoErro.TROCA_DE_SENHA_OBRIGATORIA);
    }

    private static ComputadorDesligado desligado(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (atual instanceof ComputadorDesligado desligado) {
                return desligado;
            }
        }
        return null;
    }

    private static boolean desativado(Throwable erro) {
        for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
            if (atual instanceof DisabledException) {
                return true;
            }
        }
        return false;
    }

    private void escrever(HttpServletRequest requisicao, HttpServletResponse resposta, HttpStatus status,
            CodigoErro codigo) throws IOException {
        escrever(requisicao, resposta, status, Problemas.de(status, codigo));
    }

    /** Escreve um erro já montado, no mesmo formato do Spring MVC (usado também pelo filtro de tipo e versão). */
    public void escrever(HttpServletRequest requisicao, HttpServletResponse resposta, HttpStatus status,
            ProblemDetail problema) throws IOException {
        // Os mesmos campos que o Spring MVC escreve para um ProblemDetail.
        Map<String, Object> corpo = new LinkedHashMap<>();
        if (problema.getType() != null) {
            corpo.put("type", problema.getType().toString());
        }
        corpo.put("title", problema.getTitle());
        corpo.put("status", problema.getStatus());
        corpo.put("detail", problema.getDetail());
        corpo.put("instance", requisicao.getRequestURI());
        corpo.putAll(problema.getProperties());
        resposta.setStatus(status.value());
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.getWriter().write(json.writeValueAsString(corpo));
    }
}

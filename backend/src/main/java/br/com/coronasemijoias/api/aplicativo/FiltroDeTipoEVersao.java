package br.com.coronasemijoias.api.aplicativo;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.Problemas;
import br.com.coronasemijoias.api.seguranca.RespostasDeSeguranca;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lê {@code X-App-Tipo} e {@code X-App-Versao} em toda chamada, login incluído (ADR-0004, item 10).
 * Tipo ausente ou diferente de gestao/vendas: 400 {@code APP_TIPO_INVALIDO}. Versão ausente, ilegível
 * ou abaixo do mínimo do tipo: 426 {@code APP_DESATUALIZADO}. Não exigem cabeçalho: a saúde do servidor
 * e a documentação do OpenAPI.
 *
 * <p>O tipo é só conveniência (mensagem, versão mínima, registro): este filtro guarda o tipo na
 * requisição e nada mais; ninguém é autorizado por ele.
 */
@Component
@Order(FiltroDeTipoEVersao.ORDEM)
public class FiltroDeTipoEVersao extends OncePerRequestFilter {

    public static final String CABECALHO_TIPO = "X-App-Tipo";
    public static final String CABECALHO_VERSAO = "X-App-Versao";
    static final String ATRIBUTO = FiltroDeTipoEVersao.class.getName() + ".TIPO";

    /** Antes da cadeia do Spring Security (-100): o 426 vale também para quem nem tem token. */
    static final int ORDEM = Ordered.HIGHEST_PRECEDENCE + 50;

    private final PropriedadesDoApp propriedades;
    private final RespostasDeSeguranca respostas;

    public FiltroDeTipoEVersao(PropriedadesDoApp propriedades, RespostasDeSeguranca respostas) {
        this.propriedades = propriedades;
        this.respostas = respostas;
    }

    /** O tipo que o filtro validou para esta chamada (vazio nas rotas isentas). */
    public static Optional<TipoDeApp> tipoDe(HttpServletRequest requisicao) {
        return Optional.ofNullable((TipoDeApp) requisicao.getAttribute(ATRIBUTO));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI().substring(requisicao.getContextPath().length());
        return caminho.equals("/actuator/health") || caminho.startsWith("/actuator/health/")
                || caminho.equals("/v3/api-docs") || caminho.startsWith("/v3/api-docs/")
                || caminho.equals("/v3/api-docs.yaml") || caminho.startsWith("/v3/api-docs.yaml/")
                || caminho.equals("/swagger-ui.html") || caminho.startsWith("/swagger-ui/");
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        Optional<TipoDeApp> tipo = TipoDeApp.de(requisicao.getHeader(CABECALHO_TIPO));
        if (tipo.isEmpty()) {
            respostas.escrever(requisicao, resposta, HttpStatus.BAD_REQUEST,
                    Problemas.de(HttpStatus.BAD_REQUEST, CodigoErro.APP_TIPO_INVALIDO));
            return;
        }
        VersaoDoApp minima = propriedades.minima(tipo.get());
        Optional<VersaoDoApp> versao = VersaoDoApp.ler(requisicao.getHeader(CABECALHO_VERSAO));
        if (versao.isEmpty() || versao.get().compareTo(minima) < 0) {
            ProblemDetail problema = Problemas.de(HttpStatus.UPGRADE_REQUIRED, CodigoErro.APP_DESATUALIZADO);
            problema.setProperty("versaoMinima", minima.toString());
            problema.setProperty("versaoAtual", versao.map(VersaoDoApp::toString).orElse(null));
            respostas.escrever(requisicao, resposta, HttpStatus.UPGRADE_REQUIRED, problema);
            return;
        }
        requisicao.setAttribute(ATRIBUTO, tipo.get());
        cadeia.doFilter(requisicao, resposta);
    }
}

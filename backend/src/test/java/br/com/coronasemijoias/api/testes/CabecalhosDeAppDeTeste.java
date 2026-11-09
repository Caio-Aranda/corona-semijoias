package br.com.coronasemijoias.api.testes;

import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Os apps mandam X-App-Tipo e X-App-Versao em toda chamada. Para os testes não repetirem isso em cada
 * pedido, o MockMvc completa o que faltar: versão nova, e tipo {@code vendas} num login com aparelho
 * (como o Corona Vendas faria) ou {@code gestao} nos demais. O cabeçalho escrito no próprio pedido
 * sempre vale mais, e é assim que os testes de tipo e de versão mandam o que querem.
 */
public final class CabecalhosDeAppDeTeste {

    public static final String VERSAO_NOVA = "99.0.0";

    /** Pedido com este cabeçalho não é completado: serve para testar a ausência dos cabeçalhos de app. */
    public static final String NAO_COMPLETAR = "X-Teste-Nao-Completar";

    private CabecalhosDeAppDeTeste() {
    }

    /** Chamado pelo padrão de pedido de {@link IpsDeTeste} (o MockMvc só aceita um padrão por vez). */
    static MockHttpServletRequest completar(MockHttpServletRequest requisicao) {
        if (requisicao.getHeader(NAO_COMPLETAR) != null) {
            return requisicao;
        }
        if (requisicao.getHeader("X-App-Tipo") == null) {
            byte[] bytes = requisicao.getContentAsByteArray();
            String corpo = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            boolean login = requisicao.getRequestURI().endsWith("/api/auth/login");
            requisicao.addHeader("X-App-Tipo", login && corpo.contains("\"dispositivoId\"") ? "vendas" : "gestao");
        }
        if (requisicao.getHeader("X-App-Versao") == null) {
            requisicao.addHeader("X-App-Versao", VERSAO_NOVA);
        }
        return requisicao;
    }
}

package br.com.coronasemijoias.api.acessos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** O IP real (ADR-0004, item 7): o cabeçalho encaminhado só vale vindo de um proxy confiável. */
class IpDoClienteTest {

    private final IpDoCliente ip = new IpDoCliente(
            new PropriedadesDasTentativas(0, null, null, 0, null, null, List.of("192.0.2.10", " 172.18.0.0/16 ", "")));

    private String de(String conexao, String encaminhado) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr(conexao);
        if (encaminhado != null) {
            requisicao.addHeader("X-Forwarded-For", encaminhado);
        }
        return ip.de(requisicao);
    }

    @Test
    void semProxyValeOEnderecoDaConexao() {
        assertThat(de("203.0.113.7", null)).isEqualTo("203.0.113.7");
        assertThat(de("203.0.113.7", "198.51.100.1")).isEqualTo("203.0.113.7");
    }

    @Test
    void doProxyValeAEntradaMaisADireitaQueNaoEhProxy() {
        assertThat(de("192.0.2.10", "198.51.100.1")).isEqualTo("198.51.100.1");
        // O que o cliente escreveu à esquerda não vale; proxies confiáveis no caminho são pulados.
        assertThat(de("172.18.0.5", "1.1.1.1, 198.51.100.2, 192.0.2.10")).isEqualTo("198.51.100.2");
        // Proxy sem cabeçalho: vale o próprio proxy.
        assertThat(de("192.0.2.10", null)).isEqualTo("192.0.2.10");
    }

    @Test
    void entradaIlegivelNuncaViraIpNemConsultaNomes() {
        assertThat(de("192.0.2.10", "nome.qualquer.com")).isEqualTo("192.0.2.10");
        assertThat(de("192.0.2.10", "999.1.1.1")).isEqualTo("192.0.2.10");
        assertThat(de("192.0.2.10", "2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
    }
}

package br.com.coronasemijoias.api.testes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * O endereço de quem chama nos testes. Todo pedido do MockMvc sai de um IP aleatório (10.x.x.x): o
 * banco é o mesmo para todos os testes e o limite de tentativas conta erros por IP, então os erros de
 * um teste não bloqueiam os outros. Quem testa o limite fixa o IP com {@link #de(String)} (vale
 * sobre o aleatório, porque roda depois dele).
 */
@TestConfiguration(proxyBeanMethods = false)
public class IpsDeTeste {

    @Bean
    MockMvcBuilderCustomizer ipAleatorioEmCadaPedido() {
        return construtor -> construtor.defaultRequest(get("/").with(requisicao -> {
            requisicao.setRemoteAddr(aleatorio());
            return CabecalhosDeAppDeTeste.completar(requisicao);
        }));
    }

    /** Um IP 10.x.x.x novo. */
    public static String aleatorio() {
        ThreadLocalRandom sorteio = ThreadLocalRandom.current();
        return "10." + sorteio.nextInt(256) + "." + sorteio.nextInt(256) + "." + (1 + sorteio.nextInt(254));
    }

    /** O pedido sai deste IP (a conexão), em vez do aleatório. */
    public static RequestPostProcessor de(String ip) {
        return requisicao -> {
            requisicao.setRemoteAddr(ip);
            return requisicao;
        };
    }
}

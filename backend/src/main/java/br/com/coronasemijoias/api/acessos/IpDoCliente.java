package br.com.coronasemijoias.api.acessos;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * O IP real de quem pediu (ADR-0004, item 7). A API fica atrás do Nginx, mas o cabeçalho
 * {@code X-Forwarded-For} só vale quando a conexão vem de um proxy da lista
 * {@code corona.seguranca.tentativas.proxies-confiaveis}: sem isso, qualquer um mudaria o próprio IP
 * escrevendo o cabeçalho. O cabeçalho é lido da direita para a esquerda, pulando os proxies
 * confiáveis; o primeiro endereço de fora é o de quem pediu (as entradas da esquerda foram escritas
 * pelo próprio cliente e não valem nada).
 */
@Component
public class IpDoCliente {

    static final String CABECALHO = "X-Forwarded-For";

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$");

    private final List<IpAddressMatcher> confiaveis;

    public IpDoCliente(PropriedadesDasTentativas propriedades) {
        this.confiaveis = propriedades.proxiesConfiaveis().stream().map(IpAddressMatcher::new).toList();
    }

    public String de(HttpServletRequest requisicao) {
        String conexao = literal(requisicao.getRemoteAddr()).orElse(requisicao.getRemoteAddr());
        if (!confiavel(conexao)) {
            return conexao;
        }
        String encaminhado = requisicao.getHeader(CABECALHO);
        if (encaminhado == null || encaminhado.isBlank()) {
            return conexao;
        }
        String[] partes = encaminhado.split(",");
        for (int i = partes.length - 1; i >= 0; i--) {
            Optional<String> ip = literal(partes[i].strip());
            if (ip.isEmpty()) {
                // Entrada ilegível: daqui para a esquerda nada é confiável; fica o último proxy conhecido.
                return conexao;
            }
            if (!confiavel(ip.get())) {
                return ip.get();
            }
            conexao = ip.get();
        }
        return conexao;
    }

    private boolean confiavel(String ip) {
        return confiaveis.stream().anyMatch(m -> m.matches(ip));
    }

    /** O endereço na forma canônica, só se o texto for um IP literal (nunca consulta DNS). */
    static Optional<String> literal(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String limpo = texto.strip();
        if (limpo.startsWith("[") && limpo.endsWith("]")) {
            limpo = limpo.substring(1, limpo.length() - 1);
        }
        boolean ipv4 = IPV4.matcher(limpo).matches();
        if (ipv4) {
            for (String parte : limpo.split("\\.")) {
                if (Integer.parseInt(parte) > 255) {
                    return Optional.empty(); // senão o Java tentaria resolver o texto como nome (DNS)
                }
            }
        } else if (!IPV6.matcher(limpo).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(limpo).getHostAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}

package br.com.coronasemijoias.api.cotacoes;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** O cliente de verdade: HTTP direto ao BCB, sem seguir redirecionamentos, com tempo limite no pedido inteiro. */
@Component
public class ClienteHttpDoArquivo implements ClienteDoArquivo {

    /** O arquivo tem uns 12 KB; muito mais que isso não é o arquivo. */
    private static final int LIMITE_DE_BYTES = 2 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public RespostaDoArquivo buscar(URI endereco, Duration tempoLimite) throws IOException {
        HttpRequest pedido = HttpRequest.newBuilder(endereco).timeout(tempoLimite).GET().build();
        CompletableFuture<HttpResponse<byte[]>> envio = http.sendAsync(pedido, HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> resposta = envio.get(tempoLimite.toMillis(), TimeUnit.MILLISECONDS);
            byte[] corpo = resposta.body();
            if (corpo.length > LIMITE_DE_BYTES) {
                throw new IOException("A resposta do BCB é grande demais (" + corpo.length + " bytes).");
            }
            return new RespostaDoArquivo(resposta.statusCode(), new String(corpo, StandardCharsets.ISO_8859_1));
        } catch (TimeoutException e) {
            envio.cancel(true);
            throw new IOException("O BCB não respondeu em " + tempoLimite.toSeconds() + " segundos.", e);
        } catch (ExecutionException e) {
            throw new IOException("Falha ao falar com o BCB: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            envio.cancel(true);
            throw new IOException("A busca foi interrompida.", e);
        }
    }
}

package br.com.coronasemijoias.api.cotacoes;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * Quem busca o arquivo do BCB. Entra por parâmetro na {@link BuscaDeCotacoes} para os testes não
 * dependerem da internet.
 */
public interface ClienteDoArquivo {

    /**
     * @param tempoLimite tempo máximo do pedido inteiro
     * @throws IOException erro de rede, tempo esgotado ou resposta grande demais
     */
    RespostaDoArquivo buscar(URI endereco, Duration tempoLimite) throws IOException;

    /** Código HTTP e texto da resposta (o arquivo é só ASCII). */
    record RespostaDoArquivo(int status, String corpo) {
    }
}

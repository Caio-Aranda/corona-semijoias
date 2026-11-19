package br.com.coronasemijoias.api.consignado;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lê o arquivo de exemplos do consignado ({@code packages/shared/exemplos/consignado-acerto.json}), o MESMO que o Corona
 * Vendas lê nos testes dele (ADR-0016, item 16): as contas do servidor e do aplicativo não se afastam.
 */
final class ExemplosDoConsignado {

    private ExemplosDoConsignado() {
    }

    static JsonNode ler() {
        try {
            // O Maven roda na pasta backend/.
            Path arquivo = Path.of("..", "packages", "shared", "exemplos", "consignado-acerto.json");
            return JsonMapper.builder().build().readTree(Files.readString(arquivo));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static BigDecimal valor(JsonNode no) {
        return new BigDecimal(no.asString());
    }
}

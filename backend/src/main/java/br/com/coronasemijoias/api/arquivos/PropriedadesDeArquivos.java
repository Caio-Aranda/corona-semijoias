package br.com.coronasemijoias.api.arquivos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Armazenamento de arquivos ({@code ARQUIVOS_PASTA} do {@code .env}).
 *
 * @param pasta         pasta do servidor onde os arquivos ficam (criada na primeira gravação)
 * @param tamanhoMaximo maior arquivo aceito (10 MB, como as fotos da §20)
 */
@ConfigurationProperties("corona.arquivos")
public record PropriedadesDeArquivos(
        @DefaultValue("./dados/arquivos") String pasta,
        @DefaultValue("10MB") DataSize tamanhoMaximo) {
}

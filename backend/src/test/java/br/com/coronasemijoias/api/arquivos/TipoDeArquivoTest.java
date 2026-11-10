package br.com.coronasemijoias.api.arquivos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TipoDeArquivoTest {

    @Test
    void reconheceCadaTipoPeloConteudo() {
        assertThat(TipoDeArquivo.identificar("%PDF-1.7 resto".getBytes(StandardCharsets.US_ASCII)))
                .contains(TipoDeArquivo.PDF);
        assertThat(TipoDeArquivo.identificar(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0}))
                .contains(TipoDeArquivo.JPEG);
        assertThat(TipoDeArquivo.identificar(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0}))
                .contains(TipoDeArquivo.PNG);
        assertThat(TipoDeArquivo.identificar("RIFF\u0010\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)))
                .contains(TipoDeArquivo.WEBP);
    }

    @Test
    void recusaOQueNaoEDeUmTipoAceito() {
        assertThat(TipoDeArquivo.identificar(new byte[0])).isEmpty();
        assertThat(TipoDeArquivo.identificar("MZ programa".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(TipoDeArquivo.identificar("<html>".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        // RIFF que não é WebP (por exemplo, um WAV) e arquivos curtos demais.
        assertThat(TipoDeArquivo.identificar("RIFF\u0010\u0000\u0000\u0000WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1)))
                .isEmpty();
        assertThat(TipoDeArquivo.identificar(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
    }

    @Test
    void voltaDoTipoGuardadoNoBanco() {
        for (TipoDeArquivo tipo : TipoDeArquivo.values()) {
            assertThat(TipoDeArquivo.doTipoDeConteudo(tipo.tipoDeConteudo())).isEqualTo(Optional.of(tipo));
        }
        assertThat(TipoDeArquivo.doTipoDeConteudo("text/html")).isEmpty();
    }
}

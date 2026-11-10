package br.com.coronasemijoias.api.arquivos;

import java.util.Optional;

/**
 * Tipos de arquivo que o sistema aceita (foto JPG, PNG e WebP, e PDF). O tipo vale pelo
 * <strong>conteúdo</strong> (os primeiros bytes), nunca pelo nome ou pelo tipo que o navegador disse:
 * um programa renomeado para ".pdf" não passa.
 */
public enum TipoDeArquivo {

    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String tipoDeConteudo;
    private final String extensao;

    TipoDeArquivo(String tipoDeConteudo, String extensao) {
        this.tipoDeConteudo = tipoDeConteudo;
        this.extensao = extensao;
    }

    public String tipoDeConteudo() {
        return tipoDeConteudo;
    }

    public String extensao() {
        return extensao;
    }

    /** O tipo guardado no banco de volta para o enum. */
    public static Optional<TipoDeArquivo> doTipoDeConteudo(String tipoDeConteudo) {
        for (TipoDeArquivo tipo : values()) {
            if (tipo.tipoDeConteudo.equals(tipoDeConteudo)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }

    /** Reconhece o tipo pelos primeiros bytes; vazio se não for nenhum dos aceitos. */
    public static Optional<TipoDeArquivo> identificar(byte[] c) {
        if (comecaCom(c, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        if (comecaCom(c, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (comecaCom(c, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        // WebP: "RIFF", 4 bytes de tamanho e "WEBP".
        if (c.length >= 12 && comecaCom(c, 'R', 'I', 'F', 'F') && c[8] == 'W' && c[9] == 'E' && c[10] == 'B'
                && c[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean comecaCom(byte[] conteudo, int... esperado) {
        if (conteudo.length < esperado.length) {
            return false;
        }
        for (int i = 0; i < esperado.length; i++) {
            if ((conteudo[i] & 0xFF) != esperado[i]) {
                return false;
            }
        }
        return true;
    }
}

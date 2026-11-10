package br.com.coronasemijoias.api.arquivos;

import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Guarda os arquivos numa pasta do servidor, em {@code <pasta>/<area>/<dois primeiros do id>/<id>.<ext>}.
 * A gravação é atômica (arquivo temporário e depois renomeia), então ninguém lê meio arquivo. A chave
 * é conferida contra um formato fixo e o caminho final tem de ficar dentro da pasta: uma chave com
 * {@code ..} ou barra a mais é recusada.
 */
@Component
public class ArmazenamentoEmDisco implements ArmazenamentoDeArquivos {

    private static final Pattern AREA = Pattern.compile("[a-z][a-z0-9-]{0,30}");
    private static final Pattern EXTENSAO = Pattern.compile("[a-z0-9]{1,5}");
    private static final Pattern CHAVE = Pattern.compile(
            "[a-z][a-z0-9-]{0,30}/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,5}");

    private final Path raiz;

    public ArmazenamentoEmDisco(PropriedadesDeArquivos propriedades) {
        this.raiz = Path.of(propriedades.pasta()).toAbsolutePath().normalize();
    }

    @Override
    public String guardar(String area, byte[] conteudo, String extensao) {
        if (area == null || !AREA.matcher(area).matches()) {
            throw new IllegalArgumentException("Área de arquivos inválida: " + area);
        }
        if (extensao == null || !EXTENSAO.matcher(extensao).matches()) {
            throw new IllegalArgumentException("Extensão de arquivo inválida: " + extensao);
        }
        String id = UuidV7.gerar().toString();
        String chave = area + "/" + id.substring(0, 2) + "/" + id + "." + extensao;
        Path destino = caminhoDe(chave);
        try {
            Files.createDirectories(destino.getParent());
            Path temporario = Files.createTempFile(destino.getParent(), "envio-", ".tmp");
            try {
                Files.write(temporario, conteudo);
                Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporario);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar o arquivo " + chave, e);
        }
        return chave;
    }

    @Override
    public byte[] ler(String chave) {
        try {
            return Files.readAllBytes(caminhoDe(chave));
        } catch (NoSuchFileException e) {
            throw new ArquivoNaoEncontradoException(chave);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível ler o arquivo " + chave, e);
        }
    }

    @Override
    public boolean existe(String chave) {
        return Files.isRegularFile(caminhoDe(chave));
    }

    private Path caminhoDe(String chave) {
        if (chave == null || !CHAVE.matcher(chave).matches()) {
            throw new IllegalArgumentException("Chave de arquivo inválida: " + chave);
        }
        Path caminho = raiz.resolve(chave).normalize();
        if (!caminho.startsWith(raiz)) {
            throw new IllegalArgumentException("Chave de arquivo fora da pasta: " + chave);
        }
        return caminho;
    }
}

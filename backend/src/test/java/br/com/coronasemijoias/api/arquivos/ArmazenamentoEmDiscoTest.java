package br.com.coronasemijoias.api.arquivos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ArmazenamentoEmDiscoTest {

    @TempDir
    Path pasta;

    private ArmazenamentoEmDisco armazenamento() {
        return new ArmazenamentoEmDisco(new PropriedadesDeArquivos(pasta.toString(), DataSize.ofMegabytes(10)));
    }

    @Test
    void guardaELeDeVolta() {
        ArmazenamentoEmDisco a = armazenamento();
        byte[] conteudo = {1, 2, 3, 4};
        String chave = a.guardar("vendedores", conteudo, "pdf");
        assertThat(chave).matches("vendedores/[0-9a-f]{2}/[0-9a-f-]{36}\\.pdf");
        assertThat(a.existe(chave)).isTrue();
        assertThat(a.ler(chave)).isEqualTo(conteudo);
    }

    @Test
    void chavesSaoDiferentesParaOMesmoConteudo() {
        ArmazenamentoEmDisco a = armazenamento();
        assertThat(a.guardar("vendedores", new byte[] {1}, "png"))
                .isNotEqualTo(a.guardar("vendedores", new byte[] {1}, "png"));
    }

    @Test
    void naoDeixaArquivoTemporarioParaTras() throws IOException {
        armazenamento().guardar("vendedores", new byte[] {9}, "jpg");
        try (Stream<Path> todos = Files.walk(pasta)) {
            assertThat(todos.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void arquivoQueNaoExisteDaErroClaro() {
        ArmazenamentoEmDisco a = armazenamento();
        String chave = "vendedores/ab/01234567-89ab-cdef-0123-456789abcdef.pdf";
        assertThat(a.existe(chave)).isFalse();
        assertThatThrownBy(() -> a.ler(chave)).isInstanceOf(ArquivoNaoEncontradoException.class);
    }

    @Test
    void recusaChaveQueTentaSairDaPasta() {
        ArmazenamentoEmDisco a = armazenamento();
        for (String chave : new String[] {"../segredo.pdf", "vendedores/../../x.pdf", "/etc/passwd",
                "vendedores/ab/../../x.pdf", "vendedores/ab/nao-e-uuid.pdf", "C:\\Windows\\win.ini", "",
                "vendedores\\ab\\01234567-89ab-cdef-0123-456789abcdef.pdf"}) {
            assertThatThrownBy(() -> a.ler(chave)).as(chave).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> a.existe(chave)).as(chave).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> a.guardar("../fora", new byte[] {1}, "pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.guardar("vendedores", new byte[] {1}, "../x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

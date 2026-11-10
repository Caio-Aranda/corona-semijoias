package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.erros.CampoInvalido;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.Identificadores;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Conferências dos cadastros que a anotação não resolve. Todas recusam com 400 e o nome do campo. */
final class Validacoes {

    private Validacoes() {
    }

    static ErroDaApi recusar(String campo, String mensagem) {
        return new ErroDaApi(HttpStatus.BAD_REQUEST, CodigoErro.DADOS_INVALIDOS,
                List.of(new CampoInvalido(campo, mensagem)));
    }

    /** O id que o aplicativo gerou, ou um novo (UUID v7) se não veio; só UUID v4 ou v7 (ADR-0002). */
    static UUID idDoPedido(UUID id) {
        if (id == null) {
            return UuidV7.gerar();
        }
        if (!Identificadores.aceito(id)) {
            throw recusar("id", "Este identificador não é válido. Atualize o aplicativo e tente de novo.");
        }
        return id;
    }

    /** A versão que a tela leu, obrigatória para editar. */
    static int versaoDoPedido(Integer versao) {
        if (versao == null || versao < 1) {
            throw recusar("version", "Atualize a tela e tente de novo.");
        }
        return versao;
    }

    /** Texto sem espaços nas pontas; vazio vira {@code null}. */
    static String limpar(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.strip();
        return limpo.isEmpty() ? null : limpo;
    }

    /** CPF opcional: devolve só os dígitos, {@code null} se vazio, ou recusa se estiver errado. */
    static String cpfOpcional(String digitado) {
        String cpf = Documentos.normalizarCpf(digitado);
        if (cpf != null && !Documentos.cpfValido(cpf)) {
            throw recusar("cpf", "Confira o CPF: ele não parece estar certo.");
        }
        return cpf;
    }

    /** CNPJ opcional: igual ao CPF. */
    static String cnpjOpcional(String digitado) {
        String cnpj = Documentos.normalizarCnpj(digitado);
        if (cnpj != null && !Documentos.cnpjValido(cnpj)) {
            throw recusar("cnpj", "Confira o CNPJ: ele não parece estar certo.");
        }
        return cnpj;
    }
}

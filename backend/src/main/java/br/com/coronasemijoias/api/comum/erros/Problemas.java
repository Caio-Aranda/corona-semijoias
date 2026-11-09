package br.com.coronasemijoias.api.comum.erros;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Monta o corpo de erro da API: {@link ProblemDetail} com título e mensagem em português e o campo
 * {@code codigo}. Usado pelo {@link TratadorGlobalDeErros} e pelas respostas da segurança (401 e 403
 * que saem antes de chegar ao controlador), para todo erro ter o mesmo formato.
 */
public final class Problemas {

    private Problemas() {
    }

    public static ProblemDetail de(HttpStatusCode status, CodigoErro codigo) {
        ProblemDetail problema = ProblemDetail.forStatus(status);
        problema.setTitle(codigo.titulo());
        problema.setDetail(codigo.mensagem());
        problema.setProperty("codigo", codigo.name());
        return problema;
    }
}

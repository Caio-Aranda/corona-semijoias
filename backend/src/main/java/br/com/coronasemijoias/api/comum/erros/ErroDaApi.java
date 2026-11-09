package br.com.coronasemijoias.api.comum.erros;

import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Erro previsto, com status e código próprios (por exemplo, login recusado). O
 * {@link TratadorGlobalDeErros} devolve o corpo pronto, sem trocar o código.
 */
public class ErroDaApi extends ErrorResponseException {

    private final CodigoErro codigo;

    public ErroDaApi(HttpStatusCode status, CodigoErro codigo) {
        super(status, Problemas.de(status, codigo), null);
        this.codigo = codigo;
    }

    /** Erro previsto com a lista dos campos que não passaram (mesmo formato do {@code DADOS_INVALIDOS}). */
    public ErroDaApi(HttpStatusCode status, CodigoErro codigo, List<CampoInvalido> campos) {
        super(status, comCampos(status, codigo, campos), null);
        this.codigo = codigo;
    }

    private static ProblemDetail comCampos(HttpStatusCode status, CodigoErro codigo, List<CampoInvalido> campos) {
        ProblemDetail problema = Problemas.de(status, codigo);
        problema.setProperty("campos", campos);
        return problema;
    }

    public CodigoErro codigo() {
        return codigo;
    }
}

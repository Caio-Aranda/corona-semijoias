package br.com.coronasemijoias.api.comum.erros;

import org.springframework.http.HttpStatusCode;
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

    public CodigoErro codigo() {
        return codigo;
    }
}

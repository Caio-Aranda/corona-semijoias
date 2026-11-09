package br.com.coronasemijoias.api.comum.erros;

import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    private ErroDaApi(HttpStatusCode status, CodigoErro codigo, ProblemDetail corpo) {
        super(status, corpo, null);
        this.codigo = codigo;
    }

    /**
     * 429 do bloqueio por tentativas (ADR-0004, item 7): {@code Retry-After} com os segundos exatos e
     * o texto do glossário com {@code {minutos}} = o que falta, arredondado para cima, no mínimo 1.
     */
    public static ErroDaApi acessoBloqueado(Duration falta) {
        long segundos = Math.max(1, falta.toSeconds() + (falta.toNanosPart() > 0 ? 1 : 0));
        long minutos = Math.max(1, (segundos + 59) / 60);
        CodigoErro codigo = CodigoErro.ACESSO_BLOQUEADO;
        ProblemDetail corpo = Problemas.de(HttpStatus.TOO_MANY_REQUESTS, codigo);
        String espera = minutos == 1 ? "1 minuto" : minutos + " minutos";
        corpo.setDetail(codigo.mensagem().replace("{minutos} minutos", espera));
        ErroDaApi erro = new ErroDaApi(HttpStatus.TOO_MANY_REQUESTS, codigo, corpo);
        erro.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(segundos));
        return erro;
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

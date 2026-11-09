package br.com.coronasemijoias.api.comum.erros;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

/**
 * Único lugar que transforma erro em resposta. Devolve sempre um {@link ProblemDetail} com o campo
 * {@code codigo} (para o programa) e texto em português simples (para a pessoa). Nunca devolve
 * pilha de erro, nome de classe, texto em inglês nem número de status dentro da mensagem.
 */
@RestControllerAdvice
public class TratadorGlobalDeErros extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorGlobalDeErros.class);

    private static final String CAMPO_GENERICO = "Confira este campo e tente de novo.";

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> tratarInesperado(Exception ex, HttpServletRequest requisicao) {
        // O detalhe técnico vai só para o log.
        LOG.error("Erro inesperado em {} {}", requisicao.getMethod(), requisicao.getRequestURI(), ex);
        ProblemDetail problema = problema(HttpStatus.INTERNAL_SERVER_ERROR, CodigoErro.ERRO_INTERNO);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problema);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object corpo, HttpHeaders cabecalhos, HttpStatusCode status, WebRequest requisicao) {
        if (corpo instanceof ProblemDetail pronto && pronto.getProperties() != null
                && pronto.getProperties().containsKey("codigo")) {
            return super.handleExceptionInternal(ex, pronto, cabecalhos, status, requisicao);
        }
        CodigoErro codigo = escolherCodigo(ex, status);
        if (status.is5xxServerError()) {
            LOG.error("Erro do servidor ({}): {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
        return super.handleExceptionInternal(ex, problema(status, codigo), cabecalhos, status, requisicao);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders cabecalhos, HttpStatusCode status, WebRequest requisicao) {
        List<CampoInvalido> campos = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(
                e -> campos.add(new CampoInvalido(e.getField(), mensagemDoCampo(e.getDefaultMessage()))));
        ex.getBindingResult().getGlobalErrors().forEach(
                e -> campos.add(new CampoInvalido(e.getObjectName(), mensagemDoCampo(e.getDefaultMessage()))));
        return comCampos(ex, cabecalhos, status, requisicao, campos);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders cabecalhos, HttpStatusCode status, WebRequest requisicao) {
        List<CampoInvalido> campos = new ArrayList<>();
        ex.getParameterValidationResults().forEach(resultado -> {
            String nome = resultado.getMethodParameter().getParameterName();
            for (MessageSourceResolvable erro : resultado.getResolvableErrors()) {
                String campo = erro instanceof FieldError fe ? fe.getField() : (nome != null ? nome : "valor");
                campos.add(new CampoInvalido(campo, mensagemDoCampo(erro.getDefaultMessage())));
            }
        });
        return comCampos(ex, cabecalhos, status, requisicao, campos);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders cabecalhos, HttpStatusCode status, WebRequest requisicao) {
        // Corpo com JSON válido, mas com valor no formato errado (por exemplo, dinheiro como número).
        DatabindException erroDeDado = procurarErroDeDado(ex);
        if (erroDeDado != null) {
            String campo = erroDeDado.getPath().stream()
                    .map(r -> r.getPropertyName() != null ? r.getPropertyName() : "[" + r.getIndex() + "]")
                    .collect(Collectors.joining("."));
            List<CampoInvalido> campos = List.of(new CampoInvalido(campo.isEmpty() ? "corpo" : campo, CAMPO_GENERICO));
            return comCampos(ex, cabecalhos, HttpStatus.BAD_REQUEST, requisicao, campos);
        }
        // JSON quebrado, corpo ausente etc.
        return super.handleHttpMessageNotReadable(ex, cabecalhos, status, requisicao);
    }

    private ResponseEntity<Object> comCampos(
            Exception ex, HttpHeaders cabecalhos, HttpStatusCode status, WebRequest requisicao, List<CampoInvalido> campos) {
        ProblemDetail problema = problema(status, CodigoErro.DADOS_INVALIDOS);
        problema.setProperty("campos", campos);
        return handleExceptionInternal(ex, problema, cabecalhos, status, requisicao);
    }

    private static DatabindException procurarErroDeDado(Throwable ex) {
        for (Throwable atual = ex; atual != null; atual = atual.getCause()) {
            if (atual instanceof DatabindException dado) {
                return dado;
            }
            if (atual instanceof JacksonException) {
                return null; // erro de sintaxe do JSON, não de dado
            }
        }
        return null;
    }

    private static CodigoErro escolherCodigo(Exception ex, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return CodigoErro.ERRO_INTERNO;
        }
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return CodigoErro.ROTA_NAO_ENCONTRADA;
        }
        return CodigoErro.PEDIDO_INVALIDO;
    }

    private static String mensagemDoCampo(String mensagem) {
        return mensagem == null || mensagem.isBlank() ? CAMPO_GENERICO : mensagem;
    }

    private static ProblemDetail problema(HttpStatusCode status, CodigoErro codigo) {
        ProblemDetail problema = ProblemDetail.forStatus(status);
        problema.setTitle(codigo.titulo());
        problema.setDetail(codigo.mensagem());
        problema.setProperty("codigo", codigo.name());
        return problema;
    }
}

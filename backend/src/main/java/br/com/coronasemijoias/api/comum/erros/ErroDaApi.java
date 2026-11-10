package br.com.coronasemijoias.api.comum.erros;

import br.com.coronasemijoias.api.aplicativo.TipoDeApp;
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

    /** 401 do login: o servidor acrescenta a última frase do glossário conforme o aplicativo. */
    public static ErroDaApi loginInvalido(TipoDeApp app) {
        String frase = app == TipoDeApp.GESTAO
                ? "Se esqueceu a senha, fale com o suporte do sistema."
                : "Se esqueceu a senha, peça uma nova à dona da loja.";
        return comFrase(HttpStatus.UNAUTHORIZED, CodigoErro.LOGIN_INVALIDO, frase);
    }

    /** 401 da senha temporária vencida: última frase por aplicativo (glossário). */
    public static ErroDaApi senhaTemporariaVencida(TipoDeApp app) {
        String frase = app == TipoDeApp.GESTAO
                ? "Peça uma senha nova a outra pessoa administradora ou fale com o suporte do sistema."
                : "Peça uma senha nova à dona da loja.";
        return comFrase(HttpStatus.UNAUTHORIZED, CodigoErro.SENHA_TEMPORARIA_VENCIDA, frase);
    }

    /** 403 do perfil que não entra neste aplicativo (glossário, "Aplicativo errado para o perfil"). */
    public static ErroDaApi appIncompativel(TipoDeApp app) {
        String titulo = "Este é o " + app.nome() + ".";
        String resto = app == TipoDeApp.VENDAS
                ? "Seu acesso é de dona da loja. Para entrar, abra o Corona Gestão."
                : "Seu acesso é de vendedor. Para entrar, abra o Corona Vendas.";
        ProblemDetail corpo = Problemas.de(HttpStatus.FORBIDDEN, CodigoErro.APP_INCOMPATIVEL);
        corpo.setTitle(titulo);
        corpo.setDetail(titulo + " " + resto);
        return new ErroDaApi(HttpStatus.FORBIDDEN, CodigoErro.APP_INCOMPATIVEL, corpo);
    }

    /**
     * 409 do código que outro produto já usa (ADR-0007). Diz qual produto, quando se sabe; se dois pedidos
     * chegaram ao mesmo tempo, o banco só diz que o código já foi usado.
     */
    public static ErroDaApi codigoJaUsado(String codigo, String nomeDoProduto) {
        CodigoErro tipo = CodigoErro.CODIGO_JA_USADO;
        ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, tipo);
        if (codigo != null && nomeDoProduto != null) {
            corpo.setDetail("O código " + codigo + " já é usado pelo produto " + nomeDoProduto
                    + ". Use outro código ou abra esse produto.");
        }
        return new ErroDaApi(HttpStatus.CONFLICT, tipo, corpo);
    }

    /** 409 do estoque que não basta: diz quanto há e de qual produto (glossário, parte 12). */
    public static ErroDaApi estoqueInsuficiente(int ha, String nomeDoProduto) {
        CodigoErro tipo = CodigoErro.ESTOQUE_INSUFICIENTE;
        ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, tipo);
        corpo.setDetail("Não há peças suficientes no estoque. Há " + ha + " peça(s) de \"" + nomeDoProduto
                + "\". Confira a quantidade ou receba a compra antes.");
        return new ErroDaApi(HttpStatus.CONFLICT, tipo, corpo);
    }

    /** 409 de compra que não dá para desfazer: já saíram peças e o estoque não cobre (glossário, parte 12). */
    public static ErroDaApi compraNaoPodeSerDesfeita(String nomeDoProduto, int ha) {
        CodigoErro tipo = CodigoErro.COMPRA_NAO_PODE_SER_DESFEITA;
        ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, tipo);
        corpo.setDetail("Não é possível desfazer esta compra. De \"" + nomeDoProduto + "\" já saíram peças e há só " + ha
                + " no estoque. Confira o estoque antes de desfazer.");
        return new ErroDaApi(HttpStatus.CONFLICT, tipo, corpo);
    }

    /** 409 do fechamento da contagem: o ajuste deixaria a peça abaixo de zero (glossário, parte 12). */
    public static ErroDaApi contagemNaoPodeFechar(String nomeDoProduto, int ha, int diferenca) {
        CodigoErro tipo = CodigoErro.CONTAGEM_NAO_PODE_FECHAR;
        ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, tipo);
        corpo.setDetail("Não é possível fechar esta contagem. De \"" + nomeDoProduto + "\" há " + ha
                + " no estoque agora e a contagem pede tirar " + Math.abs(diferenca)
                + ". Confira a peça, lance a compra que falta ou refaça a contagem dela.");
        return new ErroDaApi(HttpStatus.CONFLICT, tipo, corpo);
    }

    private static ErroDaApi comFrase(HttpStatus status, CodigoErro codigo, String frase) {
        ProblemDetail corpo = Problemas.de(status, codigo);
        corpo.setDetail(codigo.mensagem() + " " + frase);
        return new ErroDaApi(status, codigo, corpo);
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

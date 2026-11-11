package br.com.coronasemijoias.api.seguranca;

import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import org.springframework.security.core.AuthenticationException;

/**
 * O token é de um computador do Corona Vendas que a dona bloqueou, ou de um vendedor desativado (ADR-0013, item 14). A
 * resposta é 403 com a ordem de apagar os dados ({@link RespostasDeSeguranca}), e não o 401 de "entre de novo".
 */
public class ComputadorDesligado extends AuthenticationException {

    private final CodigoErro codigo;

    public ComputadorDesligado(CodigoErro codigo) {
        super(codigo.name());
        this.codigo = codigo;
    }

    public CodigoErro codigo() {
        return codigo;
    }
}

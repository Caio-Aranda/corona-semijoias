package br.com.coronasemijoias.api.cadastros;

import br.com.coronasemijoias.api.comum.persistencia.Excluivel;

/**
 * Cadastro que pode ser inativado e reativado (ESPECIFICACAO §9, v1.7). Inativar não é excluir: o
 * registro some das escolhas e das buscas por padrão, mas continua nos relatórios e nos históricos.
 */
public interface Ativavel extends Excluivel {

    boolean isAtivo();

    void definirAtivo(boolean ativo);
}

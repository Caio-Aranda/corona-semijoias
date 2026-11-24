package br.com.coronasemijoias.api.conciliacoes;

import java.util.List;

/**
 * Uma conferência que entra na rodada da conferência diária (tarefa 8.4). Quem implementa só LÊ: chama a conferência que já existe
 * (sem copiar consulta) e devolve as linhas que não batem, já num formato só. A rodada nunca corrige nada.
 */
public interface ConferenciaDaRodada {

    /** A chave guardada na rodada: ESTOQUE, DEVOLUCAO_E_TROCA, C1 ... C12. */
    String chave();

    /** As linhas que não batem (vazio quando está tudo certo). Pode lançar erro: a rodada guarda a chave e segue para a próxima. */
    List<LinhaDeDiferenca> conferir();

    /**
     * Uma linha que não bate. Esperado e encontrado são o número exato em texto ({@code BigDecimal.toPlainString}); a unidade é
     * REAIS, PECAS, REGISTROS ou PERCENTUAL (ou nula quando a conferência só tem texto).
     */
    record LinhaDeDiferenca(String item, String tipo, String referencia, String descricao, String esperado, String encontrado,
            String unidade) {
    }
}

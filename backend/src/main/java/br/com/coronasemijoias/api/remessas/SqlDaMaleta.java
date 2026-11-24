package br.com.coronasemijoias.api.remessas;

/**
 * Pedaços de SQL que os relatórios e a nota da maleta repetem (tarefas 3.4): estados em que as peças estão com o
 * vendedor, o número legível da maleta e a cotação do dia do envio. Só texto: quem monta a consulta é cada serviço.
 */
public final class SqlDaMaleta {

    /** Estados em que as peças estão com o vendedor (a maleta enviada, e as que a Fase 4 e a Fase 7 vão alcançar). */
    public static final String COM_O_VENDEDOR = "('ENVIADA', 'CONFERIDA', 'EM_ACERTO')";

    /**
     * Número legível da maleta: S{semana}-V{código do vendedor}-{ordem do envio daquele vendedor naquela semana}, por
     * exemplo S12-V7-2. Não há coluna guardada: sai da ordem de envio, que nunca muda depois de enviada. Precisa dos
     * apelidos {@code r} (remessas), {@code s} (semanas) e {@code v} (vendedores). Vazio enquanto não foi enviada.
     */
    public static final String NUMERO = """
            CASE WHEN r.enviada_em IS NULL THEN NULL ELSE 'S' || s.numero || '-V' || v.codigo || '-' ||
                 (SELECT count(*) FROM corona.remessas x WHERE x.vendedor_id = r.vendedor_id AND x.semana_id = r.semana_id
                     AND x.deleted_at IS NULL AND x.enviada_em IS NOT NULL
                     AND (x.enviada_em, x.id) <= (r.enviada_em, r.id)) END""";

    /**
     * A cotação vigente no momento do envio (ADR-0006, itens 7 e 15): a de maior vigente_desde até a hora do envio. Apelido {@code ct}; sem cotação, as colunas vêm vazias. Precisa do apelido {@code r}.
     */
    public static final String COTACAO_DO_ENVIO = """
            LEFT JOIN LATERAL (SELECT c.data_cotacao, c.origem, c.vigente_desde, c.usd_venda, c.pyg_venda FROM corona.cotacoes c
                                WHERE c.vigente_desde <= r.enviada_em
                                ORDER BY c.vigente_desde DESC, c.id DESC LIMIT 1) ct ON true""";

    private SqlDaMaleta() {
    }

    /** Valor em reais convertido em Dólar (2 casas, meio para cima), pela cotação {@code ct} da linha. */
    public static String dolar(String reais) {
        return "round((" + reais + ") / ct.usd_venda, 2)";
    }

    /** Valor em reais convertido em Guarani (sem casas, meio para cima), pela cotação {@code ct} da linha. */
    public static String guarani(String reais) {
        return "round((" + reais + ") / ct.pyg_venda, 0)";
    }
}

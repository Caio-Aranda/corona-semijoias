package br.com.coronasemijoias.api.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * O filtro do vendedor do token (tarefa 5.4; ADR-0013, itens 4 e 5): a regra declarada de cada tabela, com o
 * {@code vendedor_id} do token (nunca do pedido). Tabela sem regra declarada não entrega nada.
 *
 * <p>Nos clientes, as colunas fora do diretório saem {@code NULL} quando o cliente não é atendido pelo vendedor. O id
 * do vendedor entra na expressão como literal: é um UUID do próprio servidor (formato fixo, sem texto do usuário).
 */
public record FiltroPorVendedor(UUID vendedorId) implements FiltroDoVendedor {

    public FiltroPorVendedor {
        Objects.requireNonNull(vendedorId, "vendedorId");
    }

    @Override
    public CondicaoSql condicaoPara(TabelaSincronizavel tabela) {
        RegraDoVendedor regra = tabela.regra();
        if (!regra.declarada()) {
            return CondicaoSql.NENHUMA_LINHA;
        }
        List<Object> parametros = new ArrayList<>(Collections.nCopies(regra.parametros(), vendedorId));
        return new CondicaoSql(regra.condicao(), parametros);
    }

    @Override
    public String expressao(TabelaSincronizavel tabela, String coluna) {
        RegraDoVendedor regra = tabela.regra();
        String propria = FiltroDoVendedor.super.expressao(tabela, coluna);
        if (regra.diretorio().isEmpty() || regra.diretorio().contains(coluna)
                || TabelaSincronizavel.COLUNAS_DE_CONTROLE.contains(coluna)) {
            return propria;
        }
        return "CASE WHEN t.\"" + regra.colunaDoDono() + "\" = '" + vendedorId + "'::uuid THEN " + propria + " END";
    }

    @Override
    public boolean expande() {
        return true;
    }
}

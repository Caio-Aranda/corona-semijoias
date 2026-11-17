package br.com.coronasemijoias.api.relatorios;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A auditoria em palavras da loja (tarefa 8.9; glossário): o nome da ação, da área e de cada campo do antes e do depois. O que o
 * dicionário não conhece vira uma frase legível (sem sublinhado). Campo de senha, hash, token ou segredo NUNCA sai daqui, mesmo que
 * algum dia alguém o grave na auditoria.
 */
final class TextoDaAuditoria {

    private static final Map<String, String> ACOES = new LinkedHashMap<>();
    private static final Map<String, String> AREAS = new LinkedHashMap<>();
    private static final Map<String, String> CAMPOS = new LinkedHashMap<>();

    static {
        acoes("USUARIO_CRIADO", "Usuário criado", "USUARIO_EDITADO", "Usuário alterado", "USUARIO_DESATIVADO", "Usuário desativado",
                "USUARIO_REATIVADO", "Usuário reativado", "SENHA_REDEFINIDA", "Senha redefinida",
                "REATIVADO", "Cadastro reativado", "INATIVADO", "Cadastro inativado",
                "PRODUTO_CRIADO", "Produto cadastrado", "PRECO_VENDA_ALTERADO", "Preço de venda alterado",
                "PRECO_CUSTO_ALTERADO", "Custo alterado", "ESTOQUE_MINIMO_ALTERADO", "Estoque mínimo alterado",
                "VENDA_REGISTRADA", "Venda registrada", "VENDA_ESTORNADA", "Venda cancelada",
                "DEVOLUCAO_REGISTRADA", "Devolução registrada", "DEVOLUCAO_CANCELADA", "Devolução cancelada",
                "DEVOLUCAO_MANTIDA", "Devolução mantida pela dona", "DEVOLUCAO_DESFEITA", "Devolução desfeita",
                "TROCA_REGISTRADA", "Troca registrada", "TROCA_CANCELADA", "Troca cancelada",
                "AUTORIZACAO_PEDIDA", "Autorização pedida", "AUTORIZACAO_APROVADA", "Autorização aprovada",
                "AUTORIZACAO_NEGADA", "Autorização negada", "AUTORIZACAO_VENCIDA", "Autorização vencida",
                "CREDITO_SEM_SALDO_NA_VENDA", "Crédito sem saldo na venda", "CREDITO_SEM_SALDO_MANTIDO", "Crédito sem saldo mantido",
                "CREDITO_TRANSFERIDO", "Crédito transferido",
                "REMESSA_RASCUNHO_CRIADO", "Maleta começada", "REMESSA_RASCUNHO_EXCLUIDO", "Maleta começada descartada",
                "REMESSA_ENVIADA", "Maleta enviada", "REMESSA_CANCELADA", "Maleta cancelada",
                "MALETA_DEVOLUCAO_REGISTRADA", "Devolução de maleta registrada", "MALETA_DEVOLUCAO_DESFEITA", "Devolução de maleta desfeita",
                "MALETA_CONFERIDA", "Maleta conferida",
                "ACERTO_INICIADO", "Acerto iniciado", "ACERTO_DESISTIDO", "Acerto desfeito", "ACERTO_FECHADO", "Acerto fechado",
                "PENDENCIA_DECIDIDA", "Pendência decidida", "COMISSAO_ALTERADA", "Regra da comissão alterada",
                "GARANTIA_ALTERADA", "Garantia alterada", "SEMANA_FECHADA", "Semana fechada",
                "COMPRA_REGISTRADA", "Compra registrada", "COMPRA_ESTORNADA", "Compra cancelada",
                "CONTAS_A_PAGAR_GERADAS", "Contas a pagar geradas", "CONTAS_A_PAGAR_CANCELADAS", "Contas a pagar canceladas",
                "PAGAMENTO_FORNECEDOR_REGISTRADO", "Pagamento ao fornecedor registrado",
                "PAGAMENTO_FORNECEDOR_ESTORNADO", "Pagamento ao fornecedor estornado",
                "RECEBIMENTO_CLIENTE_REGISTRADO", "Recebimento do cliente registrado",
                "RECEBIMENTO_CLIENTE_ESTORNADO", "Recebimento do cliente estornado",
                "RECEBIMENTO_VENDEDOR_REGISTRADO", "Recebimento do vendedor registrado",
                "RECEBIMENTO_VENDEDOR_ESTORNADO", "Recebimento do vendedor estornado",
                "LANCAMENTO_MANUAL_REGISTRADO", "Lançamento do caixa registrado", "LANCAMENTO_MANUAL_ESTORNADO", "Lançamento do caixa estornado",
                "CATEGORIA_DO_CAIXA_CRIADA", "Categoria do caixa criada", "CATEGORIA_DO_CAIXA_ALTERADA", "Categoria do caixa alterada",
                "INVENTARIO_INICIADO", "Contagem iniciada", "INVENTARIO_FECHADO", "Contagem fechada", "INVENTARIO_CANCELADO", "Contagem cancelada",
                "APARELHO_LIBERADO", "Computador liberado", "APARELHO_BLOQUEADO", "Computador bloqueado",
                "APARELHO_SUBSTITUIDO", "Computador trocado",
                "ENVIO_TENTADO_DE_NOVO", "Envio tentado de novo", "ENVIO_DESCARTADO", "Envio descartado",
                "QUARENTENA_GUARDADA", "Envio guardado à parte", "QUARENTENA_APROVADA", "Envio guardado aprovado",
                "QUARENTENA_DESCARTADA", "Envio guardado descartado",
                "CLIENTE_CRIADO_PELO_VENDEDOR", "Cliente cadastrado pelo vendedor", "CLIENTE_ALTERADO_PELO_VENDEDOR", "Cliente alterado pelo vendedor",
                "CLIENTE_VALOR_SUBSTITUIDO", "Dado do cliente substituído",
                "VENDEDOR_RESTRICAO_ALTERADA", "Restrição do vendedor alterada", "RESTRICAO_ALTERADA", "Restrição alterada");
        areas("produtos", "Produto", "usuarios", "Usuário", "vendas", "Venda", "devolucoes", "Devolução", "trocas", "Troca",
                "remessas", "Maleta", "acertos", "Acerto", "pendencias_acerto", "Pendência do acerto", "compras", "Compra",
                "contas_pagar", "Conta a pagar", "baixas_pagar", "Pagamento a fornecedor", "pagamentos_cliente", "Recebimento do cliente",
                "recebimentos_vendedor", "Recebimento do vendedor", "lancamentos_caixa", "Caixa", "categorias_caixa", "Categoria do caixa",
                "configuracoes", "Configuração", "semanas", "Semana", "inventarios", "Contagem do estoque",
                "movimentacoes_estoque", "Estoque", "autorizacoes_excecao", "Autorização", "creditos_cliente", "Crédito do cliente",
                "creditos_sem_saldo", "Crédito sem saldo", "dispositivos", "Computador", "sync_log", "Envio do computador",
                "envios_em_quarentena", "Envio guardado à parte", "clientes", "Cliente", "conferencias", "Conferência da maleta",
                "vendedores", "Vendedor", "restricoes", "Restrição", "pracas", "Praça", "categorias", "Categoria", "fornecedores", "Fornecedor");
        campos("preco_venda", "Preço de venda", "preco_custo", "Preço de custo", "estoque_minimo", "Estoque mínimo",
                "dentro_garantia", "Dentro da garantia", "valor_reembolsado", "Valor reembolsado", "valor_devolvido", "Valor devolvido",
                "valor_em_credito", "Valor em crédito", "valor_abatido", "Valor abatido", "forma_reembolso", "Forma do reembolso",
                "motivos_revisao", "Motivos da revisão", "situacao", "Situação", "status", "Situação", "ativo", "Ativo",
                "login", "Login", "nome", "Nome", "restricao", "Restrição", "bloqueia_remessa", "Bloqueia nova maleta",
                "desconto", "Desconto", "subtotal", "Subtotal", "total", "Total", "motivo", "Motivo", "destino", "Destino",
                "venda_id", "Venda", "credito_id", "Crédito", "autorizacao_id", "Autorização", "perfil", "Tipo de acesso",
                "vendedor_id", "Vendedor", "codigo", "Código", "descricao", "Descrição", "quantidade", "Quantidade", "valor", "Valor",
                "tipo", "Tipo", "forma", "Forma de pagamento", "registrada_pela_dona", "Registrada pela dona");
    }

    private TextoDaAuditoria() {
    }

    private static void acoes(String... pares) {
        preencher(ACOES, pares);
    }

    private static void areas(String... pares) {
        preencher(AREAS, pares);
    }

    private static void campos(String... pares) {
        preencher(CAMPOS, pares);
    }

    private static void preencher(Map<String, String> mapa, String... pares) {
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put(pares[i], pares[i + 1]);
        }
    }

    /** O nome da ação em palavras da loja. */
    static String acao(String codigo) {
        String conhecido = ACOES.get(codigo);
        return conhecido != null ? conhecido : frase(codigo);
    }

    /** A área (o tipo de registro) em palavras da loja. */
    static String area(String tabela) {
        String conhecido = AREAS.get(tabela);
        return conhecido != null ? conhecido : frase(tabela);
    }

    /** O antes ou o depois em texto ("Campo: valor; Campo: valor"); vazio vira {@code null}. Nunca devolve senha, hash nem token. */
    static String dosCampos(Map<String, Object> campos) {
        if (campos == null) {
            return null;
        }
        StringJoiner junta = new StringJoiner("; ");
        for (Map.Entry<String, Object> e : campos.entrySet()) {
            if (secreto(e.getKey())) {
                continue;
            }
            String nome = CAMPOS.get(e.getKey());
            junta.add((nome != null ? nome : frase(e.getKey())) + ": " + valor(e.getValue()));
        }
        return junta.length() == 0 ? null : junta.toString();
    }

    static boolean secreto(String chave) {
        String c = chave.toLowerCase(Locale.ROOT);
        return c.contains("senha") || c.contains("hash") || c.contains("token") || c.contains("segredo");
    }

    private static String valor(Object v) {
        if (v == null) {
            return "sem valor";
        }
        if (v instanceof Boolean b) {
            return b ? "Sim" : "Não";
        }
        if (v instanceof Iterable<?> lista) {
            StringJoiner j = new StringJoiner(", ");
            for (Object o : lista) {
                j.add(valor(o));
            }
            return j.toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringJoiner j = new StringJoiner(", ");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String chave = String.valueOf(e.getKey());
                if (!secreto(chave)) {
                    String nome = CAMPOS.get(chave);
                    j.add((nome != null ? nome : frase(chave)) + " " + valor(e.getValue()));
                }
            }
            return j.toString();
        }
        return String.valueOf(v);
    }

    /** "ACAO_NOVA" ou "campo_novo" viram "Acao nova" e "Campo novo". */
    private static String frase(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return "";
        }
        String s = bruto.replace('_', ' ').toLowerCase(Locale.ROOT).strip();
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}

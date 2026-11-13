package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PecaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Os tipos de operação da Fase 6 (tarefa 6.7; ADR-0013, linha de 13/11/2026; ADR-0014, item 14): o conteúdo do formato 1,
 * a forma canônica (campos em ordem fixa, listas ordenadas, dinheiro em centavos exatos, nulo como ∅) e os registros de
 * que cada um depende. A leitura e a aplicação ficam em {@link OperacoesDoVendedor}, que chama os mesmos serviços da rota
 * online; aqui não mora regra nenhuma.
 */
final class OperacoesDaFase6 {

    /** Os tipos desta classe, todos com o formato 1. */
    static final Set<String> TIPOS = Set.of("DEVOLUCAO", "PEDIDO_AUTORIZACAO", "TROCA", "CANCELAR_DEVOLUCAO", "CANCELAR_TROCA");

    private OperacoesDaFase6() {
    }

    // --- formato 1 de cada tipo ---

    /** O pedido da rota {@code POST /api/vendedor/devolucoes} mais a hora em que o computador registrou a devolução. */
    public record DadosDaDevolucao(UUID id, UUID vendaId, String motivo, String observacao, String formaReembolso,
            List<PecaDoPedido> itens, BigDecimal valorConferido, UUID autorizacaoId, Instant devolvidaEm) {
    }

    /** O pedido da rota {@code POST /api/vendedor/autorizacoes} mais a hora do pedido. */
    public record DadosDoPedidoDeAutorizacao(UUID id, UUID vendaId, String motivo, String observacao, List<PecaDoPedido> itens,
            Instant pedidaEm) {
    }

    public record DevolucaoDaTrocaEnviada(UUID id, UUID vendaId, String motivo, String observacao, List<PecaDoPedido> itens,
            UUID autorizacaoId) {
    }

    public record VendaDaTrocaEnviada(UUID id, UUID remessaId, List<LinhaDoPedido> itens, List<PagamentoDoPedido> pagamentos,
            BigDecimal totalConferido) {
    }

    /** O pedido da rota {@code POST /api/vendedor/trocas} mais a hora da troca. */
    public record DadosDaTroca(UUID id, UUID creditoId, UUID pagamentoDoCreditoId, DevolucaoDaTrocaEnviada devolucao,
            VendaDaTrocaEnviada venda, Instant trocadaEm) {
    }

    /** {@code id}: o id do cancelamento (do aplicativo). */
    public record DadosDoCancelamentoDaDevolucao(UUID id, UUID devolucaoId, String motivo, Instant canceladaEm) {
    }

    public record DadosDoCancelamentoDaTroca(UUID id, UUID trocaId, String motivo, Instant canceladaEm) {
    }

    /** A classe de cada tipo. */
    static Class<?> classe(String tipo) {
        return switch (tipo) {
            case "DEVOLUCAO" -> DadosDaDevolucao.class;
            case "PEDIDO_AUTORIZACAO" -> DadosDoPedidoDeAutorizacao.class;
            case "TROCA" -> DadosDaTroca.class;
            case "CANCELAR_DEVOLUCAO" -> DadosDoCancelamentoDaDevolucao.class;
            default -> DadosDoCancelamentoDaTroca.class;
        };
    }

    /** O id do registro que a operação cria (precisa ser o {@code operacao_id}). */
    static UUID idDoRegistro(Object dados) {
        return switch (dados) {
            case DadosDaDevolucao d -> d.id();
            case DadosDoPedidoDeAutorizacao d -> d.id();
            case DadosDaTroca d -> d.id();
            case DadosDoCancelamentoDaDevolucao d -> d.id();
            case DadosDoCancelamentoDaTroca d -> d.id();
            default -> throw new IllegalStateException("tipo da Fase 6 desconhecido");
        };
    }

    /** A hora que o computador declarou. */
    static Instant horaDeclarada(Object dados) {
        return switch (dados) {
            case DadosDaDevolucao d -> d.devolvidaEm();
            case DadosDoPedidoDeAutorizacao d -> d.pedidaEm();
            case DadosDaTroca d -> d.trocadaEm();
            case DadosDoCancelamentoDaDevolucao d -> d.canceladaEm();
            case DadosDoCancelamentoDaTroca d -> d.canceladaEm();
            default -> null;
        };
    }

    /** Os registros de que a operação depende (para a recusa {@code DEPENDE_DE_ENVIO_RECUSADO}). */
    static List<UUID> dependencias(Object dados) {
        List<UUID> ids = new ArrayList<>();
        switch (dados) {
            case DadosDaDevolucao d -> {
                ids.add(d.vendaId());
                ids.add(d.autorizacaoId());
            }
            case DadosDoPedidoDeAutorizacao d -> ids.add(d.vendaId());
            case DadosDaTroca d -> {
                if (d.devolucao() != null) {
                    ids.add(d.devolucao().vendaId());
                    ids.add(d.devolucao().autorizacaoId());
                }
                if (d.venda() != null) {
                    ids.add(d.venda().remessaId());
                    creditos(d.venda().pagamentos(), ids);
                }
            }
            case DadosDoCancelamentoDaDevolucao d -> ids.add(d.devolucaoId());
            case DadosDoCancelamentoDaTroca d -> ids.add(d.trocaId());
            default -> {
            }
        }
        ids.removeIf(Objects::isNull);
        return ids;
    }

    /** Os créditos usados nos pagamentos (a venda e a troca dependem deles). */
    static void creditos(List<PagamentoDoPedido> pagamentos, List<UUID> ids) {
        if (pagamentos != null) {
            pagamentos.stream().filter(Objects::nonNull).map(PagamentoDoPedido::creditoId).filter(Objects::nonNull).forEach(ids::add);
        }
    }

    // --- forma canônica ---

    static String canonico(Object dados) {
        StringBuilder s = new StringBuilder();
        switch (dados) {
            case DadosDaDevolucao d -> {
                campo(s, "id", d.id());
                campo(s, "vendaId", d.vendaId());
                campo(s, "motivo", d.motivo());
                campo(s, "observacao", d.observacao());
                campo(s, "formaReembolso", d.formaReembolso());
                pecas(s, d.itens());
                campo(s, "valorConferido", dinheiro(d.valorConferido()));
                campo(s, "autorizacaoId", d.autorizacaoId());
                campo(s, "devolvidaEm", d.devolvidaEm());
            }
            case DadosDoPedidoDeAutorizacao d -> {
                campo(s, "id", d.id());
                campo(s, "vendaId", d.vendaId());
                campo(s, "motivo", d.motivo());
                campo(s, "observacao", d.observacao());
                pecas(s, d.itens());
                campo(s, "pedidaEm", d.pedidaEm());
            }
            case DadosDaTroca d -> {
                campo(s, "id", d.id());
                campo(s, "creditoId", d.creditoId());
                campo(s, "pagamentoDoCreditoId", d.pagamentoDoCreditoId());
                DevolucaoDaTrocaEnviada a = d.devolucao();
                s.append("devolucao={");
                if (a != null) {
                    campo(s, "id", a.id());
                    campo(s, "vendaId", a.vendaId());
                    campo(s, "motivo", a.motivo());
                    campo(s, "observacao", a.observacao());
                    pecas(s, a.itens());
                    campo(s, "autorizacaoId", a.autorizacaoId());
                }
                s.append("}|venda={");
                VendaDaTrocaEnviada b = d.venda();
                if (b != null) {
                    campo(s, "id", b.id());
                    campo(s, "remessaId", b.remessaId());
                    linhas(s, b.itens());
                    pagamentos(s, b.pagamentos());
                    campo(s, "totalConferido", dinheiro(b.totalConferido()));
                }
                s.append("}|");
                campo(s, "trocadaEm", d.trocadaEm());
            }
            case DadosDoCancelamentoDaDevolucao d -> {
                campo(s, "id", d.id());
                campo(s, "devolucaoId", d.devolucaoId());
                campo(s, "motivo", d.motivo());
                campo(s, "canceladaEm", d.canceladaEm());
            }
            case DadosDoCancelamentoDaTroca d -> {
                campo(s, "id", d.id());
                campo(s, "trocaId", d.trocaId());
                campo(s, "motivo", d.motivo());
                campo(s, "canceladaEm", d.canceladaEm());
            }
            default -> throw new IllegalStateException("tipo da Fase 6 desconhecido");
        }
        return s.toString();
    }

    private static void campo(StringBuilder s, String nome, Object valor) {
        s.append(nome).append('=').append(valor == null ? "∅" : valor.toString()).append('|');
    }

    /** As peças devolvidas, ordenadas pelo produto e pelo estado. */
    private static void pecas(StringBuilder s, List<PecaDoPedido> itens) {
        List<PecaDoPedido> lista = new ArrayList<>(itens == null ? List.of() : itens);
        lista.removeIf(Objects::isNull);
        lista.sort(Comparator.comparing((PecaDoPedido p) -> String.valueOf(p.produtoId())).thenComparing(p -> String.valueOf(p.estado())));
        s.append("itens=[");
        lista.forEach(p -> s.append(p.produtoId()).append(':').append(p.quantidade()).append(':').append(p.estado()).append(';'));
        s.append("]|");
    }

    /** As peças vendidas, ordenadas pelo produto (como na VENDA). */
    private static void linhas(StringBuilder s, List<LinhaDoPedido> itens) {
        List<LinhaDoPedido> lista = new ArrayList<>(itens == null ? List.of() : itens);
        lista.removeIf(Objects::isNull);
        lista.sort(Comparator.comparing(l -> String.valueOf(l.produtoId())));
        s.append("itens=[");
        lista.forEach(l -> s.append(l.produtoId()).append(':').append(l.quantidade()).append(':').append(dinheiro(l.desconto()))
                .append(';'));
        s.append("]|");
    }

    /** Os pagamentos, ordenados pelo id; o crédito só aparece quando vem (como na VENDA ampliada). */
    static void pagamentos(StringBuilder s, List<PagamentoDoPedido> pagamentos) {
        List<PagamentoDoPedido> lista = new ArrayList<>(pagamentos == null ? List.of() : pagamentos);
        lista.removeIf(Objects::isNull);
        lista.sort(Comparator.comparing(p -> String.valueOf(p.id())));
        s.append("pagamentos=[");
        for (PagamentoDoPedido p : lista) {
            s.append(p.id()).append(':').append(dinheiro(p.valor())).append(':').append(p.forma());
            if (p.creditoId() != null) {
                s.append(':').append(p.creditoId());
            }
            s.append(';');
        }
        s.append("]|");
    }

    /** Dinheiro em centavos exatos ("10.0" e "10.00" dão "1000"); mais de duas casas não é dinheiro e a leitura falha. */
    static String dinheiro(BigDecimal valor) {
        return valor == null ? null : valor.movePointRight(2).toBigIntegerExact().toString();
    }
}

package br.com.coronasemijoias.api.sync;

import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeAutorizacoes.PedidoDeAutorizacao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.ModoDaDevolucao;
import br.com.coronasemijoias.api.devolucoes.ServicoDeDevolucoes.PedidoDaDevolucaoDoCliente;
import br.com.coronasemijoias.api.sync.OperacoesDaFase6.DadosDaDevolucao;
import br.com.coronasemijoias.api.sync.OperacoesDaFase6.DadosDaTroca;
import br.com.coronasemijoias.api.sync.OperacoesDaFase6.DadosDoCancelamentoDaDevolucao;
import br.com.coronasemijoias.api.sync.OperacoesDaFase6.DadosDoCancelamentoDaTroca;
import br.com.coronasemijoias.api.sync.OperacoesDaFase6.DadosDoPedidoDeAutorizacao;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.DevolucaoDaTroca;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.PedidoDeTroca;
import br.com.coronasemijoias.api.trocas.ServicoDeTrocas.VendaDaTroca;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.LinhaDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PagamentoDoPedido;
import br.com.coronasemijoias.api.vendas.ServicoDeVendas.PedidoDeVenda;
import br.com.coronasemijoias.api.vendedor.ClientesDoVendedor;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia;
import br.com.coronasemijoias.api.vendedor.ServicoDeConferencia.LinhaContada;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * As operações que o Corona Vendas guarda na outbox e envia no push (tarefa 5.5; ADR-0013, item 2): como cada tipo e
 * formato é lido, a forma canônica que dá o resumo SHA-256 (campos em ordem fixa, dinheiro com duas casas, listas
 * ordenadas: dois JSON equivalentes dão o mesmo resumo) e a aplicação, sempre pelos MESMOS serviços da Fase 4. Nenhuma
 * regra de negócio mora aqui.
 *
 * <p>Formatos: {@link FormatosDeOperacao}. Um formato antigo continua aceito por 90 dias depois de publicado o novo
 * (ADR-0013, item 17; linha de 11/11/2026); depois disso ele ainda é lido (para o resumo e o {@code JA_RECEBIDO}), mas
 * {@link #vencido} diz que não pode mais ser aplicado (tarefa 5.13).
 */
@Service
public class OperacoesDoVendedor {

    /** Correção de hora acima disto marca a venda como "hora ajustada" (ADR-0013, item 11; dúvida D12). */
    static final Duration AJUSTE_QUE_MARCA = Duration.ofMinutes(5);

    /** A operação não dá para ler (formato errado, campo faltando): recusada com {@code PEDIDO_INVALIDO}. */
    public static class OperacaoIlegivel extends RuntimeException {

        public OperacaoIlegivel(String motivo) {
            super(motivo);
        }
    }

    // --- formato 1 de cada tipo ---

    public record DadosDaVenda(UUID id, UUID remessaId, UUID clienteId, List<LinhaDoPedido> itens,
            List<PagamentoDoPedido> pagamentos, BigDecimal totalConferido, Instant vendidaEm) {
    }

    public record DadosDoEstorno(UUID id, UUID vendaId, String motivo, Instant estornadoEm) {
    }

    public record DadosDoClienteNovo(UUID id, String nome, String cpf, LocalDate dataNascimento, String telefone,
            String whatsapp, String observacoes) {
    }

    /** Um campo alterado: o valor que o computador tinha e o novo. */
    public record Alteracao(String antes, String depois) {
    }

    public record DadosDaAlteracaoDeCliente(UUID clienteId, Integer versaoBase, Map<String, Alteracao> campos) {
    }

    public record DadosDaConferencia(UUID id, UUID remessaId, List<LinhaContada> itens) {
    }

    /** A operação lida: o conteúdo já no tipo certo, o resumo canônico e a hora que o computador declarou. */
    public record Lida(String tipo, int formato, Object dados, String resumo, Instant horaDeclarada) {
    }

    /**
     * Quem envia e o relógio dele (ADR-0013, item 11). {@code aparelhoDaRegra} é o computador que os serviços conferem
     * contra a maleta: o próprio, ou o novo quando o antigo (trocado) envia o que guardou, ou o da maleta quando a dona
     * manda tentar de novo.
     */
    public record Contexto(UUID vendedorId, UUID usuarioId, UUID aparelho, Instant agora, long diferencaDoRelogioMs,
            Instant ultimoContatoServidor, boolean peloComputadorTrocado, boolean tentativaDaDona) {
    }

    /** O resultado da aplicação: se já existia (o 200 da Fase 4) e a hora corrigida usada. */
    public record Aplicada(boolean jaExistia, Instant horaCorrigida) {
    }

    private final JsonMapper json;
    private final JdbcTemplate jdbc;
    private final ServicoDeVendas vendas;
    private final ClientesDoVendedor clientes;
    private final ServicoDeConferencia conferencias;
    private final FormatosDeOperacao formatos;
    private final ServicoDeDevolucoes devolucoes;
    private final ServicoDeAutorizacoes autorizacoes;
    private final ServicoDeTrocas trocas;

    public OperacoesDoVendedor(JsonMapper json, JdbcTemplate jdbc, ServicoDeVendas vendas, ClientesDoVendedor clientes,
            ServicoDeConferencia conferencias, FormatosDeOperacao formatos, ServicoDeDevolucoes devolucoes,
            ServicoDeAutorizacoes autorizacoes, ServicoDeTrocas trocas) {
        this.formatos = formatos;
        this.devolucoes = devolucoes;
        this.autorizacoes = autorizacoes;
        this.trocas = trocas;
        this.json = json;
        this.jdbc = jdbc;
        this.vendas = vendas;
        this.clientes = clientes;
        this.conferencias = conferencias;
    }

    /**
     * O formato é conhecido, mas o prazo dele acabou (90 dias depois de publicado o seguinte): o push recusa com
     * {@code FORMATO_NAO_ACEITO} em vez de aplicar. Quem já entrou continua {@code JA_RECEBIDO}.
     */
    public boolean vencido(String tipo, int formato) {
        return formatos.conhecido(tipo, formato) && !formatos.aceitoAgora(tipo, formato);
    }

    /** Lê a operação e calcula o resumo canônico. O {@code operacaoId} é o id do registro criado (exceto na alteração). */
    public Lida ler(UUID operacaoId, String tipo, int formato, JsonNode dados) {
        if (!formatos.conhecido(tipo, formato)) {
            throw new OperacaoIlegivel("tipo ou formato desconhecido: " + tipo + "/" + formato);
        }
        if (dados == null || !dados.isObject()) {
            throw new OperacaoIlegivel("sem dados");
        }
        try {
            if (OperacoesDaFase6.TIPOS.contains(tipo)) {
                // Fase 6 (tarefa 6.7): o conteúdo e a forma canônica em OperacoesDaFase6.
                Object d = json.treeToValue(dados, OperacoesDaFase6.classe(tipo));
                exigirMesmoId(operacaoId, OperacoesDaFase6.idDoRegistro(d));
                return new Lida(tipo, formato, d, resumo(OperacoesDaFase6.canonico(d), tipo, formato),
                        OperacoesDaFase6.horaDeclarada(d));
            }
            return switch (tipo) {
                case "VENDA" -> {
                    DadosDaVenda d = json.treeToValue(dados, DadosDaVenda.class);
                    exigirMesmoId(operacaoId, d.id());
                    yield new Lida(tipo, formato, d, resumo(canonico(d), tipo, formato), d.vendidaEm());
                }
                case "ESTORNO_VENDA" -> {
                    DadosDoEstorno d = json.treeToValue(dados, DadosDoEstorno.class);
                    exigirMesmoId(operacaoId, d.id());
                    yield new Lida(tipo, formato, d, resumo(canonico(d), tipo, formato), d.estornadoEm());
                }
                case "CLIENTE_NOVO" -> {
                    DadosDoClienteNovo d = json.treeToValue(dados, DadosDoClienteNovo.class);
                    exigirMesmoId(operacaoId, d.id());
                    yield new Lida(tipo, formato, d, resumo(canonico(d), tipo, formato), null);
                }
                case "CLIENTE_ALTERADO" -> {
                    DadosDaAlteracaoDeCliente d = json.treeToValue(dados, DadosDaAlteracaoDeCliente.class);
                    if (d.clienteId() == null || d.campos() == null || d.campos().isEmpty()) {
                        throw new OperacaoIlegivel("alteração sem cliente ou sem campos");
                    }
                    yield new Lida(tipo, formato, d, resumo(canonico(d), tipo, formato), null);
                }
                default -> {
                    DadosDaConferencia d = json.treeToValue(dados, DadosDaConferencia.class);
                    exigirMesmoId(operacaoId, d.id());
                    yield new Lida(tipo, formato, d, resumo(canonico(d), tipo, formato), null);
                }
            };
        } catch (OperacaoIlegivel e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OperacaoIlegivel("não deu para ler: " + e.getClass().getSimpleName());
        }
    }

    /** Aplica pelo serviço da Fase 4 (na transação de quem chama). Erros de regra saem como {@code ErroDaApi}. */
    public Aplicada aplicar(Lida op, Contexto ctx) {
        return switch (op.dados()) {
            case DadosDaVenda d -> {
                Instant inferior = maior(ctx.ultimoContatoServidor(), enviadaEm(d.remessaId()));
                Instant corrigida = corrigir(d.vendidaEm(), ctx, inferior);
                boolean ajustada = d.vendidaEm() != null
                        && Duration.between(d.vendidaEm(), corrigida).abs().compareTo(AJUSTE_QUE_MARCA) > 0;
                // O formato 1 ampliado (tarefa 6.7, dúvida S3) leva o crédito do cliente opcional em cada pagamento; pelo
                // envio, o crédito acima do saldo vira revisão da dona, nunca recusa.
                PedidoDeVenda pedido = new PedidoDeVenda(d.id(), d.remessaId(), d.clienteId(), d.itens(), d.pagamentos(),
                        d.totalConferido());
                boolean ja = vendas.venderPeloEnvio(ctx.vendedorId(), ctx.usuarioId(), aparelhoDaMaleta(d.remessaId(), ctx),
                        pedido, corrigida, ajustada).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            case DadosDoEstorno d -> {
                Instant inferior = maior(ctx.ultimoContatoServidor(), vendidaEm(d.vendaId()));
                Instant corrigida = corrigir(d.estornadoEm(), ctx, inferior);
                boolean ja = vendas.estornar(d.vendaId(), ctx.vendedorId(), ctx.usuarioId(),
                        aparelhoDaMaleta(remessaDaVenda(d.vendaId()), ctx), d.id(), d.motivo(), corrigida).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            case DadosDoClienteNovo d -> new Aplicada(clientes.criar(ctx.vendedorId(), ctx.usuarioId(), ctx.aparelho(),
                    new ClientesDoVendedor.Dados(d.id(), d.nome(), d.cpf(), d.dataNascimento(), d.telefone(), d.whatsapp(),
                            d.observacoes())).jaExistia(), null);
            case DadosDaAlteracaoDeCliente d -> {
                Map<String, ClientesDoVendedor.Alteracao> campos = new LinkedHashMap<>();
                d.campos().forEach((campo, a) -> campos.put(campo, new ClientesDoVendedor.Alteracao(
                        a == null ? null : a.antes(), a == null ? null : a.depois())));
                clientes.alterar(ctx.vendedorId(), ctx.usuarioId(), ctx.aparelho(), d.clienteId(), campos);
                yield new Aplicada(false, null);
            }
            case DadosDaConferencia d -> new Aplicada(conferencias.conferir(ctx.vendedorId(), ctx.usuarioId(),
                    aparelhoDaMaleta(d.remessaId(), ctx), d.remessaId(), d.id(), d.itens()).jaExistia(), null);
            // --- Fase 6 (tarefa 6.7): o mesmo serviço da rota online, no modo do envio ---
            case DadosDaDevolucao d -> {
                Instant corrigida = corrigir(d.devolvidaEm(), ctx, maior(ctx.ultimoContatoServidor(), vendidaEm(d.vendaId())));
                boolean ja = devolucoes.devolver(ctx.vendedorId(), ctx.usuarioId(),
                        aparelhoDaMaleta(remessaDaVenda(d.vendaId()), ctx), new PedidoDaDevolucaoDoCliente(d.id(), d.vendaId(),
                                d.motivo(), d.observacao(), d.formaReembolso(), d.itens(), d.valorConferido(), d.autorizacaoId()),
                        corrigida, ajustada(d.devolvidaEm(), corrigida), ModoDaDevolucao.ENVIO).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            case DadosDoPedidoDeAutorizacao d -> {
                Instant corrigida = corrigir(d.pedidaEm(), ctx, maior(ctx.ultimoContatoServidor(), vendidaEm(d.vendaId())));
                boolean ja = autorizacoes.pedir(ctx.vendedorId(), ctx.usuarioId(),
                        aparelhoDaMaleta(remessaDaVenda(d.vendaId()), ctx),
                        new PedidoDeAutorizacao(d.id(), d.vendaId(), d.motivo(), d.observacao(), d.itens()), corrigida,
                        ajustada(d.pedidaEm(), corrigida), ModoDaDevolucao.ENVIO).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            case DadosDaTroca d -> {
                UUID vendaA = d.devolucao() == null ? null : d.devolucao().vendaId();
                UUID maletaB = d.venda() == null ? null : d.venda().remessaId();
                Instant inferior = maior(ctx.ultimoContatoServidor(), maior(vendidaEm(vendaA), enviadaEm(maletaB)));
                Instant corrigida = corrigir(d.trocadaEm(), ctx, inferior);
                // O computador conferido é o da maleta de B (dúvida S11); a de A o serviço confere com o mesmo.
                boolean ja = trocas.trocar(ctx.vendedorId(), ctx.usuarioId(), aparelhoDaMaleta(maletaB, ctx), paraOServico(d),
                        corrigida, ajustada(d.trocadaEm(), corrigida), ModoDaDevolucao.ENVIO).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            case DadosDoCancelamentoDaDevolucao d -> {
                Instant corrigida = corrigir(d.canceladaEm(), ctx, maior(ctx.ultimoContatoServidor(),
                        instante("SELECT devolvida_em FROM corona.devolucoes WHERE id = ?", d.devolucaoId())));
                UUID maleta = d.devolucaoId() == null ? null : primeiro(jdbc.queryForList(
                        "SELECT remessa_id FROM corona.devolucoes WHERE id = ?", UUID.class, d.devolucaoId()));
                boolean ja = devolucoes.cancelar(d.devolucaoId(), ctx.vendedorId(), ctx.usuarioId(), aparelhoDaMaleta(maleta, ctx),
                        d.id(), d.motivo(), corrigida).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            case DadosDoCancelamentoDaTroca d -> {
                Instant corrigida = corrigir(d.canceladaEm(), ctx, maior(ctx.ultimoContatoServidor(),
                        instante("SELECT trocada_em FROM corona.trocas WHERE id = ?", d.trocaId())));
                UUID maletaB = d.trocaId() == null ? null : primeiro(jdbc.queryForList("""
                        SELECT v.remessa_id FROM corona.trocas t JOIN corona.vendas v ON v.id = t.venda_nova_id WHERE t.id = ?""",
                        UUID.class, d.trocaId()));
                boolean ja = trocas.cancelar(d.trocaId(), ctx.vendedorId(), ctx.usuarioId(), aparelhoDaMaleta(maletaB, ctx), d.id(),
                        d.motivo(), corrigida).jaExistia();
                yield new Aplicada(ja, corrigida);
            }
            default -> throw new IllegalStateException("tipo sem aplicação");
        };
    }

    /** Os registros de que a operação depende (para a recusa {@code DEPENDE_DE_ENVIO_RECUSADO}). */
    public List<UUID> dependencias(Lida op) {
        List<UUID> ids = new ArrayList<>();
        switch (op.dados()) {
            case DadosDaVenda d -> {
                ids.add(d.clienteId());
                ids.add(d.remessaId());
                OperacoesDaFase6.creditos(d.pagamentos(), ids);
            }
            case DadosDoEstorno d -> ids.add(d.vendaId());
            case DadosDaAlteracaoDeCliente d -> ids.add(d.clienteId());
            case DadosDaConferencia d -> ids.add(d.remessaId());
            default -> ids.addAll(OperacoesDaFase6.dependencias(op.dados()));
        }
        ids.removeIf(java.util.Objects::isNull);
        return ids;
    }

    /** A troca do envio no pedido do serviço (o mesmo da rota online). */
    private static PedidoDeTroca paraOServico(DadosDaTroca d) {
        OperacoesDaFase6.DevolucaoDaTrocaEnviada a = d.devolucao();
        OperacoesDaFase6.VendaDaTrocaEnviada b = d.venda();
        return new PedidoDeTroca(d.id(), d.creditoId(), d.pagamentoDoCreditoId(),
                a == null ? null : new DevolucaoDaTroca(a.id(), a.vendaId(), a.motivo(), a.observacao(), a.itens(), a.autorizacaoId()),
                b == null ? null : new VendaDaTroca(b.id(), b.remessaId(), b.itens(), b.pagamentos(), b.totalConferido()));
    }

    /** Hora corrigida em mais de 5 minutos marca a hora ajustada (ADR-0013, item 11). */
    private static boolean ajustada(Instant declarada, Instant corrigida) {
        return declarada != null && Duration.between(declarada, corrigida).abs().compareTo(AJUSTE_QUE_MARCA) > 0;
    }

    // --- hora corrigida (ADR-0013, item 11) ---

    /**
     * A hora declarada pelo computador, corrigida pela diferença do relógio dele no envio, e limitada: nunca antes do
     * {@code inferior} (último contato com o servidor, envio da maleta, a própria venda) nem depois da chegada.
     */
    static Instant corrigir(Instant declarada, Contexto ctx, Instant inferior) {
        if (declarada == null) {
            return ctx.agora();
        }
        Instant corrigida = declarada.minusMillis(ctx.diferencaDoRelogioMs());
        if (inferior != null && corrigida.isBefore(inferior)) {
            corrigida = inferior;
        }
        if (corrigida.isAfter(ctx.agora())) {
            corrigida = ctx.agora();
        }
        return corrigida;
    }

    private static Instant maior(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private Instant enviadaEm(UUID remessa) {
        return remessa == null ? null : instante("SELECT enviada_em FROM corona.remessas WHERE id = ?", remessa);
    }

    private Instant vendidaEm(UUID venda) {
        return venda == null ? null : instante("SELECT vendida_em FROM corona.vendas WHERE id = ?", venda);
    }

    private UUID remessaDaVenda(UUID venda) {
        return venda == null ? null : primeiro(jdbc.queryForList("SELECT remessa_id FROM corona.vendas WHERE id = ?", UUID.class, venda));
    }

    /** O primeiro da lista, ou nulo (a coluna pode ser nula: {@code findFirst} não aceita). */
    private static UUID primeiro(List<UUID> lista) {
        return lista.isEmpty() ? null : lista.get(0);
    }

    private Instant instante(String sql, UUID id) {
        return jdbc.query(sql, rs -> {
            if (!rs.next()) {
                return null;
            }
            java.time.OffsetDateTime v = rs.getObject(1, java.time.OffsetDateTime.class);
            return v == null ? null : v.toInstant();
        }, id);
    }

    /**
     * O computador que a regra "um computador por maleta" confere. Normalmente o próprio. Quando o computador TROCADO
     * envia o que guardou, a maleta já passou para o novo (o que o substituiu): a venda feita antes da troca é real e
     * passa. Quando a dona manda tentar de novo, vale o computador da maleta.
     */
    private UUID aparelhoDaMaleta(UUID remessa, Contexto ctx) {
        if (remessa == null || (!ctx.peloComputadorTrocado() && !ctx.tentativaDaDona())) {
            return ctx.aparelho();
        }
        UUID daMaleta = primeiro(jdbc.queryForList("SELECT dispositivo_id FROM corona.remessas WHERE id = ?", UUID.class, remessa));
        if (daMaleta == null || ctx.tentativaDaDona()) {
            return daMaleta == null ? ctx.aparelho() : daMaleta;
        }
        // Segue a corrente de trocas a partir do computador que envia: se chegar no da maleta, a venda é dele.
        UUID atual = ctx.aparelho();
        for (int i = 0; i < 10 && atual != null; i++) {
            if (atual.equals(daMaleta)) {
                return daMaleta;
            }
            atual = primeiro(jdbc.queryForList("SELECT substituido_por FROM corona.dispositivos WHERE id = ?", UUID.class, atual));
        }
        return ctx.aparelho();
    }

    // --- forma canônica ---

    private static void exigirMesmoId(UUID operacaoId, UUID doRegistro) {
        if (doRegistro == null || !doRegistro.equals(operacaoId)) {
            throw new OperacaoIlegivel("o id do registro precisa ser o da operação");
        }
    }

    private static String canonico(DadosDaVenda d) {
        StringBuilder s = new StringBuilder();
        campo(s, "id", d.id()).campo(s, "remessaId", d.remessaId()).campo(s, "clienteId", d.clienteId());
        List<LinhaDoPedido> itens = new ArrayList<>(d.itens() == null ? List.of() : d.itens());
        itens.removeIf(java.util.Objects::isNull);
        itens.sort(Comparator.comparing(l -> String.valueOf(l.produtoId())));
        s.append("itens=[");
        for (LinhaDoPedido l : itens) {
            s.append(l.produtoId()).append(':').append(l.quantidade()).append(':').append(dinheiro(l.desconto())).append(';');
        }
        s.append("]|pagamentos=[");
        List<PagamentoDoPedido> pagos = new ArrayList<>(d.pagamentos() == null ? List.of() : d.pagamentos());
        pagos.removeIf(java.util.Objects::isNull);
        pagos.sort(Comparator.comparing(p -> String.valueOf(p.id())));
        for (PagamentoDoPedido p : pagos) {
            s.append(p.id()).append(':').append(dinheiro(p.valor())).append(':').append(p.forma());
            if (p.creditoId() != null) {
                // Formato 1 ampliado (tarefa 6.7): sem crédito, o resumo é o mesmo de sempre.
                s.append(':').append(p.creditoId());
            }
            s.append(';');
        }
        s.append("]|");
        new OperacoesDoVendedor.Canon().campo(s, "totalConferido", dinheiro(d.totalConferido())).campo(s, "vendidaEm", d.vendidaEm());
        return s.toString();
    }

    private static String canonico(DadosDoEstorno d) {
        StringBuilder s = new StringBuilder();
        campo(s, "id", d.id()).campo(s, "vendaId", d.vendaId()).campo(s, "motivo", d.motivo()).campo(s, "estornadoEm", d.estornadoEm());
        return s.toString();
    }

    private static String canonico(DadosDoClienteNovo d) {
        StringBuilder s = new StringBuilder();
        campo(s, "id", d.id()).campo(s, "nome", d.nome()).campo(s, "cpf", d.cpf()).campo(s, "dataNascimento", d.dataNascimento())
                .campo(s, "telefone", d.telefone()).campo(s, "whatsapp", d.whatsapp()).campo(s, "observacoes", d.observacoes());
        return s.toString();
    }

    private static String canonico(DadosDaAlteracaoDeCliente d) {
        StringBuilder s = new StringBuilder();
        campo(s, "clienteId", d.clienteId()).campo(s, "versaoBase", d.versaoBase());
        new TreeMap<>(d.campos()).forEach((campo, a) -> s.append(campo).append("={").append(a == null ? "∅" : a.antes())
                .append("→").append(a == null ? "∅" : a.depois()).append("}|"));
        return s.toString();
    }

    private static String canonico(DadosDaConferencia d) {
        StringBuilder s = new StringBuilder();
        campo(s, "id", d.id()).campo(s, "remessaId", d.remessaId());
        List<LinhaContada> itens = new ArrayList<>(d.itens() == null ? List.of() : d.itens());
        itens.removeIf(java.util.Objects::isNull);
        itens.sort(Comparator.comparing(l -> String.valueOf(l.produtoId())));
        s.append("itens=[");
        itens.forEach(l -> s.append(l.produtoId()).append(':').append(l.contada()).append(';'));
        return s.append(']').toString();
    }

    /** Encadeia "nome=valor|" (nulo como ∅). */
    private static final class Canon {

        Canon campo(StringBuilder s, String nome, Object valor) {
            s.append(nome).append('=').append(valor == null ? "∅" : valor.toString()).append('|');
            return this;
        }
    }

    private static Canon campo(StringBuilder s, String nome, Object valor) {
        return new Canon().campo(s, nome, valor);
    }

    /** Dinheiro em centavos exatos ("10.0" e "10.00" dão "1000"); mais de duas casas não é dinheiro e a leitura falha. */
    private static String dinheiro(BigDecimal valor) {
        return valor == null ? null : valor.movePointRight(2).toBigIntegerExact().toString();
    }

    private static String resumo(String canonico, String tipo, int formato) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((tipo + "/" + formato + "|" + canonico).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

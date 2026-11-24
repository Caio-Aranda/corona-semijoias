package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.identificadores.UuidV7;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A nota do consignado (ADR-0016, itens 5 a 7 e 11): nota nova, Reposição, Troca e Remarcação. Tudo nasce completo e
 * imutável; a nota "cresce" por evento. O servidor confere maleta, preço, semana e número, e grava um movimento de maleta por
 * item ({@code CONSIGNADO_SAIDA}; na troca, também {@code CONSIGNADO_RETORNO}). Divergência vinda do balcão sem internet é
 * ACEITA com alerta (peça sem saldo, preço diferente); recusa só o que não dá para aplicar (peça fora da maleta, semana
 * fechada, número repetido, nota já acertada).
 */
@Service
public class ServicoDeNotas {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** Um item como o computador viu; o id do item e o do movimento nascem lá (reenviar nunca duplica). */
    public record ItemDaNota(UUID id, UUID produtoId, UUID remessaId, int quantidade, BigDecimal precoUnitario, UUID movimentoId) {
    }

    public record NovaNota(UUID id, UUID semanaId, Integer sequencia, UUID clienteId, UUID notaOrigemId, LocalDate dataCobranca,
            List<ItemDaNota> itens, BigDecimal totalDxVisto) {
    }

    public record Reposicao(UUID id, UUID notaId, List<ItemDaNota> itens) {
    }

    public record PecaQueVolta(UUID itemId, UUID produtoId, UUID remessaId, UUID movimentoId) {
    }

    public record PecaQueEntra(UUID itemId, UUID produtoId, UUID remessaId, BigDecimal precoUnitario, UUID movimentoId) {
    }

    public record Troca(UUID id, UUID notaId, PecaQueVolta volta, PecaQueEntra entra) {
    }

    public record Remarcacao(UUID id, UUID notaId, LocalDate dataCobranca) {
    }

    /** A nota do vendedor, lida com trava. */
    record NotaLida(UUID id, UUID clienteId, UUID semanaId, LocalDate dataCobranca) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final ServicoDeAlertasDoConsignado alertas;
    private final DebitosDoCliente debitos;

    public ServicoDeNotas(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem,
            ServicoDeAlertasDoConsignado alertas, DebitosDoCliente debitos) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.alertas = alertas;
        this.debitos = debitos;
    }

    // --- nota nova ---

    /** @return {@code true} se a nota já existia com os mesmos itens (reenvio inofensivo) */
    @Transactional
    public boolean criar(ContextoDoConsignado ctx, NovaNota pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("nota", "Informe os dados da nota.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.semanaId() == null || pedido.clienteId() == null || pedido.sequencia() == null || pedido.sequencia() < 1
                || pedido.dataCobranca() == null) {
            throw Validacoes.recusar("nota", "Informe a semana, o número, o cliente e a data de cobrança.");
        }
        List<ItemDaNota> itens = itensValidos(pedido.itens());
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        // Trava o vendedor: a numeração e o saldo das maletas dele andam um envio por vez.
        jdbc.queryForList("SELECT id FROM corona.vendedores WHERE id = ? FOR UPDATE", UUID.class, ctx.vendedorId());

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT vendedor_id, semana_id, sequencia, cliente_id FROM corona.notas_consignado WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            boolean igual = ctx.vendedorId().equals(e.get("vendedor_id")) && pedido.semanaId().equals(e.get("semana_id"))
                    && pedido.sequencia().equals(e.get("sequencia")) && pedido.clienteId().equals(e.get("cliente_id"))
                    && mesmosItens(id, "SAIDA", itens);
            if (!igual) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        semanaDoVendedor(ctx, pedido.semanaId());
        conferirCliente(pedido.clienteId(), ctx.vendedorId());
        LocalDate cobranca = pedido.dataCobranca();
        if (pedido.notaOrigemId() != null) {
            NotaLida origemNota = notaDoVendedor(ctx, pedido.notaOrigemId());
            if (!origemNota.clienteId().equals(pedido.clienteId())) {
                throw Validacoes.recusar("notaOrigemId", "A nota de origem é de outro cliente.");
            }
            cobranca = origemNota.dataCobranca(); // a data vem da nota de origem (ADR-0016, item 7)
        }
        reservarNumero(ctx, pedido.semanaId(), pedido.sequencia(), "NOTA", id);

        UUID cotacao = cotacaoDoDia(ctx.quando());
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        jdbc.update("""
                INSERT INTO corona.notas_consignado (id, vendedor_id, semana_id, sequencia, cliente_id, saida_em, data_cobranca,
                    nota_origem_id, situacao, cotacao_id, dispositivo_id, hora_ajustada)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'EM_ABERTO', ?, ?, ?)""", id, ctx.vendedorId(), pedido.semanaId(), pedido.sequencia(),
                pedido.clienteId(), Timestamp.from(ctx.quando()), cobranca, pedido.notaOrigemId(), cotacao, ctx.aparelho(),
                ctx.horaAjustada());
        gravarSaidas(ctx, id, "SAIDA", null, itens);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "NOTA_CONSIGNADO_CRIADA", "notas_consignado", id, null,
                Map.of("vendedor_id", ctx.vendedorId().toString(), "cliente_id", pedido.clienteId().toString(),
                        "itens", itens.size()));
        return false;
    }

    // --- reposição ---

    @Transactional
    public boolean reporNaNota(ContextoDoConsignado ctx, Reposicao pedido) {
        if (pedido == null || pedido.notaId() == null) {
            throw Validacoes.recusar("notaId", "Informe a nota.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        List<ItemDaNota> itens = itensValidos(pedido.itens());
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        NotaLida nota = travarNotaDoVendedor(ctx, pedido.notaId());
        List<UUID> existente = jdbc.queryForList("SELECT nota_id FROM corona.reposicoes_nota WHERE id = ?", UUID.class, id);
        if (!existente.isEmpty()) {
            if (!existente.get(0).equals(nota.id()) || !mesmosItens(nota.id(), "REPOSICAO", itens, id)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        exigirSemAcerto(nota.id());
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        jdbc.update("INSERT INTO corona.reposicoes_nota (id, nota_id, feita_em, dispositivo_id, hora_ajustada) VALUES (?, ?, ?, ?, ?)",
                id, nota.id(), Timestamp.from(ctx.quando()), ctx.aparelho(), ctx.horaAjustada());
        gravarSaidas(ctx, nota.id(), "REPOSICAO", id, itens);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "NOTA_CONSIGNADO_REPOSICAO", "reposicoes_nota", id, null,
                Map.of("nota_id", nota.id().toString(), "itens", itens.size()));
        return false;
    }

    // --- troca ---

    @Transactional
    public boolean trocar(ContextoDoConsignado ctx, Troca pedido) {
        if (pedido == null || pedido.notaId() == null || pedido.volta() == null || pedido.entra() == null) {
            throw Validacoes.recusar("troca", "Informe a nota, a peça que volta e a peça que entra.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        PecaQueVolta volta = pedido.volta();
        PecaQueEntra entra = pedido.entra();
        if (volta.itemId() == null || volta.movimentoId() == null || entra.itemId() == null || entra.movimentoId() == null
                || volta.produtoId() == null || volta.remessaId() == null || entra.produtoId() == null || entra.remessaId() == null) {
            throw Validacoes.recusar("troca", "Informe as duas peças da troca.");
        }
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        NotaLida nota = travarNotaDoVendedor(ctx, pedido.notaId());
        List<UUID> existente = jdbc.queryForList("SELECT nota_id FROM corona.trocas_nota WHERE id = ?", UUID.class, id);
        if (!existente.isEmpty()) {
            if (!existente.get(0).equals(nota.id())) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        exigirSemAcerto(nota.id());
        // A peça que volta precisa estar na nota (DX da linha ≥ 1), na maleta de onde saiu.
        Integer dx = jdbc.queryForObject("""
                SELECT coalesce(sum(quantidade), 0) FROM corona.itens_nota_consignado
                 WHERE nota_id = ? AND remessa_id = ? AND produto_id = ?""", Integer.class, nota.id(), volta.remessaId(), volta.produtoId());
        if (dx == null || dx < 1) {
            throw ErroDaApi.comDetalhe(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.DEVOLUCAO_MAIOR_QUE_O_LEVADO,
                    "Esta peça não consta na nota do cliente. Confira a peça que ele devolve.");
        }
        BigDecimal precoQueVolta = jdbc.queryForObject("""
                SELECT preco_unitario FROM corona.itens_nota_consignado
                 WHERE nota_id = ? AND remessa_id = ? AND produto_id = ? ORDER BY created_at LIMIT 1""", BigDecimal.class, nota.id(),
                volta.remessaId(), volta.produtoId());
        PrecoDaMaleta preco = precoDaMaleta(ctx, entra.remessaId(), entra.produtoId());
        if (entra.precoUnitario() != null && entra.precoUnitario().compareTo(preco.preco()) != 0) {
            alertas.registrar("PRECO_DIFERENTE_DA_MALETA", ctx.vendedorId(), "trocas_nota", id,
                    Map.of("visto", entra.precoUnitario().toPlainString(), "maleta", preco.preco().toPlainString()));
        }
        alertarSeSemSaldo(ctx, id, "trocas_nota", entra.remessaId(), entra.produtoId(), 1);
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        jdbc.update("INSERT INTO corona.trocas_nota (id, nota_id, trocada_em, dispositivo_id, hora_ajustada) VALUES (?, ?, ?, ?, ?)", id,
                nota.id(), Timestamp.from(ctx.quando()), ctx.aparelho(), ctx.horaAjustada());
        jdbc.update("""
                INSERT INTO corona.itens_nota_consignado (id, nota_id, produto_id, remessa_id, origem, quantidade, preco_unitario, troca_id)
                VALUES (?, ?, ?, ?, 'TROCA_VOLTA', -1, ?, ?)""", volta.itemId(), nota.id(), volta.produtoId(), volta.remessaId(),
                precoQueVolta, id);
        movimento(ctx, volta.movimentoId(), volta.remessaId(), volta.produtoId(), "CONSIGNADO_RETORNO", 1, nota.id(), null);
        jdbc.update("""
                INSERT INTO corona.itens_nota_consignado (id, nota_id, produto_id, remessa_id, origem, quantidade, preco_unitario, troca_id)
                VALUES (?, ?, ?, ?, 'TROCA_ENTRADA', 1, ?, ?)""", entra.itemId(), nota.id(), entra.produtoId(), entra.remessaId(),
                preco.preco(), id);
        movimento(ctx, entra.movimentoId(), entra.remessaId(), entra.produtoId(), "CONSIGNADO_SAIDA", -1, nota.id(), null);
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "NOTA_CONSIGNADO_TROCA", "trocas_nota", id, null,
                Map.of("nota_id", nota.id().toString()));
        return false;
    }

    // --- remarcação ---

    @Transactional
    public boolean remarcar(ContextoDoConsignado ctx, Remarcacao pedido) {
        if (pedido == null || pedido.notaId() == null || pedido.dataCobranca() == null) {
            throw Validacoes.recusar("dataCobranca", "Informe a nova data de cobrança.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        VendedorAtivo.exigir(jdbc, ctx.vendedorId());
        NotaLida nota = travarNotaDoVendedor(ctx, pedido.notaId());
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT nota_id, data_cobranca_nova FROM corona.remarcacoes_nota WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            if (!nota.id().equals(e.get("nota_id")) || !pedido.dataCobranca().equals(((java.sql.Date) e.get("data_cobranca_nova")).toLocalDate())) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CONSIGNADO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        exigirSemAcerto(nota.id());
        if (ctx.aparelho() != null) {
            origem.definirNaTransacaoAtual(ctx.aparelho());
        }
        jdbc.update("""
                INSERT INTO corona.remarcacoes_nota (id, nota_id, data_cobranca_anterior, data_cobranca_nova, remarcada_em,
                    dispositivo_id, hora_ajustada) VALUES (?, ?, ?, ?, ?, ?, ?)""", id, nota.id(), nota.dataCobranca(),
                pedido.dataCobranca(), Timestamp.from(ctx.quando()), ctx.aparelho(), ctx.horaAjustada());
        jdbc.update("UPDATE corona.notas_consignado SET data_cobranca = ? WHERE id = ?", pedido.dataCobranca(), nota.id());
        debitos.reavaliar(nota.id());
        auditoria.registrar(Ator.usuario(ctx.usuarioId()), "NOTA_CONSIGNADO_REMARCADA", "remarcacoes_nota", id,
                Map.of("data_cobranca", nota.dataCobranca().toString()), Map.of("data_cobranca", pedido.dataCobranca().toString()));
        return false;
    }

    // --- peças em comum ---

    /** Os itens do pedido, conferidos em forma (quantidade positiva, ids, preço com duas casas). */
    private static List<ItemDaNota> itensValidos(List<ItemDaNota> itens) {
        if (itens == null || itens.isEmpty()) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PEDIDO_SEM_PECAS);
        }
        Set<UUID> ids = new HashSet<>();
        for (ItemDaNota i : itens) {
            if (i == null || i.id() == null || i.movimentoId() == null || i.produtoId() == null || i.remessaId() == null
                    || i.quantidade() < 1 || !ids.add(i.id()) || !ids.add(i.movimentoId())) {
                throw Validacoes.recusar("itens", "Confira as peças do pedido.");
            }
            if (i.precoUnitario() == null || i.precoUnitario().signum() < 0 || i.precoUnitario().stripTrailingZeros().scale() > 2) {
                throw Validacoes.recusar("itens", "Confira o preço das peças.");
            }
        }
        return itens;
    }

    private boolean mesmosItens(UUID notaId, String origemDoItem, List<ItemDaNota> itens) {
        return mesmosItens(notaId, origemDoItem, itens, null);
    }

    private boolean mesmosItens(UUID notaId, String origemDoItem, List<ItemDaNota> itens, UUID reposicaoId) {
        Set<UUID> gravados = new HashSet<>(jdbc.queryForList("""
                SELECT id FROM corona.itens_nota_consignado WHERE nota_id = ? AND origem = ? AND reposicao_id IS NOT DISTINCT FROM ?::uuid""",
                UUID.class, notaId, origemDoItem, reposicaoId));
        return gravados.equals(new HashSet<>(itens.stream().map(ItemDaNota::id).toList()));
    }

    /** Grava os itens e as saídas da maleta, na ordem estável (maleta, peça). Avisa a dona se faltar saldo ou o preço mudou. */
    private void gravarSaidas(ContextoDoConsignado ctx, UUID notaId, String origemDoItem, UUID reposicaoId, List<ItemDaNota> itens) {
        Map<String, Integer> pedidoPorLinha = new LinkedHashMap<>();
        for (ItemDaNota i : itens) {
            pedidoPorLinha.merge(i.remessaId() + ":" + i.produtoId(), i.quantidade(), Integer::sum);
        }
        Set<String> alertadas = new HashSet<>();
        for (ItemDaNota i : itens) {
            PrecoDaMaleta p = precoDaMaleta(ctx, i.remessaId(), i.produtoId());
            if (i.precoUnitario().compareTo(p.preco()) != 0) {
                alertas.registrar("PRECO_DIFERENTE_DA_MALETA", ctx.vendedorId(), "notas_consignado", notaId,
                        Map.of("produto_id", i.produtoId().toString(), "visto", i.precoUnitario().toPlainString(),
                                "maleta", p.preco().toPlainString()));
            }
            String linha = i.remessaId() + ":" + i.produtoId();
            if (alertadas.add(linha)) {
                alertarSeSemSaldo(ctx, notaId, "notas_consignado", i.remessaId(), i.produtoId(), pedidoPorLinha.get(linha));
            }
            jdbc.update("""
                    INSERT INTO corona.itens_nota_consignado (id, nota_id, produto_id, remessa_id, origem, quantidade, preco_unitario,
                        reposicao_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", i.id(), notaId, i.produtoId(), i.remessaId(), origemDoItem,
                    i.quantidade(), p.preco(), reposicaoId);
            movimento(ctx, i.movimentoId(), i.remessaId(), i.produtoId(), "CONSIGNADO_SAIDA", -i.quantidade(), notaId, null);
        }
    }

    private void movimento(ContextoDoConsignado ctx, UUID id, UUID remessaId, UUID produtoId, String tipo, int quantidade,
            UUID notaId, UUID acertoId) {
        jdbc.update("""
                INSERT INTO corona.movimentos_remessa (id, remessa_id, produto_id, tipo, quantidade, dispositivo_id, nota_id, acerto_nota_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, remessaId, produtoId, tipo, quantidade, ctx.aparelho(), notaId, acertoId);
    }

    /** Alerta (e aceita) quando a saída pedida passa do saldo da maleta (dúvida D4). */
    private void alertarSeSemSaldo(ContextoDoConsignado ctx, UUID registroId, String tabela, UUID remessaId, UUID produtoId, int pedido) {
        int saldo = saldoDaMaleta(remessaId, produtoId);
        if (pedido > saldo) {
            alertas.registrar("PECA_SEM_SALDO_NA_MALETA", ctx.vendedorId(), tabela, registroId,
                    Map.of("produto_id", produtoId.toString(), "remessa_id", remessaId.toString(), "pedido", pedido, "saldo", saldo));
        }
    }

    int saldoDaMaleta(UUID remessaId, UUID produtoId) {
        Integer saldo = jdbc.queryForObject("""
                SELECT coalesce((SELECT i.qtd_enviada FROM corona.itens_remessa i
                                  WHERE i.remessa_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL), 0)
                       + coalesce((SELECT sum(m.quantidade) FROM corona.movimentos_remessa m WHERE m.remessa_id = ? AND m.produto_id = ?), 0)""",
                Integer.class, remessaId, produtoId, remessaId, produtoId);
        return saldo == null ? 0 : saldo;
    }

    private record PrecoDaMaleta(BigDecimal preco) {
    }

    /** A maleta é do vendedor e está com ele; a peça consta nela; o preço é o da maleta (o app não decide). */
    private PrecoDaMaleta precoDaMaleta(ContextoDoConsignado ctx, UUID remessaId, UUID produtoId) {
        List<Map<String, Object>> maleta = jdbc.queryForList(
                "SELECT status, vendedor_id FROM corona.remessas WHERE id = ? AND deleted_at IS NULL", remessaId);
        if (maleta.isEmpty() || !ctx.vendedorId().equals(maleta.get(0).get("vendedor_id"))
                || !List.of("ENVIADA", "CONFERIDA").contains(maleta.get(0).get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_NAO_ESTA_COM_VOCE);
        }
        List<BigDecimal> preco = jdbc.queryForList(
                "SELECT preco_remessa FROM corona.itens_remessa WHERE remessa_id = ? AND produto_id = ? AND deleted_at IS NULL",
                BigDecimal.class, remessaId, produtoId);
        if (preco.isEmpty()) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.PECA_FORA_DA_MALETA);
        }
        return new PrecoDaMaleta(preco.get(0));
    }

    private void semanaDoVendedor(ContextoDoConsignado ctx, UUID semanaId) {
        List<Map<String, Object>> s = jdbc.queryForList(
                "SELECT vendedor_id, situacao, fechada_em FROM corona.semanas_vendedor WHERE id = ?", semanaId);
        if (s.isEmpty() || !ctx.vendedorId().equals(s.get(0).get("vendedor_id"))) {
            throw new ErroDaApi(HttpStatus.UNPROCESSABLE_ENTITY, CodigoErro.SEMANA_NAO_ENCONTRADA);
        }
        Object fechada = s.get(0).get("fechada_em");
        if ("FECHADA".equals(s.get(0).get("situacao")) && fechada != null) {
            Instant fim = ((java.sql.Timestamp) fechada).toInstant();
            if (ctx.quando().isAfter(fim)) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_DA_NOTA_FECHADA);
            }
        }
    }

    private void conferirCliente(UUID clienteId, UUID vendedorId) {
        List<UUID> dono = jdbc.queryForList(
                "SELECT vendedor_responsavel_id FROM corona.clientes WHERE id = ? AND deleted_at IS NULL", UUID.class, clienteId);
        if (dono.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        if (!vendedorId.equals(dono.get(0))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.CLIENTE_DE_OUTRO_VENDEDOR);
        }
    }

    /** Reserva semana-sequência (única por representante e por tipo: nota e recibo têm contador próprio). Outro id com o mesmo número é recusado. */
    void reservarNumero(ContextoDoConsignado ctx, UUID semanaId, int sequencia, String tipo, UUID registroId) {
        Boolean usado = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.numeros_da_semana WHERE vendedor_id = ? AND semana_id = ? AND tipo = ? AND sequencia = ?)",
                Boolean.class, ctx.vendedorId(), semanaId, tipo, sequencia);
        if (Boolean.TRUE.equals(usado)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NUMERO_REPETIDO);
        }
        jdbc.update("INSERT INTO corona.numeros_da_semana (id, vendedor_id, semana_id, sequencia, tipo, registro_id) VALUES (?, ?, ?, ?, ?, ?)",
                UuidV7.gerar(), ctx.vendedorId(), semanaId, sequencia, tipo, registroId);
    }

    NotaLida notaDoVendedor(ContextoDoConsignado ctx, UUID notaId) {
        return lerNota(ctx, notaId, false);
    }

    NotaLida travarNotaDoVendedor(ContextoDoConsignado ctx, UUID notaId) {
        return lerNota(ctx, notaId, true);
    }

    private NotaLida lerNota(ContextoDoConsignado ctx, UUID notaId, boolean travar) {
        List<NotaLida> lista = jdbc.query("SELECT id, cliente_id, semana_id, data_cobranca FROM corona.notas_consignado"
                + " WHERE id = ? AND vendedor_id = ? AND deleted_at IS NULL" + (travar ? " FOR UPDATE" : ""),
                (rs, i) -> new NotaLida(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getDate(4).toLocalDate()), notaId, ctx.vendedorId());
        if (lista.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.NOTA_NAO_ENCONTRADA);
        }
        return lista.get(0);
    }

    private void exigirSemAcerto(UUID notaId) {
        Boolean tem = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM corona.acertos_nota WHERE nota_id = ? AND estornado_em IS NULL)", Boolean.class, notaId);
        if (Boolean.TRUE.equals(tem)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NOTA_JA_ACERTADA);
        }
    }

    /** A cotação vigente no momento do lançamento (ADR-0006, item 15), ou nenhuma. */
    private UUID cotacaoDoDia(Instant quando) {
        return CotacaoVigente.idVigenteEm(jdbc, quando);
    }

    /** Todas as linhas (produto + maleta) da nota com DX e preço, para o acerto. */
    List<Map<String, Object>> linhasDaNota(UUID notaId) {
        return new ArrayList<>(jdbc.queryForList("""
                SELECT remessa_id, produto_id, sum(quantidade) AS dx, max(preco_unitario) AS preco
                  FROM corona.itens_nota_consignado WHERE nota_id = ?
                 GROUP BY remessa_id, produto_id ORDER BY remessa_id, produto_id""", notaId));
    }
}

package br.com.coronasemijoias.api.acertos;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.comum.erros.Problemas;
import br.com.coronasemijoias.api.comum.persistencia.OrigemDoDispositivo;
import br.com.coronasemijoias.api.vendedor.VendedorAtivo;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acerto, parte 1: iniciar, contar e desistir (tarefa 7.4; ADR-0012, itens 2, 3, 4, 13, 14, 15 e 16). Único lugar que liga e
 * desliga {@code remessas.acerto_id} e que escreve em {@code acertos} e {@code leituras_acerto}. Nesta tarefa não há dinheiro.
 *
 * <p>Ordem única das travas (ADR-0012, item 13): vendedor, acerto, remessas (por id), itens e produtos, contas. A trava do
 * vendedor é {@code FOR NO KEY UPDATE}: venda, movimento e conferência pegam {@code FOR KEY SHARE} na linha do vendedor (chave
 * estrangeira) e não conflitam com ela, então não há espera em volta com a venda, que já segura a maleta.
 */
@Service
public class ServicoDeAcertos {

    static final int LIMITE_DO_MOTIVO = 500;
    static final int LIMITE_DA_LEITURA = 10_000;
    private static final Set<String> ESTADOS_DA_PECA = Set.of("BOA", "DEFEITO");
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** Quem pede: o usuário, o vendedor (nulo = a dona) e o computador da sessão (só do vendedor). */
    public record Quem(UUID usuarioId, UUID vendedorId, UUID dispositivoId) {
        boolean pelaDona() {
            return vendedorId == null;
        }
    }

    public record PedidoDeInicio(UUID id, UUID vendedorId, UUID semanaId) {
    }

    public record PedidoDeLeitura(UUID id, UUID produtoId, Integer quantidade, String estadoPeca) {
    }

    public record PedidoDeDesistencia(UUID id, String motivo) {
    }

    /** {@code jaExistia}: o comando já estava gravado (200); senão, algo novo foi gravado (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    private record Aberta(UUID id, String status, UUID dispositivoId, UUID semanaId, LocalDate inicioDaSemana) {
    }

    private final JdbcTemplate jdbc;
    private final ServicoDeAuditoria auditoria;
    private final OrigemDoDispositivo origem;
    private final Clock relogio;

    ServicoDeAcertos(JdbcTemplate jdbc, ServicoDeAuditoria auditoria, OrigemDoDispositivo origem, Clock relogio) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.origem = origem;
        this.relogio = relogio;
    }

    /**
     * Trava o vendedor ({@code FOR NO KEY UPDATE}): serializa iniciar, desistir e enviar remessa do mesmo vendedor. Primeira
     * trava da ordem; 404 se o cadastro não existe.
     */
    public static void trancarVendedor(JdbcTemplate jdbc, UUID vendedorId) {
        List<Integer> achado = jdbc.queryForList(
                "SELECT 1 FROM corona.vendedores WHERE id = ? AND deleted_at IS NULL FOR NO KEY UPDATE", Integer.class, vendedorId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
    }

    // --- iniciar ---

    /**
     * Inicia o acerto do vendedor e liga a ele todas as maletas enviadas ou conferidas, que passam a {@code EM_ACERTO} com o
     * status de antes guardado. Pelo vendedor ({@code quem.vendedorId()}), exige cadastro ativo, ao menos uma maleta aberta,
     * fila do computador vazia e nenhuma maleta conferida em outro computador; a dona inicia sempre.
     */
    @Transactional
    public Resultado iniciar(Quem quem, PedidoDeInicio pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("acerto", "Informe o acerto.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        boolean pelaDona = quem.pelaDona();
        UUID vendedorId = pelaDona ? pedido.vendedorId() : quem.vendedorId();
        if (vendedorId == null) {
            throw Validacoes.recusar("vendedorId", "Escolha o vendedor do acerto.");
        }
        trancarVendedor(jdbc, vendedorId);

        // Idempotência: o mesmo id, do mesmo vendedor (e da mesma semana, se informada) é o mesmo pedido.
        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT vendedor_id, semana_id, pedido_pelo_vendedor FROM corona.acertos WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            boolean igual = vendedorId.equals(e.get("vendedor_id")) && (pelaDona != (Boolean) e.get("pedido_pelo_vendedor"))
                    && (pedido.semanaId() == null || pedido.semanaId().equals(e.get("semana_id")));
            if (igual) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_JA_REGISTRADO_DIFERENTE);
        }
        List<UUID> emAndamento = jdbc.queryForList(
                "SELECT id FROM corona.acertos WHERE vendedor_id = ? AND status = 'EM_ACERTO'", UUID.class, vendedorId);
        if (!emAndamento.isEmpty()) {
            ProblemDetail corpo = Problemas.de(HttpStatus.CONFLICT, CodigoErro.ACERTO_EM_ANDAMENTO);
            corpo.setProperty("acertoId", emAndamento.get(0).toString());
            throw ErroDaApi.comCorpo(HttpStatus.CONFLICT, CodigoErro.ACERTO_EM_ANDAMENTO, corpo);
        }
        if (!pelaDona) {
            VendedorAtivo.exigir(jdbc, vendedorId);
        }

        // As maletas abertas: travadas em ordem de id (depois do vendedor; o acerto ainda não existe).
        List<Aberta> abertas = jdbc.query("""
                SELECT r.id, r.status, r.dispositivo_id, r.semana_id, s.inicio
                  FROM corona.remessas r JOIN corona.semanas s ON s.id = r.semana_id
                 WHERE r.vendedor_id = ? AND r.status IN ('ENVIADA', 'CONFERIDA') AND r.deleted_at IS NULL
                 ORDER BY r.id FOR UPDATE OF r""", (rs, n) -> new Aberta(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("dispositivo_id", UUID.class), rs.getObject("semana_id", UUID.class),
                rs.getObject("inicio", LocalDate.class)), vendedorId);
        if (!pelaDona) {
            if (abertas.isEmpty()) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEM_MALETA_PARA_ACERTO);
            }
            Integer aguardando = quem.dispositivoId() == null ? null : jdbc.query(
                    "SELECT aguardando_envio FROM corona.dispositivos WHERE id = ?",
                    rs -> rs.next() ? (Integer) rs.getObject(1) : null, quem.dispositivoId());
            if (aguardando != null && aguardando > 0) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ENVIOS_AGUARDANDO);
            }
            for (Aberta a : abertas) {
                if ("CONFERIDA".equals(a.status()) && a.dispositivoId() != null && !a.dispositivoId().equals(quem.dispositivoId())) {
                    throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.MALETA_EM_OUTRO_APARELHO);
                }
            }
        }

        UUID semanaId = escolherASemana(pedido.semanaId(), abertas);
        Boolean aberta = jdbc.query("SELECT situacao = 'ABERTA' FROM corona.semanas WHERE id = ? AND deleted_at IS NULL FOR SHARE",
                rs -> rs.next() ? rs.getBoolean(1) : null, semanaId);
        if (aberta == null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_NAO_ENCONTRADA);
        }
        if (!aberta) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_FECHADA);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.acertos WHERE vendedor_id = ? AND semana_id = ? AND status <> 'DESISTIDO')""",
                Boolean.class, vendedorId, semanaId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_DA_SEMANA_JA_EXISTE);
        }

        if (quem.dispositivoId() != null) {
            origem.definirNaTransacaoAtual(quem.dispositivoId());
        }
        try {
            jdbc.update("""
                    INSERT INTO corona.acertos (id, vendedor_id, semana_id, status, pedido_pelo_vendedor, iniciado_em, iniciado_por,
                        dispositivo_id) VALUES (?, ?, ?, 'EM_ACERTO', ?, ?, ?, ?)""", id, vendedorId, semanaId, !pelaDona,
                    Timestamp.from(relogio.instant()), quem.usuarioId(), pelaDona ? null : quem.dispositivoId());
        } catch (DuplicateKeyException e) {
            // O mesmo id em outro vendedor, ao mesmo tempo: é um pedido diferente com o mesmo id.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_JA_REGISTRADO_DIFERENTE);
        }
        List<String> ligadas = new ArrayList<>();
        for (Aberta a : abertas) {
            jdbc.update("""
                    UPDATE corona.remessas SET status = 'EM_ACERTO', acerto_id = ?, status_antes_do_acerto = status
                     WHERE id = ?""", id, a.id());
            ligadas.add(a.id() + ":" + a.status());
        }
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("vendedor_id", vendedorId.toString());
        depois.put("semana_id", semanaId.toString());
        depois.put("pedido_pelo_vendedor", !pelaDona);
        depois.put("maletas", ligadas);
        auditoria.registrar(Ator.usuario(quem.usuarioId()), "ACERTO_INICIADO", "acertos", id, null, depois);
        return new Resultado(id, false);
    }

    /** A semana informada; senão a mais recente das maletas ligadas; sem maleta, a semana que contém hoje em Brasília. */
    private UUID escolherASemana(UUID informada, List<Aberta> abertas) {
        if (informada != null) {
            return informada;
        }
        Aberta maisRecente = null;
        for (Aberta a : abertas) {
            if (maisRecente == null || a.inicioDaSemana().isAfter(maisRecente.inicioDaSemana())) {
                maisRecente = a;
            }
        }
        if (maisRecente != null) {
            return maisRecente.semanaId();
        }
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), BRASILIA);
        List<UUID> deHoje = jdbc.queryForList(
                "SELECT id FROM corona.semanas WHERE deleted_at IS NULL AND ? BETWEEN inicio AND fim ORDER BY inicio DESC", UUID.class, hoje);
        if (deHoje.isEmpty()) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEMANA_NAO_ENCONTRADA);
        }
        return deHoje.get(0);
    }

    // --- desistir ---

    /**
     * A dona desiste do acerto em andamento: cada maleta volta ao status de antes, o acerto vira {@code DESISTIDO} e nada de
     * dinheiro, venda ou movimento é gravado. As leituras ficam como histórico. Motivo obrigatório; o id do comando é do app.
     */
    @Transactional
    public Resultado desistir(Quem quem, UUID acertoId, PedidoDeDesistencia pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de desistir do acerto.");
        }
        UUID comando = Validacoes.idDoPedido(pedido.id());
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de desistir do acerto.");
        }
        if (motivo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        UUID vendedorId = jdbc.query("SELECT vendedor_id FROM corona.acertos WHERE id = ? AND deleted_at IS NULL",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, acertoId);
        if (vendedorId == null) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
        }
        trancarVendedor(jdbc, vendedorId);
        Map<String, Object> acerto = jdbc.queryForMap(
                "SELECT status, desistencia_id FROM corona.acertos WHERE id = ? FOR UPDATE", acertoId);
        String status = (String) acerto.get("status");
        if ("DESISTIDO".equals(status)) {
            if (comando.equals(acerto.get("desistencia_id"))) {
                return new Resultado(acertoId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DESISTENCIA_JA_REGISTRADA_DIFERENTE);
        }
        if (!"EM_ACERTO".equals(status)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_NAO_ESTA_EM_ANDAMENTO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.acertos WHERE desistencia_id = ?)",
                Boolean.class, comando))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.DESISTENCIA_JA_REGISTRADA_DIFERENTE);
        }
        List<Map<String, Object>> maletas = jdbc.queryForList("""
                SELECT id, status_antes_do_acerto FROM corona.remessas WHERE acerto_id = ? ORDER BY id FOR UPDATE""", acertoId);
        List<String> devolvidas = new ArrayList<>();
        for (Map<String, Object> m : maletas) {
            jdbc.update("""
                    UPDATE corona.remessas SET status = status_antes_do_acerto, acerto_id = NULL, status_antes_do_acerto = NULL
                     WHERE id = ?""", m.get("id"));
            devolvidas.add(m.get("id") + ":" + m.get("status_antes_do_acerto"));
        }
        jdbc.update("""
                UPDATE corona.acertos SET status = 'DESISTIDO', desistencia_id = ?, desistido_em = ?, desistido_por = ?,
                       motivo_desistencia = ? WHERE id = ?""", comando, Timestamp.from(relogio.instant()), quem.usuarioId(), motivo,
                acertoId);
        Map<String, Object> antes = new LinkedHashMap<>();
        antes.put("status", "EM_ACERTO");
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("status", "DESISTIDO");
        depois.put("motivo", motivo);
        depois.put("maletas_devolvidas_ao_estado_anterior", devolvidas);
        auditoria.registrar(Ator.usuario(quem.usuarioId()), "ACERTO_DESISTIDO", "acertos", acertoId, antes, depois);
        return new Resultado(acertoId, false);
    }

    // --- leitura (contagem) ---

    /**
     * Registra uma leitura (um bipe ou um desfazer) na contagem do acerto em andamento. Trava o acerto: leituras do mesmo
     * acerto são uma de cada vez (a soma nunca fica negativa) e esperam uma desistência em andamento. Mesmo id e mesmo conteúdo
     * devolve 200 (antes de olhar o status); outro conteúdo, 409.
     */
    @Transactional
    public Resultado registrarLeitura(Quem quem, UUID acertoId, PedidoDeLeitura pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("leitura", "Informe a leitura.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        if (pedido.produtoId() == null) {
            throw Validacoes.recusar("produtoId", "Escolha a peça lida.");
        }
        if (pedido.quantidade() == null || pedido.quantidade() == 0 || Math.abs(pedido.quantidade()) > LIMITE_DA_LEITURA) {
            throw Validacoes.recusar("quantidade", "Informe uma quantidade diferente de zero, de até " + LIMITE_DA_LEITURA + " peças.");
        }
        if (pedido.estadoPeca() == null || !ESTADOS_DA_PECA.contains(pedido.estadoPeca())) {
            throw Validacoes.recusar("estadoPeca", "Diga se a peça está boa ou com defeito.");
        }
        List<Map<String, Object>> achado = jdbc.queryForList(
                "SELECT vendedor_id, status FROM corona.acertos WHERE id = ? AND deleted_at IS NULL FOR UPDATE", acertoId);
        if (achado.isEmpty() || (!quem.pelaDona() && !quem.vendedorId().equals(achado.get(0).get("vendedor_id")))) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.ACERTO_NAO_ENCONTRADO);
        }
        UUID vendedorId = (UUID) achado.get(0).get("vendedor_id");

        List<Map<String, Object>> existente = jdbc.queryForList(
                "SELECT acerto_id, produto_id, quantidade, estado_peca FROM corona.leituras_acerto WHERE id = ?", id);
        if (!existente.isEmpty()) {
            Map<String, Object> e = existente.get(0);
            if (acertoId.equals(e.get("acerto_id")) && pedido.produtoId().equals(e.get("produto_id"))
                    && pedido.quantidade().equals(e.get("quantidade")) && pedido.estadoPeca().equals(e.get("estado_peca"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LEITURA_JA_REGISTRADA_DIFERENTE);
        }
        if (!"EM_ACERTO".equals(achado.get(0).get("status"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.ACERTO_NAO_ESTA_EM_ANDAMENTO);
        }
        // A peça é de uma maleta do acerto ou da devolução "para a loja" do vendedor (esperada no acerto); de fora, nunca.
        boolean daMaleta = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM corona.itens_remessa i JOIN corona.remessas r ON r.id = i.remessa_id
                                WHERE r.acerto_id = ? AND i.produto_id = ? AND i.deleted_at IS NULL)
                    OR EXISTS (SELECT 1 FROM corona.itens_devolucao d2
                                 JOIN corona.devolucoes d ON d.id = d2.devolucao_id
                                 JOIN corona.itens_venda iv ON iv.id = d2.item_venda_id
                                WHERE d.vendedor_id = ? AND d.situacao <> 'CANCELADA' AND d2.destino_peca = 'LOJA'
                                  AND iv.produto_id = ?)""", Boolean.class, acertoId, pedido.produtoId(), vendedorId,
                pedido.produtoId()));
        if (!daMaleta) {
            throw ErroDaApi.comDetalhe(HttpStatus.NOT_FOUND, CodigoErro.PECA_NAO_ENCONTRADA,
                    "Esta peça não consta nas maletas deste acerto.");
        }
        Long soma = jdbc.queryForObject("""
                SELECT coalesce(sum(quantidade), 0) FROM corona.leituras_acerto
                 WHERE acerto_id = ? AND produto_id = ? AND estado_peca = ?""", Long.class, acertoId, pedido.produtoId(),
                pedido.estadoPeca());
        if (soma + pedido.quantidade() < 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.LEITURA_DEIXA_CONTAGEM_NEGATIVA, soma == 0
                    ? "Ainda não há peças contadas assim para tirar. Confira a contagem."
                    : "Só há " + soma + " peça(s) contada(s) assim. Não dá para tirar " + (-pedido.quantidade()) + ".");
        }
        if (quem.dispositivoId() != null) {
            origem.definirNaTransacaoAtual(quem.dispositivoId());
        }
        try {
            jdbc.update("""
                    INSERT INTO corona.leituras_acerto (id, acerto_id, produto_id, quantidade, estado_peca, lida_em, lida_por,
                        pelo_vendedor, dispositivo_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, acertoId, pedido.produtoId(),
                    pedido.quantidade(), pedido.estadoPeca(), Timestamp.from(relogio.instant()), quem.usuarioId(), !quem.pelaDona(),
                    quem.pelaDona() ? null : quem.dispositivoId());
        } catch (DuplicateKeyException e) {
            // O mesmo id gravado por outro pedido ao mesmo tempo (em outro acerto): é um pedido diferente com o mesmo id.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LEITURA_JA_REGISTRADA_DIFERENTE);
        }
        return new Resultado(id, false);
    }
}

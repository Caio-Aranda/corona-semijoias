package br.com.coronasemijoias.api.caixa;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
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
 * O lançamento manual do caixa (tarefa 7.7; ADR-0012, itens 11 e 15): a dona lança entradas e saídas avulsas, com categoria do
 * mesmo tipo, forma (dinheiro, Transferência (PIX) ou boleto), data (nunca no futuro, nunca numa semana fechada) e descrição. O
 * valor vem sempre positivo, em Real; o sinal é do servidor. O lançamento é imutável: a correção é o estorno, com motivo. Quem
 * grava é o {@link ServicoDeCaixa}. Saldo negativo não bloqueia nada (só avisa). Só a dona (ADMIN).
 */
@Service
public class ServicoDeLancamentosManuais {

    /** O pedido de lançamento: o id vem do aplicativo (idempotência); o valor é positivo e o tipo diz se entrou ou saiu. */
    public record PedidoDeLancamento(UUID id, String tipo, BigDecimal valor, String forma, UUID categoriaId, LocalDate data,
            String descricao) {
    }

    /** O pedido de estorno do lançamento manual: motivo obrigatório. */
    public record PedidoDeEstorno(UUID id, String motivo) {
    }

    /** {@code jaExistia}: já estava gravado (200); senão, foi gravado agora (201). */
    public record Resultado(UUID id, boolean jaExistia) {
    }

    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX", "BOLETO");
    private static final BigDecimal VALOR_MAXIMO = new BigDecimal("999999999999.99");
    private static final int LIMITE_DA_DESCRICAO = 200;
    private static final int LIMITE_DO_MOTIVO = 400;

    private final JdbcTemplate jdbc;
    private final ServicoDeCaixa caixa;
    private final ServicoDeAuditoria auditoria;

    ServicoDeLancamentosManuais(JdbcTemplate jdbc, ServicoDeCaixa caixa, ServicoDeAuditoria auditoria) {
        this.jdbc = jdbc;
        this.caixa = caixa;
        this.auditoria = auditoria;
    }

    @Transactional
    public Resultado lancar(UUID usuarioId, PedidoDeLancamento pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("lancamento", "Informe o lançamento.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        String tipo = pedido.tipo();
        if (!"ENTRADA".equals(tipo) && !"SAIDA".equals(tipo)) {
            throw Validacoes.recusar("tipo", "Escolha se o dinheiro entrou ou saiu.");
        }
        BigDecimal valor = valorEmReais(pedido.valor());
        if (pedido.forma() == null || !FORMAS.contains(pedido.forma())) {
            throw Validacoes.recusar("forma", "Escolha como o dinheiro entrou ou saiu: dinheiro, Transferência (PIX) ou boleto.");
        }
        if (pedido.categoriaId() == null) {
            throw Validacoes.recusar("categoriaId", "Escolha a categoria do lançamento.");
        }
        if (pedido.data() == null) {
            throw Validacoes.recusar("data", "Informe a data do lançamento.");
        }
        if (pedido.data().isAfter(caixa.hoje())) {
            throw Validacoes.recusar("data", "A data do lançamento não pode ser no futuro.");
        }
        String descricao = Validacoes.limpar(pedido.descricao());
        if (descricao == null) {
            throw Validacoes.recusar("descricao", "Descreva o lançamento.");
        }
        if (descricao.length() > LIMITE_DA_DESCRICAO) {
            throw Validacoes.recusar("descricao", "Use no máximo " + LIMITE_DA_DESCRICAO + " caracteres.");
        }
        String resumo = resumo(tipo + "|" + valor.toPlainString() + "|" + pedido.forma() + "|" + pedido.categoriaId() + "|" + pedido.data()
                + "|" + descricao);
        travar("lancamento:" + id);

        List<Map<String, Object>> existente = jdbc.queryForList("SELECT conteudo FROM corona.lancamentos_caixa WHERE id = ?", id);
        if (!existente.isEmpty()) {
            if (resumo.equals(existente.get(0).get("conteudo"))) {
                return new Resultado(id, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_DO_CAIXA_JA_REGISTRADO_DIFERENTE);
        }
        conferirCategoria(pedido.categoriaId(), tipo);
        caixa.lancarManual(usuarioId, id, tipo, valor, pedido.forma(), pedido.categoriaId(), pedido.data(), descricao, resumo);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("tipo", tipo);
        depois.put("valor", valor.toPlainString());
        depois.put("forma", pedido.forma());
        depois.put("categoria_id", pedido.categoriaId().toString());
        depois.put("data", pedido.data().toString());
        depois.put("descricao", descricao);
        auditoria.registrar(Ator.usuario(usuarioId), "LANCAMENTO_MANUAL_REGISTRADO", "lancamentos_caixa", id, null, depois);
        return new Resultado(id, false);
    }

    @Transactional
    public Resultado estornar(UUID usuarioId, UUID lancamentoId, PedidoDeEstorno pedido) {
        if (pedido == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o lançamento.");
        }
        UUID estornoId = Validacoes.idDoPedido(pedido.id());
        String motivo = Validacoes.limpar(pedido.motivo());
        if (motivo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de estornar o lançamento.");
        }
        if (motivo.length() > LIMITE_DO_MOTIVO) {
            throw Validacoes.recusar("motivo", "Use no máximo " + LIMITE_DO_MOTIVO + " caracteres.");
        }
        String resumo = resumo(lancamentoId + "|" + motivo);
        travar("lancamento:" + lancamentoId);

        List<Map<String, Object>> achado = jdbc.queryForList(
                "SELECT origem_tipo, estorna_id, valor FROM corona.lancamentos_caixa WHERE id = ?", lancamentoId);
        if (achado.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.LANCAMENTO_DO_CAIXA_NAO_ENCONTRADO);
        }
        Map<String, Object> original = achado.get(0);
        if (!"MANUAL".equals(original.get("origem_tipo"))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_AUTOMATICO);
        }
        if (original.get("estorna_id") != null) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_DO_CAIXA_E_ESTORNO);
        }
        List<Map<String, Object>> jaEstornado = jdbc.queryForList("SELECT id, conteudo FROM corona.lancamentos_caixa WHERE estorna_id = ?",
                lancamentoId);
        if (!jaEstornado.isEmpty()) {
            if (estornoId.equals(jaEstornado.get(0).get("id")) && resumo.equals(jaEstornado.get(0).get("conteudo"))) {
                return new Resultado(estornoId, true);
            }
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_ESTORNADO);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.lancamentos_caixa WHERE id = ?)", Boolean.class,
                estornoId))) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_DO_CAIXA_JA_REGISTRADO_DIFERENTE);
        }
        caixa.estornarManual(usuarioId, lancamentoId, estornoId, motivo, resumo);

        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("lancamento_id", lancamentoId.toString());
        depois.put("valor", ((BigDecimal) original.get("valor")).negate().toPlainString());
        depois.put("motivo", motivo);
        auditoria.registrar(Ator.usuario(usuarioId), "LANCAMENTO_MANUAL_ESTORNADO", "lancamentos_caixa", estornoId, null, depois);
        return new Resultado(estornoId, false);
    }

    /** A categoria precisa existir, estar ativa, ser do mesmo tipo do lançamento e não ser do sistema. */
    private void conferirCategoria(UUID categoriaId, String tipo) {
        List<Map<String, Object>> c = jdbc.queryForList(
                "SELECT tipo, ativo, sistema FROM corona.categorias_caixa WHERE id = ? AND deleted_at IS NULL", categoriaId);
        if (c.isEmpty()) {
            throw Validacoes.recusar("categoriaId", "Esta categoria não existe mais. Atualize a tela e escolha de novo.");
        }
        Map<String, Object> categoria = c.get(0);
        if (Boolean.TRUE.equals(categoria.get("sistema"))) {
            throw Validacoes.recusar("categoriaId", "Esta categoria é do sistema. Escolha uma das suas categorias.");
        }
        if (!Boolean.TRUE.equals(categoria.get("ativo"))) {
            throw Validacoes.recusar("categoriaId", "Esta categoria está inativa. Reative a categoria ou escolha outra.");
        }
        if (!tipo.equals(categoria.get("tipo"))) {
            throw Validacoes.recusar("categoriaId", "Esta categoria é de "
                    + ("ENTRADA".equals(categoria.get("tipo")) ? "entrada" : "saída") + " de dinheiro. Escolha uma categoria do mesmo tipo.");
        }
    }

    private void travar(String chave) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, chave);
    }

    private static BigDecimal valorEmReais(BigDecimal valor) {
        if (valor == null || valor.signum() <= 0) {
            throw Validacoes.recusar("valor", "Informe o valor, maior que zero.");
        }
        if (valor.stripTrailingZeros().scale() > Dinheiro.ESCALA || valor.compareTo(VALOR_MAXIMO) > 0) {
            throw Validacoes.recusar("valor", "Use um valor em reais com no máximo duas casas depois da vírgula.");
        }
        return valor.setScale(Dinheiro.ESCALA);
    }

    private static String resumo(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

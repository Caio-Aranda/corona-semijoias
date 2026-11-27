package br.com.coronasemijoias.api.consignado;

import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria;
import br.com.coronasemijoias.api.auditoria.ServicoDeAuditoria.Ator;
import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.caixa.ServicoDeCaixa;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor;
import br.com.coronasemijoias.api.contasreceber.ServicoDeRecebimentosDoVendedor.ValorRecebido;
import br.com.coronasemijoias.api.cotacoes.Cotacao;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receber a cobrança da falta do Fechamento da maleta (ESPECIFICACAO §23.10, item 1a). A dona lança o que o representante pagou
 * da falta "Cobrar do representante": por valor (parte ou tudo), forma do caixa (Dinheiro ou Transferência) e moeda (Real, Dólar ou
 * Guarani, como o recebimento do representante: gravado em Real pela cotação vigente na hora, ADR-0006). Cada pagamento é imutável e
 * entra no caixa como uma entrada; corrigir é desfazer (estorno com motivo). A falta é travada antes de somar o que já foi pago.
 * Só da dona; nunca vai ao Corona Vendas.
 */
@Service
public class ServicoDaCobrancaDaMaleta {

    /** Em REAL vem o {@code valor}; em DOLAR ou GUARANI, só o {@code valorNaMoeda} (o servidor converte). {@code cotacaoId}: a que a tela mostrou. */
    public record PedidoDePagamento(UUID id, String forma, String moeda, BigDecimal valor, BigDecimal valorNaMoeda, UUID cotacaoId) {
    }

    private static final Set<String> FORMAS = Set.of("DINHEIRO", "PIX");

    private final JdbcTemplate jdbc;
    private final ServicoDeCaixa caixa;
    private final CotacaoVigente cotacoes;
    private final ServicoDeAuditoria auditoria;
    private final Clock relogio;

    public ServicoDaCobrancaDaMaleta(JdbcTemplate jdbc, ServicoDeCaixa caixa, CotacaoVigente cotacoes, ServicoDeAuditoria auditoria,
            Clock relogio) {
        this.jdbc = jdbc;
        this.caixa = caixa;
        this.cotacoes = cotacoes;
        this.auditoria = auditoria;
        this.relogio = relogio;
    }

    /** @return {@code true} se o pagamento já existia igual (reenvio inofensivo) */
    @Transactional
    public boolean pagar(UUID pendenciaId, PedidoDePagamento pedido, UUID usuarioId) {
        if (pedido == null || pendenciaId == null) {
            throw Validacoes.recusar("valor", "Informe o valor pago.");
        }
        UUID id = Validacoes.idDoPedido(pedido.id());
        String forma = pedido.forma();
        if (forma == null || !FORMAS.contains(forma)) {
            throw Validacoes.recusar("forma", "Escolha como o representante pagou: Dinheiro ou Transferência.");
        }
        String moeda = ServicoDeRecebimentosDoVendedor.moedaDoRecebimento(pedido.moeda(), pedido.valor(), pedido.valorNaMoeda());
        boolean emReais = "REAL".equals(moeda);
        BigDecimal digitado = emReais ? ServicoDeRecebimentosDoVendedor.valorEmReais(pedido.valor())
                : ServicoDeRecebimentosDoVendedor.valorNaMoeda(moeda, pedido.valorNaMoeda());

        // A falta travada: dois pagamentos ao mesmo tempo esperam um pelo outro.
        List<Map<String, Object>> falta = jdbc.queryForList("""
                SELECT decisao, valor, vendedor_id FROM corona.pendencias_da_maleta WHERE id = ? FOR UPDATE""", pendenciaId);
        if (falta.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.CADASTRO_NAO_ENCONTRADO);
        }
        List<Map<String, Object>> ja = jdbc.queryForList("""
                SELECT pendencia_id, forma, moeda, valor, valor_na_moeda, estorna_id FROM corona.pagamentos_cobranca_maleta WHERE id = ?""",
                id);
        if (!ja.isEmpty()) {
            Map<String, Object> g = ja.get(0);
            BigDecimal comparado = emReais ? (BigDecimal) g.get("valor") : (BigDecimal) g.get("valor_na_moeda");
            boolean igual = pendenciaId.equals(g.get("pendencia_id")) && g.get("estorna_id") == null && forma.equals(g.get("forma"))
                    && moeda.equals(g.get("moeda")) && comparado != null && comparado.compareTo(digitado) == 0;
            if (!igual) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            return true;
        }
        if (!ServicoDoFechamentoDaMaleta.COBRAR_DO_REPRESENTANTE.equals(falta.get(0).get("decisao"))) {
            throw Validacoes.recusar("pendencia", "Esta falta ficou como perda da loja: não há o que cobrar do representante.");
        }

        ValorRecebido recebido = emReais ? ValorRecebido.emReais(digitado) : converter(moeda, digitado, pedido.cotacaoId());
        BigDecimal valor = recebido.valor();
        BigDecimal devido = (BigDecimal) falta.get(0).get("valor");
        BigDecimal pago = jdbc.queryForObject("SELECT coalesce(sum(valor), 0) FROM corona.pagamentos_cobranca_maleta WHERE pendencia_id = ?",
                BigDecimal.class, pendenciaId);
        BigDecimal faltaPagar = devido.subtract(pago);
        if (valor.compareTo(faltaPagar) > 0) {
            throw ErroDaApi.comDetalhe(HttpStatus.CONFLICT, CodigoErro.PAGAMENTO_ACIMA_DO_SALDO,
                    "O valor passa do que falta pagar desta peça. Falta pagar R$ "
                            + String.format(java.util.Locale.forLanguageTag("pt-BR"), "%,.2f", faltaPagar) + ".");
        }
        UUID vendedor = (UUID) falta.get(0).get("vendedor_id");
        jdbc.update("""
                INSERT INTO corona.pagamentos_cobranca_maleta (id, pendencia_id, vendedor_id, valor, forma, moeda, valor_na_moeda, cotacao_id,
                                                               data_pagamento, registrado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, pendenciaId, vendedor, valor, forma, moeda, recebido.valorNaMoeda(),
                recebido.cotacaoId(), caixa.hoje(), usuarioId);
        caixa.lancarCobrancaDaMaleta(usuarioId, id, forma, valor);
        Map<String, Object> depois = new LinkedHashMap<>();
        depois.put("pendencia_id", pendenciaId.toString());
        depois.put("valor", valor.toPlainString());
        depois.put("forma", forma);
        if (!emReais) {
            depois.put("moeda", moeda);
            depois.put("valor_na_moeda", recebido.valorNaMoeda().toPlainString());
        }
        auditoria.registrar(Ator.usuario(usuarioId), "COBRANCA_DA_MALETA_PAGA", "pagamentos_cobranca_maleta", id, null, depois);
        return false;
    }

    /** Desfaz um pagamento (estorno com motivo): o lançamento oposto no caixa. Reenviar o mesmo estorno não duplica. */
    @Transactional
    public void estornar(UUID pagamentoId, UUID estornoId, String motivo, UUID usuarioId) {
        UUID id = Validacoes.idDoPedido(estornoId);
        String limpo = Validacoes.limpar(motivo);
        if (limpo == null) {
            throw Validacoes.recusar("motivo", "Diga o motivo de desfazer este pagamento.");
        }
        if (limpo.length() > 500) {
            throw Validacoes.recusar("motivo", "O motivo pode ter no máximo 500 letras.");
        }
        List<Map<String, Object>> original = jdbc.queryForList("""
                SELECT pendencia_id, vendedor_id, valor, forma, moeda, valor_na_moeda, cotacao_id, estorna_id
                  FROM corona.pagamentos_cobranca_maleta WHERE id = ?""", pagamentoId);
        if (original.isEmpty() || original.get(0).get("estorna_id") != null) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.LANCAMENTO_NAO_ENCONTRADO);
        }
        Map<String, Object> o = original.get(0);
        // A falta travada, como no pagamento.
        jdbc.queryForList("SELECT id FROM corona.pendencias_da_maleta WHERE id = ? FOR UPDATE", o.get("pendencia_id"));
        List<Map<String, Object>> ja = jdbc.queryForList("SELECT estorna_id FROM corona.pagamentos_cobranca_maleta WHERE id = ?", id);
        if (!ja.isEmpty()) {
            if (!pagamentoId.equals(ja.get(0).get("estorna_id"))) {
                throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_REGISTRADO_DIFERENTE);
            }
            return;
        }
        Boolean desfeito = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM corona.pagamentos_cobranca_maleta WHERE estorna_id = ?)",
                Boolean.class, pagamentoId);
        if (Boolean.TRUE.equals(desfeito)) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.LANCAMENTO_JA_DESFEITO);
        }
        BigDecimal naMoeda = (BigDecimal) o.get("valor_na_moeda");
        jdbc.update("""
                INSERT INTO corona.pagamentos_cobranca_maleta (id, pendencia_id, vendedor_id, valor, forma, moeda, valor_na_moeda, cotacao_id,
                                                               data_pagamento, estorna_id, motivo, registrado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, o.get("pendencia_id"), o.get("vendedor_id"),
                ((BigDecimal) o.get("valor")).negate(), o.get("forma"), o.get("moeda"), naMoeda == null ? null : naMoeda.negate(),
                o.get("cotacao_id"), caixa.hoje(), pagamentoId, limpo, usuarioId);
        caixa.estornarCobrancaDaMaleta(usuarioId, pagamentoId, id);
        auditoria.registrar(Ator.usuario(usuarioId), "COBRANCA_DA_MALETA_DESFEITA", "pagamentos_cobranca_maleta", id, null,
                Map.of("estorna_id", pagamentoId.toString(), "motivo", limpo));
    }

    /** Converte pela cotação vigente agora; sem cotação, ou se a tela mostrou outra, 409 e nada é gravado (como o recebimento). */
    private ValorRecebido converter(String moeda, BigDecimal digitado, UUID cotacaoDaTela) {
        Cotacao cotacao = cotacoes.vigenteEm(relogio.instant())
                .orElseThrow(() -> new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.SEM_COTACAO_PARA_O_RECEBIMENTO));
        if (cotacaoDaTela != null && !Objects.equals(cotacaoDaTela, cotacao.id())) {
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.COTACAO_DO_RECEBIMENTO_MUDOU);
        }
        return ServicoDeRecebimentosDoVendedor.converter(moeda, digitado, cotacao);
    }
}

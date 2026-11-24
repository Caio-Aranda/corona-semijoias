package br.com.coronasemijoias.api.remessas;

import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.comum.erros.CodigoErro;
import br.com.coronasemijoias.api.comum.erros.ErroDaApi;
import br.com.coronasemijoias.api.cotacoes.Cotacao;
import br.com.coronasemijoias.api.cotacoes.CotacaoVigente;
import br.com.coronasemijoias.api.seguranca.ExigePerfil;
import io.swagger.v3.oas.annotations.Operation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dados da "Nota de remessa" (tarefa 3.4; ADR-0010, item 13). O servidor devolve tudo o que o Corona Gestão precisa para
 * imprimir o PDF (a assinatura é do aplicativo), já congelado no envio: preço de cada peça, totais e a cotação do dia
 * do envio. Só ADMIN, só leitura, nunca traz custo. Rascunho é recusado; cancelada vem marcada.
 */
@RestController
@RequestMapping("/api/dona/remessas")
@ExigePerfil("ADMIN")
public class NotaDeRemessaController {

    /** O nome da loja na nota (a marca; a especificação não tem cadastro do nome da loja). */
    static final String LOJA = "Corona Semijoias";

    public record Pessoa(UUID id, int codigo, String nome) {
    }

    public record Semana(UUID id, int numero, LocalDate inicio, LocalDate fim) {
    }

    public record ItemDaNota(UUID produtoId, String codigo, String nome, String tamTon, int quantidade,
            BigDecimal precoRemessa, BigDecimal subtotal) {
    }

    /** A cotação do dia do envio; {@code totalEmDolar} e {@code totalEmGuarani} convertem o total da nota. */
    public record CotacaoDaNota(String data, BigDecimal dolar, BigDecimal guarani, BigDecimal totalEmDolar,
            BigDecimal totalEmGuarani) {
    }

    public record NotaDeRemessa(String loja, UUID remessaId, String codigo, String numero, String tipo, String status, boolean cancelada,
            String motivoDoCancelamento, Pessoa vendedor, String praca, Semana semana, Instant enviadaEm,
            List<ItemDaNota> itens, int totalPecas, BigDecimal totalValor, CotacaoDaNota cotacao) {
    }

    private final JdbcTemplate jdbc;
    private final CotacaoVigente cotacoes;

    NotaDeRemessaController(JdbcTemplate jdbc, CotacaoVigente cotacoes) {
        this.jdbc = jdbc;
        this.cotacoes = cotacoes;
    }

    @Operation(operationId = "notaDaRemessa", summary = "Dados da nota da maleta (para imprimir)",
            description = "Só depois do envio (rascunho: 409). Maleta cancelada vem com cancelada = true e o motivo. Valores em "
                    + "Real, congelados no envio; cotacao é a do dia do envio (vazia se ainda não havia cotação).")
    @GetMapping("/{id}/nota")
    @Transactional(readOnly = true)
    public NotaDeRemessa nota(@PathVariable UUID id) {
        var achadas = jdbc.queryForList("""
                SELECT r.id, r.codigo AS codigo_da_maleta, r.tipo, r.status, r.motivo_cancelamento, r.enviada_em, r.vendedor_id, v.codigo, v.nome AS vendedor,
                       pr.nome AS praca, s.id AS semana_id, s.numero AS semana, s.inicio, s.fim, """ + SqlDaMaleta.NUMERO + """
                        AS numero
                  FROM corona.remessas r JOIN corona.vendedores v ON v.id = r.vendedor_id
                  JOIN corona.semanas s ON s.id = r.semana_id LEFT JOIN corona.pracas pr ON pr.id = r.praca_id
                 WHERE r.id = ? AND r.deleted_at IS NULL""", id);
        if (achadas.isEmpty()) {
            throw new ErroDaApi(HttpStatus.NOT_FOUND, CodigoErro.REMESSA_NAO_ENCONTRADA);
        }
        var r = achadas.get(0);
        String status = (String) r.get("status");
        java.sql.Timestamp enviada = (java.sql.Timestamp) r.get("enviada_em");
        if ("RASCUNHO".equals(status) || enviada == null) {
            // Rascunho, ou cancelada antes do envio (nunca teve nota): nada a imprimir.
            throw new ErroDaApi(HttpStatus.CONFLICT, CodigoErro.NOTA_SO_DEPOIS_DO_ENVIO);
        }
        List<ItemDaNota> itens = jdbc.query("""
                SELECT p.id, p.nome, p.tam_ton, i.qtd_enviada, i.preco_remessa,
                       (SELECT cd.codigo FROM corona.produto_codigos cd WHERE cd.produto_id = p.id AND cd.deleted_at IS NULL
                         ORDER BY cd.principal DESC, cd.created_at LIMIT 1) AS codigo
                  FROM corona.itens_remessa i JOIN corona.produtos p ON p.id = i.produto_id
                 WHERE i.remessa_id = ? AND i.deleted_at IS NULL ORDER BY lower(p.nome), p.id""", (rs, n) -> {
                    BigDecimal preco = Dinheiro.arredondar(rs.getBigDecimal("preco_remessa"));
                    int qtd = rs.getInt("qtd_enviada");
                    return new ItemDaNota(rs.getObject("id", UUID.class), rs.getString("codigo"), rs.getString("nome"),
                            rs.getString("tam_ton"), qtd,
                            preco, Dinheiro.precoDaLinha(preco, qtd));
                }, id);
        int pecas = itens.stream().mapToInt(ItemDaNota::quantidade).sum();
        BigDecimal total = Dinheiro.arredondar(itens.stream().map(ItemDaNota::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        Instant enviadaEm = enviada.toInstant();
        CotacaoDaNota cotacao = cotacoes.vigenteEm(enviadaEm).map(c -> daNota(c, total)).orElse(null);
        return new NotaDeRemessa(LOJA, id, (String) r.get("codigo_da_maleta"), (String) r.get("numero"), (String) r.get("tipo"), status,
                "CANCELADA".equals(status), (String) r.get("motivo_cancelamento"),
                new Pessoa((UUID) r.get("vendedor_id"), ((Number) r.get("codigo")).intValue(), (String) r.get("vendedor")),
                (String) r.get("praca"), new Semana((UUID) r.get("semana_id"), ((Number) r.get("semana")).intValue(),
                        ((java.sql.Date) r.get("inicio")).toLocalDate(), ((java.sql.Date) r.get("fim")).toLocalDate()),
                enviadaEm, itens, pecas, total, cotacao);
    }

    private static CotacaoDaNota daNota(Cotacao c, BigDecimal total) {
        return new CotacaoDaNota(CotacaoVigente.dataParaMostrar(c), c.taxas().usdVenda(), c.taxas().pygVenda(), c.emDolar(total),
                c.emGuarani(total));
    }
}

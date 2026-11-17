package br.com.coronasemijoias.api.relatorios;

import static br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal.emTresMoedas;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.CONCLUIDA;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.somaEmDolar;
import static br.com.coronasemijoias.api.relatorios.VendidoLiquido.somaEmGuarani;

import br.com.coronasemijoias.api.cadastros.Validacoes;
import br.com.coronasemijoias.api.comum.dinheiro.Dinheiro;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.Consulta;
import br.com.coronasemijoias.api.relatorios.MotorDeRelatorio.DefinicaoDeTotal;
import br.com.coronasemijoias.api.relatorios.VendidoLiquido.Recorte;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatórios de vendas da dona (tarefa 8.5; §12, "Vendas"): por vendedor, praça, cliente, produto, categoria e período, os
 * rankings de vendedores e de peças, o ticket médio, uma linha por venda e as vendas canceladas. Todos agrupam a MESMA
 * consulta do vendido líquido ({@link VendidoLiquido}), a que a curva ABC também usa: o número de uma peça é o mesmo aqui e lá.
 * Só leitura, no motor dos demais relatórios (linhas, rodapé e detalhamento saem das mesmas condições). Tudo em Real; Dólar e
 * Guarani são calculados na leitura pela cotação do dia da venda, linha a linha, e o rodapé soma as linhas já convertidas.
 * A venda estornada fica fora de todo total e aparece marcada. O ticket médio é o vendido líquido dividido pelo número de
 * vendas, arredondado uma vez em {@link Dinheiro}. Nenhum custo aparece aqui.
 */
@Service
public class ConsultasDeVendasDaDona {

    /** Os filtros de todos os relatórios de vendas (o ano de uma venda é o dia de Brasília). */
    public record Filtros(String busca, String vendedorId, String pracaId, String clienteId, String produtoId,
            String categoriaId, String situacao, LocalDate de, LocalDate ate, String recorte, Integer pagina, Integer tamanho) {

        Recorte recorteDasVendas() {
            return new Recorte(busca, vendedorId, pracaId, clienteId, produtoId, categoriaId, de, ate);
        }
    }

    /** Uma linha agrupada: um vendedor, uma praça, um cliente, uma peça, uma categoria ou um dia. */
    public record LinhaDeVendido(long posicao, String chave, String nome, String detalhe, String codigo,
            String fotoPrincipalHash, long vendas, long pecas, BigDecimal vendido, BigDecimal vendidoEmDolar,
            BigDecimal vendidoEmGuarani, BigDecimal devolvido, BigDecimal devolvidoEmDolar, BigDecimal devolvidoEmGuarani,
            BigDecimal liquido, BigDecimal liquidoEmDolar, BigDecimal liquidoEmGuarani, BigDecimal ticketMedio) {
    }

    /** Uma venda: o que saiu, o que voltou, o líquido e, se foi estornada, quem estornou e por quê. */
    public record LinhaDeVenda(UUID vendaId, Instant vendidaEm, UUID vendedorId, String vendedor, String praca, UUID clienteId,
            String cliente, String situacao, boolean cancelada, long pecas, BigDecimal vendido, BigDecimal vendidoEmDolar,
            BigDecimal vendidoEmGuarani, BigDecimal devolvido, BigDecimal devolvidoEmDolar, BigDecimal devolvidoEmGuarani,
            BigDecimal liquido, BigDecimal liquidoEmDolar, BigDecimal liquidoEmGuarani, LocalDate dataDaCotacao,
            Instant estornadaEm, String estornadaPor, String motivoDoEstorno) {
    }

    /** Como agrupar as linhas do vendido líquido. */
    enum Grupo {
        VENDEDOR("l.vendedor_id", "l.vendedor", "l.praca", "l.vendedor_id, l.vendedor, l.praca", "lower(x.nome), x.chave"),
        PRACA("l.praca_id", "coalesce(l.praca, 'Sem praça')", "NULL::text", "l.praca_id, l.praca", "lower(x.nome), x.chave"),
        CLIENTE("l.cliente_id", "l.cliente", "NULL::text", "l.cliente_id, l.cliente", "lower(x.nome), x.chave"),
        PRODUTO("l.produto_id", "l.produto", "l.referencia", "l.produto_id, l.produto, l.referencia", "lower(x.nome), x.chave"),
        CATEGORIA("l.categoria_id", "l.categoria", "NULL::text", "l.categoria_id, l.categoria", "lower(x.nome), x.chave"),
        DIA("l.dia", "l.dia::text", "NULL::text", "l.dia", "x.chave");

        private final String chave;
        private final String nome;
        private final String detalhe;
        private final String agrupar;
        private final String ordemAlfabetica;

        Grupo(String chave, String nome, String detalhe, String agrupar, String ordemAlfabetica) {
            this.chave = chave;
            this.nome = nome;
            this.detalhe = detalhe;
            this.agrupar = agrupar;
            this.ordemAlfabetica = ordemAlfabetica;
        }
    }

    /** Em que ordem mostrar as linhas agrupadas. */
    enum Ordem {
        ALFABETICA, RANKING, TICKET_MEDIO
    }

    private static final String MOEDAS_DO_VENDIDO = ", " + somaEmDolar("l.bruto") + " AS vendido_dolar, "
            + somaEmGuarani("l.bruto") + " AS vendido_guarani";
    private static final String MOEDAS_DO_DEVOLVIDO = ", " + somaEmDolar("l.devolvido") + " AS devolvido_dolar, "
            + somaEmGuarani("l.devolvido") + " AS devolvido_guarani";
    private static final String MOEDAS_DO_LIQUIDO = ", " + somaEmDolar("l.liquido") + " AS liquido_dolar, "
            + somaEmGuarani("l.liquido") + " AS liquido_guarani";

    private final MotorDeRelatorio motor;

    ConsultasDeVendasDaDona(MotorDeRelatorio motor) {
        this.motor = motor;
    }

    // --- agrupados ---

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> porVendedor(Filtros f) {
        return agrupado(Grupo.VENDEDOR, Ordem.ALFABETICA, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> porPraca(Filtros f) {
        return agrupado(Grupo.PRACA, Ordem.ALFABETICA, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> porCliente(Filtros f) {
        return agrupado(Grupo.CLIENTE, Ordem.ALFABETICA, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> porProduto(Filtros f) {
        return agrupado(Grupo.PRODUTO, Ordem.ALFABETICA, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> porCategoria(Filtros f) {
        return agrupado(Grupo.CATEGORIA, Ordem.ALFABETICA, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> porPeriodo(Filtros f) {
        return agrupado(Grupo.DIA, Ordem.ALFABETICA, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> rankingDeVendedores(Filtros f) {
        return agrupado(Grupo.VENDEDOR, Ordem.RANKING, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> rankingDePecas(Filtros f) {
        return agrupado(Grupo.PRODUTO, Ordem.RANKING, f);
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVendido> ticketMedio(Filtros f) {
        return agrupado(Grupo.VENDEDOR, Ordem.TICKET_MEDIO, f);
    }

    private RelatorioDeCadastro<LinhaDeVendido> agrupado(Grupo g, Ordem ordem, Filtros f) {
        Consulta filtros = VendidoLiquido.filtrar(f.recorteDasVendas());
        String agrupado = "SELECT " + g.chave + "::text AS chave, " + g.nome + " AS nome, " + g.detalhe + " AS detalhe, "
                + "max(l.codigo) AS codigo, max(l.foto_principal_hash) AS foto, count(DISTINCT l.venda_id) AS vendas, "
                + "coalesce(sum(l.pecas), 0) AS pecas, sum(l.bruto) AS vendido" + MOEDAS_DO_VENDIDO
                + ", sum(l.devolvido) AS devolvido" + MOEDAS_DO_DEVOLVIDO + ", sum(l.liquido) AS liquido" + MOEDAS_DO_LIQUIDO
                + ", row_number() OVER (ORDER BY sum(l.liquido) DESC, " + g.nome + ", " + g.chave + "::text) AS posicao"
                + " FROM (" + VendidoLiquido.FONTE + ") l WHERE " + CONCLUIDA
                + (filtros.condicoes.isEmpty() ? "" : " AND " + String.join(" AND ", filtros.condicoes))
                + " GROUP BY " + g.agrupar;
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("liquido", "Vendido líquido", null, "x.pecas", "x.liquido", "x.liquido_dolar", "x.liquido_guarani"),
                emTresMoedas("vendido", "Vendido", null, "x.pecas", "x.vendido", "x.vendido_dolar", "x.vendido_guarani"),
                emTresMoedas("devolvido", "Devolvido", "x.devolvido > 0", null, "x.devolvido", "x.devolvido_dolar",
                        "x.devolvido_guarani"));
        String ordenar = switch (ordem) {
            case ALFABETICA -> g.ordemAlfabetica;
            case RANKING -> "x.posicao";
            case TICKET_MEDIO -> "x.liquido / x.vendas DESC, lower(x.nome), x.chave";
        };
        Consulta c = new Consulta("x.*", "(" + agrupado + ") x", ordenar, totais);
        c.parametros.addValues(filtros.parametros.getValues());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> {
            BigDecimal liquido = MotorDeRelatorio.reais(rs, "liquido");
            long vendas = rs.getLong("vendas");
            return new LinhaDeVendido(rs.getLong("posicao"), rs.getString("chave"), rs.getString("nome"),
                    rs.getString("detalhe"), rs.getString("codigo"), rs.getString("foto"), vendas, rs.getLong("pecas"),
                    MotorDeRelatorio.reais(rs, "vendido"), rs.getBigDecimal("vendido_dolar"), rs.getBigDecimal("vendido_guarani"),
                    MotorDeRelatorio.reais(rs, "devolvido"), rs.getBigDecimal("devolvido_dolar"),
                    rs.getBigDecimal("devolvido_guarani"), liquido, rs.getBigDecimal("liquido_dolar"),
                    rs.getBigDecimal("liquido_guarani"), vendas == 0 ? null : Dinheiro.dividir(liquido, vendas));
        });
    }

    // --- uma linha por venda ---

    /** Vendas registradas (padrão), canceladas ou todas; a estornada fica marcada e fora do total. */
    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVenda> vendas(Filtros f) {
        return porVenda(f, f.situacao());
    }

    @Transactional(readOnly = true)
    public RelatorioDeCadastro<LinhaDeVenda> canceladas(Filtros f) {
        return porVenda(f, "CANCELADAS");
    }

    private RelatorioDeCadastro<LinhaDeVenda> porVenda(Filtros f, String situacaoPedida) {
        String situacao = situacaoPedida == null || situacaoPedida.isBlank() ? "REGISTRADAS" : situacaoPedida.strip().toUpperCase();
        Consulta filtros = VendidoLiquido.filtrar(f.recorteDasVendas());
        switch (situacao) {
            case "REGISTRADAS" -> filtros.onde(CONCLUIDA);
            case "CANCELADAS" -> filtros.onde("l.status = 'ESTORNADA'");
            case "TODAS" -> {
            }
            default -> throw Validacoes.recusar("situacao", "Escolha feitas, canceladas ou todas.");
        }
        String agrupado = "SELECT l.venda_id, l.vendida_em, l.vendedor_id, l.vendedor, l.praca, l.cliente_id, l.cliente, l.status, "
                + "max(l.data_cotacao) AS data_cotacao, max(l.estornada_em) AS estornada_em, max(l.estornada_por) AS estornada_por, "
                + "max(l.motivo_estorno) AS motivo_estorno, sum(l.pecas) AS pecas, sum(l.bruto) AS vendido" + MOEDAS_DO_VENDIDO
                + ", sum(l.devolvido) AS devolvido" + MOEDAS_DO_DEVOLVIDO
                + ", CASE WHEN l.status = 'CONCLUIDA' THEN sum(l.liquido) ELSE 0 END AS liquido"
                + ", CASE WHEN l.status = 'CONCLUIDA' THEN " + somaEmDolar("l.liquido") + " ELSE 0 END AS liquido_dolar"
                + ", CASE WHEN l.status = 'CONCLUIDA' THEN " + somaEmGuarani("l.liquido") + " ELSE 0 END AS liquido_guarani"
                + " FROM (" + VendidoLiquido.FONTE + ") l"
                + (filtros.condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", filtros.condicoes))
                + " GROUP BY l.venda_id, l.vendida_em, l.vendedor_id, l.vendedor, l.praca, l.cliente_id, l.cliente, l.status";
        String feita = "x.status = 'CONCLUIDA'";
        List<DefinicaoDeTotal> totais = List.of(
                emTresMoedas("liquido", "Vendido líquido", feita, "x.pecas", "x.liquido", "x.liquido_dolar", "x.liquido_guarani"),
                emTresMoedas("vendido", "Vendido", feita, "x.pecas", "x.vendido", "x.vendido_dolar", "x.vendido_guarani"),
                emTresMoedas("devolvido", "Devolvido", feita + " AND x.devolvido > 0", null, "x.devolvido", "x.devolvido_dolar",
                        "x.devolvido_guarani"),
                emTresMoedas("canceladas", "Vendas canceladas (fora do total)", "x.status = 'ESTORNADA'", null, "x.vendido",
                        "x.vendido_dolar", "x.vendido_guarani"));
        Consulta c = new Consulta("x.*", "(" + agrupado + ") x", "x.vendida_em DESC, x.venda_id", totais);
        c.parametros.addValues(filtros.parametros.getValues());
        return motor.executar(c, f.recorte(), f.pagina(), f.tamanho(), (rs, n) -> new LinhaDeVenda(
                rs.getObject("venda_id", UUID.class), instante(rs, "vendida_em"), rs.getObject("vendedor_id", UUID.class),
                rs.getString("vendedor"), rs.getString("praca"), rs.getObject("cliente_id", UUID.class), rs.getString("cliente"),
                rs.getString("status"), "ESTORNADA".equals(rs.getString("status")), rs.getLong("pecas"),
                MotorDeRelatorio.reais(rs, "vendido"), rs.getBigDecimal("vendido_dolar"), rs.getBigDecimal("vendido_guarani"),
                MotorDeRelatorio.reais(rs, "devolvido"), rs.getBigDecimal("devolvido_dolar"), rs.getBigDecimal("devolvido_guarani"),
                MotorDeRelatorio.reais(rs, "liquido"), rs.getBigDecimal("liquido_dolar"), rs.getBigDecimal("liquido_guarani"),
                rs.getObject("data_cotacao", LocalDate.class), instante(rs, "estornada_em"), rs.getString("estornada_por"),
                rs.getString("motivo_estorno")));
    }

    static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime v = rs.getObject(coluna, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
}
